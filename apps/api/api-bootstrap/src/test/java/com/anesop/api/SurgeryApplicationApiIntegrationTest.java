package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 手术申请接收与校验（M2-M1-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：模拟 HIS 导入幂等（重复导入不增行）、手工创建/列表分页/筛选/详情、取消流转 +
 * 状态历史 + 审计、409/404/400/422、无权限 403、DEPT 数据范围过滤、住院号脱敏且无明文、
 * 患者摘要接口不含证件号/手机号。全部账号、患者与申请均为 SYNTHETIC 合成数据。
 */
class SurgeryApplicationApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private static final String PATIENT_ANES_ID = "51000000-0000-4000-8000-000000000001";
    private static final String PATIENT_OR_ID = "51000000-0000-4000-8000-000000000002";
    private static final String HIS_PATIENT_ANES = "SYNTH-HIS-P-9001";
    private static final String HIS_PATIENT_OR = "SYNTH-HIS-P-9002";
    private static final String INPATIENT_ANES = "SYNTH-ZY-9001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareData() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        // 每个用例从干净的 M1 表开始，避免导入/手工数据互相污染。
        // 状态历史是 append-only（S-4 触发器）：测试夹具临时 DISABLE TRIGGER USER 清理，
        // 随后立即恢复；生产路径不得使用该开关。
        jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m1_sched.application_status_history");
            jdbcTemplate.update("DELETE FROM m1_sched.surgery_application");
            jdbcTemplate.update("DELETE FROM m1_sched.patient_snapshot");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history ENABLE TRIGGER USER");
        }
        insertPatient(PATIENT_ANES_ID, HIS_PATIENT_ANES, "演示患者甲", "SYNT****9001", "hash-9001",
                SyntheticAccounts.DEMO_ANES_ORG_ID, "麻醉科（合成）");
        insertPatient(PATIENT_OR_ID, HIS_PATIENT_OR, "演示患者乙", "SYNT****9002", "hash-9002",
                SyntheticAccounts.DEMO_OR_ORG_ID, "手术室（合成）");
    }

    @Test
    void importIsIdempotentAndMarksSimulated() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult first = mockMvc.perform(post("/v1/scheduling/applications/import")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("count", 4, "seed", 20260101))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.adapter").value("MOCK_HIS"))
                .andExpect(jsonPath("$.requestedCount").value(4))
                .andExpect(jsonPath("$.importedCount").value(4))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.applications", hasSize(4)))
                .andReturn();
        assertThat(first.getResponse().getContentAsString())
                .as("模拟导入响应明确标记来源且不含住院号明文")
                .contains("模拟 HIS 导入")
                .doesNotContain(INPATIENT_ANES);

        mockMvc.perform(post("/v1/scheduling/applications/import")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("count", 4, "seed", 20260101))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(4))
                .andExpect(jsonPath("$.applications", hasSize(0)));

        Integer applications = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m1_sched.surgery_application", Integer.class);
        Integer patients = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m1_sched.patient_snapshot WHERE his_patient_no LIKE 'SYNTH-P-%'",
                Integer.class);
        assertThat(applications).as("重复导入不得新增申请行").isEqualTo(4);
        assertThat(patients).as("重复导入不得新增患者快照").isEqualTo(4);
        System.out.printf("[M2-M1-01][import] first=4 second=0 rows=%d patients=%d simulated=true%n",
                applications, patients);
    }

    @Test
    void importStoresMaskedInpatientNumberWithoutPlaintext() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(post("/v1/scheduling/applications/import")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("count", 3, "seed", 7))))
                .andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT inpatient_no_masked, inpatient_no_hash FROM m1_sched.patient_snapshot
                WHERE his_patient_no LIKE 'SYNTH-P-%'
                """);
        assertThat(rows).hasSize(3);
        for (Map<String, Object> row : rows) {
            String masked = String.valueOf(row.get("inpatient_no_masked"));
            String hash = String.valueOf(row.get("inpatient_no_hash"));
            assertThat(masked).contains("****").startsWith("SYNT");
            assertThat(hash).hasSize(64).doesNotContain("SYNTH");
            assertThat(masked).doesNotContain("SYNTH-ZY-");
        }
        Integer plaintextHits = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m1_sched.patient_snapshot
                WHERE inpatient_no_masked LIKE '%SYNTH-ZY-%' OR inpatient_no_hash LIKE '%SYNTH-ZY-%'
                """, Integer.class);
        assertThat(plaintextHits).as("住院号明文不得入库").isZero();
        System.out.printf("[M2-M1-01][masking] rows=%d plaintextHits=%d sample=%s%n",
                rows.size(), plaintextHits, rows.get(0).get("inpatient_no_masked"));
    }

    @Test
    void manualCreateListDetailAndValidation() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult created = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createRequest("SYNTH-MANUAL-APP-9001"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.patient.inpatientNoMasked").value("SYNT****9001"))
                .andExpect(jsonPath("$.patient.departmentName").value("麻醉科（合成）"))
                .andExpect(jsonPath("$.statusHistory", hasSize(1)))
                .andExpect(jsonPath("$.statusHistory[0].fromStatus").value(nullValue()))
                .andReturn();
        String applicationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("applicationId").asText();
        assertThat(created.getResponse().getContentAsString())
                .doesNotContain("\"idCard", "\"phone", "SYNTH-ZY-9001");

        mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createRequest("SYNTH-MANUAL-APP-9001"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        mockMvc.perform(get("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .param("status", "SUBMITTED")
                        .param("urgency", "URGENT")
                        .param("departmentName", "麻醉科（合成）")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.items[0].applicationId").value(applicationId));

        mockMvc.perform(get("/v1/scheduling/applications/" + applicationId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hisApplicationNo").value("SYNTH-MANUAL-APP-9001"))
                .andExpect(jsonPath("$.statusHistory", hasSize(1)));

        mockMvc.perform(get("/v1/scheduling/applications/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createRequest("SYNTH-MANUAL-APP-9002", 0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));

        Map<String, Object> unknownPatient = createRequest("SYNTH-MANUAL-APP-9003");
        unknownPatient.put("patientId", UUID.randomUUID().toString());
        mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(unknownPatient)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        Map<String, Object> invalidUrgency = createRequest("SYNTH-MANUAL-APP-9004");
        invalidUrgency.put("urgency", "SOMEDAY");
        mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(invalidUrgency)))
                .andExpect(status().isBadRequest());
        System.out.printf("[M2-M1-01][create] applicationId=%s status=SUBMITTED duplicate=409 unknownPatient=422%n",
                applicationId);
    }

    @Test
    void cancelWritesHistoryAndAuditAndRejectsRepeat() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createManual(admin, "SYNTH-MANUAL-APP-9010");

        mockMvc.perform(post("/v1/scheduling/applications/" + applicationId + "/cancel")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "PATIENT_CONDITION",
                                "reason", "演示取消原因（合成数据）",
                                "version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(applicationId));

        mockMvc.perform(get("/v1/scheduling/applications/" + applicationId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.statusHistory", hasSize(2)))
                .andExpect(jsonPath("$.statusHistory[1].fromStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.statusHistory[1].toStatus").value("CANCELLED"));

        Map<String, Object> historyRow = jdbcTemplate.queryForMap("""
                SELECT reason_code, reason FROM m1_sched.application_status_history
                WHERE application_id = ?::uuid AND to_status = 'CANCELLED'
                """, applicationId);
        assertThat(historyRow.get("reason_code")).isEqualTo("PATIENT_CONDITION");
        assertThat(historyRow.get("reason")).isEqualTo("演示取消原因（合成数据）");

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, resource_type, resource_id, result
                FROM m0_platform.sys_audit_log
                WHERE action = 'SURGERY_APPLICATION_CANCEL' AND resource_id = ?
                """, applicationId);
        assertThat(audits).as("取消必须写审计").hasSize(1);
        assertThat(audits.get(0).get("action")).isEqualTo("SURGERY_APPLICATION_CANCEL");
        assertThat(((Number) audits.get(0).get("result")).intValue()).isEqualTo(1);

        mockMvc.perform(post("/v1/scheduling/applications/" + applicationId + "/cancel")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "PATIENT_CONDITION",
                                "reason", "重复取消（合成数据）",
                                "version", 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        System.out.printf("[M2-M1-01][cancel] applicationId=%s status=CANCELLED history=2 audit=1 repeat=409%n",
                applicationId);
    }

    @Test
    void dataScopeHidesOtherDepartmentApplications() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createRequest("SYNTH-MANUAL-APP-9020"))))
                .andExpect(status().isCreated());
        String orPatientRequest = objectMapper.writeValueAsString(
                createRequestFor("SYNTH-MANUAL-APP-9021", PATIENT_OR_ID));
        MvcResult orCreated = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(orPatientRequest))
                .andExpect(status().isCreated())
                .andReturn();
        String orApplicationId = objectMapper.readTree(orCreated.getResponse().getContentAsString())
                .get("applicationId").asText();

        mockMvc.perform(get("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + dept))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.items[0].patient.departmentId")
                        .value(SyntheticAccounts.DEMO_ANES_ORG_ID));

        mockMvc.perform(get("/v1/scheduling/applications/" + orApplicationId)
                        .header("Authorization", "Bearer " + dept))
                .andExpect(status().isNotFound());
        System.out.printf("[M2-M1-01][scope] dept=%s visible=1 hidden=%s%n",
                SyntheticAccounts.DEMO_DEPT_USERNAME, orApplicationId);
    }

    @Test
    void permissionsAreEnforced() throws Exception {
        mockMvc.perform(get("/v1/scheduling/applications"))
                .andExpect(status().isUnauthorized());

        String noPermission = login(SyntheticAccounts.DEMO_SELF_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + noPermission))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME, SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(0));
        mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createRequest("SYNTH-MANUAL-APP-9030"))))
                .andExpect(status().isForbidden());
        System.out.printf("[M2-M1-01][403] noPermission=%s surgeonWrite=403 anonymous=401%n",
                SyntheticAccounts.DEMO_SELF_USERNAME);
    }

    @Test
    void importCountMustBeOneToFive() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        for (int count : new int[] {0, 6}) {
            mockMvc.perform(post("/v1/scheduling/applications/import")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(JSON)
                            .content(objectMapper.writeValueAsString(Map.of("count", count))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        }
        mockMvc.perform(post("/v1/scheduling/applications/import")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("count", 1, "seed", 11))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedCount").value(1))
                .andExpect(jsonPath("$.importedCount").value(1))
                .andExpect(jsonPath("$.applications", hasSize(1)));
        System.out.println("[M2-M1-01][import-count] count=0/6 → 400；count=1 → requested=1 imported=1");
    }

    @Test
    void concurrentImportCreatesEachApplicationAndPatientOnce() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Callable<MvcResult> task = () -> mockMvc.perform(post("/v1/scheduling/applications/import")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("count", 4, "seed", 20260101))))
                .andExpect(status().isOk())
                .andReturn();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        JsonNode firstBody;
        JsonNode secondBody;
        try {
            Future<MvcResult> first = pool.submit(task);
            Future<MvcResult> second = pool.submit(task);
            firstBody = objectMapper.readTree(first.get(60, TimeUnit.SECONDS)
                    .getResponse().getContentAsString());
            secondBody = objectMapper.readTree(second.get(60, TimeUnit.SECONDS)
                    .getResponse().getContentAsString());
            int imported = firstBody.get("importedCount").asInt() + secondBody.get("importedCount").asInt();
            int skipped = firstBody.get("skippedCount").asInt() + secondBody.get("skippedCount").asInt();
            assertThat(imported).as("并发导入只允许一个线程新增").isEqualTo(4);
            assertThat(skipped).as("另一线程全部按幂等跳过").isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }

        Integer applications = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m1_sched.surgery_application WHERE his_application_no LIKE 'SYNTH-HIS-APP-%'",
                Integer.class);
        Integer patients = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m1_sched.patient_snapshot WHERE his_patient_no LIKE 'SYNTH-P-%'",
                Integer.class);
        assertThat(applications).as("并发导入不得重复建单").isEqualTo(4);
        assertThat(patients).as("并发导入不得重复建患者快照").isEqualTo(4);
        System.out.printf("[M2-M1-01][concurrent-import] first=%d/%d second=%d/%d rows=%d patients=%d%n",
                firstBody.get("importedCount").asInt(), firstBody.get("skippedCount").asInt(),
                secondBody.get("importedCount").asInt(), secondBody.get("skippedCount").asInt(),
                applications, patients);
    }

    @Test
    void patientSearchUsesPostBodyAndDeprecatedGetStillWorks() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(post("/v1/patients/search")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "hisPatientNo", HIS_PATIENT_ANES, "page", 1, "size", 20))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.items[0].hisPatientNo").value(HIS_PATIENT_ANES))
                .andExpect(jsonPath("$.items[0].inpatientNoMasked").value("SYNT****9001"))
                .andExpect(jsonPath("$.items[0].idCardMasked").value(""))
                .andExpect(jsonPath("$.items[0].phoneMasked").value(""));

        // 兼容：旧 GET 仍可用但契约标记 deprecated；不得影响 POST 检索。
        mockMvc.perform(get("/v1/patients")
                        .header("Authorization", "Bearer " + admin)
                        .param("hisPatientNo", HIS_PATIENT_ANES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1));
        System.out.println("[M2-M1-01][search] POST /v1/patients/search total=1 GET(deprecated) total=1");
    }

    @Test
    void patientEndpointsReturnMaskedSummaryWithoutIdentityOrPhone() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult list = mockMvc.perform(get("/v1/patients")
                        .header("Authorization", "Bearer " + admin)
                        .param("hisPatientNo", HIS_PATIENT_ANES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.items[0].hisPatientNo").value(HIS_PATIENT_ANES))
                .andExpect(jsonPath("$.items[0].age").value(48))
                .andExpect(jsonPath("$.items[0].inpatientNoMasked").value("SYNT****9001"))
                .andExpect(jsonPath("$.items[0].departmentName").value("麻醉科（合成）"))
                .andReturn();
        assertThat(list.getResponse().getContentAsString())
                .as("兼容字段存在但为空字符串，不返回真实证件号/手机号/住院号明文")
                .doesNotContain("110***********", "138****", "SYNTH-ZY-9001");

        mockMvc.perform(get("/v1/patients/" + PATIENT_ANES_ID)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthDate").value("1978-03-15"))
                .andExpect(jsonPath("$.inpatientNoMasked").value("SYNT****9001"));

        mockMvc.perform(get("/v1/patients/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());

        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/patients")
                        .header("Authorization", "Bearer " + dept))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1));
        mockMvc.perform(get("/v1/patients/" + PATIENT_OR_ID)
                        .header("Authorization", "Bearer " + dept))
                .andExpect(status().isNotFound());
        // Q-3：患者摘要使用独立最小权限 m1:patient:read。
        String noPermission = login(SyntheticAccounts.DEMO_SELF_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/patients")
                        .header("Authorization", "Bearer " + noPermission))
                .andExpect(status().isForbidden());
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME, SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/patients")
                        .header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isOk());
        System.out.printf("[M2-M1-01][patient] masked=%s deptVisible=1 noIdCardNoPhone=true%n",
                "SYNT****9001");
    }

    private void insertPatient(String id, String hisPatientNo, String name, String masked, String hash,
            String deptId, String deptName) {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, ?, 'FEMALE', DATE '1978-03-15', 48, ?, ?, ?, ?::uuid,
                        'f1000000-0000-4000-8000-000000000001'::uuid, '演示病区', '12',
                        now(), now(), 1, false)
                """, id, hisPatientNo, name, masked, hash, deptName, deptId);
    }

    private Map<String, Object> createRequest(String hisApplicationNo) {
        return createRequest(hisApplicationNo, 90);
    }

    private Map<String, Object> createRequest(String hisApplicationNo, int duration) {
        return createRequestFor(hisApplicationNo, PATIENT_ANES_ID, duration);
    }

    private Map<String, Object> createRequestFor(String hisApplicationNo, String patientId) {
        return createRequestFor(hisApplicationNo, patientId, 90);
    }

    private Map<String, Object> createRequestFor(String hisApplicationNo, String patientId, int duration) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("hisApplicationNo", hisApplicationNo);
        request.put("patientId", patientId);
        request.put("surgeryName", "演示术式（腹腔镜胆囊切除术）");
        request.put("surgeryCode", "SYNTH-OP-0002");
        request.put("urgency", "URGENT");
        request.put("plannedDurationMin", duration);
        request.put("surgeonName", "演示术者");
        request.put("preopDiagnosis", "演示诊断（胆囊结石）");
        request.put("remark", "演示备注（合成数据）");
        return request;
    }

    private String createManual(String token, String hisApplicationNo) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createRequest(hisApplicationNo))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("applicationId").asText();
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

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
