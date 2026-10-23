package io.github.h0l1da2.apilog.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * API 호출 기록 화면(HTML 한 장)을 {@code ${api-access-log.base-path}}에서 내려준다.
 *
 * 설계 의도:
 * - 정적 리소스 경로(META-INF/resources)에 두지 않고 이 컨트롤러로만 내려준다.
 *   앱의 정적 리소스 설정과 상관없이 동작하고, base-path 하나만 보호하면 된다
 * - 외부 CDN 없이 동작한다 (폐쇄망 대비, 차트도 SVG로 직접 그린다)
 */
@Controller
public class ApiAccessLogPageController {

    private static final String PAGE = "META-INF/api-access-log/index.html";

    private final byte[] page;

    public ApiAccessLogPageController() {
        try (InputStream in = new ClassPathResource(PAGE).getInputStream()) {
            this.page = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("api-access-log 화면 파일을 읽을 수 없습니다: " + PAGE, e);
        }
    }

    @GetMapping({"${api-access-log.base-path:/api-logs}", "${api-access-log.base-path:/api-logs}/"})
    @ResponseBody
    public ResponseEntity<byte[]> page() {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY")
                .body(page);
    }
}
