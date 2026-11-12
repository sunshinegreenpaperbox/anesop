package com.anesop.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/**
 * S-6：登录/刷新 IP 级限流（低阈值独立上下文）。
 *
 * <p>容量 2、补充 1/小时：前 2 次放行，第 3 次返回 429 {@code RATE_LIMITED}。
 * 限流是内存令牌桶（单实例语义），多实例改造登记为遗留。
 */
@SpringBootTest(properties = {
        "anesop.security.rate-limit.capacity=2",
        "anesop.security.rate-limit.refill-tokens=1",
        "anesop.security.rate-limit.refill-period=PT1H"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitIntegrationTest {

    /** 基类之外的独立上下文：不继承 AbstractApiIntegrationTest，避免其高容量限流属性覆盖低阈值。 */
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
    void loginIsRateLimitedPerIp() throws Exception {
        String wrongBody = objectMapper.writeValueAsString(Map.of(
                "username", SyntheticAccounts.DEMO_ADMIN_USERNAME,
                "password", "WRONG-PASSWORD-SYNTH",
                "authType", "LOCAL"));

        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongBody))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongBody))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后重试"));
    }

    @Test
    void refreshIsRateLimitedPerIp() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("refreshToken", "SYNTH-UNKNOWN-REFRESH"));

        mockMvc.perform(post("/v1/auth/refresh").contentType(JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/auth/refresh").contentType(JSON).content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/v1/auth/refresh").contentType(JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
