package io.github.h0l1da2.apilog.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SSE 종료 이벤트 캡처")
class SseTerminalEventCaptureTest {

    private static void writeInPieces(SseTerminalEventCapture capture, String text, int piece) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i += piece) {
            capture.write(bytes, i, Math.min(piece, bytes.length - i));
        }
    }

    @Test
    @DisplayName("조각나서 써져도 마지막 done 이벤트 data만 남긴다")
    void keepsLastDoneAcrossSplitWrites() {
        final SseTerminalEventCapture capture = new SseTerminalEventCapture(1000);
        writeInPieces(capture, "event:message\r\ndata:" + "x".repeat(5000) + "\r\n\r\n"
                + "event: done\r\ndata: {\"a\":1}\r\ndata: {\"b\":2}\r\n\r\n", 7);

        final SseTerminalEventCapture.TerminalEvent event = capture.lastTerminalEvent();
        assertThat(event.name()).isEqualTo("done");
        assertThat(new String(event.data(), StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}\n{\"b\":2}");
    }

    @Test
    @DisplayName("done data가 상한보다 크면 앞부분만 남기고 원래 크기를 센다")
    void truncatesLargeDone() {
        final SseTerminalEventCapture capture = new SseTerminalEventCapture(10);
        writeInPieces(capture, "event:done\ndata:" + "y".repeat(50) + "\n\n", 3);

        final SseTerminalEventCapture.TerminalEvent event = capture.lastTerminalEvent();
        assertThat(event.data()).hasSize(10);
        assertThat(event.totalBytes()).isEqualTo(50);
    }

    @Test
    @DisplayName("종료 이벤트가 없으면 null")
    void nullWithoutTerminal() {
        final SseTerminalEventCapture capture = new SseTerminalEventCapture(100);
        writeInPieces(capture, "event:message\ndata:abc\n\n", 4);

        assertThat(capture.lastTerminalEvent()).isNull();
    }
}
