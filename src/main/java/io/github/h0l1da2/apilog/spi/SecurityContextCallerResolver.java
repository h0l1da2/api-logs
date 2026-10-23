package io.github.h0l1da2.apilog.spi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Spring Security가 있을 때의 기본 구현. 인증된 사용자의 이름({@link Authentication#getName()})을 쓴다.
 */
public class SecurityContextCallerResolver implements CallerResolver {

    @Override
    public String resolve(HttpServletRequest request) {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return ANONYMOUS;
        }
        return authentication.getName();
    }
}
