package io.github.h0l1da2.apilog.capture;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.h0l1da2.apilog.capture.CapturingResponseWrapper.Mode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("응답 캡처 래퍼")
class CapturingResponseWrapperTest {

    private static CapturingResponseWrapper wrapper(MockHttpServletResponse response, int limit, Mode mode) {
        final CapturePolicy policy = new CapturePolicy(io.github.h0l1da2.apilog.TestProperties.of());
        return new CapturingResponseWrapper(response, limit, mode, policy::isTextual, policy::isEventStream);
    }

    @Test
    @DisplayName("바이너리로 확정되면 복사하지 않고 바이트 수만 센다")
    void countsOnlyBinary() throws Exception {
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final CapturingResponseWrapper wrapper = wrapper(response, 100, Mode.AUTO);
        wrapper.setContentType("application/octet-stream");
        wrapper.getOutputStream().write(new byte[500]);

        assertThat(wrapper.mode()).isEqualTo(Mode.COUNT_ONLY);
        assertThat(wrapper.capturedBytes()).isEmpty();
        assertThat(wrapper.totalBytes()).isEqualTo(500);
        assertThat(response.getContentAsByteArray()).hasSize(500);
    }

    @Test
    @DisplayName("SSE로 확정되면 앞부분 버퍼 대신 종료 이벤트만 보관한다")
    void keepsOnlyTerminalEventOfEventStream() throws Exception {
        final CapturingResponseWrapper wrapper = wrapper(new MockHttpServletResponse(), 1000, Mode.AUTO);
        wrapper.setContentType("text/event-stream");
        wrapper.getOutputStream().write("event:message\ndata:abc\n\nevent:done\ndata:{\"ok\":true}\n\n"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.mode()).isEqualTo(Mode.EVENT_STREAM);
        assertThat(wrapper.capturedBytes()).isEmpty();
        assertThat(new String(wrapper.lastTerminalEvent().data(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("SSE 요청이어도 JSON 에러 응답이면 일반 응답처럼 앞부분을 남긴다")
    void buffersJsonWhenEventStreamExpected() throws Exception {
        final CapturingResponseWrapper wrapper = wrapper(new MockHttpServletResponse(), 1000, Mode.EXPECT_EVENT_STREAM);
        wrapper.setContentType("application/json");
        wrapper.getOutputStream().write("{\"code\":\"UNAUTHORIZED\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.mode()).isEqualTo(Mode.BUFFER);
        assertThat(new String(wrapper.capturedBytes(), StandardCharsets.UTF_8)).contains("UNAUTHORIZED");
    }

    @Test
    @DisplayName("응답 본문을 저장하지 않는 정책이면 처음부터 복사하지 않는다")
    void countsOnlyWhenPolicyExcludesBody() throws Exception {
        final CapturingResponseWrapper wrapper = wrapper(new MockHttpServletResponse(), 1000, Mode.COUNT_ONLY);
        wrapper.setContentType("application/json");
        wrapper.getOutputStream().write("{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(wrapper.capturedBytes()).isEmpty();
        assertThat(wrapper.totalBytes()).isEqualTo(7);
    }
}
