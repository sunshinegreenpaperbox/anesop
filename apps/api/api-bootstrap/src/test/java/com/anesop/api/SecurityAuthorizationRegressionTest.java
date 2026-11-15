package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * M5-M19-02：权限矩阵复核的关键补测（M0 用户管理 / M5 驾驶舱）。
 *
 * <p>已有用例覆盖 M0 认证、M1–M4 的角色×端点矩阵（{@code M4AcceptanceTest}、
 * {@code M5DashboardRiskResourceApiIntegrationTest} 等）；本类补齐两条关键缺口：
 * <ol>
 *   <li>SYS_QC_MANAGER（V26 新授权）可读驾驶舱；SYS_SURGEON 403 且写 ACCESS_DENIED 审计；
 *       匿名 401；</li>
 *   <li>M0 用户管理写权限只授予 SYS_ADMIN：麻醉医师读用户列表 403 且写审计，
 *       SYS_ADMIN 200（最小权限，防止权限蔓延）。</li>
 * </ol>
 * 全部账号与口令为 SYNTHETIC 测试数据。
 */
class SecurityAuthorizationRegressionTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareAccounts() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        SyntheticAccounts.ensureQcManager(jdbcTemplate);
    }

    @Test
    void dashboardRoleMatrixIncludesQcManagerAndAuditsSurgeonDenial() throws Exception {
        String qcToken = login(SyntheticAccounts.QC_MANAGER_USERNAME, SyntheticAccounts.QC_MANAGER_PASSWORD);
        for (String path : List.of("/v1/dashboard/realtime", "/v1/dashboard/kpi",
                "/v1/dashboard/risk", "/v1/dashboard/resources")) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + qcToken))
                    .andExpect(status().isOk());
        }

        String surgeonToken = login(SyntheticAccounts.SURGEON_USERNAME, SyntheticAccounts.SURGEON_PASSWORD);
        MvcResult denied = mockMvc.perform(get("/v1/dashboard/realtime")
                        .header("Authorization", "Bearer " + surgeonToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andReturn();
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, result, actor_name, detail::text AS detail
                FROM m0_platform.sys_audit_log WHERE trace_id = ?
                """, denied.getResponse().getHeader("X-Trace-Id"));
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("action")).isEqualTo("ACCESS_DENIED");
        assertThat(audits.get(0).get("result")).isEqualTo(0);
        assertThat(audits.get(0).get("actor_name")).isEqualTo(SyntheticAccounts.SURGEON_USERNAME);
        assertThat(String.valueOf(audits.get(0).get("detail")))
                .contains("/v1/dashboard/realtime")
                .doesNotContain("password", "token");

        mockMvc.perform(get("/v1/dashboard/realtime"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void userAdministrationIsAdminOnlyAndAuditsDenial() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/auth/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());

        String anesthesiologistToken = login(SyntheticAccounts.DEMO_SELF_USERNAME,
                SyntheticAccounts.DEMO_SELF_PASSWORD);
        MvcResult denied = mockMvc.perform(get("/v1/auth/users")
                        .header("Authorization", "Bearer " + anesthesiologistToken))
                .andExpect(status().isForbidden())
                .andReturn();
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, result FROM m0_platform.sys_audit_log WHERE trace_id = ?
                """, denied.getResponse().getHeader("X-Trace-Id"));
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("action")).isEqualTo("ACCESS_DENIED");
        assertThat(audits.get(0).get("result")).isEqualTo(0);

        // 写路径同样拒绝（@PreAuthorize 在业务写入前拒绝；请求体本身合法，排除 400 干扰）。
        String createUser = objectMapper.writeValueAsString(Map.of(
                "username", "synth.denied.user",
                "displayName", "合成拒绝用例",
                "initialPassword", "Synth-Denied-2026!"));
        mockMvc.perform(post("/v1/auth/users").contentType(JSON).content(createUser)
                        .header("Authorization", "Bearer " + anesthesiologistToken))
                .andExpect(status().isForbidden());
        Integer created = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_user WHERE username = 'synth.denied.user'",
                Integer.class);
        assertThat(created).as("越权请求不得产生任何写入").isZero();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login").contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
    }
}
