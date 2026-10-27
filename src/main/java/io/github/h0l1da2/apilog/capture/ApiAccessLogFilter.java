package io.github.h0l1da2.apilog.capture;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import io.github.h0l1da2.apilog.BodyPolicy;
import io.github.h0l1da2.apilog.spi.CallerResolver;
import io.github.h0l1da2.apilog.store.ApiAccessLogRecord;
import io.github.h0l1da2.apilog.store.ApiAccessLogSink;
import io.github.h0l1da2.apilog.support.Ulid;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 요청 한 건을 감싸 처리가 끝나면 기록 한 건을 {@link ApiAccessLogSink}에 넘긴다.
 *
 * 책임:
 * - 요청·응답 모두 흘려보내며 앞부분만 복사한다. 앱이 보는 요청·응답(인코딩 포함)은 바꾸지 않는다
 * - 멀티파트·바이너리는 본문 없이 메타 정보만 남긴다 ({@code skipReason})
 * - SSE는 스트림 전체를 복사하지 않고 마지막 종료 이벤트(done/error)의 data만 응답 본문으로 남긴다
 * - 비동기 요청(SSE 포함)은 첫 디스패치가 끝난 시점이 아니라 비동기 처리가 끝났을 때 기록한다
 * - 저장 전에 마스킹과 자르기를 끝낸다
 *
 * 주의:
 * - Spring Security보다 바깥에 등록해야 401·403도 기록된다
 * - 기록 실패가 요청 처리에 영향을 주면 안 된다. 예외는 삼키고 debug 로그만 남긴다 (본문은 로그에 남기지 않는다)
 */
public class ApiAccessLogFilter extends OncePerRequestFilter {

    private static final Log log = LogFactory.getLog(ApiAccessLogFilter.class);
    private static final String SERVLET_ERROR_EXCEPTION = "jakarta.servlet.error.exception";

    private final ApiAccessLogProperties properties;
    private final CapturePolicy policy;
    private final BodyMasker masker;
    private final ApiAccessLogSink sink;

