package io.github.h0l1da2.apilog.capture;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/**
 * 응답을 그대로 흘려보내면서 저장할 만큼만 복사해 두는 래퍼다.
 *
 * 캡처 방식({@link Mode}):
 * - BUFFER: 앞부분(최대 {@code limit} 바이트)만 복사한다. 일반 JSON·텍스트 응답
 * - EVENT_STREAM: 마지막 종료 이벤트(done/error)의 data만 보관한다 ({@link SseTerminalEventCapture})
 * - COUNT_ONLY: 복사하지 않고 바이트 수만 센다. 바이너리 응답, 응답 본문을 저장하지 않는 정책
 *
 * 설계 의도:
 * - ContentCachingResponseWrapper처럼 전체를 메모리에 쌓지 않는다. SSE·파일 다운로드도 스트리밍이 깨지지 않는다
 * - 처음 쓰기 시점에 응답 Content-Type을 보고 방식을 확정한다. SSE·바이너리로 확정되면 그때까지 복사한 것도 버린다
 * - 총 바이트 수는 끝까지 센다(자르기 전 원래 크기 기록용)
 */
public class CapturingResponseWrapper extends HttpServletResponseWrapper {

    public enum Mode {
        /** Content-Type을 보고 정한다. 정해지기 전에는 BUFFER처럼 복사한다. */
        AUTO,
        /** SSE로 예상된다(Accept: text/event-stream). Content-Type이 정해지기 전에는 복사하지 않는다. */
        EXPECT_EVENT_STREAM,
        BUFFER,
        EVENT_STREAM,
        COUNT_ONLY
    }

    private final int limit;
    private final Predicate<String> textual;
    private final Predicate<String> eventStream;
    private ByteArrayOutputStream captured;
    private SseTerminalEventCapture events;
    private volatile Mode mode;
    private volatile long totalBytes;
    private TeeOutputStream outputStream;
    private PrintWriter writer;

    public CapturingResponseWrapper(HttpServletResponse response, int limit) {
        this(response, limit, Mode.AUTO, CapturingResponseWrapper::looksTextual, CapturingResponseWrapper::looksEventStream);
    }

    public CapturingResponseWrapper(HttpServletResponse response, int limit, Mode initialMode,
                                    Predicate<String> textual, Predicate<String> eventStream) {
        super(response);
        this.limit = Math.max(limit, 0);
        this.textual = textual;
        this.eventStream = eventStream;
        this.mode = initialMode;
        if (initialMode == Mode.EVENT_STREAM) {
            this.events = new SseTerminalEventCapture(this.limit);
        }
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (outputStream == null) {
            outputStream = new TeeOutputStream(super.getOutputStream());
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            final String encoding = getCharacterEncoding();
            final Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.ISO_8859_1;
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset), false);
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        super.flushBuffer();
    }

    /** 요청 처리 후 writer 버퍼에 남은 내용을 실제 응답으로 내보낸다. */
    public void flushWriterQuietly() {
        if (writer != null) {
            writer.flush();
        }
    }

    /** BUFFER 방식으로 복사한 앞부분. 다른 방식이면 비어 있다. */
    public synchronized byte[] capturedBytes() {
        return mode() == Mode.BUFFER && captured != null ? captured.toByteArray() : new byte[0];
    }

    public long totalBytes() {
        return totalBytes;
    }

    /** 확정된 캡처 방식. 아직 쓰기가 없었다면 Content-Type으로 판단한다. */
    public synchronized Mode mode() {
        decideMode();
        return switch (mode) {
            case AUTO -> Mode.BUFFER;
            case EXPECT_EVENT_STREAM -> Mode.COUNT_ONLY;
            default -> mode;
        };
    }

    /** SSE 응답의 마지막 종료 이벤트(done/error). SSE가 아니거나 종료 이벤트가 없으면 null. */
    public synchronized SseTerminalEventCapture.TerminalEvent lastTerminalEvent() {
        return events == null ? null : events.lastTerminalEvent();
    }

    private synchronized void capture(byte[] bytes, int offset, int length) {
        totalBytes += length;
        decideMode();
        switch (mode) {
            case AUTO, BUFFER -> buffer(bytes, offset, length);
            case EVENT_STREAM -> events.write(bytes, offset, length);
            case EXPECT_EVENT_STREAM, COUNT_ONLY -> {
                // 복사하지 않는다
            }
        }
    }

    private void buffer(byte[] bytes, int offset, int length) {
        if (captured == null) {
            captured = new ByteArrayOutputStream(Math.min(Math.max(limit, 1), 8192));
        }
        final int room = limit - captured.size();
        if (room > 0) {
            captured.write(bytes, offset, Math.min(room, length));
        }
    }

    /** 미정(AUTO·EXPECT_EVENT_STREAM)이고 Content-Type이 정해졌으면 방식을 확정한다. */
    private void decideMode() {
        if (mode != Mode.AUTO && mode != Mode.EXPECT_EVENT_STREAM) {
            return;
        }
        final String contentType = getContentType();
        if (contentType == null) {
            return;
        }
        if (eventStream.test(contentType)) {
            events = new SseTerminalEventCapture(limit);
            if (captured != null) {
                // Content-Type보다 먼저 쓰인 부분이 있으면 파서에 넘기고 버퍼는 버린다
                final byte[] early = captured.toByteArray();
                events.write(early, 0, early.length);
                captured = null;
            }
            mode = Mode.EVENT_STREAM;
        } else if (textual.test(contentType)) {
            mode = Mode.BUFFER;
        } else {
            captured = null;
            mode = Mode.COUNT_ONLY;
        }
    }

    private static boolean looksTextual(String contentType) {
        final String type = contentType.toLowerCase(java.util.Locale.ROOT);
        return type.contains("json") || type.startsWith("text/") || type.contains("xml");
    }

    private static boolean looksEventStream(String contentType) {
        return contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream");
    }

    private final class TeeOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;

        private TeeOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            capture(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            capture(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }
    }
}
