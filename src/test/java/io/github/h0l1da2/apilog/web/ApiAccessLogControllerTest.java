package io.github.h0l1da2.apilog.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.h0l1da2.apilog.ApiAccessLogProperties;
import io.github.h0l1da2.apilog.TestProperties;
import io.github.h0l1da2.apilog.query.ApiAccessLogReader;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("API 호출 기록 조회 API")
class ApiAccessLogControllerTest {

    @TempDir
    Path directory;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        final ApiAccessLogProperties properties = TestProperties.of("directory", directory.toString());
        final ApiAccessLogReader reader = new ApiAccessLogReader(properties, JsonMapper.builder().build(), () -> 0L);
        mockMvc = MockMvcBuilders.standaloneSetup(new ApiAccessLogController(reader, properties))
                .addPlaceholderValue("api-access-log.base-path", "/api-logs")
                .build();
    }

    @Test
    @DisplayName("기록이 없으면 빈 목록과 0건 집계를 돌려준다")
    void emptyResults() throws Exception {
        mockMvc.perform(get("/api-logs/api/logs").param("status", "4xx,5xx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api-logs/api/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("없는 id는 404, 잘못된 시각·커서는 400을 돌려준다")
    void errors() throws Exception {
        mockMvc.perform(get("/api-logs/api/logs/01ARZ3NDEKTSV4RRFFQ69G5FAV"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api-logs/api/logs").param("from", "어제"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api-logs/api/logs").param("cursor", "garbage"))
                .andExpect(status().isBadRequest());
    }
}
