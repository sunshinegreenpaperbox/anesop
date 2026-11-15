package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.security.RateLimitFilter;
import com.anesop.api.support.EmbeddedPostgresSupport;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
 * S-6 / M5-M19-02：登录/刷新端点令牌桶限流（低阈值独立上下文）。
 *
 * <p>容量 2、补充 1/小时：同一 IP+用户名前 2 次放行，第 3 次返回 429
 * {@code RATE_LIMITED} 且带 {@code Retry-After}；不同用户名不共享桶；正常登录
 * （含请求体回放）不受影响。审计/响应不含口令或令牌明文。
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
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
    }

    @Test
    void loginIsRateLimitedPerIpAndUsername() throws Exception {
        String wrongBody = wrongPasswordBody(SyntheticAccounts.DEMO_ADMIN_USERNAME, "WRONG-PASSWORD-SYNTH");

        MvcResult first = mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongBody))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult second = mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongBody))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult limited = mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongBody))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后重试"))
                .andReturn();
        long retryAfter = Long.parseLong(limited.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).as("Retry-After 必须是下一次可用令牌的秒数").isBetween(1L, 3600L);
        assertThat(limited.getResponse().getContentAsString())
                .as("限流响应不得回显口令")
                .doesNotContain("WRONG-PASSWORD-SYNTH");

        // 失败登录审计：只记录用户名与固定文案，不含口令。
        String firstTrace = first.getResponse().getHeader("X-Trace-Id");
        String secondTrace = second.getResponse().getHeader("X-Trace-Id");
        assertThat(auditsFor(limited.getResponse().getHeader("X-Trace-Id")))
                .as("被限流的请求在过滤器层短路，不进入认证/审计")
                .isEmpty();
        List<Map<String, Object>> loginAudits = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail
                FROM m0_platform.sys_audit_log
                WHERE action = 'LOGIN' AND trace_id IN (?, ?)
                ORDER BY occurred_at
                """, firstTrace, secondTrace);
        assertThat(loginAudits).hasSize(2);
        assertThat(loginAudits).allSatisfy(row -> {
            assertThat(row.get("result")).isEqualTo(0);
            assertThat(String.valueOf(row.get("detail")))
                    .doesNotContain("WRONG-PASSWORD-SYNTH")
                    .contains(SyntheticAccounts.DEMO_ADMIN_USERNAME);
        });
    }

    @Test
    void differentUsernamesDoNotShareLoginBucket() throws Exception {
        String wrongAdmin = wrongPasswordBody(SyntheticAccounts.DEMO_ADMIN_USERNAME, "WRONG-PASSWORD-SYNTH");
        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongAdmin))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongAdmin))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongAdmin))
                .andExpect(status().isTooManyRequests());

        // 同一 IP 的另一用户名仍有独立额度：不会因他人被限流而误伤。
        String wrongSurgeon = wrongPasswordBody(SyntheticAccounts.SURGEON_USERNAME, "WRONG-PASSWORD-SYNTH");
        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongSurgeon))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongSurgeon))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/auth/login").contentType(JSON).content(wrongSurgeon))
                .andExpect(status().isTooManyRequests());
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
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void normalLoginWithinCapacityIsNotAffectedAndTokenWorks() throws Exception {
        MvcResult login = mockMvc.perform(post("/v1/auth/login").contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", SyntheticAccounts.DEMO_ADMIN_USERNAME,
                                "password", SyntheticAccounts.DEMO_ADMIN_PASSWORD,
                                "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        JsonNode token = objectMapper.readTree(login.getResponse().getContentAsString());
        String accessToken = token.get("accessToken").asText();
        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // 登录成功审计不含口令/令牌明文。
        String detail = jdbcTemplate.queryForObject("""
                SELECT detail::text FROM m0_platform.sys_audit_log
                WHERE action = 'LOGIN' AND trace_id = ?
                """, String.class, login.getResponse().getHeader("X-Trace-Id"));
        assertThat(detail)
                .doesNotContain(SyntheticAccounts.DEMO_ADMIN_PASSWORD)
                .doesNotContain(accessToken)
                .contains(SyntheticAccounts.DEMO_ADMIN_USERNAME);
    }

    private String wrongPasswordBody(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", username,
                "password", password,
                "authType", "LOCAL"));
    }

    private List<Map<String, Object>> auditsFor(String traceId) {
        return jdbcTemplate.queryForList("""
                SELECT action, result FROM m0_platform.sys_audit_log WHERE trace_id = ?
                """, traceId);
    }
}
