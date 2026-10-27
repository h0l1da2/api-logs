package io.github.h0l1da2.apilog.capture;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 요청 본문을 읽히는 대로 앞부분(최대 {@code limit} 바이트)만 복사해 두는 래퍼다.
 *
 * 설계 의도:
 * - Spring의 ContentCachingRequestWrapper는 인코딩이 없는 요청에 ISO-8859-1을 돌려준다.
 *   그 값이 Content-Type charset으로 붙어 JSON 본문의 한글이 깨지므로 직접 구현했다
 * - 이 래퍼는 {@link #getCharacterEncoding()}을 바꾸지 않는다. 앱이 보는 요청은 원래와 같다
 */
public class CapturingRequestWrapper extends HttpServletRequestWrapper {

    private final int limit;
    private final ByteArrayOutputStream captured;
    private long totalBytes;
    private ServletInputStream inputStream;
    private BufferedReader reader;

    public CapturingRequestWrapper(HttpServletRequest request, int limit) {
        super(request);
        this.limit = limit;
        this.captured = new ByteArrayOutputStream(Math.min(Math.max(limit, 0), 8192));
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (inputStream == null) {
            inputStream = new TeeInputStream(super.getInputStream());
        }
        return inputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (reader == null) {
            final String encoding = getCharacterEncoding();
            Charset charset = StandardCharsets.UTF_8;
            if (encoding != null) {
                try {
                    charset = Charset.forName(encoding);
                } catch (RuntimeException ignored) {
                    // 알 수 없는 인코딩이면 UTF-8로 읽는다
                }
            }
            reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
        return reader;
    }

    /** 지금까지 읽힌 본문의 앞부분. 앱이 본문을 읽지 않았다면 비어 있다. */
    public byte[] capturedBytes() {
        return captured.toByteArray();
    }

    /**
     * 앱이 지금까지 읽은 본문 전체 바이트 수. 상한과 관계없이 센다.
     * chunked 요청처럼 Content-Length가 없을 때 원래 크기와 잘림 여부를 판단하는 데 쓴다.
     * 앱이 본문을 끝까지 읽지 않았다면 실제 크기보다 작다.
     */
    public long totalBytes() {
        return totalBytes;
    }

    private void capture(byte[] bytes, int offset, int length) {
        if (length > 0) {
            totalBytes += length;
        }
        final int room = limit - captured.size();
        if (room > 0 && length > 0) {
            captured.write(bytes, offset, Math.min(room, length));
        }
    }

    private final class TeeInputStream extends ServletInputStream {

        private final ServletInputStream delegate;

        private TeeInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            final int b = delegate.read();
            if (b >= 0) {
                capture(new byte[] {(byte) b}, 0, 1);
            }
            return b;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            final int count = delegate.read(bytes, offset, length);
            if (count > 0) {
                capture(bytes, offset, count);
            }
            return count;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
