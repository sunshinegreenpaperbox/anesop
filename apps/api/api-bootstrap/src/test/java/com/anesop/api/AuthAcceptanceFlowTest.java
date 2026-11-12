package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S0-07 验收流程（打印关键响应，作为验收证据）：
 * login 200（token 前缀）→ 错误口令 401 → /me 200 → /sessions 200 → 越权 403 → refresh 轮换。
 *
 * <p>同时打印三类审计记录（登录成功/失败/越权），便于人工核对 action/actor/ip/result。
 * traceId 取响应头 X-Trace-Id（服务端生成，与审计 trace_id 一致）。
 * 全部数据为 SYNTHETIC；断言失败即验收不通过。
 */
class AuthAcceptanceFlowTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareAccounts() {
        SyntheticAccounts.resetDemoAdmin(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
    }

    @Test
    void acceptanceFlow() throws Exception {
        MvcResult loginResult = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        JsonNode login = json(loginResult);
        String accessToken = login.get("accessToken").asText();
        String refreshToken = login.get("refreshToken").asText();
        System.out.printf("[acceptance] 1 POST /v1/auth/login -> HTTP 200 accessTokenPrefix=%s refreshTokenLen=%d "
                        + "expiresIn=%d roles=%s%n",
                accessToken.substring(0, 18), refreshToken.length(), login.get("expiresIn").asInt(),
                login.get("user").get("roles"));

        MvcResult wrong = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(body(SyntheticAccounts.DEMO_ADMIN_USERNAME, "WRONG-PASSWORD-SYNTH")))
                .andExpect(status().isUnauthorized())
                .andReturn();
        System.out.printf("[acceptance] 2 POST /v1/auth/login（错误口令）-> HTTP %d code=%s%n",
                wrong.getResponse().getStatus(), json(wrong).get("code").asText());

        MvcResult me = mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode meBody = json(me);
        System.out.printf("[acceptance] 3 GET /v1/auth/me -> HTTP 200 roles=%s permissions=%s dataScopes=%s%n",
                meBody.get("roles"), meBody.get("permissions"), meBody.get("dataScopes"));

        MvcResult sessions = mockMvc.perform(get("/v1/auth/sessions")
                        .param("page", "1").param("size", "20")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode sessionsBody = json(sessions);
        System.out.printf("[acceptance] 4 GET /v1/auth/sessions -> HTTP 200 total=%d status=%s%n",
                sessionsBody.get("meta").get("total").asInt(),
                sessionsBody.get("items").get(0).get("status").asText());

        JsonNode surgeon = json(login(SyntheticAccounts.SURGEON_USERNAME, SyntheticAccounts.SURGEON_PASSWORD));
        MvcResult forbidden = mockMvc.perform(get("/v1/auth/sessions")
                        .header("Authorization", "Bearer " + surgeon.get("accessToken").asText()))
                .andExpect(status().isForbidden())
                .andReturn();
        System.out.printf("[acceptance] 5 GET /v1/auth/sessions（无 m0:auth:read）-> HTTP %d code=%s%n",
                forbidden.getResponse().getStatus(), json(forbidden).get("code").asText());

        MvcResult refreshed = mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andReturn();
        String rotatedRefresh = json(refreshed).get("refreshToken").asText();
        int oldRefreshStatus = mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn().getResponse().getStatus();
        System.out.printf("[acceptance] 6 POST /v1/auth/refresh -> HTTP 200 rotated=%s；旧刷新令牌 -> HTTP %d%n",
                !rotatedRefresh.equals(refreshToken), oldRefreshStatus);
        assertThat(oldRefreshStatus).isEqualTo(401);

        List<String> traces = List.of(
                loginResult.getResponse().getHeader("X-Trace-Id"),
                wrong.getResponse().getHeader("X-Trace-Id"),
                forbidden.getResponse().getHeader("X-Trace-Id"));
        for (String traceId : traces) {
            Map<String, Object> audit = jdbcTemplate.queryForMap("""
                    SELECT action, actor_name, host(ip) AS ip, result
                    FROM m0_platform.sys_audit_log WHERE trace_id = ?
                    """, traceId);
            System.out.printf("[acceptance] audit trace=%s action=%s actor=%s ip=%s result=%s%n",
                    traceId, audit.get("action"), audit.get("actor_name"), audit.get("ip"), audit.get("result"));
        }
    }

    private MvcResult login(String username, String password) throws Exception {
        return mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(body(username, password)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String body(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", username, "password", password, "authType", "LOCAL"));
    }
}
