package io.github.h0l1da2.apilog.query;

import java.util.List;
import java.util.Locale;

/**
 * 목록 조회 조건. 비어 있는 조건은 적용하지 않는다.
 *
 * @param fromMillis    이 시각(포함) 이후 요청만. null이면 보관 범위 전체
 * @param toMillis      이 시각(제외) 이전 요청만. null이면 지금까지
 * @param statuses      "2xx" 같은 상태 클래스 또는 "404" 같은 정확한 코드
 * @param path          경로 부분 일치
 * @param requestId     정확히 일치
 * @param caller        부분 일치
 * @param minElapsedMs  이 시간(ms) 이상 걸린 요청만
 * @param text          본문·예외 메시지 등 기록 전체에서 문자열 검색
 * @param cursor        다음 페이지 커서 ({@link LogPage#nextCursor()} 값을 그대로)
 */
public record LogQuery(
        Long fromMillis,
        Long toMillis,
        List<String> statuses,
        String method,
        String path,
        String requestId,
        String caller,
        Long minElapsedMs,
        String text,
        int size,
        String cursor
) {

    public static final int DEFAULT_SIZE = 50;
    public static final int MAX_SIZE = 200;

    public LogQuery {
        statuses = statuses == null ? List.of()
                : statuses.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        method = blankToNull(method);
        path = blankToNull(path);
        requestId = blankToNull(requestId);
        caller = blankToNull(caller);
        text = blankToNull(text);
        cursor = blankToNull(cursor);
    }

    public static LogQuery latest(int size) {
        return new LogQuery(null, null, List.of(), null, null, null, null, null, null, size, null);
    }

    boolean matchesStatus(int status) {
        if (statuses.isEmpty()) {
            return true;
        }
        final String code = String.valueOf(status);
        for (String s : statuses) {
            if (s.length() == 3 && s.endsWith("xx") ? code.charAt(0) == s.charAt(0) : code.equals(s)) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
