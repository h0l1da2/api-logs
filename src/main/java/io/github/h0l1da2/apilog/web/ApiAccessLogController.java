package io.github.h0l1da2.apilog.web;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import io.github.h0l1da2.apilog.query.ApiAccessLogReader;
import io.github.h0l1da2.apilog.query.LogPage;
import io.github.h0l1da2.apilog.query.LogQuery;
import io.github.h0l1da2.apilog.query.LogSummary;
import io.github.h0l1da2.apilog.store.ApiAccessLogRecord;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 호출 기록 조회 API. 경로는 {@code ${api-access-log.base-path}/api} (기본 /api-logs/api).
 *
 * 주의:
 * - 앱의 전역 예외 처리기에 기대지 않도록 오류도 ResponseEntity로 직접 돌려준다
 * - 보안은 이 모듈이 구성하지 않는다. 앱이 base-path를 보호해야 한다
 * - 시각 파라미터는 {@code 2026-10-22T09:00}(설정 zone 기준) 또는 오프셋 포함 ISO 형식
 */
@RestController
@RequestMapping("${api-access-log.base-path:/api-logs}/api")
public class ApiAccessLogController {

    private final ApiAccessLogReader reader;
    private final ZoneId zone;

    public ApiAccessLogController(ApiAccessLogReader reader, ApiAccessLogProperties properties) {
        this.reader = reader;
        this.zone = properties.zone();
    }

    @GetMapping("/logs")
    public ResponseEntity<?> logs(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String caller,
            @RequestParam(required = false) Long minElapsedMs,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor
    ) throws IOException {
        try {
            final LogQuery query = new LogQuery(toMillis(from), toMillis(to), splitComma(status), method, path,
                    requestId, caller, minElapsedMs, q, size, cursor);
            final LogPage page = reader.search(query);
            return ResponseEntity.ok(page);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/logs/{id}")
    public ResponseEntity<?> log(@PathVariable String id) throws IOException {
        return reader.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "기록을 찾을 수 없습니다. 보관 기간이 지났거나 id가 틀렸습니다.")));
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) throws IOException {
        try {
            final LogSummary summary = reader.summarize(toMillis(from), toMillis(to));
            return ResponseEntity.ok(summary);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    private Long toMillis(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 오프셋 없는 형식이면 설정 zone으로 해석한다
        }
        try {
            return LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("시각 형식이 아닙니다: " + value);
        }
    }

    private static List<String> splitComma(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().flatMap(v -> List.of(v.split(",")).stream()).toList();
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
