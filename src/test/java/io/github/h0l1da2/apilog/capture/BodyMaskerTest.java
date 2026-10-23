package io.github.h0l1da2.apilog.capture;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.h0l1da2.apilog.TestProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("본문 마스킹")
class BodyMaskerTest {

    private final BodyMasker masker = new BodyMasker(TestProperties.of(), JsonMapper.builder().build());

    @Test
    @DisplayName("JSON 어느 깊이에 있든 이름·전화번호·비밀번호 키의 값을 가린다")
    void masksNestedJsonKeys() {
        final String masked = masker.maskBody("""
                {"loginId":"admin","password":"p@ss","profile":{"name":"홍길동","phoneNumber":"01012345678"},
                 "items":[{"accessToken":"abc"}]}""", true, false);

        assertThat(masked)
                .contains("\"loginId\":\"admin\"")
                .contains("\"password\":\"***\"")
                .contains("\"name\":\"***\"")
                .contains("\"phoneNumber\":\"***\"")
                .contains("\"accessToken\":\"***\"")
                .doesNotContain("홍길동", "p@ss", "abc");
    }

    @Test
    @DisplayName("사람 이름이 아닌 *Name 필드는 가리지 않는다")
    void keepsNonPersonNameFields() {
        final String masked = masker.maskBody(
                "{\"agentName\":\"에이전트A\",\"institutionName\":\"한국기관\"}", true, false);

        assertThat(masked).contains("에이전트A").contains("한국기관");
    }

    @Test
    @DisplayName("키를 몰라도 휴대폰 번호 형식의 값은 가린다")
    void masksPhonePatternInValues() {
        final String masked = masker.maskBody(
                "{\"rows\":[[\"홍길동\",\"010-1234-5678\"]],\"memo\":\"연락처 01098765432\"}", true, false);

        assertThat(masked).doesNotContain("010-1234-5678", "01098765432").contains("홍길동");
    }

    @Test
    @DisplayName("잘려서 파싱되지 않는 JSON도 정규식으로 키를 가린다")
    void masksTruncatedJson() {
        final String masked = masker.maskBody("{\"password\":\"p@ss\",\"name\":\"홍길동\",\"data\":[1,2,", true, false);

        assertThat(masked).doesNotContain("p@ss", "홍길동").contains("\"password\":\"***\"");
    }

    @Test
    @DisplayName("폼과 쿼리스트링의 키도 가린다")
    void masksFormAndQuery() {
        assertThat(masker.maskQuery("page=1&password=p%40ss&name=%ED%99%8D")).isEqualTo("page=1&password=***&name=***");
        assertThat(masker.maskBody("loginId=admin&password=p", false, true)).isEqualTo("loginId=admin&password=***");
    }

    @Test
    @DisplayName("인증·쿠키 헤더는 항상 가린다")
    void masksSensitiveHeaders() {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer token");
        headers.put("Cookie", "refresh_token=abc");
        headers.put("Content-Type", "application/json");

        assertThat(masker.maskHeaders(headers))
                .containsEntry("Authorization", "***")
                .containsEntry("Cookie", "***")
                .containsEntry("Content-Type", "application/json");
    }
}
