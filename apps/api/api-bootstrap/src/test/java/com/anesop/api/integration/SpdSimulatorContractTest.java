package com.anesop.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.contracts.material.model.SpdAdapterStatus;
import com.anesop.contracts.material.model.SpdSyncResult;
import com.anesop.simulators.FailureMode;
import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.spd.SpdSimulator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SPD 适配器 ↔ SPD 模拟器字段映射契约测试（M5-M19-01 / EXT-03）。
 *
 * <p>真实启动 {@link SpdSimulator}，断言：耗材目录字段集合与映射（→ 创建耗材请求 → 真实 API 201）、
 * 同步回执幂等（同 idempotencyKey 同回执）、回执/状态映射满足 {@code material.yaml} required 列表、
 * 503 与超时按“降级为本地库存可用”处理（counts=0、success=false）。
 */
class SpdSimulatorContractTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private SpdSimulator simulator;
    private ContractHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        cleanSyntheticMaterials();
        simulator = new SpdSimulator(new SimulatorConfig.Endpoint(0));
        simulator.start();
        client = new ContractHttpClient(objectMapper,
                Duration.ofMillis(500), Duration.ofMillis(800), 3, 5);
    }

    @AfterEach
    void tearDown() {
        if (simulator != null) {
            simulator.close();
        }
    }

    @Test
    void materialCatalogFieldsMapToCreateRequestAndApiPersistsThem() throws Exception {
        ContractHttpClient.Result catalog = client.get(simulator.baseUrl() + "/spd/v1/materials");
        assertThat(catalog.delivered()).isTrue();
        assertThat(catalog.attempts()).isEqualTo(1);
        assertThat(fieldNames(catalog.body())).containsExactlyInAnyOrder("system", "synthetic", "total", "materials");
        assertThat(catalog.body().path("materials").size()).isEqualTo(2);

        JsonNode first = catalog.body().path("materials").get(0);
        assertThat(fieldNames(first)).isEqualTo(WireMapper.SPD_MATERIAL_FIELDS);

        Map<String, Object> createRequest = WireMapper.materialCreateRequest(first);
        assertThat(createRequest).containsEntry("code", "SPD-M-0001")
                .containsEntry("name", "中心静脉导管（演示）")
                .containsEntry("specification", "7F 三腔")
                .containsEntry("unit", "套")
                .containsEntry("category", "CATHETER")
                .containsEntry("udiDi", "UDI-DI-SYNTH-0001")
                .containsEntry("highValue", true)
                .containsEntry("expiryManaged", true)
                .containsEntry("remark", "SYNTHETIC SPD 目录");

        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(post("/v1/materials").header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SPD-M-0001"))
                .andExpect(jsonPath("$.udiDi").value("UDI-DI-SYNTH-0001"))
                .andExpect(jsonPath("$.highValue").value(true))
                .andExpect(jsonPath("$.expiryManaged").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/v1/materials").header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isConflict());
    }

    @Test
    void syncReceiptIsIdempotentPerKeyAndMapsToContractResult() throws Exception {
        String body = "{\"idempotencyKey\":\"SYNTH-KEY-1\",\"since\":\"2026-11-01T00:00:00+08:00\"}";
        ContractHttpClient.Result first = client.post(simulator.baseUrl() + "/spd/v1/sync", body);
        ContractHttpClient.Result replay = client.post(simulator.baseUrl() + "/spd/v1/sync", body);

        assertThat(first.delivered()).isTrue();
        assertThat(fieldNames(first.body())).isEqualTo(WireMapper.SPD_SYNC_WIRE_FIELDS);
        assertThat(first.body()).as("同 idempotencyKey 重放返回同一回执").isEqualTo(replay.body());

        SpdSyncResult mapped = WireMapper.spdSync(first.body());
        assertThat(mapped.getAdapterCode()).isEqualTo("EXT-03");
        assertThat(mapped.getSuccess()).isTrue();
        assertThat(mapped.getDegraded()).isFalse();
        assertThat(mapped.getPulledCount()).isEqualTo(SpdSimulator.PULLED_COUNT);
        assertThat(mapped.getPushedCount()).isEqualTo(SpdSimulator.PUSHED_COUNT);
        assertThat(mapped.getSyncedAt()).isNotNull();
        assertThat(mapped.getMessage()).contains("SYNTHETIC");
        assertContractFields(mapped, WireMapper.SPD_SYNC_CONTRACT_FIELDS);

        // 新 key → 新回执号（不误判为重放）。
        ContractHttpClient.Result another = client.post(simulator.baseUrl() + "/spd/v1/sync",
                "{\"idempotencyKey\":\"SYNTH-KEY-2\"}");
        assertThat(another.text("syncNo")).isNotEqualTo(first.text("syncNo"));

        // 平台 SPD 同步端点语义一致：success 与 degraded 互为反面、返回 200 不抛错。
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(post("/v1/materials/spd-adapter/sync").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adapterCode").value("EXT-03"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    void adapterStatusMapsToContractSchemaAndMatchesApiSemantics() throws Exception {
        ContractHttpClient.Result wire = client.get(simulator.baseUrl() + "/spd/v1/adapter-status");
        assertThat(wire.delivered()).isTrue();
        assertThat(fieldNames(wire.body())).isEqualTo(WireMapper.SPD_STATUS_WIRE_FIELDS);

        SpdAdapterStatus mapped = WireMapper.spdStatus(wire.body());
        assertThat(mapped.getAdapterCode()).isEqualTo("EXT-03");
        assertThat(mapped.getMode()).isEqualTo(SpdAdapterStatus.ModeEnum.SIMULATED);
        assertThat(mapped.getAvailable()).isTrue();
        assertThat(mapped.getDegraded()).isFalse();
        assertThat(mapped.getPendingSyncCount()).isZero();
        assertThat(mapped.getLastSyncedAt()).isNotNull();
        assertContractFields(mapped, WireMapper.SPD_STATUS_CONTRACT_FIELDS);

        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        // 适配器状态取“最近一次同步”：先触发一次成功同步，避免受其它测试（降级用例）写入的历史日志影响。
        mockMvc.perform(post("/v1/materials/spd-adapter/sync").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get("/v1/materials/spd-adapter/status")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adapterCode").value("EXT-03"))
                .andExpect(jsonPath("$.mode").value("SIMULATED"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.pendingSyncCount").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void unavailableAndTimeoutDegradeToLocalStockAvailable() throws Exception {
        SimulatorConfig.Endpoint broken = new SimulatorConfig.Endpoint(0);
        broken.setFailureMode(FailureMode.HTTP_ERROR);
        broken.setErrorStatus(503);
        SpdSimulator unavailable = new SpdSimulator(broken);
        unavailable.start();
        try {
            ContractHttpClient.Result failed = client.post(unavailable.baseUrl() + "/spd/v1/sync",
                    "{\"idempotencyKey\":\"SYNTH-KEY-FAIL\"}");
            assertThat(failed.delivered()).isFalse();
            assertThat(failed.status()).isEqualTo(503);
            assertThat(failed.attempts()).as("5xx 重试 3 次").isEqualTo(3);

            SpdSyncResult degraded = WireMapper.spdSyncDegraded("HTTP 503");
            assertThat(degraded.getSuccess()).isFalse();
            assertThat(degraded.getDegraded()).isTrue();
            assertThat(degraded.getPulledCount()).isZero();
            assertThat(degraded.getPushedCount()).isZero();
            assertThat(degraded.getMessage()).contains("降级");
            assertContractFields(degraded, WireMapper.SPD_SYNC_CONTRACT_FIELDS);

            SpdAdapterStatus degradedStatus = WireMapper.spdDegraded("HTTP 503");
            assertThat(degradedStatus.getAvailable()).isFalse();
            assertThat(degradedStatus.getDegraded()).isTrue();
            assertThat(degradedStatus.getPendingSyncCount()).isEqualTo(1);
            assertContractFields(degradedStatus, WireMapper.SPD_STATUS_CONTRACT_FIELDS);
        } finally {
            unavailable.close();
        }

        SimulatorConfig.Endpoint slowEndpoint = new SimulatorConfig.Endpoint(0);
        slowEndpoint.setLatencyMs(800);
        SpdSimulator slow = new SpdSimulator(slowEndpoint);
        slow.start();
        try {
            ContractHttpClient impatient = new ContractHttpClient(objectMapper,
                    Duration.ofMillis(200), Duration.ofMillis(120), 2, 5);
            ContractHttpClient.Result timedOut = impatient.get(slow.baseUrl() + "/spd/v1/materials");
            assertThat(timedOut.delivered()).isFalse();
            assertThat(timedOut.attempts()).isEqualTo(2);
        } finally {
            slow.close();
        }

        // 降级不阻塞本地耗材库存：目录查询仍可用。
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/materials").header("Authorization", "Bearer " + admin)
                        .param("size", "5"))
                .andExpect(status().isOk());
    }

    private void assertContractFields(Object model, java.util.List<String> required) {
        JsonNode json = objectMapper.valueToTree(model);
        for (String field : required) {
            assertThat(json.has(field) && !json.get(field).isNull())
                    .as("契约 required 字段必须有值: " + field)
                    .isTrue();
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void cleanSyntheticMaterials() {
        jdbcTemplate.update("""
                DELETE FROM m4_material.material_stock WHERE material_id IN (
                    SELECT id FROM m4_material.material WHERE code LIKE 'SPD-M-%')
                """);
        jdbcTemplate.update("DELETE FROM m4_material.udi_instance WHERE material_id IN ("
                + "SELECT id FROM m4_material.material WHERE code LIKE 'SPD-M-%')");
        jdbcTemplate.update("DELETE FROM m4_material.material WHERE code LIKE 'SPD-M-%'");
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password, "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
