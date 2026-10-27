package io.github.h0l1da2.apilog.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import io.github.h0l1da2.apilog.TestProperties;
import io.github.h0l1da2.apilog.store.ApiAccessLogRecord;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("API 호출 기록 필터")
class ApiAccessLogFilterTest {

    private final List<ApiAccessLogRecord> records = new CopyOnWriteArrayList<>();

    private MockMvc mockMvc(String... properties) {
        final ApiAccessLogProperties props = TestProperties.of(properties);
        final ApiAccessLogFilter filter = new ApiAccessLogFilter(props, new CapturePolicy(props),
                new BodyMasker(props, JsonMapper.builder().build()), records::add);
        return MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(filter)
                .addInterceptors(new CallerCaptureInterceptor(request -> "user:1/ROLE_ADMIN"))
                .build();
    }

    @Test
    @DisplayName("JSON 요청·응답 본문을 마스킹해서 한 건으로 남긴다")
    void recordsMaskedJsonExchange() throws Exception {
        mockMvc().perform(post("/api/v1/users/7?password=p&page=1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "req-1")
                        .content("{\"loginId\":\"admin\",\"password\":\"p@ss\",\"agentName\":\"에이전트\"}"))
                .andExpect(status().isOk());

        assertThat(records).hasSize(1);
        final ApiAccessLogRecord record = records.getFirst();
        assertThat(record.method()).isEqualTo("POST");
        assertThat(record.path()).isEqualTo("/api/v1/users/7");
        assertThat(record.route()).isEqualTo("/api/v1/users/{id}");
        assertThat(record.query()).isEqualTo("password=***&page=1");
        assertThat(record.status()).isEqualTo(200);
        assertThat(record.caller()).isEqualTo("user:1/ROLE_ADMIN");
        assertThat(record.requestId()).isEqualTo("req-1");
        assertThat(record.reqBody()).contains("\"password\":\"***\"").contains("에이전트").doesNotContain("p@ss");
        assertThat(record.resBody()).contains("\"name\":\"***\"").contains("에이전트").doesNotContain("홍길동");
        assertThat(record.truncated()).isFalse();
        assertThat(record.skipReason()).isNull();
        assertThat(record.id()).hasSize(26);
    }

    @Test
    @DisplayName("예외 메시지를 남기되 전화번호는 가린다")
    void recordsErrorMessage() throws Exception {
        mockMvc().perform(get("/api/v1/fail")).andExpect(status().isInternalServerError());

        final ApiAccessLogRecord record = records.getFirst();
        assertThat(record.status()).isEqualTo(500);
        assertThat(record.error())
                .contains("IllegalStateException")
                .contains("SQL syntax error")
                .doesNotContain("010-1234-5678");
        assertThat(record.resBody()).contains("FAILED");
    }

    @Test
    @DisplayName("본문이 상한보다 크면 잘라서 남기고 원래 크기를 기록한다")
    void truncatesLargeBody() throws Exception {
        mockMvc("max-body-bytes", "100").perform(get("/api/v1/large")).andExpect(status().isOk());

        final ApiAccessLogRecord record = records.getFirst();
        assertThat(record.truncated()).isTrue();
        assertThat(record.resBody().getBytes()).hasSizeLessThanOrEqualTo(100);
        assertThat(record.resBodyBytes()).isGreaterThan(100L);
    }

    @Test
    @DisplayName("Content-Length 없는(chunked) 요청 본문이 상한보다 크면 잘림과 전체 크기를 기록한다")
    void truncatesChunkedRequestBody() throws Exception {
        final ApiAccessLogProperties props = TestProperties.of("max-body-bytes", "100");
        final ApiAccessLogFilter filter = new ApiAccessLogFilter(props, new CapturePolicy(props),
                new BodyMasker(props, JsonMapper.builder().build()), records::add);
        final String body = "{\"data\":\"" + "x".repeat(500) + "\"}";
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/chunked") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));

        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> req.getInputStream().readAllBytes());

        final ApiAccessLogRecord record = records.getFirst();
        assertThat(record.reqBodyBytes()).isEqualTo((long) body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(record.truncated()).isTrue();
    }

    @Test
    @DisplayName("SSE는 스트림이 끝난 뒤 done 이벤트 data만 마스킹해서 남기고, 클라이언트에는 전체를 보낸다")
    void recordsOnlyDoneEventOfEventStream() throws Exception {
        final String stream = "event:message\ndata:{\"text\":\"" + "중간청크".repeat(100) + "\"}\n\n"
                + "event:done\ndata:{\"code\":\"START_SUCCESS\",\"data\":{\"user_name\":\"홍길동\"}}\n\n";
        final MockHttpServletRequest request = sseRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();

        sseFilter("max-body-bytes", "200").doFilter(request, response, (req, res) -> {
            req.startAsync(req, res);
            res.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            res.setCharacterEncoding("UTF-8");
            res.getOutputStream().write(stream.getBytes(StandardCharsets.UTF_8));
        });

        assertThat(records).as("비동기가 끝나기 전에는 기록하지 않는다").isEmpty();
        ((MockAsyncContext) request.getAsyncContext()).complete();

        final ApiAccessLogRecord record = records.getFirst();
        assertThat(record.skipReason()).isEqualTo("SSE");
        assertThat(record.resBody())
                .contains("START_SUCCESS")
                .contains("\"user_name\":\"***\"")
                .doesNotContain("중간청크", "홍길동");
        assertThat(record.resBodyBytes()).isEqualTo((long) stream.getBytes(StandardCharsets.UTF_8).length);
        assertThat(record.truncated()).isFalse();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(stream);
    }

    @Test
    @DisplayName("done 없이 끝난 SSE는 본문 없이 SSE_NO_DONE으로 남긴다")
    void marksEventStreamWithoutDone() throws Exception {
        final MockHttpServletRequest request = sseRequest();

        sseFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            req.startAsync(req, res);
            res.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            res.getOutputStream().write("event:message\ndata:{\"text\":\"a\"}\n\n".getBytes(StandardCharsets.UTF_8));
        });
        ((MockAsyncContext) request.getAsyncContext()).complete();

