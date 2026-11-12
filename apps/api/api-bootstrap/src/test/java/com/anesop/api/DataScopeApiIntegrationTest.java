package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
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
 * 数据范围绑定与越权（M2-M0-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：/me 返回真实 scopeIds（DEPARTMENT=SITE/DEPT 组织、OWN_PATIENTS=SELF 无对象）、
 * DEPT 用户只能查本科室、未绑定账号 fail-closed→403、ROOM 只可见已绑定术间、
 * cross-department 403 并写 ACCESS_DENIED 审计、缺 m0:org:read 的功能权限 403、admin 全院可见。
 * 全部账号与组织均为 SYNTHETIC 合成数据。
 */
class DataScopeApiIntegrationTest extends AbstractApiIntegrationTest {

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
        SyntheticAccounts.ensureUnboundUser(jdbcTemplate);
        SyntheticAccounts.ensureRoomUser(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
    }

    @Test
    void meReturnsBoundDepartmentScopeIdsForDeptUser() throws Exception {
        String token = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", hasItem("SYS_OR_MANAGER")))
                .andExpect(jsonPath("$.dataScopes", hasSize(1)))
                .andExpect(jsonPath("$.dataScopes[0].type").value("DEPARTMENT"))
                .andExpect(jsonPath("$.dataScopes[0].scopeIds", hasSize(1)))
                .andExpect(jsonPath("$.dataScopes[0].scopeIds[0]").value(SyntheticAccounts.DEMO_ANES_ORG_ID))
                .andExpect(jsonPath("$.siteIds", hasSize(0)));
    }

    @Test
    void meKeepsAllScopeUnrestrictedAndSelfScopeEmpty() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataScopes[0].type").value("ALL"))
                .andExpect(jsonPath("$.dataScopes[0].scopeIds", hasSize(0)));

        String selfToken = login(SyntheticAccounts.DEMO_SELF_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + selfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataScopes[0].type").value("OWN_PATIENTS"))
                .andExpect(jsonPath("$.dataScopes[0].scopeIds", hasSize(0)));
    }

    @Test
    void deptUserOnlySeesOwnDepartmentMembers() throws Exception {
        String token = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult result = mockMvc.perform(get("/v1/auth/data-scope/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        List<String> visible = visibleUsers(result);
        assertThat(visible)
                .as("DEPT 用户只看本科室成员（demo_dept 与 demo_self 绑定麻醉科）")
                .containsExactly(SyntheticAccounts.DEMO_DEPT_ID, SyntheticAccounts.DEMO_SELF_ID)
                .doesNotContain(SyntheticAccounts.ROOM_USER_ID, SyntheticAccounts.DEMO_ADMIN_ID);

        mockMvc.perform(get("/v1/auth/data-scope/users")
                        .param("orgId", SyntheticAccounts.DEMO_ANES_ORG_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void crossDepartmentAccessReturns403AndWritesAccessDeniedAudit() throws Exception {
        String token = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult result = mockMvc.perform(get("/v1/auth/data-scope/users")
                        .param("orgId", SyntheticAccounts.DEMO_OR_ORG_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andReturn();

        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId).isNotBlank();
        Map<String, Object> audit = accessDeniedAudit(traceId);
        assertThat(audit.get("action")).isEqualTo("ACCESS_DENIED");
        assertThat(String.valueOf(audit.get("actor_id"))).isEqualTo(SyntheticAccounts.DEMO_DEPT_ID);
        assertThat(audit.get("actor_name")).isEqualTo(SyntheticAccounts.DEMO_DEPT_USERNAME);
        assertThat(((Number) audit.get("result")).intValue()).isZero();
        System.out.printf("[M2-M0-01][403] actor=%s resource=%s traceId=%s%n",
                audit.get("actor_name"), audit.get("resource_id"), traceId);
    }

    @Test
    void unboundUserFailsClosedOnMeAndProtectedQuery() throws Exception {
        String token = login(SyntheticAccounts.UNBOUND_USERNAME, SyntheticAccounts.UNBOUND_PASSWORD);

        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataScopes", hasSize(0)))
                .andExpect(jsonPath("$.siteIds", hasSize(0)));

        MvcResult result = mockMvc.perform(get("/v1/auth/data-scope/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andReturn();

        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(accessDeniedAudit(traceId).get("actor_name"))
                .isEqualTo(SyntheticAccounts.UNBOUND_USERNAME);
    }

    @Test
    void roomUserOnlySeesBoundRoomAndOtherRoomIs403() throws Exception {
        String token = login(SyntheticAccounts.ROOM_USERNAME, SyntheticAccounts.ROOM_PASSWORD);

        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataScopes[0].type").value("ROOM"))
                .andExpect(jsonPath("$.dataScopes[0].scopeIds[0]").value(SyntheticAccounts.ROOM_1_ID));

        MvcResult bound = mockMvc.perform(get("/v1/auth/data-scope/users")
                        .param("roomId", SyntheticAccounts.ROOM_1_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(visibleUsers(bound)).containsExactly(SyntheticAccounts.ROOM_USER_ID);

        mockMvc.perform(get("/v1/auth/data-scope/users")
                        .param("roomId", SyntheticAccounts.ROOM_2_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void missingOrgReadPermissionReturns403EvenWithNonEmptyScope() throws Exception {
        String token = login(SyntheticAccounts.SURGEON_USERNAME, SyntheticAccounts.SURGEON_PASSWORD);

        MvcResult result = mockMvc.perform(get("/v1/auth/data-scope/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andReturn();

        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(accessDeniedAudit(traceId).get("actor_name"))
                .as("SELF 范围非空但缺少 m0:org:read，数据范围不得替代功能权限")
                .isEqualTo(SyntheticAccounts.SURGEON_USERNAME);
        System.out.printf("[M2-M0-01][403] missing perm m0:org:read actor=%s traceId=%s%n",
                SyntheticAccounts.SURGEON_USERNAME, traceId);
    }

    @Test
    void adminSeesAllUsersAndAnyOrganisation() throws Exception {
        String token = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult result = mockMvc.perform(get("/v1/auth/data-scope/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(visibleUsers(result))
                .contains(SyntheticAccounts.DEMO_ADMIN_ID, SyntheticAccounts.DEMO_DEPT_ID,
                        SyntheticAccounts.DEMO_SELF_ID, SyntheticAccounts.ROOM_USER_ID);

        mockMvc.perform(get("/v1/auth/data-scope/users")
                        .param("orgId", SyntheticAccounts.DEMO_OR_ORG_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void openApiDocumentExposesDataScopePath() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode paths = objectMapper.readTree(result.getResponse().getContentAsString()).get("paths");

        assertThat(paths.has("/v1/auth/data-scope/users")).isTrue();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password, "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private List<String> visibleUsers(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.isArray()).as("示例接口返回用户标识数组").isTrue();
        return objectMapper.convertValue(body, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
    }

    private Map<String, Object> accessDeniedAudit(String traceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT action, actor_id::text AS actor_id, actor_name, resource_id, result
                FROM m0_platform.sys_audit_log
                WHERE trace_id = ? AND action = 'ACCESS_DENIED'
                ORDER BY occurred_at DESC
                """, traceId);
        assertThat(rows).as("403 必须写 ACCESS_DENIED 审计").hasSize(1);
        return rows.get(0);
    }
}
