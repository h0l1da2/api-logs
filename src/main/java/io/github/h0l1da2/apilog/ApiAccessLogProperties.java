package io.github.h0l1da2.apilog;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * api-access-log 모듈 설정이다. 접두어는 {@code api-access-log}.
 *
 * 주의:
 * - {@code mask-keys}, {@code mask-patterns}는 설정하면 기본 목록을 대체한다(추가가 아님)
 * - {@code max-body-bytes}(기본 1MB)는 요청·응답 각각의 상한이다. 화면에서 본문 전체를 보기 위해 넉넉히 두되,
 *   대용량 CSV 같은 응답이 메모리·디스크를 채우지 않도록 상한은 남겨 둔다
 * - {@code password}는 이 모듈이 쓰지 않는다. 화면 보안은 앱이 구성하며, 같은 접두어로 읽도록 둔 것이다
 */
@ConfigurationProperties(prefix = "api-access-log")
public record ApiAccessLogProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("/api-logs") String basePath,
        @DefaultValue("admin") String username,
        String password,
        @DefaultValue("./logs/api-access") String directory,
        @DefaultValue("2") int retentionDays,
        @DefaultValue("/api/**") List<String> includePaths,
        @DefaultValue({"/actuator/**", "/api-logs/**"}) List<String> excludePaths,
        @DefaultValue("1048576") int maxBodyBytes,
        @DefaultValue("2048") int maxErrorBytes,
        @DefaultValue("false") boolean recordHeaders,
        List<String> maskKeys,
        List<String> maskPatterns,
        Map<String, BodyPolicy> bodyPolicies,
        @DefaultValue("10000") int queueCapacity,
        @DefaultValue("200") int maxFileMb,
        @DefaultValue("Asia/Seoul") ZoneId zone,
        @DefaultValue("X-Request-Id") String requestIdHeader
) {

    /**
     * 이름·전화번호·주민번호(개인정보)와 비밀번호·토큰(보안 비밀).
     * 대소문자와 _·-는 무시하고 비교하므로 {@code userName}이 {@code user_name}, {@code USER_NAME}도 가린다.
     */
    public static final List<String> DEFAULT_MASK_KEYS = List.of(
            // 이름 (Admin API + EMON 회원·훈련생·첨삭강사)
            "name", "managerName", "approverUsername", "configuredByName",
            "userName", "encptUserName", "trpNm", "correctTutorName",
            // 휴대폰·연락처
            "phoneNumber", "mobile", "encptMobile", "hpNo", "tel", "telNo",
            // 주민등록번호
            "resNo", "encptResNo",
            "password", "passwordConfirm", "newPassword", "currentPassword",
            "accessToken", "refreshToken", "apiKey", "secret", "token"
    );

    /** 키를 모르는 값도 휴대폰 번호·주민등록번호(13자리, 생년월일 유효 범위) 형식이면 가린다. */
    public static final List<String> DEFAULT_MASK_PATTERNS = List.of(
            "01[016789]-?\\d{3,4}-?\\d{4}",
            "(?<!\\d)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])-?[1-8]\\d{6}(?!\\d)"
    );

    /** jsonl 파일을 저장할 폴더. */
    public Path directoryPath() {
        return Path.of(directory);
    }

    public ApiAccessLogProperties {
        maskKeys = (maskKeys == null || maskKeys.isEmpty()) ? DEFAULT_MASK_KEYS : List.copyOf(maskKeys);
        maskPatterns = (maskPatterns == null || maskPatterns.isEmpty()) ? DEFAULT_MASK_PATTERNS : List.copyOf(maskPatterns);
        bodyPolicies = (bodyPolicies == null) ? Map.of() : Map.copyOf(bodyPolicies);
        if (retentionDays < 1) {
            throw new IllegalArgumentException("api-access-log.retention-days 는 1 이상이어야 합니다.");
        }
        if (maxBodyBytes < 0 || maxErrorBytes < 0 || queueCapacity < 1 || maxFileMb < 1) {
            throw new IllegalArgumentException("api-access-log 의 크기·용량 설정은 양수여야 합니다.");
        }
    }
}
