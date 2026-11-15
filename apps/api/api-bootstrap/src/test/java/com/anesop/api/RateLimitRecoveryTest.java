package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.security.RateLimitFilter;
import com.anesop.api.support.EmbeddedPostgresSupport;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M5-M19-02：限流窗口恢复。容量 1、每 5 秒补充 1 个令牌：
 * 第 1 次失败登录 401、第 2 次 429，等待一个窗口后第 3 次恢复为 401（不再 429）。
 *
 * <p>窗口取 5 秒而非 1 秒：首个请求包含 Security 链/BCrypt/审计的冷启动开销，
 * 1 秒窗口可能在第二个请求前已自然补充，导致用例偶发（实测在嵌入式 PG 上出现过）。
 */
@SpringBootTest(properties = {
        "anesop.security.rate-limit.capacity=1",
        "anesop.security.rate-limit.refill-tokens=1",
        "anesop.security.rate-limit.refill-period=PT5S"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitRecoveryTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("anesop.security.jwt.secret",
                () -> "c3ludGhldGljLXRlc3Qtb25seS1zaWduaW5nLWtleS0wMTIzNDU2Nzg5");
    }

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        rateLimitFilter.reset();
        SyntheticAccounts.resetDemoAdmin(jdbcTemplate);
    }

    @Test
    void bucketRefillsAfterWindow() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", SyntheticAccounts.DEMO_ADMIN_USERNAME,
                "password", "WRONG-PASSWORD-SYNTH",
                "authType", "LOCAL"));

        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(body))
                .andExpect(status().isUnauthorized());
        MvcResult limited = mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();

        Thread.sleep(5_200L); // 等待 1 个补充周期（PT5S）+ 余量

        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(body))
                .andExpect(status().isUnauthorized());
    }
}
