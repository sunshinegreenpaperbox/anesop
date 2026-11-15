package com.anesop.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.contracts.drug.model.PharmacyAdapterStatus;
import com.anesop.simulators.FailureMode;
import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.pharmacy.PharmacySimulator;
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
 * 药房系统适配器 ↔ 药房模拟器字段映射契约测试（M5-M19-01 / EXT-02）。
 *
 * <p>真实启动 {@link PharmacySimulator}，断言：目录/库存/发药字段集合、映射到创建药品请求、
 * 真实 API 创建成功、适配器状态映射满足 {@code drug.yaml} required 列表、HTTP 500 与超时按
 * “降级为本地字典可用”处理（不阻塞临床）。
 */
class PharmacySimulatorContractTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private PharmacySimulator simulator;
    private ContractHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        cleanSyntheticDrugs();
        simulator = new PharmacySimulator(new SimulatorConfig.Endpoint(0));
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
    void drugCatalogFieldsMapToCreateRequestAndApiPersistsThem() throws Exception {
        ContractHttpClient.Result catalog = client.get(simulator.baseUrl() + "/pharmacy/v1/drugs");
        assertThat(catalog.delivered()).isTrue();
        assertThat(catalog.attempts()).isEqualTo(1);
        assertThat(catalog.body().path("drugs").size()).isEqualTo(3);

        JsonNode first = catalog.body().path("drugs").get(0);
        assertThat(fieldNames(first)).isEqualTo(WireMapper.PHARMACY_DRUG_FIELDS);

        Map<String, Object> createRequest = WireMapper.drugCreateRequest(first);
        assertThat(createRequest).containsEntry("code", "PMS-D-0001")
                .containsEntry("name", "舒芬太尼注射液（药房模拟）")
                .containsEntry("genericName", "枸橼酸舒芬太尼（药房模拟）")
                .containsEntry("specification", "50ug/1ml")
                .containsEntry("unit", "支")
                .containsEntry("dosageForm", "INJECTION")
                .containsEntry("category", "OPIOID")
                .containsEntry("narcotic", true)
                .containsEntry("highRisk", true)
                .containsEntry("aliases", java.util.List.of("舒芬（药房模拟）"));

        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(post("/v1/drugs").header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("PMS-D-0001"))
                .andExpect(jsonPath("$.narcotic").value(true))
                .andExpect(jsonPath("$.highRisk").value(true))
                .andExpect(jsonPath("$.aliases[0]").value("舒芬（药房模拟）"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 重复同步同一目录 → 409（编码唯一，保证目录同步幂等）。
        mockMvc.perform(post("/v1/drugs").header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isConflict());
    }

    @Test
    void stockAndDispenseSemanticsMatchDocumentedFailureModes() throws Exception {
        ContractHttpClient.Result stock = client.get(
                simulator.baseUrl() + "/pharmacy/v1/stock/PMS-D-0001");
        assertThat(stock.delivered()).isTrue();
        assertThat(stock.text("batchNo")).isEqualTo(PharmacySimulator.BATCH_NO);
        assertThat(stock.body().path("quantity").asInt()).isEqualTo(PharmacySimulator.STOCK_QUANTITY);

        ContractHttpClient.Result dispensed = client.post(simulator.baseUrl() + "/pharmacy/v1/dispense",
                "{\"prescriptionNo\":\"RX-SYNTH-0001\",\"drugCode\":\"PMS-D-0001\",\"quantity\":2}");
        assertThat(dispensed.delivered()).isTrue();
        assertThat(dispensed.body().path("dispensed").asBoolean()).isTrue();
        assertThat(dispensed.body().path("quantity").asInt()).isEqualTo(2);

        ContractHttpClient.Result insufficient = client.post(simulator.baseUrl() + "/pharmacy/v1/dispense",
                "{\"prescriptionNo\":\"RX-SYNTH-0002\",\"drugCode\":\"PMS-D-0001\",\"quantity\":9999}");
        assertThat(insufficient.status()).isEqualTo(409);
        assertThat(insufficient.text("code")).isEqualTo("INSUFFICIENT_STOCK");
        assertThat(insufficient.attempts()).as("409 业务拒绝不重试").isEqualTo(1);

        ContractHttpClient.Result unknown = client.post(simulator.baseUrl() + "/pharmacy/v1/dispense",
                "{\"prescriptionNo\":\"RX-SYNTH-0003\",\"drugCode\":\"PMS-D-9999\",\"quantity\":1}");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.attempts()).isEqualTo(1);
    }

    @Test
    void adapterStatusMapsToContractSchemaAndMatchesApiSemantics() throws Exception {
        ContractHttpClient.Result wire = client.get(simulator.baseUrl() + "/pharmacy/v1/adapter-status");
        assertThat(wire.delivered()).isTrue();
        assertThat(fieldNames(wire.body())).isEqualTo(WireMapper.PHARMACY_STATUS_WIRE_FIELDS);

        PharmacyAdapterStatus mapped = WireMapper.pharmacyStatus(wire.body());
        assertThat(mapped.getAdapterCode()).isEqualTo("EXT-02");
        assertThat(mapped.getMode()).isEqualTo(PharmacyAdapterStatus.ModeEnum.SIMULATED);
        assertThat(mapped.getAvailable()).isTrue();
        assertThat(mapped.getDegraded()).isFalse();
        assertThat(mapped.getPendingSyncCount()).isZero();
        assertThat(mapped.getLastSyncedAt()).isNotNull();
        assertThat(mapped.getMessage()).contains("SYNTHETIC");
        assertContractFields(mapped, WireMapper.PHARMACY_STATUS_CONTRACT_FIELDS);

        // 平台适配器状态与模拟器映射语义一致（同一字段名/含义）。
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/drugs/pharmacy-adapter/status")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adapterCode").value("EXT-02"))
                .andExpect(jsonPath("$.mode").value("SIMULATED"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.pendingSyncCount").value(0))
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void httpErrorAndTimeoutDegradeToLocalDictionaryAvailable() throws Exception {
        SimulatorConfig.Endpoint broken = new SimulatorConfig.Endpoint(0);
        broken.setFailureMode(FailureMode.HTTP_ERROR);
        broken.setErrorStatus(500);
        PharmacySimulator unavailable = new PharmacySimulator(broken);
        unavailable.start();
        try {
            ContractHttpClient.Result failed = client.get(unavailable.baseUrl() + "/pharmacy/v1/adapter-status");
            assertThat(failed.delivered()).isFalse();
            assertThat(failed.status()).isEqualTo(500);
            assertThat(failed.attempts()).as("5xx 重试 3 次").isEqualTo(3);

            PharmacyAdapterStatus degraded = WireMapper.pharmacyDegraded("HTTP 500");
            assertThat(degraded.getAvailable()).isFalse();
            assertThat(degraded.getDegraded()).isTrue();
            assertThat(degraded.getPendingSyncCount()).isEqualTo(1);
            assertThat(degraded.getLastSyncedAt()).isNull();
            assertThat(degraded.getMessage()).contains("降级");
            assertContractFields(degraded, WireMapper.PHARMACY_STATUS_CONTRACT_FIELDS);
        } finally {
            unavailable.close();
        }

        SimulatorConfig.Endpoint slowEndpoint = new SimulatorConfig.Endpoint(0);
        slowEndpoint.setLatencyMs(800);
        PharmacySimulator slow = new PharmacySimulator(slowEndpoint);
        slow.start();
        try {
            ContractHttpClient impatient = new ContractHttpClient(objectMapper,
                    Duration.ofMillis(200), Duration.ofMillis(120), 2, 5);
            ContractHttpClient.Result timedOut = impatient.get(
                    slow.baseUrl() + "/pharmacy/v1/adapter-status");
            assertThat(timedOut.delivered()).isFalse();
            assertThat(timedOut.attempts()).isEqualTo(2);
            assertThat(WireMapper.pharmacyDegraded(timedOut.failureReason()).getAvailable()).isFalse();
        } finally {
            slow.close();
        }

        // 降级不阻塞本地药品字典：平台查询仍可用。
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/drugs").header("Authorization", "Bearer " + admin)
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

    private void cleanSyntheticDrugs() {
        jdbcTemplate.update("""
                DELETE FROM m4_drug.drug_alias WHERE drug_id IN (
                    SELECT id FROM m4_drug.drug WHERE code LIKE 'PMS-D-%')
                """);
        jdbcTemplate.update("DELETE FROM m4_drug.drug WHERE code LIKE 'PMS-D-%'");
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
