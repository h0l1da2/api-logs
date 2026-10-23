package io.github.h0l1da2.apilog.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("파일 역순 줄 읽기")
class ReverseLineReaderTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("마지막 줄부터 읽고 각 줄의 시작 위치를 돌려준다")
    void readsLinesBackwardWithOffsets() throws Exception {
        final Path file = Files.writeString(directory.resolve("a.jsonl"), "첫째\nsecond\n\nthird\n");

        final List<ReverseLineReader.Line> lines = readAll(file, Long.MAX_VALUE);

        assertThat(lines).extracting(ReverseLineReader.Line::text).containsExactly("third", "second", "첫째");
        assertThat(lines.get(1).offset()).isEqualTo("첫째\n".getBytes().length);
    }

    @Test
    @DisplayName("지정한 위치 앞부터 이어 읽는다")
    void resumesBeforeOffset() throws Exception {
        final Path file = Files.writeString(directory.resolve("a.jsonl"), "one\ntwo\nthree\n");
        final long offsetOfTwo = "one\n".length();

        assertThat(readAll(file, offsetOfTwo)).extracting(ReverseLineReader.Line::text).containsExactly("one");
    }

    @Test
    @DisplayName("블록 경계에 걸친 긴 줄과 한글도 깨지지 않는다")
    void handlesLinesAcrossBlocks() throws Exception {
        final String longLine = "가".repeat(40_000);
        final String content = IntStream.range(0, 3).mapToObj(i -> i + longLine).collect(Collectors.joining("\n"));
        final Path file = Files.writeString(directory.resolve("a.jsonl"), content);

        final List<ReverseLineReader.Line> lines = readAll(file, Long.MAX_VALUE);

        assertThat(lines).extracting(ReverseLineReader.Line::text)
                .containsExactly("2" + longLine, "1" + longLine, "0" + longLine);
    }

    private static List<ReverseLineReader.Line> readAll(Path file, long end) throws Exception {
        final List<ReverseLineReader.Line> lines = new ArrayList<>();
        try (ReverseLineReader reader = new ReverseLineReader(file, end)) {
            ReverseLineReader.Line line;
            while ((line = reader.next()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }
}
