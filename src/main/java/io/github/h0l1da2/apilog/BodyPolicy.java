package io.github.h0l1da2.apilog;

/**
 * route별로 본문을 어디까지 저장할지 정하는 정책이다.
 */
public enum BodyPolicy {

    /** 요청·응답 본문을 모두 저장한다 (기본값). */
    FULL,

    /** 요청 본문만 저장하고 응답은 상태·시간만 남긴다. */
    REQUEST_ONLY,

    /** 본문 없이 메타 정보만 남긴다. */
    NONE
}
