package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 术前访视与评估（M2-M4-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16（Docker 不可用）。
 *
 * <p>覆盖：V10 结构/权限/注释、创建（服务端计算四量表）、列表/详情、乐观锁更新、最终化锁定、
 * 非法输入 400、重复草稿/版本冲突 409、申请不可见 422、跨科室 404、无权限 403、审计留痕
 * （detail 无姓名/病史）。全部数据为 SYNTHETIC。
 */
class PreopAssessmentApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private static final String PATIENT_ANES = "53000000-0000-4000-8000-000000000001";
    private static final String HIS_ANES = "SYNTH-HIS-P-9201";
    private static final String PATIENT_OR = "53000000-0000-4000-8000-000000000002";
    private static final String HIS_OR = "SYNTH-HIS-P-9202";

    private static final String PREOP_DEPT_USER_ID = "e0000000-0000-4000-8000-0000000000d1";
    private static final String PREOP_DEPT_USERNAME = "test.preop.dept";
    private static final String PREOP_DEPT_PASSWORD = "Synth@2026";
    private static final String PREOP_DEPT_ROLE_ID = "a0000000-0000-4000-8000-0000000000f2";

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
        ensurePreopDeptUser();

        // 同一 Spring 上下文/数据库被多个集成测试类共享：按外键依赖顺序清理，
        // 避免前一用例的排程/变更记录引用申请导致 DELETE 失败。
        // V10 起 preop_assessment 禁止 DELETE（临床记录 append-only），测试清理需临时停用用户触发器。
        jdbcTemplate.execute("ALTER TABLE m2_anes.preop_assessment DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m2_anes.preop_assessment");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.preop_assessment ENABLE TRIGGER USER");
        }
        jdbcTemplate.update("DELETE FROM m1_sched.notification_outbox");
        jdbcTemplate.execute("ALTER TABLE m1_sched.schedule_change_request DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m1_sched.schedule_change_request");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.schedule_change_request ENABLE TRIGGER USER");
        }
        jdbcTemplate.update("DELETE FROM m1_sched.schedule");
        jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m1_sched.application_status_history");
            jdbcTemplate.update("DELETE FROM m1_sched.surgery_application");
            jdbcTemplate.update("DELETE FROM m1_sched.patient_snapshot");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history ENABLE TRIGGER USER");
        }
        insertPatient(PATIENT_ANES, HIS_ANES, "演示患者甲", SyntheticAccounts.DEMO_ANES_ORG_ID,
                "麻醉科（合成）");
        insertPatient(PATIENT_OR, HIS_OR, "演示患者乙", SyntheticAccounts.DEMO_OR_ORG_ID,
                "手术室（合成）");
    }

    // ---------------------------------------------------------------- 主流程 --

    @Test
    void createListUpdateFinalizeLocksAndAudits() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M4-APP-0001", PATIENT_ANES, HIS_ANES);

        MvcResult created = mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createBody(applicationId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                // 服务端计算：RCRI 1（高危手术）、Apfel 4、STOP-BANG 4、DASI 18.95
                .andExpect(jsonPath("$.scores.rcriScore").value(1))
                .andExpect(jsonPath("$.scores.rcriRisk").value("MODERATE"))
                .andExpect(jsonPath("$.scores.apfelScore").value(4))
                .andExpect(jsonPath("$.scores.apfelRisk").value("HIGH"))
                .andExpect(jsonPath("$.scores.stopBangScore").value(4))
                .andExpect(jsonPath("$.scores.dasiScore").value(18.95))
                .andExpect(jsonPath("$.scores.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.scores.calculatorVersion").value("1.0.0"))
                .andReturn();
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        String assessmentId = createdBody.get("assessmentId").asText();
        String createdAt = createdBody.get("createdAt").asText();

        mockMvc.perform(get("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .param("applicationId", applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.applicationId").value(applicationId))
                .andExpect(jsonPath("$.application.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.application.patientSnapshot.hisPatientNo").value(HIS_ANES))
                .andExpect(jsonPath("$.items", hasSize(1)));

        mockMvc.perform(get("/v1/anesthesia/preop-assessments/" + assessmentId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assessmentId").value(assessmentId));

        mockMvc.perform(put("/v1/anesthesia/preop-assessments/" + assessmentId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(updateBody(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.asaClass").value("III"))
                .andExpect(jsonPath("$.scores.rcriScore").value(3))
                .andExpect(jsonPath("$.scores.stopBangScore").value(5))
                .andExpect(jsonPath("$.scores.dasiScore").value(7.2))
                .andExpect(jsonPath("$.scores.functionalCapacityLow").value(true));

        mockMvc.perform(post("/v1/anesthesia/preop-assessments/" + assessmentId + "/finalize")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 2, "note", "访视完成（合成）"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALIZED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.finalizeNote").value("访视完成（合成）"))
                .andExpect(jsonPath("$.finalizedAt").isNotEmpty())
                .andExpect(jsonPath("$.finalizedBy").value(SyntheticAccounts.DEMO_ADMIN_ID));

        // 最终化后不可修改、不可重复最终化 → 409
        mockMvc.perform(put("/v1/anesthesia/preop-assessments/" + assessmentId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(updateBody(3))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        mockMvc.perform(post("/v1/anesthesia/preop-assessments/" + assessmentId + "/finalize")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 3))))
                .andExpect(status().isConflict());

        // 最终化后可新建版本（同一申请第二份访视单，保留原单）
        mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createBody(applicationId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT finalize_note FROM m2_anes.preop_assessment WHERE id = ?::uuid",
                String.class, assessmentId)).isEqualTo("访视完成（合成）");

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'PREOP_ASSESSMENT' AND resource_id = ?
                ORDER BY occurred_at
                """, assessmentId);
        assertThat(audits).extracting(row -> row.get("action"))
                .containsExactly("PREOP_CREATE", "PREOP_UPDATE", "PREOP_FINALIZE");
        assertThat(audits).allMatch(row -> ((Number) row.get("result")).intValue() == 1);
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail")).contains("演示患者"));
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail")).contains("演示病史"));

        System.out.printf("[M2-M4-01][flow] assessment=%s version=3 scores=RCRI1/Apfel4/STOP4/DASI18.95 "
                        + "risk=HIGH locked=409 audit=3 noPhi=true createdAt=%s%n",
                assessmentId, createdAt);
    }

    // ------------------------------------------------------------ 非法输入 --

    @Test
    void invalidInputsAndStateConflictsReturnDocumentedErrors() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M4-APP-0002", PATIENT_ANES, HIS_ANES);

        // 非法 ASA 枚举 → Jackson/Bean Validation 400
        Map<String, Object> invalidAsa = createBody(applicationId);
        invalidAsa.put("asaClass", "VII");
        mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(invalidAsa)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));

        // 缺评分项（RCRI 少一项）→ 400
        Map<String, Object> missingItem = createBody(applicationId);
        Map<String, Object> scoreInput = asMap(missingItem.get("scoreInput"));
        Map<String, Object> rcri = asMap(scoreInput.get("rcri"));
        rcri.remove("renalImpairment");
        mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(missingItem)))
                .andExpect(status().isBadRequest());

        // 患者快照与申请不一致 → 400
        Map<String, Object> mismatch = createBody(applicationId);
        asMap(mismatch.get("patientSnapshot")).put("hisPatientNo", "SYNTH-OTHER-9999");
        mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(mismatch)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));

        // 申请不存在 → 422（请求体参数，无路径参数）
        Map<String, Object> unknownApplication = createBody("60000000-0000-4000-8000-00000000dead");
        mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(unknownApplication)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        // 正常创建一次；重复草稿 → 409
        MvcResult created = mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createBody(applicationId))))
                .andExpect(status().isCreated())
                .andReturn();
        String assessmentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("assessmentId").asText();
        mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createBody(applicationId))))
                .andExpect(status().isConflict());

        // 乐观锁版本过期 → 409
        mockMvc.perform(put("/v1/anesthesia/preop-assessments/" + assessmentId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(updateBody(99))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        System.out.println("[M2-M4-01][errors] invalidAsa=400 missingItem=400 patientMismatch=400 "
                + "unknownApplication=422 duplicateDraft=409 staleVersion=409");
    }

    // ------------------------------------------- 服务端回填 / scores 忽略 --

    @Test
    void serverBackfillsPatientSnapshotAndIgnoresClientNamesAndScores() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M4-APP-0004", PATIENT_ANES, HIS_ANES);

        Map<String, Object> body = createBody(applicationId);
        Map<String, Object> clientSnapshot = asMap(body.get("patientSnapshot"));
        clientSnapshot.put("patientName", "篡改姓名");
        clientSnapshot.put("age", 99);
        clientSnapshot.put("gender", "MALE");
        clientSnapshot.put("inpatientNoMasked", "HACK****9999");
        // 客户端伪造分数：契约无此字段，服务端忽略并按条目重算。
        body.put("scores", Map.of("rcriScore", 0, "riskLevel", "LOW", "calculatorVersion", "fake"));

        MvcResult created = mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientSnapshot.patientName").value("演示患者甲"))
                .andExpect(jsonPath("$.patientSnapshot.age").value(54))
                .andExpect(jsonPath("$.patientSnapshot.gender").value("FEMALE"))
                .andExpect(jsonPath("$.patientSnapshot.inpatientNoMasked").value("SYNT****9201"))
                .andExpect(jsonPath("$.scores.rcriScore").value(1))
                .andExpect(jsonPath("$.scores.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.scores.calculatorVersion").value("1.0.0"))
                .andReturn();
        String assessmentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("assessmentId").asText();

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT patient_snapshot->>'patientName' AS patient_name,
                       patient_snapshot->>'age' AS age,
                       scores->>'rcriScore' AS rcri_score,
                       scores->>'calculatorVersion' AS calculator_version
                FROM m2_anes.preop_assessment WHERE id = ?::uuid
                """, assessmentId);
        assertThat(stored.get("patient_name")).isEqualTo("演示患者甲");
        assertThat(stored.get("age")).isEqualTo("54");
        assertThat(stored.get("rcri_score")).isEqualTo("1");
        assertThat(stored.get("calculator_version")).isEqualTo("1.0.0");

        System.out.printf("[M2-M4-01][backfill] assessment=%s name=%s age=%s masked=%s "
                        + "fakeScoresIgnored=%s%n", assessmentId, stored.get("patient_name"),
                stored.get("age"), "SYNT****9201", stored.get("rcri_score"));
    }

    @Test
    void duplicateDasiActivitiesAreRejectedExplicitly() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M4-APP-0005", PATIENT_ANES, HIS_ANES);

        Map<String, Object> body = createBody(applicationId);
        asMap(body.get("scoreInput")).put("dasiActivities",
                List.of("SELF_CARE", "WALK_INDOORS", "SELF_CARE"));

        mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("scoreInput.dasiActivities"));

        System.out.println("[M2-M4-01][dasi] duplicateActivities=400 field=scoreInput.dasiActivities");
    }

    // --------------------------------------------- 最终化数据库层不可变 --

    @Test
    void finalizedRowIsImmutableAtDatabaseLevel() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M4-APP-0006", PATIENT_ANES, HIS_ANES);
        String assessmentId = createDraft(admin, applicationId);
        mockMvc.perform(post("/v1/anesthesia/preop-assessments/" + assessmentId + "/finalize")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALIZED"));

        Throwable updateFailure = catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.preop_assessment SET rcri_score = 0 WHERE id = ?::uuid",
                assessmentId));
        assertSqlState55000(updateFailure, "FINALIZED");
        Throwable playerMutation = catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.preop_assessment SET patient_snapshot = "
                        + "'{\"patientName\":\"篡改\"}'::jsonb WHERE id = ?::uuid",
                assessmentId));
        assertSqlState55000(playerMutation, "FINALIZED");
        Throwable deleteFailure = catchThrowable(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.preop_assessment WHERE id = ?::uuid", assessmentId));
        assertSqlState55000(deleteFailure, "append-only");
        Throwable truncateFailure = catchThrowable(() -> jdbcTemplate.execute(
                "TRUNCATE m2_anes.preop_assessment"));
        assertSqlState55000(truncateFailure, "append-only");

        // 触发器只约束 FINALIZED 行：草稿仍可 UPDATE（保存草稿/最终化路径）。
        String secondApplication = createApplication(admin, "SYNTH-M4-APP-0007", PATIENT_OR, HIS_OR);
        Map<String, Object> secondBody = createBody(secondApplication);
        // 另一患者的申请：客户端快照只用于一致性校验，内容仍由服务端回填。
        Map<String, Object> secondSnapshot = asMap(secondBody.get("patientSnapshot"));
        secondSnapshot.put("patientId", PATIENT_OR);
        secondSnapshot.put("hisPatientNo", HIS_OR);
        secondSnapshot.put("patientName", "演示患者乙");
        MvcResult secondDraft = mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(secondBody)))
                .andExpect(status().isCreated())
                .andReturn();
        String draftId = objectMapper.readTree(secondDraft.getResponse().getContentAsString())
                .get("assessmentId").asText();
        assertThat(jdbcTemplate.update(
                "UPDATE m2_anes.preop_assessment SET history_summary = ? WHERE id = ?::uuid",
                "演示病史（草稿修正；合成）", draftId)).isEqualTo(1);

        System.out.println("[M2-M4-01][immutable] finalized UPDATE/DELETE/TRUNCATE=55000 "
                + "patientSnapshotProtected=true draftUpdateAllowed=true");
    }

    // ------------------------------------------------------- 权限与数据范围 --

    @Test
    void crossDepartmentIsNotFoundAndMissingPermissionIsForbidden() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M4-APP-0003", PATIENT_ANES, HIS_ANES);
        MvcResult created = mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createBody(applicationId))))
                .andExpect(status().isCreated())
                .andReturn();
        String assessmentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("assessmentId").asText();

        // 无 m2:preop:read（SYS_SURGEON）→ 403
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME, SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + surgeon)
                        .param("applicationId", applicationId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        // 有 m2 权限但 DEPT 范围属手术室，申请属麻醉科 → 集合查询 403、单对象 404
        String deptUser = login(PREOP_DEPT_USERNAME, PREOP_DEPT_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + deptUser)
                        .param("applicationId", applicationId))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/anesthesia/preop-assessments/" + assessmentId)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(put("/v1/anesthesia/preop-assessments/" + assessmentId)
                        .header("Authorization", "Bearer " + deptUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(updateBody(1))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/anesthesia/preop-assessments/" + assessmentId + "/finalize")
                        .header("Authorization", "Bearer " + deptUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 1))))
                .andExpect(status().isNotFound());

        System.out.println("[M2-M4-01][scope] noPermission=403 listOutOfScope=403 "
                + "getOutOfScope=404 updateOutOfScope=404 finalizeOutOfScope=404");
    }

    // ------------------------------------------------------------- 迁移结构 --

    @Test
    void migrationCreatesTableConstraintsIndexesPermissionsAndComments() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'm2_anes' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """, String.class);
        assertThat(tables).contains("preop_assessment", "anesthesia_vital");

        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'm2_anes' AND table_name = 'preop_assessment'
                """, String.class);
        assertThat(columns).contains(
                "id", "application_id", "patient_id", "patient_snapshot", "history_summary",
                "asa_class", "mallampati", "airway_details", "score_input", "rcri_score",
                "apfel_score", "stopbang_score", "dasi_score", "risk_level", "calculator_version",
                "scores", "plan_type", "airway_plan", "plan_notes", "status", "finalize_note",
                "finalized_at", "finalized_by", "created_at", "created_by", "updated_at",
                "updated_by", "version", "deleted");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'm2_anes' AND indexname = 'uk_preop_assessment_application_draft'
                """, String.class))
                .contains("UNIQUE", "application_id", "WHERE", "status");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'fk_preop_assessment_application'
                """, String.class))
                .contains("FOREIGN KEY", "application_id", "surgery_application");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_preop_assessment_finalized'
                """, String.class))
                .contains("FINALIZED", "finalized_at", "finalized_by");

        assertThat(jdbcTemplate.queryForList("""
                SELECT tgname FROM pg_trigger
                WHERE tgrelid = 'm2_anes.preop_assessment'::regclass AND NOT tgisinternal
                ORDER BY tgname
                """, String.class)).containsExactly(
                "trg_preop_assessment_no_delete", "trg_preop_assessment_no_truncate",
                "trg_preop_assessment_no_update_finalized");

        assertThat(jdbcTemplate.queryForList("""
                SELECT code FROM m0_platform.sys_permission WHERE code LIKE 'm2:preop:%' ORDER BY code
                """, String.class)).containsExactly("m2:preop:read", "m2:preop:write");
        Integer grants = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                WHERE p.code LIKE 'm2:preop:%'
                  AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001',
                                     'a0000000-0000-4000-8000-000000000002',
                                     'a0000000-0000-4000-8000-000000000003')
                """, Integer.class);
        assertThat(grants).as("m2:preop:* 授予 3 个角色（admin/麻醉医师/麻醉护士）").isEqualTo(6);

        Integer tablesWithoutComment = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = 'm2_anes' AND c.relkind = 'r'
                  AND (obj_description(c.oid, 'pg_class') IS NULL
                       OR btrim(obj_description(c.oid, 'pg_class')) = '')
                """, Integer.class);
        assertThat(tablesWithoutComment).isZero();
        Integer columnsWithoutComment = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_attribute a
                JOIN pg_class c ON a.attrelid = c.oid
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = 'm2_anes' AND c.relkind = 'r'
                  AND a.attnum > 0 AND NOT a.attisdropped
                  AND (col_description(c.oid, a.attnum) IS NULL
                       OR btrim(col_description(c.oid, a.attnum)) = '')
                """, Integer.class);
        assertThat(columnsWithoutComment).isZero();

        System.out.printf("[M2-M4-01][v10] table=preop_assessment columns=29 draftUnique=true fk=true "
                + "permissions=2 grants=%d comments=true triggers=3%n", grants);
    }

    // ------------------------------------------------------------- helpers --

    private String createDraft(String token, String applicationId) throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/anesthesia/preop-assessments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(createBody(applicationId))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .get("assessmentId").asText();
    }

    private static void assertSqlState55000(Throwable failure, String messageFragment) {
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo("55000");
        assertThat(root.getMessage()).contains(messageFragment);
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

    private String createApplication(String token, String hisApplicationNo, String patientId,
            String hisPatientNo) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("hisApplicationNo", hisApplicationNo);
        request.put("patientId", patientId);
        request.put("surgeryName", "演示腹腔镜胆囊切除术（合成）");
        request.put("urgency", "ELECTIVE");
        request.put("plannedDurationMin", 120);
        request.put("surgeonName", "演示术者（合成）");
        MvcResult result = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("patient").get("hisPatientNo").asText()).isEqualTo(hisPatientNo);
        return body.get("applicationId").asText();
    }

    private void insertPatient(String id, String hisPatientNo, String name, String deptId,
            String deptName) {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, ?, 'FEMALE', DATE '1972-05-18', 54, 'SYNT****9201', ?,
                        ?, ?::uuid, 'f1000000-0000-4000-8000-000000000001'::uuid, '演示病区', '01',
                        now(), now(), 1, false)
                """, id, hisPatientNo, name, "hash-" + hisPatientNo, deptName, deptId);
    }

    private void ensurePreopDeptUser() {
        String hash = new BCryptPasswordEncoder().encode(PREOP_DEPT_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_PREOP_DEPT', '合成术前访视科室角色',
                        'SYNTHETIC：m2 术前访视 DEPT 数据范围用例', 'DEPT')
                ON CONFLICT (id) DO NOTHING
                """, PREOP_DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试术前访视科室用户（合成）', 1, false, 0, 1, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, PREOP_DEPT_USER_ID, PREOP_DEPT_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, PREOP_DEPT_USER_ID, PREOP_DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_org (user_id, org_id, is_primary)
                VALUES (?::uuid, ?::uuid, true) ON CONFLICT DO NOTHING
                """, PREOP_DEPT_USER_ID, SyntheticAccounts.DEMO_OR_ORG_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                SELECT ?::uuid, id FROM m0_platform.sys_permission
                WHERE code IN ('m2:preop:read', 'm2:preop:write')
                ON CONFLICT DO NOTHING
                """, PREOP_DEPT_ROLE_ID);
    }

    private Map<String, Object> createBody(String applicationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId);
        body.put("patientSnapshot", patientSnapshot());
        body.put("historySummary", "演示病史（高血压，规律服药；合成数据）");
        body.put("asaClass", "II");
        body.put("airwayAssessment", airway("II", "4.5", "NORMAL"));
        body.put("scoreInput", scoreInput());
        body.put("plan", plan("GENERAL"));
        return body;
    }

    private Map<String, Object> updateBody(int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("historySummary", "演示病史（高血压、糖尿病，规律服药；合成数据）");
        body.put("asaClass", "III");
        body.put("airwayAssessment", airway("III", "3.8", "LIMITED"));
        body.put("scoreInput", severeScoreInput());
        body.put("plan", plan("COMBINED"));
        return body;
    }

    private Map<String, Object> patientSnapshot() {
        Map<String, Object> patient = new LinkedHashMap<>();
        patient.put("patientId", PATIENT_ANES);
        patient.put("hisPatientNo", HIS_ANES);
        patient.put("patientName", "演示患者甲");
        patient.put("gender", "FEMALE");
        patient.put("birthDate", "1972-05-18");
        patient.put("age", 54);
        patient.put("departmentName", "麻醉科（合成）");
        patient.put("ward", "演示病区");
        patient.put("bedNo", "01");
        return patient;
    }

    private Map<String, Object> airway(String mallampati, String mouthOpening, String cervical) {
        Map<String, Object> airway = new LinkedHashMap<>();
        airway.put("mallampati", mallampati);
        airway.put("mouthOpeningCm", Double.parseDouble(mouthOpening));
        airway.put("cervicalSpineMobility", cervical);
        airway.put("notes", "演示气道评估（合成数据）");
        return airway;
    }

    private Map<String, Object> scoreInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("rcri", rcri(true, false, false, false, false));
        input.put("apfel", apfel(true, true, true, true));
        input.put("stopBang", stopBang(true, true, false, true, false, true, false, false));
        input.put("dasiActivities", List.of("SELF_CARE", "WALK_INDOORS", "WALK_BLOCK",
                "CLIMB_STAIRS", "LIGHT_HOUSEHOLD_WORK", "MODERATE_HOUSEHOLD_WORK"));
        return input;
    }

    private Map<String, Object> severeScoreInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        Map<String, Object> rcri = rcri(true, true, false, false, false);
        rcri.put("insulinDependentDiabetes", true);
        input.put("rcri", rcri);
        input.put("apfel", apfel(true, true, false, true));
        input.put("stopBang", stopBang(true, true, true, true, false, true, false, false));
        input.put("dasiActivities", List.of("SELF_CARE", "WALK_INDOORS", "LIGHT_HOUSEHOLD_WORK"));
        return input;
    }

    private Map<String, Object> rcri(boolean highRisk, boolean ischemic, boolean heartFailure,
            boolean cerebrovascular, boolean renal) {
        Map<String, Object> rcri = new LinkedHashMap<>();
        rcri.put("highRiskSurgery", highRisk);
        rcri.put("ischemicHeartDisease", ischemic);
        rcri.put("congestiveHeartFailure", heartFailure);
        rcri.put("cerebrovascularDisease", cerebrovascular);
        rcri.put("insulinDependentDiabetes", false);
        rcri.put("renalImpairment", renal);
        return rcri;
    }

    private Map<String, Object> apfel(boolean female, boolean ponv, boolean nonSmoker,
            boolean opioid) {
        Map<String, Object> apfel = new LinkedHashMap<>();
        apfel.put("female", female);
        apfel.put("ponvOrMotionSicknessHistory", ponv);
        apfel.put("nonSmoker", nonSmoker);
        apfel.put("postoperativeOpioid", opioid);
        return apfel;
    }

    private Map<String, Object> stopBang(boolean snoring, boolean tiredness, boolean apnea,
            boolean hypertension, boolean bmi, boolean age, boolean neck, boolean male) {
        Map<String, Object> stopBang = new LinkedHashMap<>();
        stopBang.put("snoring", snoring);
        stopBang.put("tiredness", tiredness);
        stopBang.put("observedApnea", apnea);
        stopBang.put("hypertension", hypertension);
        stopBang.put("bmiOver35", bmi);
        stopBang.put("ageOver50", age);
        stopBang.put("neckCircumferenceOver40", neck);
        stopBang.put("male", male);
        return stopBang;
    }

    private Map<String, Object> plan(String type) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("planType", type);
        plan.put("airwayPlan", "气管插管（合成示例）");
        plan.put("notes", "演示麻醉计划（合成数据）");
        return plan;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
