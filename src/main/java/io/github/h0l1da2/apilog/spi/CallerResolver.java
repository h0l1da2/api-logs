package io.github.h0l1da2.apilog.spi;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청을 보낸 주체(caller)를 기록용 문자열로 바꾼다. 앱이 빈으로 등록하면 기본 구현을 대체한다.
 *
 * 주의:
 * - 컨트롤러 실행 직전(인터셉터)에 호출된다. 인증에서 막힌 요청은 호출되지 않고 {@link #ANONYMOUS}로 남는다
 * - 개인정보(이름 등)를 돌려주지 않는다. id·역할 정도만 쓴다
 */
@FunctionalInterface
public interface CallerResolver {

    String ANONYMOUS = "anonymous";

    String resolve(HttpServletRequest request);
}
