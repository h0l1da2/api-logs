package io.github.h0l1da2.apilog.capture;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * SSE 응답을 흘려보내며 줄 단위로 파싱해 마지막 종료 이벤트({@code done}/{@code error})의 data만 보관한다.
 *
 * 설계 의도:
 * - 채팅 SSE는 message·table 같은 중간 청크가 많고 연결이 길다. 전부 복사하면 연결 수에 비례해 힙을 쓴다
 * - 로그로 남길 가치가 있는 건 결과 요약인 종료 이벤트뿐이므로, 그 data만 상한({@code limit})까지 보관한다
 * - 나머지 이벤트는 줄 머리(이벤트 이름 판별용 몇십 바이트)만 잠깐 보고 버린다
 *
 * 주의:
 * - {@code event:} 줄이 {@code data:} 줄보다 먼저 와야 종료 이벤트로 인식한다 (Spring SseEmitter 출력 순서)
 * - 응답 스레드와 기록 스레드가 다를 수 있어 메서드를 동기화한다
 */
final class SseTerminalEventCapture {

    static final Set<String> TERMINAL_EVENTS = Set.of("done", "error");

    /** 종료 이벤트가 아닌 줄은 이만큼만 보고 버린다. "event:" + 이벤트 이름을 판별하기에 충분한 길이. */
    private static final int LINE_HEAD_BYTES = 64;

    private final int limit;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream(LINE_HEAD_BYTES);
    private long lineLength;
    private boolean previousCr;

    private String eventName;
    private final ByteArrayOutputStream data = new ByteArrayOutputStream(256);
    private long dataBytes;

    private TerminalEvent last;

    SseTerminalEventCapture(int limit) {
        this.limit = Math.max(limit, 0);
    }

    /** 마지막 종료 이벤트. 없으면 null. */
    record TerminalEvent(String name, byte[] data, long totalBytes) {
    }

    synchronized void write(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            accept(bytes[i]);
        }
    }

    synchronized TerminalEvent lastTerminalEvent() {
        return last;
    }

    private void accept(byte b) {
        if (b == '\n' && previousCr) {
            previousCr = false;
            return;
        }
        previousCr = b == '\r';
        if (b == '\r' || b == '\n') {
            endLine();
            return;
        }
        lineLength++;
        final long keep = isTerminal() ? (long) limit + LINE_HEAD_BYTES : LINE_HEAD_BYTES;
        if (line.size() < keep) {
            line.write(b);
        }
    }

    private void endLine() {
        if (lineLength == 0) {
            dispatch();
            return;
        }
        final byte[] bytes = line.toByteArray();
        if (startsWith(bytes, "event:")) {
            eventName = new String(bytes, StandardCharsets.UTF_8).substring(6).trim();
        } else if (startsWith(bytes, "data:") && isTerminal()) {
            int start = 5;
            if (bytes.length > start && bytes[start] == ' ') {
                start++;
            }
            if (dataBytes > 0) {
                appendData(new byte[] {'\n'}, 0, 1);
                dataBytes++;
            }
            appendData(bytes, start, bytes.length - start);
            dataBytes += lineLength - start;
        }
        line.reset();
        lineLength = 0;
    }

    private void appendData(byte[] bytes, int offset, int length) {
        final int room = limit - data.size();
        if (room > 0 && length > 0) {
            data.write(bytes, offset, Math.min(room, length));
        }
    }

    private void dispatch() {
        if (isTerminal()) {
            last = new TerminalEvent(eventName, data.toByteArray(), dataBytes);
        }
        eventName = null;
        data.reset();
        dataBytes = 0;
    }

    private boolean isTerminal() {
        return eventName != null && TERMINAL_EVENTS.contains(eventName);
    }

    private static boolean startsWith(byte[] bytes, String prefix) {
        if (bytes.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (bytes[i] != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
