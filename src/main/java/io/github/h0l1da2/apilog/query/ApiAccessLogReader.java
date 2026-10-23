package io.github.h0l1da2.apilog.query;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import io.github.h0l1da2.apilog.store.ApiAccessLogRecord;
import io.github.h0l1da2.apilog.store.LogFiles;
import io.github.h0l1da2.apilog.support.Ulid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import tools.jackson.databind.ObjectMapper;

/**
 * 날짜별 jsonl 파일을 읽어 목록·상세·집계를 만든다.
 *
 * 책임:
 * - 목록: 최신 파일의 끝에서부터 거꾸로 읽으며 조건에 맞는 기록을 size개 채우면 멈춘다
 * - 상세: 기록 id(ULID)의 시각으로 파일 하나만 골라 찾는다
 * - 집계: 기간 안의 기록을 모두 읽어 계산한다 (보관 2일 기준 수십 MB 이하)
 *
 * 커서 형식:
 * - {@code <날짜>:<바이트 위치>} (예: {@code 2026-10-22:10342}). 그 날짜 파일의 해당 위치 앞부터 이어 읽는다
 * - 파일에는 요청이 끝난 순서로 쌓이므로, id 대신 파일 위치로 이어 읽어야 빠짐·중복이 없다
 */
public class ApiAccessLogReader {

    private final ApiAccessLogProperties properties;
    private final ObjectMapper mapper;
    private final Path directory;
    private final ZoneId zone;
    private final LongSupplier droppedCounter;

    public ApiAccessLogReader(ApiAccessLogProperties properties, ObjectMapper mapper, LongSupplier droppedCounter) {
        this.properties = properties;
        this.mapper = mapper;
        this.directory = properties.directoryPath();
        this.zone = properties.zone();
        this.droppedCounter = droppedCounter;
    }

    public LogPage search(LogQuery query) throws IOException {
        final Cursor cursor = Cursor.parse(query.cursor());
        final List<LogItem> items = new ArrayList<>(query.size());
        long scanned = 0;

        for (LocalDate date : datesNewestFirst(query.fromMillis(), query.toMillis())) {
            if (cursor != null && date.isAfter(cursor.date())) {
                continue;
            }
            final Path file = LogFiles.fileFor(directory, date);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            final long end = cursor != null && date.equals(cursor.date()) ? cursor.offset() : Long.MAX_VALUE;
            try (ReverseLineReader reader = new ReverseLineReader(file, end)) {
                ReverseLineReader.Line line;
                while ((line = reader.next()) != null) {
                    scanned++;
                    if (query.text() != null && !containsIgnoreCase(line.text(), query.text())) {
                        continue;
                    }
                    final Optional<ApiAccessLogRecord> record = parse(line.text());
                    if (record.isEmpty() || !matches(record.get(), query)) {
                        continue;
                    }
                    items.add(LogItem.from(record.get()));
                    if (items.size() >= query.size()) {
                        return new LogPage(items, new Cursor(date, line.offset()).format(), scanned);
                    }
                }
            }
        }
        return new LogPage(items, null, scanned);
    }