        final ApiAccessLogRecord record = records.getFirst();
        assertThat(record.skipReason()).isEqualTo("SSE_NO_DONE");
        assertThat(record.resBody()).isNull();
    }

    @Test
    @DisplayName("SSE error 이벤트로 끝나면 그 data와 에러 표시를 남긴다")
    void recordsErrorEventOfEventStream() throws Exception {
        final MockHttpServletRequest request = sseRequest();

        sseFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            req.startAsync(req, res);
            res.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            res.getOutputStream().write("event:error\ndata:{\"message\":\"실패\"}\n\n".getBytes(StandardCharsets.UTF_8));
        });
        ((MockAsyncContext) request.getAsyncContext()).complete();

        final ApiAccessLogRecord record = records.getFirst();
        assertThat(record.resBody()).contains("실패");
        assertThat(record.error()).contains("SSE error");
    }

    private ApiAccessLogFilter sseFilter(String... properties) {
        final ApiAccessLogProperties props = TestProperties.of(properties);
        return new ApiAccessLogFilter(props, new CapturePolicy(props),
                new BodyMasker(props, JsonMapper.builder().build()), records::add);
    }

    private static MockHttpServletRequest sseRequest() {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/stream");
        request.addHeader("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
        request.setAsyncSupported(true);
        return request;
    }

    @Test
    @DisplayName("제외 경로와 대상 밖 경로는 기록하지 않는다")
    void ignoresExcludedPaths() throws Exception {
        final MockMvc mvc = mockMvc();
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk());

        assertThat(records).isEmpty();
    }

    @Test
    @DisplayName("응답 본문은 클라이언트에 그대로 전달된다")
    void passesResponseThrough() throws Exception {
        mockMvc().perform(get("/api/v1/large"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("{\"data\":\"xxxx")));
    }

    @RestController
    static class TestController {

        @PostMapping("/api/v1/users/{id}")
        Map<String, Object> echo(@PathVariable String id, @RequestBody Map<String, Object> body) {
            return Map.of("id", id, "name", "홍길동", "agentName", body.get("agentName"));
        }

        @GetMapping("/api/v1/fail")
        Map<String, Object> fail() {
            throw new IllegalStateException("SQL syntax error near 010-1234-5678");
        }

        @GetMapping("/api/v1/large")
        Map<String, Object> large() {
            return Map.of("data", "x".repeat(500));
        }

        @GetMapping({"/actuator/health", "/health"})
        String health() {
            return "UP";
        }

        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<Map<String, Object>> handle(IllegalStateException e) {
            return ResponseEntity.internalServerError().body(Map.of("code", "FAILED"));
        }
    }
}
