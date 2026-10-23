package io.github.h0l1da2.apilog.store;

/**
 * 필터가 만든 기록을 받는 곳. 운영에서는 {@link ApiAccessLogWriter}, 테스트에서는 목록 등으로 바꿔 끼운다.
 *
 * 주의:
 * - 요청 스레드에서 호출된다. 막히거나 예외를 던지면 안 된다
 */
@FunctionalInterface
public interface ApiAccessLogSink {

    void offer(ApiAccessLogRecord record);
}