    public Optional<ApiAccessLogRecord> findById(String id) throws IOException {
        final LocalDate date;
        try {
            date = LogFiles.dateOfRecord(id, zone);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        final Path file = LogFiles.fileFor(directory, date);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        final String needle = "\"id\":\"" + id + "\"";
        try (ReverseLineReader reader = new ReverseLineReader(file, Long.MAX_VALUE)) {
            ReverseLineReader.Line line;
            while ((line = reader.next()) != null) {
                if (line.text().contains(needle)) {
                    return parse(line.text());
                }
            }
        }
        return Optional.empty();
    }

    public LogSummary summarize(Long fromMillis, Long toMillis) throws IOException {
        final long to = toMillis != null ? toMillis : System.currentTimeMillis();
        final long from = fromMillis != null ? fromMillis : to - ChronoUnit.DAYS.getDuration().toMillis();
        long total = 0;
        long ok = 0;
        long clientErr = 0;
        long serverErr = 0;
        long elapsedSum = 0;
        final List<Long> elapsed = new ArrayList<>();
        final Map<Integer, Long> byStatus = new TreeMap<>();
        final Map<String, long[]> byRoute = new HashMap<>();
        final Map<String, long[]> byHour = new TreeMap<>();

        for (LocalDate date : datesNewestFirst(from, to)) {
            final Path file = LogFiles.fileFor(directory, date);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try (ReverseLineReader reader = new ReverseLineReader(file, Long.MAX_VALUE)) {
                ReverseLineReader.Line line;
                while ((line = reader.next()) != null) {
                    final Optional<ApiAccessLogRecord> parsed = parse(line.text());
                    if (parsed.isEmpty()) {
                        continue;
                    }
                    final ApiAccessLogRecord record = parsed.get();
                    final long millis = millisOf(record);
                    if (millis < from || millis >= to) {
                        continue;
                    }
                    total++;
                    final int status = record.status();
                    final boolean error = status >= 400;
                    if (status >= 500) {
                        serverErr++;
                    } else if (status >= 400) {
                        clientErr++;
                    } else {
                        ok++;
                    }
                    elapsedSum += record.elapsedMs();
                    elapsed.add(record.elapsedMs());
                    byStatus.merge(status, 1L, Long::sum);
                    final String route = record.route() != null ? record.route() : record.path();
                    final long[] routeCount = byRoute.computeIfAbsent(route, key -> new long[2]);
                    routeCount[0]++;
                    if (error) {
                        routeCount[1]++;
                    }
                    final long[] hour = byHour.computeIfAbsent(hourOf(millis), key -> new long[2]);
                    hour[error ? 1 : 0]++;
                }
            }
        }

        elapsed.sort(Comparator.naturalOrder());
        final long p95 = elapsed.isEmpty() ? 0 : elapsed.get((int) Math.ceil(elapsed.size() * 0.95) - 1);
        return new LogSummary(
                total, ok, clientErr, serverErr,
                total == 0 ? 0 : elapsedSum / total,
                p95,
                byStatus.entrySet().stream()
                        .map(e -> new LogSummary.StatusCount(e.getKey(), e.getValue())).toList(),
                byRoute.entrySet().stream()
                        .map(e -> new LogSummary.RouteCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                        .sorted(Comparator.comparingLong(LogSummary.RouteCount::count).reversed())
                        .limit(10)
                        .toList(),
                byHour.entrySet().stream()
                        .map(e -> new LogSummary.HourCount(e.getKey(), e.getValue()[0], e.getValue()[1])).toList(),
                droppedCounter.getAsLong());
    }

    private boolean matches(ApiAccessLogRecord record, LogQuery query) {
        final long millis = millisOf(record);
        if (query.fromMillis() != null && millis < query.fromMillis()) {
            return false;
        }
        if (query.toMillis() != null && millis >= query.toMillis()) {
            return false;
        }
        if (!query.matchesStatus(record.status())) {
            return false;
        }
        if (query.method() != null && !query.method().equalsIgnoreCase(record.method())) {
            return false;
        }
        if (query.path() != null && !containsIgnoreCase(record.path(), query.path())) {
            return false;
        }
        if (query.requestId() != null && !query.requestId().equals(record.requestId())) {
            return false;
        }
        if (query.caller() != null && !containsIgnoreCase(record.caller(), query.caller())) {
            return false;
        }
        return query.minElapsedMs() == null || record.elapsedMs() >= query.minElapsedMs();
    }

    /** 보관 범위 안에서 조회 기간에 걸치는 날짜를 최신순으로. */
    private List<LocalDate> datesNewestFirst(Long fromMillis, Long toMillis) {
        final LocalDate today = LocalDate.now(zone);
        final LocalDate oldestKept = today.minusDays(properties.retentionDays() - 1L);
        LocalDate newest = toMillis != null ? LocalDate.ofInstant(Instant.ofEpochMilli(toMillis), zone) : today;
        LocalDate oldest = fromMillis != null ? LocalDate.ofInstant(Instant.ofEpochMilli(fromMillis), zone) : oldestKept;
        if (newest.isAfter(today)) {
            newest = today;
        }
        if (oldest.isBefore(oldestKept)) {
            oldest = oldestKept;
        }
        final List<LocalDate> dates = new ArrayList<>();
        for (LocalDate date = newest; !date.isBefore(oldest); date = date.minusDays(1)) {
            dates.add(date);
        }
        return dates;
    }

    private Optional<ApiAccessLogRecord> parse(String line) {
        try {
            return Optional.of(mapper.readValue(line, ApiAccessLogRecord.class));
        } catch (RuntimeException e) {
            // 쓰는 중이던 마지막 줄 등 깨진 줄은 건너뛴다
            return Optional.empty();
        }
    }

    private static long millisOf(ApiAccessLogRecord record) {
        try {
            return Ulid.timestamp(record.id());
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private String hourOf(long millis) {
        return Instant.ofEpochMilli(millis).atZone(zone).truncatedTo(ChronoUnit.HOURS)
                .toLocalDateTime().toString();
    }

    private static boolean containsIgnoreCase(String text, String part) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    private record Cursor(LocalDate date, long offset) {

        static Cursor parse(String value) {
            if (value == null) {
                return null;
            }
            final int colon = value.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("커서 형식이 아닙니다: " + value);
            }
            try {
                return new Cursor(LocalDate.parse(value.substring(0, colon)),
                        Long.parseLong(value.substring(colon + 1)));
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new IllegalArgumentException("커서 형식이 아닙니다: " + value, e);
            }
        }

        String format() {
            return date + ":" + offset;
        }
    }
}
