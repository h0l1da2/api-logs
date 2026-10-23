package io.github.h0l1da2.apilog;

import io.github.h0l1da2.apilog.capture.ApiAccessLogFilter;
import io.github.h0l1da2.apilog.capture.BodyMasker;
import io.github.h0l1da2.apilog.capture.CallerCaptureInterceptor;
import io.github.h0l1da2.apilog.capture.CapturePolicy;
import io.github.h0l1da2.apilog.query.ApiAccessLogReader;
import io.github.h0l1da2.apilog.spi.CallerResolver;
import io.github.h0l1da2.apilog.spi.SecurityContextCallerResolver;
import io.github.h0l1da2.apilog.store.ApiAccessLogWriter;
import io.github.h0l1da2.apilog.store.RetentionCleaner;
import io.github.h0l1da2.apilog.web.ApiAccessLogController;
import io.github.h0l1da2.apilog.web.ApiAccessLogPageController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * api-access-log 자동 설정이다.
 *
 * 책임:
 * - 서블릿 웹 앱에서 {@code api-access-log.enabled}가 true(기본값)일 때만 모듈을 켠다
 * - 기록 필터를 Spring Security보다 바깥(앱의 requestId 필터 바로 안쪽)에 등록한다
 *
 * 설계 의도:
 * - 패키지를 사용하는 앱과 분리해 컴포넌트 스캔이 아니라 자동 설정으로만 등록되게 한다
 * - JSON 처리는 앱의 ObjectMapper 설정에 영향을 주지도 받지도 않도록 모듈 전용 인스턴스를 쓴다
 *
 * 현재 상태:
 * - 수집·쓰기·보관 삭제·조회 API·화면 모두 등록한다
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "api-access-log", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ApiAccessLogProperties.class)
public class ApiAccessLogAutoConfiguration {

    /** 앱의 requestId 필터(HIGHEST_PRECEDENCE)보다 안쪽, Spring Security(-100)보다 바깥. */
    public static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final String SECURITY_CONTEXT_HOLDER =
            "org.springframework.security.core.context.SecurityContextHolder";

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Bean
    public CapturePolicy apiAccessLogCapturePolicy(ApiAccessLogProperties properties) {
        return new CapturePolicy(properties);
    }

    @Bean
    public BodyMasker apiAccessLogBodyMasker(ApiAccessLogProperties properties) {
        return new BodyMasker(properties, mapper);
    }

    @Bean
    public ApiAccessLogWriter apiAccessLogWriter(ApiAccessLogProperties properties) {
        return new ApiAccessLogWriter(properties, mapper);
    }

    @Bean
    public RetentionCleaner apiAccessLogRetentionCleaner(ApiAccessLogProperties properties) {
        return new RetentionCleaner(properties);
    }

    @Bean
    public ApiAccessLogReader apiAccessLogReader(ApiAccessLogProperties properties, ApiAccessLogWriter writer) {
        return new ApiAccessLogReader(properties, mapper, writer::dropped);
    }

    @Bean
    public ApiAccessLogController apiAccessLogController(ApiAccessLogReader reader,
                                                         ApiAccessLogProperties properties) {
        return new ApiAccessLogController(reader, properties);
    }

    @Bean
    public ApiAccessLogPageController apiAccessLogPageController() {
        return new ApiAccessLogPageController();
    }

    @Bean
    @ConditionalOnMissingBean
    public CallerResolver apiAccessLogCallerResolver() {
        if (ClassUtils.isPresent(SECURITY_CONTEXT_HOLDER, getClass().getClassLoader())) {
            return new SecurityContextCallerResolver();
        }
        return request -> CallerResolver.ANONYMOUS;
    }

    @Bean
    public FilterRegistrationBean<ApiAccessLogFilter> apiAccessLogFilter(
            ApiAccessLogProperties properties, CapturePolicy policy, BodyMasker masker, ApiAccessLogWriter writer) {
        final FilterRegistrationBean<ApiAccessLogFilter> registration =
                new FilterRegistrationBean<>(new ApiAccessLogFilter(properties, policy, masker, writer));
        registration.setName("apiAccessLogFilter");
        registration.setOrder(FILTER_ORDER);
        return registration;
    }

    @Bean
    public WebMvcConfigurer apiAccessLogWebMvcConfigurer(CallerResolver callerResolver) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new CallerCaptureInterceptor(callerResolver));
            }
        };
    }
}
