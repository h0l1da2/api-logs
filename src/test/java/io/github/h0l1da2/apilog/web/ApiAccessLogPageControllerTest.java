package io.github.h0l1da2.apilog.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@DisplayName("API 호출 기록 화면")
class ApiAccessLogPageControllerTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ApiAccessLogPageController())
            .addPlaceholderValue("api-access-log.base-path", "/api-logs")
            .build();

    @Test
    @DisplayName("base-path와 끝에 / 붙은 경로 모두 HTML 화면을 내려준다")
    void servesPage() throws Exception {
        for (String path : new String[] {"/api-logs", "/api-logs/"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(content().string(Matchers.containsString("API 호출 기록")));
        }
    }

    @Test
    @DisplayName("화면은 외부 스크립트·스타일을 불러오지 않는다 (폐쇄망 대비)")
    void hasNoExternalResources() throws Exception {
        mockMvc.perform(get("/api-logs"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("src=\"http"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("href=\"http"))));
    }
}
