package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * PACU 复苏记录、Aldrete 评估与转出（M3-M10-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：入室时间窗/重复入室、Aldrete 服务端计算（边界与越界 400）、评估时间序列、
 * 转出阈值不足 409（details 逐项条件码）、达标后转出成功与 SBAR/去向/达标快照、转出后再评估
 * 409、append-only 触发器 55000、总分=五项之和 CHECK、权限 403 与跨科室 404/422/403、
 * 审计（无 PHI）与 V18 结构/权限/注释。全部数据为 SYNTHETIC。
 */
class PacuRecoveryApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String PATIENT_ANES = "54000000-0000-4000-8000-000000000012";
    private static final String HIS_ANES = "SYNTH-HIS-P-9402";
    private static final String OR_01 = "f2000000-0000-4000-8000-0000000000c1";

    private static final String PACU_DEPT_USER_ID = "e0000000-0000-4000-8000-0000000000e4";
    private static final String PACU_DEPT_USERNAME = "test.pacu.dept";
    private static final String PACU_DEPT_PASSWORD = "Synth@2026";
    private static final String PACU_DEPT_ROLE_ID = "a0000000-0000-4000-8000-0000000000f6";
    private static final String PACU_DEPT_ORG_ID = "f1000000-0000-4000-8000-0000000000eb";

    private static final String SBAR_SITUATION = "演示：全麻腹腔镜胆囊切除术后，PACU 观察 65 分钟（合成）";
    private static final String SBAR_BACKGROUND = "演示：ASA II，术中生命体征平稳（合成）";
    private static final String SBAR_ASSESSMENT = "演示：Aldrete 10 分，无恶心呕吐，疼痛 NRS 2（合成）";
    private static final String SBAR_RECOMMENDATION = "演示：返病房继续监测，按需镇痛（合成）";

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
        ensurePacuDeptOrg();
        ensurePacuDeptUser();
        clearClinicalRows();
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
            jdbcTemplate.execute(
                    "ALTER TABLE m1_sched.application_status_history ENABLE TRIGGER USER");
        }
        insertPatient();
    }

    @AfterEach
    void cleanUpAfterTest() {
        clearClinicalRows();
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_org WHERE user_id = ?::uuid",
                PACU_DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_role WHERE user_id = ?::uuid",
                PACU_DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user WHERE id = ?::uuid",
                PACU_DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_role_permission WHERE role_id = ?::uuid",
                PACU_DEPT_ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_role WHERE id = ?::uuid",
                PACU_DEPT_ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_org WHERE id = ?::uuid",
                PACU_DEPT_ORG_ID);
    }

    // -------------------------------------------- 入室 → 评估 → 转出主流程 --

    @Test
    void pacuAdmissionAssessmentDischargeFlowAndImmutability() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-PACU-0001");
        String recordId = createRecord(admin, created.scheduleId());
        OffsetDateTime base = startRecordInPast(recordId);

        // 入室：患者标识由记录单解析、状态 ADMITTED、阈值来自配置。
        String pacuId = admit(admin, recordId, base.plusSeconds(30))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.patientId").value(PATIENT_ANES))
                .andExpect(jsonPath("$.aldreteThreshold").value(9))
                .andExpect(jsonPath("$.latestAldreteTotal").isEmpty())
                .andReturnPacuId(objectMapper);

        mockMvc.perform(get("/v1/anesthesia/pacu-records").param("recordId", recordId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].pacuId").value(pacuId));
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vitalSigns.heartRate").value(82));

        // Aldrete 评估：服务端计算 9 分 → READY。
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                assessmentBody(base.plusSeconds(60), 2, 2, 2, 2, 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aldreteTotal").value(9))
                .andExpect(jsonPath("$.activity").value(2));
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.latestAldreteTotal").value(9));

        // 时间序列：assessedAt 升序，总分 9 → 10。
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                assessmentBody(base.plusSeconds(90), 2, 2, 2, 2, 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aldreteTotal").value(10));
        // assessedAt 单调：不得早于上一条评估（latest 按 assessed_at/created_at/id 判定）→ 400。
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                assessmentBody(base.plusSeconds(80), 1, 2, 2, 2, 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("assessedAt"));
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].aldreteTotal").value(9))
                .andExpect(jsonPath("$.items[1].aldreteTotal").value(10));

        // 未确认转出条件 400（确认字段不能替代服务端校验）。
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/discharge")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                dischargeBody("WARD", false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("criteriaConfirmed"));

        // 转出：达标快照冻结（阈值 9、总分 10、依据的评估标识）。
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/discharge")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(dischargeBody("WARD", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.destination").value("WARD"))
                .andExpect(jsonPath("$.criteria.aldreteThreshold").value(9))
                .andExpect(jsonPath("$.criteria.aldreteTotal").value(10))
                .andExpect(jsonPath("$.criteria.meetsThreshold").value(true))
                .andExpect(jsonPath("$.criteria.itemsComplete").value(true))
                .andExpect(jsonPath("$.handover.situation").value(SBAR_SITUATION))
                .andExpect(jsonPath("$.dischargedAt").isNotEmpty());
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId + "/discharge")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destination").value("WARD"))
                .andExpect(jsonPath("$.handover.recommendation").value(SBAR_RECOMMENDATION));
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.status").value("DISCHARGED"))
                .andExpect(jsonPath("$.dischargedAt").isNotEmpty());

        // 转出后不可再评估、不可重复转出（服务层 409）。
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                assessmentBody(base.plusSeconds(120), 2, 2, 2, 2, 2))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/discharge")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(dischargeBody("WARD", true))))
                .andExpect(status().isConflict());

        // append-only：评估/转出/复苏记录的 UPDATE/DELETE/TRUNCATE 与状态回退均被 55000 拒绝。
        assertSqlState55000(() -> jdbcTemplate.update(
                "UPDATE m2_anes.pacu_assessment SET notes = 'x' WHERE pacu_id = ?::uuid", pacuId),
                "append-only");
        assertSqlState55000(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.pacu_assessment WHERE pacu_id = ?::uuid", pacuId),
                "append-only");
        assertSqlState55000(() -> jdbcTemplate.execute("TRUNCATE m2_anes.pacu_assessment"),
                "append-only");
        assertSqlState55000(() -> jdbcTemplate.update(
                "UPDATE m2_anes.pacu_discharge SET destination = 'ICU' WHERE pacu_id = ?::uuid",
                pacuId), "append-only");
        assertSqlState55000(() -> jdbcTemplate.update(
                "UPDATE m2_anes.pacu_record SET status = 'MONITORING' WHERE id = ?::uuid", pacuId),
                "状态不可回退");
        assertSqlState55000(() -> jdbcTemplate.update(
                "UPDATE m2_anes.pacu_record SET consciousness = 'DROWSY' WHERE id = ?::uuid",
                pacuId), "不可修改");
        assertSqlState55000(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.pacu_record WHERE id = ?::uuid", pacuId), "DELETE");
        // 转出后数据库层也拒绝追加评估（服务层 409 的第二道防线）。
        assertSqlState55000(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.pacu_assessment
                    (pacu_id, assessed_at, aldrete_activity, aldrete_respiration,
                     aldrete_circulation, aldrete_consciousness, aldrete_oxygenation,
                     aldrete_total)
                VALUES (?::uuid, now(), 2, 2, 2, 2, 2, 10)
                """, pacuId), "已转出");

        // 审计：三个动作 result=1，detail 不含患者姓名/SBAR 原文/评估备注原文。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'PACU_RECORD' AND resource_id = ?
                   AND action IN ('PACU_ADMIT', 'PACU_ASSESS', 'PACU_DISCHARGE') AND result = 1
                """, Integer.class, pacuId)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'PACU_RECORD' AND resource_id = ?
                   AND (detail::text LIKE '%演示患者%' OR detail::text LIKE '%返病房%'
                        OR detail::text LIKE '%胸外按压%')
                """, Integer.class, pacuId)).isZero();

        System.out.printf("[M3-M10-01][pacu] record=%s pacu=%s aldrete=9->10 threshold=9 "
                + "discharge=WARD appendOnly=55000 audit=4%n", recordId, pacuId);
    }

    // ----------------------------------------- 阈值不足与未评估转出 409 --

    @Test
    void dischargeBelowThresholdAndWithoutAssessmentReturn409Details() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-PACU-0002");
        String recordId = createRecord(admin, created.scheduleId());
        OffsetDateTime base = startRecordInPast(recordId);

        // 未评估直接转出 → 409 ALDRETE_ASSESSED，失败审计 result=0。
        String pacuId = admit(admin, recordId, base.plusSeconds(30))
                .andExpect(status().isCreated())
                .andReturnPacuId(objectMapper);
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/discharge")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(dischargeBody("WARD", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.details[0].field").value("ALDRETE_ASSESSED"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'PACU_RECORD' AND resource_id = ?
                   AND action = 'PACU_DISCHARGE' AND result = 0
                """, Integer.class, pacuId)).isEqualTo(1);

        // 8 分（阈值 9）→ 409 ALDRETE_THRESHOLD_MET；状态保持 MONITORING，未写转出。
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                assessmentBody(base.plusSeconds(60), 1, 2, 2, 2, 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.aldreteTotal").value(8));
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/discharge")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(dischargeBody("ICU", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[*].field").value(
                        org.hamcrest.Matchers.contains("ALDRETE_THRESHOLD_MET")));
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.status").value("MONITORING"))
                .andExpect(jsonPath("$.dischargedAt").isEmpty());
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId + "/discharge")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.pacu_discharge WHERE pacu_id = ?::uuid
                """, Integer.class, pacuId)).isZero();
    }

    // -------------------------------------------- 入室/评估字段校验 400 --

    @Test
    void admissionAndAssessmentValidationRejectsInvalidInput() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-PACU-0003");
        String recordId = createRecord(admin, created.scheduleId());
        OffsetDateTime base = startRecordInPast(recordId);

        // 入室早于麻醉开始 / 晚于当前时间+容差 → 400。
        admit(admin, recordId, base.minusMinutes(15)).andExpect(status().isBadRequest());
        admit(admin, recordId, OffsetDateTime.now(ZoneOffset.ofHours(8)).plusHours(2))
                .andExpect(status().isBadRequest());
        // 入室必填与物理范围 → 400。
        Map<String, Object> noVitals = admissionBody(recordId, base.plusSeconds(30));
        noVitals.remove("vitalSigns");
        mockMvc.perform(post("/v1/anesthesia/pacu-records")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(noVitals)))
                .andExpect(status().isBadRequest());
        Map<String, Object> badSpo2 = admissionBody(recordId, base.plusSeconds(30));
        @SuppressWarnings("unchecked")
        Map<String, Object> vitals = (Map<String, Object>) badSpo2.get("vitalSigns");
        vitals.put("spo2", 120);
        mockMvc.perform(post("/v1/anesthesia/pacu-records")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(badSpo2)))
                .andExpect(status().isBadRequest());
        // 记录单不可见/不存在（请求体参数）→ 422。
        mockMvc.perform(post("/v1/anesthesia/pacu-records")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(admissionBody(
                                "12000000-0000-4000-8000-0000000000ff",
                                base.plusSeconds(30)))))
                .andExpect(status().isUnprocessableEntity());

        // 重复入室 409（部分唯一索引 + 服务层先查）。
        String pacuId = admit(admin, recordId, base.plusSeconds(30))
                .andExpect(status().isCreated())
                .andReturnPacuId(objectMapper);
        admit(admin, recordId, base.plusSeconds(40))
                .andExpect(status().isConflict());

        // Aldrete 越界/缺项/时间早于入室 → 400；非法 pacuId → 404。
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                assessmentBody(base.plusSeconds(60), 3, 2, 2, 2, 2))))
                .andExpect(status().isBadRequest());
        Map<String, Object> missingItem = assessmentBody(base.plusSeconds(60), 2, 2, 2, 2, 2);
        missingItem.remove("respiration");
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(missingItem)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                assessmentBody(base.minusSeconds(60), 2, 2, 2, 2, 2))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/anesthesia/pacu-records/not-a-uuid")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    // --------------------------------------- 权限、数据范围与 V18 结构 --

    @Test
    void permissionsScopeAndV18SchemaAreEnforced() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-PACU-0004");
        String recordId = createRecord(admin, created.scheduleId());
        OffsetDateTime base = startRecordInPast(recordId);
        String pacuId = admit(admin, recordId, base.plusSeconds(30))
                .andExpect(status().isCreated())
                .andReturnPacuId(objectMapper);

        // 无 PACU 权限（术者）→ 403。
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId)
                        .header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                assessmentBody(base.plusSeconds(60), 2, 2, 2, 2, 2))))
                .andExpect(status().isForbidden());

        // 跨科室（DEPT 数据范围，且有记录读写但无 pacu 权限）：单对象 404、集合 403、入室 422。
        String deptUser = login(PACU_DEPT_USERNAME, PACU_DEPT_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/pacu-records/" + pacuId + "/assessments")
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/pacu-records").param("recordId", recordId)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/anesthesia/pacu-records")
                        .header("Authorization", "Bearer " + deptUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                admissionBody(recordId, base.plusSeconds(40)))))
                .andExpect(status().isUnprocessableEntity());

        // 麻醉护士默认获得 PACU 读写（V18）；V18 结构/权限/注释断言。
        Integer nurseGrants = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                 JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE rp.role_id = 'a0000000-0000-4000-8000-000000000003'
                   AND p.code IN ('m2:pacu:read', 'm2:pacu:write')
                """, Integer.class);
        assertThat(nurseGrants).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT code FROM m0_platform.sys_permission
                 WHERE code LIKE 'm2:pacu:%' ORDER BY code
                """, String.class)).containsExactly("m2:pacu:read", "m2:pacu:write");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                 JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE p.code LIKE 'm2:pacu:%'
                   AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001',
                                      'a0000000-0000-4000-8000-000000000002',
                                      'a0000000-0000-4000-8000-000000000003')
                """, Integer.class)).isEqualTo(6);

        List<String> recordColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'm2_anes' AND table_name = 'pacu_record'
                """, String.class);
        assertThat(recordColumns).contains("anesthesia_record_id", "patient_id", "admitted_at",
                "discharged_at", "status", "admitted_vital_signs", "consciousness", "pain_score",
                "nausea", "airway", "version", "deleted");
        List<String> assessmentColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'm2_anes' AND table_name = 'pacu_assessment'
                """, String.class);
        assertThat(assessmentColumns).contains("aldrete_activity", "aldrete_respiration",
                "aldrete_circulation", "aldrete_consciousness", "aldrete_oxygenation",
                "aldrete_total", "notes", "created_by");
        assertThat(triggerNames("pacu_assessment")).containsExactly(
                "trg_pacu_assessment_discharged_insert", "trg_pacu_assessment_no_delete",
                "trg_pacu_assessment_no_truncate", "trg_pacu_assessment_no_update");
        assertThat(triggerNames("pacu_discharge")).containsExactly(
                "trg_pacu_discharge_no_delete", "trg_pacu_discharge_no_truncate",
                "trg_pacu_discharge_no_update");
        assertThat(triggerNames("pacu_record")).containsExactly(
                "trg_pacu_record_guard_update", "trg_pacu_record_no_delete",
                "trg_pacu_record_no_truncate");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_pacu_assessment_total_sum'
                """, String.class)).contains("aldrete_total", "aldrete_activity");
        Integer tablesWithoutComment = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid
                 WHERE n.nspname = 'm2_anes' AND c.relkind = 'r'
                   AND c.relname IN ('pacu_record', 'pacu_assessment', 'pacu_discharge')
                   AND (obj_description(c.oid, 'pg_class') IS NULL
                        OR btrim(obj_description(c.oid, 'pg_class')) = '')
                """, Integer.class);
        assertThat(tablesWithoutComment).isZero();
        Integer columnsWithoutComment = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_attribute a
                 JOIN pg_class c ON a.attrelid = c.oid
                 JOIN pg_namespace n ON c.relnamespace = n.oid
                 WHERE n.nspname = 'm2_anes' AND c.relkind = 'r'
                   AND c.relname IN ('pacu_record', 'pacu_assessment', 'pacu_discharge')
                   AND a.attnum > 0 AND NOT a.attisdropped
                   AND (col_description(c.oid, a.attnum) IS NULL
                        OR btrim(col_description(c.oid, a.attnum)) = '')
                """, Integer.class);
        assertThat(columnsWithoutComment).isZero();
    }

    // ------------------------------------------------ 数据库第二道防线 --

    @Test
    void databaseGuardsRejectInvalidScoresAndIdentityChanges() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-PACU-0005");
        String recordId = createRecord(admin, created.scheduleId());
        OffsetDateTime base = startRecordInPast(recordId);
        String pacuId = admit(admin, recordId, base.plusSeconds(30))
                .andExpect(status().isCreated())
                .andReturnPacuId(objectMapper);

        // 总分≠五项之和、单项越界由 CHECK 拒绝（23514）。
        assertSqlState(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.pacu_assessment
                    (pacu_id, assessed_at, aldrete_activity, aldrete_respiration,
                     aldrete_circulation, aldrete_consciousness, aldrete_oxygenation,
                     aldrete_total)
                VALUES (?::uuid, now(), 2, 2, 2, 2, 2, 9)
                """, pacuId), "23514");
        assertSqlState(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.pacu_assessment
                    (pacu_id, assessed_at, aldrete_activity, aldrete_respiration,
                     aldrete_circulation, aldrete_consciousness, aldrete_oxygenation,
                     aldrete_total)
                VALUES (?::uuid, now(), 3, 2, 2, 2, 1, 10)
                """, pacuId), "23514");
        // 转出时间与 DISCHARGED 必须同置（服务层 + 守卫触发器 55000 第二道防线）。
        assertSqlState55000(() -> jdbcTemplate.update("""
                UPDATE m2_anes.pacu_record SET discharged_at = now() WHERE id = ?::uuid
                """, pacuId), "DISCHARGED");
        // 一条记录单同时最多一条未转出记录（部分唯一索引 23505）。
        assertSqlState(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.pacu_record
                    (anesthesia_record_id, patient_id, admitted_at, status,
                     admitted_vital_signs, consciousness, pain_score, nausea, airway)
                VALUES (?::uuid, ?::uuid, now(), 'ADMITTED', '{}'::jsonb, 'ALERT', 3, false,
                        'NATURAL')
                """, recordId, PATIENT_ANES), "23505");
    }

    // ------------------------------------------------------------- helpers --

    private record Created(String applicationId, String scheduleId) {
    }

    /** 便捷包装：POST 入室并返回 pacuId（调用方仍需断言状态码）。 */
    private AdmitResult admit(String token, String recordId, OffsetDateTime admittedAt)
            throws Exception {
        org.springframework.test.web.servlet.ResultActions actions =
                mockMvc.perform(post("/v1/anesthesia/pacu-records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                admissionBody(recordId, admittedAt))));
        return new AdmitResult(actions);
    }

    /** 包装 ResultActions，暴露 andExpect 链与 andReturnPacuId。 */
    private final class AdmitResult {
        private final org.springframework.test.web.servlet.ResultActions actions;

        private AdmitResult(org.springframework.test.web.servlet.ResultActions actions) {
            this.actions = actions;
        }

        private AdmitResult andExpect(
                org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        private String andReturnPacuId(ObjectMapper mapper) throws Exception {
            MvcResult result = actions.andReturn();
            JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
            return body.get("pacuId").asText();
        }
    }

    /**
     * 夹具：把记录单置为“已开始”且开始时间落在过去（SYNTHETIC 测试数据）。
     *
     * <p>记录单由 API 创建（created_at≈now），而入室/Aldrete/转出用例需要过去的时间轴；
     * 这里临时禁用触发器把 created_at/started_at 回填到过去（仅测试夹具，生产不可这么写）。
     * 返回 “now − 20 分钟” 作为入室基准时间。
     */
    private OffsetDateTime startRecordInPast(String recordId) {
        jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("""
                    UPDATE m2_anes.anesthesia_record
                       SET created_at = now() - interval '40 minutes',
                           started_at = now() - interval '30 minutes',
                           ended_at = NULL,
                           status = 'ONGOING'
                     WHERE id = ?::uuid
                    """, recordId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record ENABLE TRIGGER USER");
        }
        return OffsetDateTime.now(ZoneOffset.ofHours(8)).truncatedTo(ChronoUnit.MILLIS)
                .minusMinutes(20);
    }

    private Map<String, Object> admissionBody(String recordId, OffsetDateTime admittedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recordId", recordId);
        body.put("admittedAt", admittedAt.toString());
        Map<String, Object> vitals = new LinkedHashMap<>();
        vitals.put("systolicBp", 118);
        vitals.put("diastolicBp", 72);
        vitals.put("heartRate", 82);
        vitals.put("spo2", 97);
        vitals.put("respiratoryRate", 14);
        vitals.put("temperature", 36.5);
        body.put("vitalSigns", vitals);
        body.put("consciousness", "ALERT");
        body.put("painScore", 3);
        body.put("nausea", false);
        body.put("airway", "NATURAL");
        return body;
    }

    private Map<String, Object> assessmentBody(OffsetDateTime assessedAt, int activity,
            int respiration, int circulation, int consciousness, int oxygenation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assessedAt", assessedAt.toString());
        body.put("activity", activity);
        body.put("respiration", respiration);
        body.put("circulation", circulation);
        body.put("consciousness", consciousness);
        body.put("oxygenation", oxygenation);
        return body;
    }

    private Map<String, Object> dischargeBody(String destination, boolean criteriaConfirmed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destination", destination);
        body.put("criteriaConfirmed", criteriaConfirmed);
        Map<String, Object> handover = new LinkedHashMap<>();
        handover.put("situation", SBAR_SITUATION);
        handover.put("background", SBAR_BACKGROUND);
        handover.put("assessment", SBAR_ASSESSMENT);
        handover.put("recommendation", SBAR_RECOMMENDATION);
        body.put("handover", handover);
        return body;
    }

    private String createRecord(String token, String scheduleId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId, "anesthesiaType", "GENERAL"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("recordId").asText();
    }

    private Created createSchedule(String token, String hisApplicationNo) throws Exception {
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("hisApplicationNo", hisApplicationNo);
        application.put("patientId", PATIENT_ANES);
        application.put("surgeryName", "演示腹腔镜胆囊切除术（合成）");
        application.put("urgency", "ELECTIVE");
        application.put("plannedDurationMin", 120);
        application.put("surgeonName", "演示术者（合成）");
        MvcResult created = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(application)))
                .andExpect(status().isCreated())
                .andReturn();
        String applicationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("applicationId").asText();

        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("applicationId", applicationId);
        schedule.put("roomId", OR_01);
        schedule.put("plannedStart", "2026-12-03T08:00:00+08:00");
        schedule.put("plannedEnd", "2026-12-03T10:00:00+08:00");
        schedule.put("sequenceNo", 1);
        schedule.put("priority", "ELECTIVE");
        MvcResult scheduled = mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(schedule)))
                .andExpect(status().isCreated())
                .andReturn();
        String scheduleId = objectMapper.readTree(scheduled.getResponse().getContentAsString())
                .get("scheduleId").asText();
        return new Created(applicationId, scheduleId);
    }

    private List<String> triggerNames(String table) {
        return jdbcTemplate.queryForList("""
                SELECT tgname FROM pg_trigger
                 WHERE tgrelid = ('m2_anes.' || ?)::regclass AND NOT tgisinternal
                 ORDER BY tgname
                """, String.class, table);
    }

    private void clearClinicalRows() {
        // append-only 临床表：测试夹具临时 DISABLE TRIGGER USER 清理后立即恢复。
        String[] tables = {"m2_anes.pacu_discharge", "m2_anes.pacu_assessment",
            "m2_anes.pacu_record", "m2_anes.record_revision", "m2_anes.fluid_record",
            "m2_anes.drug_administration", "m2_anes.record_event",
            "m2_anes.resuscitation_record", "m2_anes.anesthesia_record",
            "m2_anes.consent_record", "m2_anes.three_way_check"};
        for (String table : tables) {
            jdbcTemplate.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
        }
        try {
            jdbcTemplate.update("DELETE FROM m2_anes.pacu_discharge");
            jdbcTemplate.update("DELETE FROM m2_anes.pacu_assessment");
            jdbcTemplate.update("DELETE FROM m2_anes.pacu_record");
            jdbcTemplate.update("DELETE FROM m2_anes.record_revision");
            jdbcTemplate.update("DELETE FROM m2_anes.fluid_record");
            jdbcTemplate.update("DELETE FROM m2_anes.drug_administration");
            jdbcTemplate.update("DELETE FROM m2_anes.record_event");
            jdbcTemplate.update("DELETE FROM m2_anes.resuscitation_record");
            jdbcTemplate.update("DELETE FROM m2_anes.anesthesia_record");
            jdbcTemplate.update("DELETE FROM m2_anes.consent_record");
            jdbcTemplate.update("DELETE FROM m2_anes.three_way_check");
        } finally {
            for (String table : tables) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ENABLE TRIGGER USER");
            }
        }
    }

    private static void assertSqlState55000(Runnable action, String messageFragment) {
        assertSqlState(action, "55000", messageFragment);
    }

    private static void assertSqlState(Runnable action, String expectedState) {
        assertSqlState(action, expectedState, null);
    }

    private static void assertSqlState(Runnable action, String expectedState,
            String messageFragment) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo(expectedState);
        if (messageFragment != null) {
            assertThat(root.getMessage()).contains(messageFragment);
        }
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

    private void insertPatient() {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, '演示患者戊', 'MALE', DATE '1969-05-12', 57, 'SYNT****9402', ?,
                        '复苏合成科室', ?::uuid, 'f1000000-0000-4000-8000-000000000001'::uuid,
                        '演示病区', '05', now(), now(), 1, false)
                """, PATIENT_ANES, HIS_ANES, "hash-" + HIS_ANES, PACU_DEPT_ORG_ID);
    }

    private void ensurePacuDeptOrg() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_org (id, code, name, org_type, path, sort, status)
                VALUES (?::uuid, 'SYN_PACU_DEPT', '复苏合成科室', 'DEPT', ?, 98, 1)
                ON CONFLICT (id) DO NOTHING
                """, PACU_DEPT_ORG_ID, "/" + PACU_DEPT_ORG_ID);
    }

    private void ensurePacuDeptUser() {
        String hash = new BCryptPasswordEncoder().encode(PACU_DEPT_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_PACU_DEPT', '合成复苏科室角色',
                        'SYNTHETIC：M3-M10-01 PACU 数据范围用例', 'DEPT')
                ON CONFLICT (id) DO NOTHING
                """, PACU_DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试复苏科室用户（合成）', 1, false, 0, 1, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, PACU_DEPT_USER_ID, PACU_DEPT_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, PACU_DEPT_USER_ID, PACU_DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_org (user_id, org_id, is_primary)
                VALUES (?::uuid, ?::uuid, true) ON CONFLICT DO NOTHING
                """, PACU_DEPT_USER_ID, PACU_DEPT_ORG_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                SELECT ?::uuid, id FROM m0_platform.sys_permission
                 WHERE code IN ('m2:record:read', 'm2:record:write',
                                'm2:pacu:read', 'm2:pacu:write')
                ON CONFLICT DO NOTHING
                """, PACU_DEPT_ROLE_ID);
    }
}
