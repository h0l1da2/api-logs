package io.github.h0l1da2.apilog.query;

import java.util.List;

/**
 * 목록 조회 결과.
 *
 * @param items      최신순 기록
 * @param nextCursor 다음 페이지를 읽을 커서. 더 없으면 null
 * @param scanned    이번 조회에서 읽은 줄 수 (조회 비용 확인용)
 */
public record LogPage(List<LogItem> items, String nextCursor, long scanned) {
}
