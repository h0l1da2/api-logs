package io.github.h0l1da2.apilog;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.h0l1da2.apilog.capture.ApiAccessLogFilter;
import io.github.h0l1da2.apilog.spi.CallerResolver;
import io.github.h0l1da2.apilog.spi.SecurityContextCallerResolver;
import io.github.h0l1da2.apilog.store.ApiAccessLogWriter;
import io.github.h0l1da2.apilog.store.RetentionCleaner;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@DisplayName("api-access-log 자동 설정")
class ApiAccessLogAutoConfigurationTest {

    @TempDir
    Path directory;

    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ApiAccessLogAutoConfiguration.class))
                .withPropertyValues("api-access-log.directory=" + directory);
    }

    @Test
    @DisplayName("설정이 없으면 켜지고 설계 문서의 기본값을 쓴다")
    void enabledByDefaultWithDefaults() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ApiAccessLogAutoConfiguration.class))
                .withPropertyValues("api-access-log.directory=" + directory)
                .run(context -> {
                    final ApiAccessLogProperties properties = context.getBean(ApiAccessLogProperties.class);
                    assertThat(properties.basePath()).isEqualTo("/api-logs");
                    assertThat(properties.retentionDays()).isEqualTo(2);
                    assertThat(properties.maxBodyBytes()).isEqualTo(1_048_576);
                    assertThat(properties.maxErrorBytes()).isEqualTo(2048);
                    assertThat(properties.includePaths()).containsExactly("/api/**");
                    assertThat(properties.requestIdHeader()).isEqualTo("X-Request-Id");
                    assertThat(properties.maskKeys()).contains("name", "phoneNumber", "password", "accessToken");
                    assertThat(properties.bodyPolicies()).isEmpty();
                });
    }

    @Test
    @DisplayName("켜지면 필터·writer·삭제기·caller 해석기를 등록한다")
    void registersComponents() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(ApiAccessLogWriter.class);
            assertThat(context).hasSingleBean(RetentionCleaner.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            assertThat(context.getBean(FilterRegistrationBean.class).getFilter())
                    .isInstanceOf(ApiAccessLogFilter.class);
            assertThat(context.getBean(FilterRegistrationBean.class).getOrder())
                    .isEqualTo(ApiAccessLogAutoConfiguration.FILTER_ORDER);
            assertThat(context.getBean(CallerResolver.class)).isInstanceOf(SecurityContextCallerResolver.class);
            assertThat(context.getBean(ApiAccessLogWriter.class).isRunning()).isTrue();
        });
    }

    @Test
    @DisplayName("앱이 CallerResolver를 등록하면 그것을 쓴다")
    void usesAppCallerResolver() {
        runner().withBean(CallerResolver.class, () -> request -> "custom")
                .run(context -> assertThat(context.getBean(CallerResolver.class).resolve(null)).isEqualTo("custom"));
    }

    @Test
    @DisplayName("enabled=false 이면 등록되지 않는다")
    void disabledByProperty() {
        runner().withPropertyValues("api-access-log.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ApiAccessLogProperties.class));
    }

    @Test
    @DisplayName("서블릿 웹 앱이 아니면 등록되지 않는다")
    void notRegisteredOutsideServletWebApp() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ApiAccessLogAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(ApiAccessLogProperties.class));
    }

    @Test
    @DisplayName("설정한 값으로 기본값을 대체한다")
    void bindsConfiguredValues() {
        runner().withPropertyValues(
                        "api-access-log.retention-days=3",
                        "api-access-log.mask-keys=foo,bar",
                        "api-access-log.body-policies[/api/v1/internal/**]=REQUEST_ONLY")
                .run(context -> {
                    final ApiAccessLogProperties properties = context.getBean(ApiAccessLogProperties.class);
                    assertThat(properties.directoryPath()).isEqualTo(directory);
                    assertThat(properties.retentionDays()).isEqualTo(3);
                    assertThat(properties.maskKeys()).containsExactly("foo", "bar");
                    assertThat(properties.bodyPolicies())
                            .containsEntry("/api/v1/internal/**", BodyPolicy.REQUEST_ONLY);
                });
    }

    @Test
    @DisplayName("보관 일수가 1 미만이면 시작에 실패한다")
    void failsOnInvalidRetentionDays() {
        runner().withPropertyValues("api-access-log.retention-days=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
