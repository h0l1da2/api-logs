package io.github.h0l1da2.apilog.capture;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import io.github.h0l1da2.apilog.BodyPolicy;
import io.github.h0l1da2.apilog.spi.CallerResolver;
import io.github.h0l1da2.apilog.store.ApiAccessLogRecord;
import io.github.h0l1da2.apilog.store.ApiAccessLogSink;
import io.github.h0l1da2.apilog.support.Ulid;
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
 * - SSE·비동기·멀티파트·바이너리는 본문 없이 메타 정보만 남긴다 ({@code skipReason})
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
        final CapturingResponseWrapper responseToUse =
                new CapturingResponseWrapper(response, properties.maxBodyBytes());

        Throwable failure = null;
        try {
            chain.doFilter(requestToUse, responseToUse);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            responseToUse.flushWriterQuietly();
            try {
                sink.offer(buildRecord(requestToUse, responseToUse, startMillis, startNanos, failure));
            } catch (RuntimeException e) {
                log.debug("API 호출 기록을 만들지 못했습니다: " + e.getClass().getSimpleName());
            }
        }
    }

    private ApiAccessLogRecord buildRecord(HttpServletRequest request, CapturingResponseWrapper response,
                                           long startMillis, long startNanos, Throwable failure) {
        final String path = pathWithinApplication(request);
        final String route = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        final BodyPolicy bodyPolicy = policy.bodyPolicyFor(route, path);
        final String requestContentType = request.getContentType();
        final String responseContentType = response.getContentType();
        final boolean async = request.isAsyncStarted();
        final boolean eventStream = policy.isEventStream(responseContentType)
                || acceptsEventStream(request);

        String skipReason = null;
        if (eventStream) {
            skipReason = "SSE";
        } else if (async) {
            skipReason = "ASYNC";
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
            requestBodyBytes = declared >= 0 ? declared : bytes.length;
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
        if (skipReason == null && bodyPolicy == BodyPolicy.FULL) {
            if (policy.isTextual(responseContentType)) {
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
                errorOf(request, failure));
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
