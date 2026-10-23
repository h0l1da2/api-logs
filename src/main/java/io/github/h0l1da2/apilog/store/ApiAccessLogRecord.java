package io.github.h0l1da2.apilog.store;

import java.util.Map;

/**
 * jsonl 한 줄 = 요청 한 건. 필드 의미는 설계 문서의 "레코드 스키마" 표를 따른다.
 *
 * 주의:
 * - 본문·헤더·쿼리·예외 메시지는 마스킹과 자르기가 끝난 값만 담는다
 * - 필드를 바꾸면 {@link #CURRENT_SCHEMA_VERSION}을 올린다
 */
public record ApiAccessLogRecord(
        int schemaVersion,
        String id,
        String ts,
        String requestId,
        String method,
        String path,
        String route,
        String query,
        int status,
        long elapsedMs,
        String caller,
        String clientIp,
        Map<String, String> reqHeaders,
        Map<String, String> resHeaders,
        String reqContentType,
        String resContentType,
        String reqBody,
        String resBody,
        Long reqBodyBytes,
        Long resBodyBytes,
        boolean truncated,
        String skipReason,
        String error
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