    public ApiAccessLogFilter(ApiAccessLogProperties properties, CapturePolicy policy, BodyMasker masker,
                              ApiAccessLogSink sink) {
        this.properties = properties;
        this.policy = policy;
        this.masker = masker;
        this.sink = sink;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !policy.shouldRecord(pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        final long startMillis = System.currentTimeMillis();
        final long startNanos = System.nanoTime();
        final String requestContentType = request.getContentType();
        final boolean cacheRequestBody = !policy.isMultipart(requestContentType)
                && policy.isTextual(requestContentType);
        final HttpServletRequest requestToUse = cacheRequestBody
                ? new CapturingRequestWrapper(request, properties.maxBodyBytes())
                : request;
        final CapturingResponseWrapper responseToUse = new CapturingResponseWrapper(response,
                properties.maxBodyBytes(), initialResponseMode(request), policy::isTextual, policy::isEventStream);

        Throwable failure = null;
        boolean deferred = false;
        try {
            chain.doFilter(requestToUse, responseToUse);
            deferred = requestToUse.isAsyncStarted() && deferUntilAsyncComplete(requestToUse, responseToUse,
                    startMillis, startNanos);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            if (!deferred) {
                responseToUse.flushWriterQuietly();
                offer(requestToUse, responseToUse, startMillis, startNanos, failure, null);
            }
        }
    }

    /**
     * 처음부터 응답 캡처 방식을 정한다. 이 시점엔 route를 모르므로 실제 경로로 본문 정책을 본다.
     * - 응답 본문을 저장하지 않는 정책이면 복사하지 않는다
     * - SSE 요청이면 Content-Type이 확정될 때까지 복사하지 않는다 (확정되면 종료 이벤트만 보관)
     */
    private CapturingResponseWrapper.Mode initialResponseMode(HttpServletRequest request) {
        if (policy.bodyPolicyFor(null, pathWithinApplication(request)) != BodyPolicy.FULL) {
            return CapturingResponseWrapper.Mode.COUNT_ONLY;
        }
        return acceptsEventStream(request)
                ? CapturingResponseWrapper.Mode.EXPECT_EVENT_STREAM
                : CapturingResponseWrapper.Mode.AUTO;
    }

    /** 비동기 처리가 끝나면 한 번만 기록하도록 리스너를 건다. 걸지 못하면 false (즉시 기록). */
    private boolean deferUntilAsyncComplete(HttpServletRequest request, CapturingResponseWrapper response,
                                            long startMillis, long startNanos) {
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                private final AtomicBoolean recorded = new AtomicBoolean();
                private volatile Throwable error;
                private volatile boolean timedOut;

                @Override
                public void onComplete(AsyncEvent event) {
                    if (recorded.compareAndSet(false, true)) {
                        response.flushWriterQuietly();
                        offer(request, response, startMillis, startNanos, error,
                                timedOut && error == null ? "비동기 처리 시간 초과" : null);
                    }
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    timedOut = true;
                }

                @Override
                public void onError(AsyncEvent event) {
                    error = event.getThrowable();
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    // 같은 요청에서 비동기를 다시 시작하면 리스너가 빠지므로 다시 건다
                    event.getAsyncContext().addListener(this);
                }
            });
            return true;
        } catch (RuntimeException e) {
            log.debug("비동기 완료 리스너를 걸지 못해 즉시 기록합니다: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void offer(HttpServletRequest request, CapturingResponseWrapper response,
                       long startMillis, long startNanos, Throwable failure, String asyncError) {
        try {
            sink.offer(buildRecord(request, response, startMillis, startNanos, failure, asyncError));
        } catch (RuntimeException e) {
            log.debug("API 호출 기록을 만들지 못했습니다: " + e.getClass().getSimpleName());
        }
    }

    private ApiAccessLogRecord buildRecord(HttpServletRequest request, CapturingResponseWrapper response,
                                           long startMillis, long startNanos, Throwable failure,
                                           String asyncError) {
        final String path = pathWithinApplication(request);
        final String route = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        final BodyPolicy bodyPolicy = policy.bodyPolicyFor(route, path);
        final String requestContentType = request.getContentType();
        final String responseContentType = response.getContentType();
        final CapturingResponseWrapper.Mode responseMode = response.mode();
        final boolean eventStream = responseMode == CapturingResponseWrapper.Mode.EVENT_STREAM
                || policy.isEventStream(responseContentType);

        String skipReason = null;
        String error = errorOf(request, failure);
        if (eventStream) {
            skipReason = "SSE";
        } else if (policy.isMultipart(requestContentType)) {
            skipReason = "MULTIPART";
        }

        // 요청 본문
        String requestBody = null;
        Long requestBodyBytes = null;
        boolean truncated = false;
        if (request instanceof CapturingRequestWrapper cached) {
            byte[] bytes = cached.capturedBytes();
            if (bytes.length == 0 && policy.isForm(requestContentType)) {
                // 폼은 컨테이너가 본문을 직접 파싱하므로 파라미터로 다시 만든다
                bytes = formFromParameters(request).getBytes(StandardCharsets.UTF_8);
            }
            final long declared = request.getContentLengthLong();
            // Content-Length가 없으면(chunked 등) 래퍼가 상한과 관계없이 센 전체 읽은 바이트 수를 쓴다.
            // capturedBytes()는 상한까지만 보관하므로 그 길이로는 잘림을 알 수 없다.
            // 폼처럼 컨테이너가 본문을 직접 읽어 래퍼가 세지 못한 경우에만 재구성한 길이를 쓴다
            requestBodyBytes = declared >= 0 ? declared
                    : cached.totalBytes() > 0 ? cached.totalBytes() : bytes.length;
            truncated = requestBodyBytes > properties.maxBodyBytes();
            if (bodyPolicy != BodyPolicy.NONE && bytes.length > 0) {
                requestBody = masker.maskBody(
                        decode(bytes, request.getCharacterEncoding(), policy.isJson(requestContentType)),
                        policy.isJson(requestContentType), policy.isForm(requestContentType));
            }
        } else if (policy.isMultipart(requestContentType)) {
            requestBody = summarizeParts(request);
            requestBodyBytes = request.getContentLengthLong() >= 0 ? request.getContentLengthLong() : null;
        }

        // 응답 본문
        String responseBody = null;
        final Long responseBodyBytes = response.totalBytes();
        if (eventStream && bodyPolicy == BodyPolicy.FULL) {
            // SSE는 마지막 종료 이벤트(done/error)의 data만 남긴다
            final SseTerminalEventCapture.TerminalEvent terminal = response.lastTerminalEvent();
            if (terminal == null) {
                skipReason = "SSE_NO_DONE";
            } else {
                responseBody = masker.maskBody(new String(terminal.data(), StandardCharsets.UTF_8),
                        true, false);
                truncated = truncated || terminal.totalBytes() > properties.maxBodyBytes();
                if ("error".equals(terminal.name()) && error == null) {
                    error = "SSE error 이벤트로 종료";
                }
            }
        } else if (skipReason == null && bodyPolicy == BodyPolicy.FULL) {
            if (responseMode == CapturingResponseWrapper.Mode.BUFFER && policy.isTextual(responseContentType)) {
                final byte[] bytes = response.capturedBytes();
                if (bytes.length > 0) {
                    responseBody = masker.maskBody(
                            decode(bytes, response.getCharacterEncoding(), policy.isJson(responseContentType)),
                            policy.isJson(responseContentType), false);
                }
                truncated = truncated || response.totalBytes() > properties.maxBodyBytes();
            } else if (response.totalBytes() > 0) {
                skipReason = "BINARY";
            }
        }

        final String recordId = Ulid.generate(startMillis);
        return new ApiAccessLogRecord(
                ApiAccessLogRecord.CURRENT_SCHEMA_VERSION,
                recordId,
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(startMillis), properties.zone()).toString(),
                requestId(request, response),
                request.getMethod(),
                path,
                route,
                masker.maskQuery(request.getQueryString()),
                response.getStatus(),
                (System.nanoTime() - startNanos) / 1_000_000,
                caller(request),
                clientIp(request),
                properties.recordHeaders() ? masker.maskHeaders(requestHeaders(request)) : null,
                properties.recordHeaders() ? masker.maskHeaders(responseHeaders(response)) : null,
                requestContentType,
                responseContentType,
                requestBody,
                responseBody,
                requestBodyBytes,
                responseBodyBytes,
                truncated,
                skipReason,
                error != null ? error : asyncError);
    }

    private String errorOf(HttpServletRequest request, Throwable failure) {
        Object error = request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE);
        if (error == null) {
            error = request.getAttribute(SERVLET_ERROR_EXCEPTION);
        }
        if (error == null) {
            error = failure;
        }
        if (!(error instanceof Throwable throwable)) {
            return null;
        }
        final StringBuilder text = new StringBuilder(describe(throwable));
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root != throwable) {
            text.append(" / cause: ").append(describe(root));
        }
        return masker.maskValues(truncateUtf8(text.toString(), properties.maxErrorBytes()));
    }

    private static String describe(Throwable throwable) {
        final String message = throwable.getMessage();
        return message == null
                ? throwable.getClass().getName()
                : throwable.getClass().getName() + ": " + message;
    }

    private static String truncateUtf8(String text, int maxBytes) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        int end = maxBytes;
        // UTF-8 연속 바이트(10xxxxxx) 중간에서 자르지 않도록 앞으로 당긴다
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static String formFromParameters(HttpServletRequest request) {
        return request.getParameterMap().entrySet().stream()
                .flatMap(entry -> java.util.Arrays.stream(entry.getValue())
                        .map(value -> java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                                + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)))
                .collect(Collectors.joining("&"));
    }

    private String summarizeParts(HttpServletRequest request) {
        try {
            final Collection<Part> parts = request.getParts();
            return "[multipart] " + parts.stream()
                    .map(part -> part.getName()
                            + (part.getSubmittedFileName() != null ? "=" + part.getSubmittedFileName() : "")
                            + "(" + part.getSize() + "B)")
                    .collect(Collectors.joining(", "));
        } catch (Exception e) {
            return "[multipart]";
        }
    }

    private String caller(HttpServletRequest request) {
        final Object caller = request.getAttribute(CallerCaptureInterceptor.CALLER_ATTRIBUTE);
        return caller instanceof String value ? value : CallerResolver.ANONYMOUS;
    }

    private String requestId(HttpServletRequest request, HttpServletResponse response) {
        final String fromResponse = response.getHeader(properties.requestIdHeader());
        return fromResponse != null ? fromResponse : request.getHeader(properties.requestIdHeader());
    }

    private static String clientIp(HttpServletRequest request) {
        final String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static boolean acceptsEventStream(HttpServletRequest request) {
        final String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        final String uri = request.getRequestURI();
        final String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }

    /** JSON은 규격상 UTF-8이므로 서블릿 기본값(ISO-8859-1)을 무시하고 UTF-8로 읽는다. */
    private static String decode(byte[] bytes, String encoding, boolean json) {
        Charset charset = StandardCharsets.UTF_8;
        if (!json && encoding != null) {
            try {
                charset = Charset.forName(encoding);
            } catch (RuntimeException ignored) {
                // 알 수 없는 인코딩이면 UTF-8로 읽는다
            }
        }
        return new String(bytes, charset);
    }

    private static Map<String, String> requestHeaders(HttpServletRequest request) {
        final Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, String.join(", ", Collections.list(request.getHeaders(name))));
        }
        return headers;
    }

    private static Map<String, String> responseHeaders(HttpServletResponse response) {
        final Map<String, String> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            headers.put(name, String.join(", ", response.getHeaders(name)));
        }
        return headers;
    }
}
