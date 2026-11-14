package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SPD 适配器（EXT-03）降级链路（M4-M14-01）：独立上下文把 simulated-available 置 false，
 * 验证同步失败仍返回 200 + degraded=true、写 spd_sync_log 与 result=0 审计，且本地耗材库存可用。
 */
@SpringBootTest(properties = "anesop.m4.spd.simulated-available=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class M4MaterialSpdDegradedApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void spdSyncFailureDegradesButLocalStockStaysAvailable() throws Exception {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        long before = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m4_material.spd_sync_log", Long.class);

        mockMvc.perform(post("/v1/materials/spd-adapter/sync")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adapterCode").value("EXT-03"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("降级")));

        long after = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m4_material.spd_sync_log", Long.class);
        assertThat(after).isEqualTo(before + 1);

        mockMvc.perform(get("/v1/materials/spd-adapter/status")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.pendingSyncCount").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT result, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE action = 'SPD_SYNC' ORDER BY occurred_at DESC LIMIT 2
                """);
        assertThat(audits).isNotEmpty();
        assertThat(((Number) audits.get(0).get("result")).intValue()).isZero();
        assertThat(String.valueOf(audits.get(0).get("detail"))).contains("degraded");
        String logDetail = jdbcTemplate.queryForObject("""
                SELECT detail::text FROM m4_material.spd_sync_log
                 ORDER BY occurred_at DESC, id LIMIT 1
                """, String.class);
        assertThat(logDetail).contains("SIMULATED_UNAVAILABLE");

        // 本地耗材目录/库存仍可用（降级不阻塞本地出入库）：目录查询 200。
        mockMvc.perform(get("/v1/materials").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password,
                                "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
