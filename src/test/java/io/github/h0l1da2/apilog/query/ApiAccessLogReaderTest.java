package io.github.h0l1da2.apilog.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import io.github.h0l1da2.apilog.TestProperties;
import io.github.h0l1da2.apilog.store.ApiAccessLogRecord;
import io.github.h0l1da2.apilog.store.LogFiles;
import io.github.h0l1da2.apilog.support.Ulid;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("API 호출 기록 조회")
class ApiAccessLogReaderTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @TempDir
    Path directory;

    private ApiAccessLogReader reader;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        final ApiAccessLogProperties properties = TestProperties.of("directory", directory.toString());
        reader = new ApiAccessLogReader(properties, mapper, () -> 3L);
        today = LocalDate.now(SEOUL);
    }

    @Test
    @DisplayName("최신순으로 읽고, 커서로 다음 페이지를 빠짐·중복 없이 이어 읽는다")
    void pagesNewestFirst() throws Exception {
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(write(today, 9, i, 200, "/api/v1/a", "{}"));
        }
        write(today.minusDays(1), 9, 0, 200, "/api/v1/old", "{}");

        final LogPage first = reader.search(query(2, null, List.of()));
        final LogPage second = reader.search(query(2, first.nextCursor(), List.of()));
        final LogPage third = reader.search(query(10, second.nextCursor(), List.of()));

        assertThat(first.items()).extracting(LogItem::id).containsExactly(ids.get(4), ids.get(3));
        assertThat(second.items()).extracting(LogItem::id).containsExactly(ids.get(2), ids.get(1));
        assertThat(third.items()).extracting(LogItem::path).containsExactly("/api/v1/a", "/api/v1/old");
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    @DisplayName("상태 클래스·코드, 경로, 본문 검색 조건으로 거른다")
    void filters() throws Exception {
        write(today, 9, 0, 200, "/api/v1/users", "{\"ok\":true}");
        write(today, 9, 1, 404, "/api/v1/users/9", "{\"code\":\"NOT_FOUND\"}");
        write(today, 9, 2, 500, "/api/v1/emon", "{\"error\":\"SQL syntax\"}");

        assertThat(reader.search(query(10, null, List.of("5xx"))).items())
                .extracting(LogItem::status).containsExactly(500);
        assertThat(reader.search(query(10, null, List.of("404", "5xx"))).items()).hasSize(2);
        assertThat(reader.search(new LogQuery(null, null, List.of(), null, "users", null, null, null,
                null, 10, null)).items()).hasSize(2);
        assertThat(reader.search(new LogQuery(null, null, List.of(), null, null, null, null, null,
                "sql syntax", 10, null)).items()).extracting(LogItem::path).containsExactly("/api/v1/emon");
    }

    @Test
    @DisplayName("id로 상세 기록을 찾고, 없으면 빈 값을 돌려준다")
    void findsById() throws Exception {
        final String id = write(today, 9, 0, 200, "/api/v1/a", "{\"full\":\"body\"}");

        assertThat(reader.findById(id)).get().extracting(ApiAccessLogRecord::resBody).isEqualTo("{\"full\":\"body\"}");
        assertThat(reader.findById(Ulid.generate(System.currentTimeMillis()))).isEmpty();
        assertThat(reader.findById("not-an-id")).isEmpty();
    }

    @Test
    @DisplayName("기간 안의 기록으로 상태·route·시간대 집계를 만든다")
    void summarizes() throws Exception {
        write(today, 9, 0, 200, "/api/v1/a", "{}");
        write(today, 9, 1, 404, "/api/v1/a", "{}");
        write(today, 10, 0, 500, "/api/v1/b", "{}");
        final long from = today.atTime(0, 0).atZone(SEOUL).toInstant().toEpochMilli();
        final long to = today.plusDays(1).atTime(0, 0).atZone(SEOUL).toInstant().toEpochMilli();

        final LogSummary summary = reader.summarize(from, to);

        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.ok()).isEqualTo(1);
        assertThat(summary.clientErr()).isEqualTo(1);
        assertThat(summary.serverErr()).isEqualTo(1);
        assertThat(summary.byRoute().getFirst()).isEqualTo(new LogSummary.RouteCount("/api/v1/a", 2, 1));
        assertThat(summary.byHour()).extracting(LogSummary.HourCount::hour)
                .containsExactly(today + "T09:00", today + "T10:00");
        assertThat(summary.dropped()).isEqualTo(3);
    }

    @Test
    @DisplayName("깨진 줄은 건너뛰고, 잘못된 커서는 거부한다")
    void skipsBrokenLinesAndRejectsBadCursor() throws Exception {
        write(today, 9, 0, 200, "/api/v1/a", "{}");
        Files.writeString(LogFiles.fileFor(directory, today), "{\"broken\n", StandardOpenOption.APPEND);

        assertThat(reader.search(query(10, null, List.of())).items()).hasSize(1);
        assertThatThrownBy(() -> reader.search(query(10, "garbage", List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LogQuery query(int size, String cursor, List<String> statuses) {
        return new LogQuery(null, null, statuses, null, null, null, null, null, null, size, cursor);
    }

    private String write(LocalDate date, int hour, int second, int status, String path, String resBody)
            throws Exception {
        final long millis = date.atTime(hour, 0, second).atZone(SEOUL).toInstant().toEpochMilli();
        final String id = Ulid.generate(millis);
        final ApiAccessLogRecord record = new ApiAccessLogRecord(1, id, date + "T00:00:00+09:00", "req", "GET",
                path, path, null, status, 10L * (second + 1), "anonymous", "127.0.0.1", null, null, null,
                "application/json", null, resBody, null, (long) resBody.length(), false, null, null);
        Files.createDirectories(directory);
        Files.writeString(LogFiles.fileFor(directory, date), mapper.writeValueAsString(record) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return id;
    }
}
