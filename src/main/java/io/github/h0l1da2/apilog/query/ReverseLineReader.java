package io.github.h0l1da2.apilog.query;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 파일을 끝에서부터 블록 단위로 읽어 줄을 역순(최신 → 과거)으로 돌려준다.
 *
 * 설계 의도:
 * - 최신 기록부터 필요한 만큼만 읽고 멈출 수 있게 한다 (파일 전체를 메모리에 올리지 않는다)
 * - 각 줄의 시작 위치(바이트 offset)를 같이 돌려줘 페이징 커서로 쓴다
 */
public class ReverseLineReader implements Closeable {

    private static final int BLOCK_SIZE = 64 * 1024;

    private final RandomAccessFile file;
    private byte[] block = new byte[0];
    private int blockIndex;
    private long blockStart;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    /** {@code endExclusive} 앞까지만 읽는다 (커서 이후 페이지). 파일 끝부터면 파일 크기를 넘긴다. */
    public ReverseLineReader(Path path, long endExclusive) throws IOException {
        this.file = new RandomAccessFile(path.toFile(), "r");
        this.blockStart = Math.min(endExclusive, file.length());
        this.blockIndex = 0;
    }

    /** 다음(더 과거) 줄. 더 없으면 null. 빈 줄은 건너뛴다. */
    public Line next() throws IOException {
        while (true) {
            final Line line = readLine();
            if (line == null) {
                return null;
            }
            if (!line.text().isBlank()) {
                return line;
            }
        }
    }

    private Line readLine() throws IOException {
        // pending에는 줄의 뒷부분이 역순 바이트로 쌓인다
        pending.reset();
        boolean any = false;
        while (true) {
            if (blockIndex <= 0) {
                if (blockStart <= 0) {
                    return any ? toLine(0) : null;
                }
                loadPreviousBlock();
            }
            final byte b = block[--blockIndex];
            final long offset = blockStart + blockIndex;
            if (b == '\n') {
                if (any) {
                    return toLine(offset + 1);
                }
                // 파일 끝의 줄바꿈 또는 연속 줄바꿈
                continue;
            }
            pending.write(b);
            any = true;
        }
    }

    private Line toLine(long startOffset) {
        final byte[] reversed = pending.toByteArray();
        for (int i = 0, j = reversed.length - 1; i < j; i++, j--) {
            final byte tmp = reversed[i];
            reversed[i] = reversed[j];
            reversed[j] = tmp;
        }
        return new Line(startOffset, new String(reversed, StandardCharsets.UTF_8));
    }

    private void loadPreviousBlock() throws IOException {
        final long start = Math.max(0, blockStart - BLOCK_SIZE);
        final int length = (int) (blockStart - start);
        block = new byte[length];
        file.seek(start);
        file.readFully(block);
        blockStart = start;
        blockIndex = length;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    /** 줄 내용과 파일 안에서의 시작 위치. */
    public record Line(long offset, String text) {
    }
}
