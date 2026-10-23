package io.github.h0l1da2.apilog.query;

import java.util.List;

/**
 * 화면 상단 카드와 차트용 집계.
 *
 * @param ok           2xx·3xx 응답 수
 * @param clientErr    4xx 응답 수
 * @param serverErr    5xx 응답 수
 * @param dropped      큐가 차거나 파일 상한에 걸려 기록하지 못한 건수 (서버 재시작 후 누적)
 */
public record LogSummary(
        long total,
        long ok,
        long clientErr,
        long serverErr,
        long avgElapsedMs,
        long p95ElapsedMs,
        List<StatusCount> byStatus,
        List<RouteCount> byRoute,
        List<HourCount> byHour,
        long dropped
) {

    public record StatusCount(int status, long count) {
    }

    /** route별 호출·에러 수. route가 없으면(매핑 전 실패) 경로를 쓴다. */
    public record RouteCount(String route, long count, long errors) {
    }

    /** 1시간 단위 버킷. hour는 "2026-10-22T13:00" 형식(설정한 zone 기준). */
    public record HourCount(String hour, long ok, long err) {
    }
}
