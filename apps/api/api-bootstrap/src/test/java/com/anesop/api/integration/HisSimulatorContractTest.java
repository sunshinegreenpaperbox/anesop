package com.anesop.api.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.his.HisSimulator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HIS 适配器 ↔ HIS 模拟器字段映射契约测试（M5-M19-01 / EXT-01）。
 *
 * <p>真实启动 {@link HisSimulator}（JDK HttpServer，端口 0 系统分配），用 JDK HttpClient
 * 按适配器语义（超时/重试/降级）调用，断言：
 * <ul>
 *   <li>外部字段集合与 {@code docs/integration/README.md} §3.1 登记的模式完全一致；</li>
 *   <li>映射到 {@code HisSurgeryApplicationRecord} 的每个字段值、值域转换（F/M/U、中文紧急度）；</li>
 *   <li>映射结果被真实 API 接受（201）且同一申请号重复创建 409（幂等键）；</li>
 *   <li>创建/变更回调、错误码、重试后成功、超时降级不阻塞平台主链路。</li>
 * </ul>
 * 全部数据为 SYNTHETIC。
 */
class HisSimulatorContractTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String PATIENT_ID = "51000000-0000-4000-8000-0000000000e1";
    private static final UUID DEPT_ID = UUID.fromString(SyntheticAccounts.DEMO_ANES_ORG_ID);
    private static final UUID SITE_ID = UUID.fromString("f1000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private HisSimulator simulator;
    private ContractHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        cleanSyntheticHisRows();
        simulator = new HisSimulator(new SimulatorConfig.Endpoint(0));
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
    void hisApplicationFieldsMapToPlatformRecordAndApiAcceptsThem() throws Exception {
        ContractHttpClient.Result pull = client.get(
                simulator.baseUrl() + "/his/v1/surgery-applications?count=2&seed=11");

        assertThat(pull.delivered()).isTrue();
        assertThat(pull.status()).isEqualTo(200);
        assertThat(pull.attempts()).as("正常响应不得重试").isEqualTo(1);
        assertThat(pull.body().path("simulated").asBoolean()).isTrue();
        assertThat(pull.text("source")).isEqualTo("HIS-SIM");
        JsonNode applications = pull.body().path("applications");
        assertThat(applications.size()).isEqualTo(2);

        JsonNode first = applications.get(0);
        assertThat(fieldNames(first)).isEqualTo(WireMapper.HIS_APPLICATION_FIELDS);
        assertThat(fieldNames(first.path("patient"))).isEqualTo(WireMapper.HIS_PATIENT_FIELDS);

        var record = WireMapper.hisRecord(first, DEPT_ID, SITE_ID);
        assertThat(record.hisApplicationNo()).isEqualTo(first.path("applicationNo").asText());
        assertThat(record.hisPatientNo()).isEqualTo(first.path("patient").path("patientNo").asText());
        assertThat(record.patientName()).isEqualTo(first.path("patient").path("patientName").asText());
        assertThat(record.gender()).isEqualTo("FEMALE");
        assertThat(WireMapper.mapSex(first.path("patient").path("sex").asText())).isEqualTo(record.gender());
        assertThat(record.birthDate().toString()).isEqualTo(first.path("patient").path("birthday").asText());
        assertThat(record.age()).isEqualTo(first.path("patient").path("age").asInt());
        assertThat(record.inpatientNo()).isEqualTo(first.path("patient").path("admissionNo").asText());
        assertThat(record.deptName()).isEqualTo(first.path("patient").path("department").asText());
        assertThat(record.deptId()).isEqualTo(DEPT_ID);
        assertThat(record.siteId()).isEqualTo(SITE_ID);
        assertThat(record.ward()).isEqualTo(first.path("patient").path("ward").asText());
        assertThat(record.bedNo()).isEqualTo(first.path("patient").path("bed").asText());
        assertThat(record.surgeryName()).isEqualTo(first.path("operationName").asText());
        assertThat(record.surgeryCode()).isEqualTo(first.path("operationCode").asText());
        assertThat(record.urgency()).isIn("ELECTIVE", "URGENT", "EMERGENCY");
        assertThat(WireMapper.mapUrgency(first.path("urgency").asText())).isEqualTo(record.urgency());
        assertThat(record.plannedDurationMin()).isEqualTo(first.path("plannedMinutes").asInt());
        assertThat(record.surgeonName()).isEqualTo(first.path("surgeon").asText());
        assertThat(record.preopDiagnosis()).isEqualTo(first.path("diagnosis").asText());
        assertThat(record.remark()).isEqualTo(first.path("note").asText());

        // I-01a 患者主索引与 I-01b 申请中的患者字段必须一致（同一患者号）。
        ContractHttpClient.Result patient = client.get(simulator.baseUrl()
                + "/his/v1/patients/" + record.hisPatientNo());
        assertThat(patient.delivered()).isTrue();
        assertThat(patient.text("patientNo")).isEqualTo(record.hisPatientNo());
        assertThat(patient.text("patientName")).isEqualTo(record.patientName());
        assertThat(patient.text("admissionNo")).isEqualTo(record.inpatientNo());

        insertPatientSnapshot(record);

        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Map<String, Object> createRequest = WireMapper.hisCreateRequest(first, PATIENT_ID);
        MvcResult created = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hisApplicationNo").value(record.hisApplicationNo()))
                .andExpect(jsonPath("$.patient.patientId").value(PATIENT_ID))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn();
        assertThat(created.getResponse().getContentAsString()).doesNotContain(record.inpatientNo());

        // 幂等键：同一 HIS 申请号重复创建 → 409（客户端重试/补传不得产生重复申请）。
        mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isConflict());

        Integer persisted = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m1_sched.surgery_application WHERE his_application_no = ?
                """, Integer.class, record.hisApplicationNo());
        assertThat(persisted).isEqualTo(1);

        // 同种子重复拉取得到相同申请号（导入幂等前提）。
        ContractHttpClient.Result again = client.get(
                simulator.baseUrl() + "/his/v1/surgery-applications?count=2&seed=11");
        assertThat(again.body().path("applications")).isEqualTo(applications);
    }

    @Test
    void callbacksAcknowledgeCreationAndChangeAndRejectInvalidPayloads() throws Exception {
        String applicationNo = client.get(
                simulator.baseUrl() + "/his/v1/surgery-applications?count=1&seed=3")
                .body().path("applications").get(0).path("applicationNo").asText();

        ContractHttpClient.Result accepted = client.post(simulator.baseUrl()
                + "/his/v1/surgery-applications/" + applicationNo + "/status", "{\"status\":\"ACCEPTED\"}");
        assertThat(accepted.delivered()).isTrue();
        assertThat(accepted.body().path("accepted").asBoolean()).isTrue();
        assertThat(accepted.text("receivedAt")).isNotBlank();

        ContractHttpClient.Result changed = client.post(simulator.baseUrl()
                + "/his/v1/surgery-applications/" + applicationNo + "/change",
                "{\"reason\":\"术间调整（SYNTHETIC）\"}");
        assertThat(changed.body().path("changed").asBoolean()).isTrue();

        // 4xx 是业务拒绝，不是瞬时故障：适配器不得重试。
        ContractHttpClient.Result invalidStatus = client.post(simulator.baseUrl()
                + "/his/v1/surgery-applications/" + applicationNo + "/status", "{\"status\":\"MAYBE\"}");
        assertThat(invalidStatus.status()).isEqualTo(400);
        assertThat(invalidStatus.attempts()).as("4xx 不重试").isEqualTo(1);
        assertThat(invalidStatus.text("code")).isEqualTo("INVALID_STATUS");

        ContractHttpClient.Result unknown = client.post(simulator.baseUrl()
                + "/his/v1/surgery-applications/SYNTH-HIS-APP-9999/status", "{\"status\":\"ACCEPTED\"}");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.attempts()).isEqualTo(1);
    }

    @Test
    void transientFailuresAreRetriedAndTimeoutDegradesWithoutBlockingPlatform() throws Exception {
        HisSimulator flaky = new HisSimulator(failingEndpoint(0, 2, 503));
        flaky.start();
        try {
            ContractHttpClient retrying = new ContractHttpClient(objectMapper,
                    Duration.ofMillis(500), Duration.ofMillis(800), 3, 5);
            ContractHttpClient.Result recovered = retrying.get(
                    flaky.baseUrl() + "/his/v1/surgery-applications?count=1&seed=5");

            assertThat(recovered.delivered()).as("前 2 次 503 后第 3 次成功").isTrue();
            assertThat(recovered.attempts()).isEqualTo(3);
            assertThat(flaky.requestCount()).isEqualTo(3);
        } finally {
            flaky.close();
        }

        SimulatorConfig.Endpoint slowEndpoint = new SimulatorConfig.Endpoint(0);
        slowEndpoint.setLatencyMs(800);
        HisSimulator slow = new HisSimulator(slowEndpoint);
        slow.start();
        try {
            ContractHttpClient impatient = new ContractHttpClient(objectMapper,
                    Duration.ofMillis(200), Duration.ofMillis(120), 2, 5);
            ContractHttpClient.Result timedOut = impatient.get(
                    slow.baseUrl() + "/his/v1/surgery-applications?count=1&seed=5");

            assertThat(timedOut.delivered()).as("超时按降级处理，不向上抛异常").isFalse();
            assertThat(timedOut.attempts()).isEqualTo(2);
            assertThat(timedOut.failureReason()).containsIgnoringCase("timeout");
        } finally {
            slow.close();
        }

        // 降级不阻塞平台主链路：模拟器不可用时平台仍可用（临床可手工建单）。
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .param("size", "5"))
                .andExpect(status().isOk());
    }

    private static SimulatorConfig.Endpoint failingEndpoint(int port, int failFirstRequests, int errorStatus) {
        SimulatorConfig.Endpoint endpoint = new SimulatorConfig.Endpoint(port);
        endpoint.setFailFirstRequests(failFirstRequests);
        endpoint.setErrorStatus(errorStatus);
        return endpoint;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void insertPatientSnapshot(com.anesop.m1.api.his.HisSurgeryApplicationRecord record) {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, ?, ?, ?::date, ?, ?, ?, ?, ?::uuid, ?::uuid, ?, ?,
                        now(), now(), 1, false)
                """, PATIENT_ID, record.hisPatientNo(), record.patientName(), record.gender(),
                record.birthDate().toString(), record.age(), "SYNT****0001",
                "hash-" + record.hisPatientNo(), record.deptName(), DEPT_ID.toString(),
                SITE_ID.toString(), record.ward(), record.bedNo());
    }

    private void cleanSyntheticHisRows() {
        jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("""
                    DELETE FROM m1_sched.application_status_history WHERE application_id IN (
                        SELECT id FROM m1_sched.surgery_application WHERE his_application_no LIKE 'SYNTH-HIS-APP-%')
                    """);
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.surgery_application WHERE his_application_no LIKE 'SYNTH-HIS-APP-%'");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.patient_snapshot WHERE his_patient_no LIKE 'SYNTH-P-%'");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history ENABLE TRIGGER USER");
        }
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
