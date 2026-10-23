package io.github.h0l1da2.apilog.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import io.github.h0l1da2.apilog.TestProperties;
import io.github.h0l1da2.apilog.store.ApiAccessLogRecord;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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
    @DisplayName("SSE는 본문 없이 메타 정보만 남기고 스트리밍은 그대로 동작한다")
    void skipsEventStreamBody() throws Exception {
        mockMvc().perform(get("/api/v1/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk());

        final ApiAccessLogRecord record = records.getFirst();
        assertThat(record.skipReason()).isEqualTo("SSE");
        assertThat(record.resBody()).isNull();
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

        @GetMapping(path = "/api/v1/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() {
            return new SseEmitter();
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
