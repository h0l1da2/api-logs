package io.github.h0l1da2.apilog.capture;

import io.github.h0l1da2.apilog.spi.CallerResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 컨트롤러 실행 직전에 caller를 요청 속성에 남긴다.
 *
 * 설계 의도:
 * - 기록 필터는 Spring Security보다 바깥에 있어 사용자를 직접 알 수 없다. 인증이 끝난 이 시점에 미리 담아 둔다
 */
public class CallerCaptureInterceptor implements HandlerInterceptor {

    public static final String CALLER_ATTRIBUTE = CallerCaptureInterceptor.class.getName() + ".caller";

    private final CallerResolver callerResolver;

    public CallerCaptureInterceptor(CallerResolver callerResolver) {
        this.callerResolver = callerResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(CALLER_ATTRIBUTE) == null) {
            try {
                request.setAttribute(CALLER_ATTRIBUTE, callerResolver.resolve(request));
            } catch (RuntimeException e) {
                request.setAttribute(CALLER_ATTRIBUTE, CallerResolver.ANONYMOUS);
            }
        }
        return true;
    }
}
