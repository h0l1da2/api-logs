package io.github.h0l1da2.apilog.query;

import io.github.h0l1da2.apilog.store.ApiAccessLogRecord;

/**
 * 목록 한 줄. 본문은 앞부분만 담는다 (전체는 상세 조회).
 */
public record LogItem(
        String id,
        String ts,
        String requestId,
        String method,
        String path,
        String route,
        int status,
        long elapsedMs,
        String caller,
        boolean truncated,
        String skipReason,
        String error,
        String reqPreview,
        String resPreview
) {

    static final int PREVIEW_LENGTH = 100;

    static LogItem from(ApiAccessLogRecord record) {
        return new LogItem(record.id(), record.ts(), record.requestId(), record.method(), record.path(),
                record.route(), record.status(), record.elapsedMs(), record.caller(), record.truncated(),
                record.skipReason(), preview(record.error()), preview(record.reqBody()), preview(record.resBody()));
    }

    private static String preview(String text) {
        if (text == null || text.length() <= PREVIEW_LENGTH) {
            return text;
        }
        return text.substring(0, PREVIEW_LENGTH) + "…";
    }
}
