package io.github.h0l1da2.apilog.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import io.github.h0l1da2.apilog.TestProperties;
import io.github.h0l1da2.apilog.support.Ulid;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("API 호출 기록 파일 쓰기")
class ApiAccessLogWriterTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @TempDir
    Path directory;

    @Test
    @DisplayName("기록 id의 날짜별 파일에 한 줄씩 쓰고, 멈출 때 남은 기록까지 쓴다")
    void writesRecordsIntoDailyFiles() throws Exception {
        final ApiAccessLogProperties properties = TestProperties.of("directory", directory.toString());
        final ApiAccessLogWriter writer = new ApiAccessLogWriter(properties, JsonMapper.builder().build());
        final LocalDate today = LocalDate.of(2026, 10, 22);

        writer.start();
        writer.offer(record(today));
        writer.offer(record(today));
        writer.offer(record(today.minusDays(1)));
        writer.stop();

        final List<String> todayLines = Files.readAllLines(LogFiles.fileFor(directory, today));
        final List<String> yesterdayLines = Files.readAllLines(LogFiles.fileFor(directory, today.minusDays(1)));
        assertThat(todayLines).hasSize(2);
        assertThat(yesterdayLines).hasSize(1);
        assertThat(todayLines.getFirst()).startsWith("{").contains("\"schemaVersion\":1").contains("\"status\":200");
        assertThat(writer.dropped()).isZero();
    }

    @Test
    @DisplayName("파일은 소유자만 읽고 쓸 수 있게 만든다")
    void createsOwnerOnlyFiles() throws Exception {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        final ApiAccessLogWriter writer = new ApiAccessLogWriter(
                TestProperties.of("directory", directory.toString()), JsonMapper.builder().build());
        final LocalDate today = LocalDate.of(2026, 10, 22);

        writer.start();
        writer.offer(record(today));
        writer.stop();

        assertThat(PosixFilePermissions.toString(
                Files.getPosixFilePermissions(LogFiles.fileFor(directory, today)))).isEqualTo("rw-------");
    }

    @Test
    @DisplayName("멈춘 뒤 들어온 기록은 버리고 건수를 센다")
    void countsDroppedRecordsWhenStopped() {
        final ApiAccessLogWriter writer = new ApiAccessLogWriter(
                TestProperties.of("directory", directory.toString()), JsonMapper.builder().build());

        writer.offer(record(LocalDate.of(2026, 10, 22)));

        assertThat(writer.dropped()).isEqualTo(1);
    }

    static ApiAccessLogRecord record(LocalDate date) {
        final long millis = date.atTime(12, 0).atZone(SEOUL).toInstant().toEpochMilli();
        return new ApiAccessLogRecord(1, Ulid.generate(millis), date + "T12:00:00+09:00", "req", "GET",
                "/api/v1/x", "/api/v1/x", null, 200, 5, "anonymous", "127.0.0.1", null, null,
                null, "application/json", null, "{}", null, 2L, false, null, null);
    }
}
