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

/**
 * 응답을 그대로 흘려보내면서 앞부분(최대 {@code limit} 바이트)만 복사해 두는 래퍼다.
 *
 * 설계 의도:
 * - ContentCachingResponseWrapper처럼 전체를 메모리에 쌓지 않는다. SSE·파일 다운로드도 스트리밍이 깨지지 않는다
 * - 총 바이트 수는 끝까지 센다(자르기 전 원래 크기 기록용)
 */
public class CapturingResponseWrapper extends HttpServletResponseWrapper {

    private final int limit;
    private final ByteArrayOutputStream captured;
    private long totalBytes;
    private TeeOutputStream outputStream;
    private PrintWriter writer;

    public CapturingResponseWrapper(HttpServletResponse response, int limit) {
        super(response);
        this.limit = limit;
        this.captured = new ByteArrayOutputStream(Math.min(limit, 8192));
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

    public byte[] capturedBytes() {
        return captured.toByteArray();
    }

    public long totalBytes() {
        return totalBytes;
    }

    private void capture(byte[] bytes, int offset, int length) {
        totalBytes += length;
        final int room = limit - captured.size();
        if (room > 0) {
            captured.write(bytes, offset, Math.min(room, length));
        }
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
