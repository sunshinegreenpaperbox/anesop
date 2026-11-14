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
import com.anesop.m2.impl.support.PhiMasker;
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
 * 术后随访与镇痛（M3-M11-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：随访录入/列表/详情、评分边界 0/10 与越界 400、随访时间窗与同类型时间单调 400、
 * 记录单未结束 409、镇痛参数校验（bolus/background/lockout）400、镇痛记录追加与列表、
 * 自由文本脱敏（手机号/身份证号形似串）、审计无 PHI、append-only 触发器 55000、
 * 权限 403 与跨科室 404/403/422、V19 结构/约束/触发器/权限/注释。全部数据为 SYNTHETIC。
 */
class FollowupPainApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String PATIENT_ANES = "54000000-0000-4000-8000-000000000012";
    private static final String HIS_ANES = "SYNTH-HIS-P-9501";
    private static final String OR_01 = "f2000000-0000-4000-8000-0000000000c1";
    /** 合成手机号（验证脱敏，非真实号码）。 */
    private static final String SYNTH_MOBILE = "13800138000";
    /** 合成手机号分隔写法（+86/短横线/空格，验证归一化后脱敏）。 */
    private static final String SYNTH_MOBILE_SEPARATED = "+86 138-0013-8000";
    /** 合成 18 位身份证号形似串（验证脱敏）。 */
    private static final String SYNTH_ID_CARD = "110101199003074567";
    /** 合成身份证号分隔写法（验证归一化后脱敏）。 */
    private static final String SYNTH_ID_CARD_SEPARATED = "110101 19900307 4567";

    private static final String DEPT_USER_ID = "e0000000-0000-4000-8000-0000000000e5";
    private static final String DEPT_USERNAME = "test.followup.dept";
    private static final String DEPT_PASSWORD = "Synth@2026";
    private static final String DEPT_ROLE_ID = "a0000000-0000-4000-8000-0000000000f7";
    private static final String DEPT_ORG_ID = "f1000000-0000-4000-8000-0000000000ec";

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
        ensureDeptOrg();
        ensureDeptUser();
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
                DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_role WHERE user_id = ?::uuid",
                DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user WHERE id = ?::uuid",
                DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_role_permission WHERE role_id = ?::uuid",
                DEPT_ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_role WHERE id = ?::uuid",
                DEPT_ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_org WHERE id = ?::uuid",
                DEPT_ORG_ID);
    }

    // ------------------------------------------- 随访 → 镇痛 → 审计/不可变 --

    @Test
    void followupPainFlowMasksTextAuditsAndIsAppendOnly() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-FU-0001");
        String recordId = createRecord(admin, created.scheduleId());
        OffsetDateTime base = endRecordInPast(recordId);

        // 随访 H24：0–10 / 0–3 合法；自由文本含合成手机号/身份证号，服务端脱敏后落库。
        Map<String, Object> body = followupBody(recordId, "H24", base.plusMinutes(20), 3, 1);
        body.put("complications", List.of("PONV", "PAIN_INADEQUATE"));
        body.put("complicationNotes", "演示：恶心，联系电话 " + SYNTH_MOBILE_SEPARATED + "（合成）");
        body.put("patientFeedback", "演示：身份证 " + SYNTH_ID_CARD_SEPARATED + "，疼痛可耐受（合成）");
        body.put("followedUpBy", "演示随访护士（合成）");
        MvcResult createdResult = mockMvc.perform(post("/v1/anesthesia/followups")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordId").value(recordId))
                .andExpect(jsonPath("$.patientId").value(PATIENT_ANES))
                .andExpect(jsonPath("$.followupType").value("H24"))
                .andExpect(jsonPath("$.nrsScore").value(3))
                .andExpect(jsonPath("$.ponvScore").value(1))
                .andExpect(jsonPath("$.complications", hasSize(2)))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        JsonNode followup = objectMapper.readTree(
                createdResult.getResponse().getContentAsString());
        String followupId = followup.get("followupId").asText();
        // 脱敏：手机号/身份证号中段遮蔽，响应不含原始串。
        assertThat(followup.get("complicationNotes").asText())
                .contains("138****8000").doesNotContain(SYNTH_MOBILE, SYNTH_MOBILE_SEPARATED);
        assertThat(followup.get("patientFeedback").asText())
                .contains("110101********4567")
                .doesNotContain(SYNTH_ID_CARD, SYNTH_ID_CARD_SEPARATED);

        // 列表/详情。
        mockMvc.perform(get("/v1/anesthesia/followups").param("recordId", recordId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].followupId").value(followupId));
        mockMvc.perform(get("/v1/anesthesia/followups/" + followupId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complicationNotes").value(
                        "演示：恶心，联系电话 138****8000（合成）"));

        // 镇痛记录：PCIA + bolus/background/lockout + 效果 + 调整记录（调整说明脱敏）。
        Map<String, Object> pain = painBody("PCIA", 2.0, 1.0, 15, "PARTIAL");
        pain.put("drug", "演示舒芬太尼方案 +86 138 0013 8000（合成）");
        pain.put("adjustments", List.of(Map.of(
                "adjustedAt", base.plusMinutes(40).toString(),
                "description", "演示：追加 bolus 1 次，联系 138 0013 8000（合成）")));
        mockMvc.perform(post("/v1/anesthesia/followups/" + followupId + "/pain-management")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(pain)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.followupId").value(followupId))
                .andExpect(jsonPath("$.recordId").value(recordId))
                .andExpect(jsonPath("$.modality").value("PCIA"))
                .andExpect(jsonPath("$.bolusDose").value(2.0))
                .andExpect(jsonPath("$.backgroundDose").value(1.0))
                .andExpect(jsonPath("$.lockoutMinutes").value(15))
                .andExpect(jsonPath("$.effect").value("PARTIAL"))
                .andExpect(jsonPath("$.drug").value("演示舒芬太尼方案 138****8000（合成）"));
        mockMvc.perform(get("/v1/anesthesia/followups/" + followupId + "/pain-management")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].adjustments[0].description").value(
                        "演示：追加 bolus 1 次，联系 138****8000（合成）"));

        // append-only：随访与镇痛记录的 UPDATE/DELETE/TRUNCATE 一律 55000。
        assertSqlState55000(() -> jdbcTemplate.update(
                "UPDATE m2_anes.followup_record SET nrs_score = 9 WHERE id = ?::uuid",
                followupId), "append-only");
        assertSqlState55000(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.followup_record WHERE id = ?::uuid", followupId),
                "append-only");
        // 父表被 pain_management 外键引用，TRUNCATE 由 PostgreSQL 直接拒绝（0A000）；
        // 即便 CASCADE，append-only 触发器仍会以 55000 拒绝物理清空。
        assertSqlState55000(() -> jdbcTemplate.update(
                "UPDATE m2_anes.pain_management SET lockout_minutes = 30 WHERE followup_id = "
                        + "?::uuid", followupId), "append-only");
        assertSqlState55000(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.pain_management WHERE followup_id = ?::uuid", followupId),
                "append-only");
        assertSqlState55000(() -> jdbcTemplate.execute("TRUNCATE m2_anes.pain_management"),
                "不可物理删除");
        // 父表被 FK 引用时 CASCADE 也必须被 append-only 触发器拒绝（评审 S-5）。
        assertSqlState55000(() -> jdbcTemplate.execute(
                "TRUNCATE m2_anes.followup_record CASCADE"), "不可物理删除");

        // 数据库存的是脱敏后的文本（原文不落库）。
        String storedNotes = jdbcTemplate.queryForObject(
                "SELECT complication_notes FROM m2_anes.followup_record WHERE id = ?::uuid",
                String.class, followupId);
        assertThat(storedNotes).isEqualTo("演示：恶心，联系电话 138****8000（合成）")
                .doesNotContain(SYNTH_MOBILE, SYNTH_MOBILE_SEPARATED);
        String storedFeedback = jdbcTemplate.queryForObject(
                "SELECT patient_feedback FROM m2_anes.followup_record WHERE id = ?::uuid",
                String.class, followupId);
        assertThat(storedFeedback).isEqualTo("演示：身份证 110101********4567，疼痛可耐受（合成）")
                .doesNotContain(SYNTH_ID_CARD, SYNTH_ID_CARD_SEPARATED);
        String storedDrug = jdbcTemplate.queryForObject(
                "SELECT drug FROM m2_anes.pain_management WHERE followup_id = ?::uuid",
                String.class, followupId);
        assertThat(storedDrug).isEqualTo("演示舒芬太尼方案 138****8000（合成）")
                .doesNotContain(SYNTH_MOBILE, SYNTH_MOBILE_SEPARATED);
        String storedAdjustments = jdbcTemplate.queryForObject(
                "SELECT adjustments::text FROM m2_anes.pain_management WHERE followup_id = ?::uuid",
                String.class, followupId);
        assertThat(storedAdjustments).contains("138****8000")
                .doesNotContain(SYNTH_MOBILE, "138 0013 8000");
        assertThat(PhiMasker.containsMaskableIdentifier(storedNotes)).isFalse();
        assertThat(PhiMasker.containsMaskableIdentifier(storedFeedback)).isFalse();
        assertThat(PhiMasker.containsMaskableIdentifier(storedDrug)).isFalse();
        assertThat(PhiMasker.containsMaskableIdentifier(storedAdjustments)).isFalse();

        // 审计：两个动作 result=1；detail 不含手机号/身份证号/自由文本原文。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'FOLLOWUP_RECORD' AND resource_id = ?
                   AND action = 'FOLLOWUP_CREATE' AND result = 1
                """, Integer.class, followupId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'PAIN_MANAGEMENT' AND action = 'PAIN_MANAGE'
                   AND result = 1 AND detail->>'followupId' = ?
                """, Integer.class, followupId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE action IN ('FOLLOWUP_CREATE', 'PAIN_MANAGE')
                   AND (detail::text LIKE ? OR detail::text LIKE ? OR detail::text LIKE ?
                        OR detail::text LIKE ? OR detail::text LIKE '%疼痛可耐受%'
                        OR detail::text LIKE '%追加 bolus%')
                """, Integer.class, "%" + SYNTH_MOBILE + "%", "%" + SYNTH_MOBILE_SEPARATED + "%",
                "%" + SYNTH_ID_CARD + "%", "%" + SYNTH_ID_CARD_SEPARATED + "%")).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE action = 'PAIN_MANAGE' AND detail->>'followupId' = ?
                   AND detail->>'drug' = '演示舒芬太尼方案 138****8000（合成）'
                """, Integer.class, followupId)).isEqualTo(1);

        System.out.printf("[M3-M11-01][followup] record=%s followup=%s h24 nrs=3 ponv=1 "
                + "pain=PCIA masked=true appendOnly=55000 audit=2%n", recordId, followupId);
    }

    // --------------------------------------- 边界、时间单调与记录未结束 --

    @Test
    void boundariesMonotonicWindowAndRecordNotEndedAreEnforced() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-FU-0002");
        String recordId = createRecord(admin, created.scheduleId());
        OffsetDateTime base = endRecordInPast(recordId);

        // NRS 0/10 与 PONV 0/3 边界合法（同类型时间递增）。
        createFollowup(admin, followupBody(recordId, "H24", base.plusMinutes(10), 0, 0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nrsScore").value(0))
                .andExpect(jsonPath("$.ponvScore").value(0));
        createFollowup(admin, followupBody(recordId, "H24", base.plusMinutes(20), 10, 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nrsScore").value(10))
                .andExpect(jsonPath("$.ponvScore").value(3));

        // 越界 400（NRS 11/-1、PONV 4）。
        createFollowup(admin, followupBody(recordId, "H48", base.plusMinutes(30), 11, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("nrsScore"));
        createFollowup(admin, followupBody(recordId, "H48", base.plusMinutes(30), 0, 4))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("ponvScore"));

        // 同记录同类型时间倒序 400（H24 最新为 +20 分钟，再提交 +15 分钟）。
        createFollowup(admin, followupBody(recordId, "H24", base.plusMinutes(15), 2, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("assessedAt"));
        // 不同类型（H48）可使用更早时间（各自序列独立）。
        createFollowup(admin, followupBody(recordId, "H48", base.plusMinutes(5), 2, 0))
                .andExpect(status().isCreated());

        // 随访时间早于麻醉结束时间 / 晚于当前时间+容差 → 400。
        createFollowup(admin, followupBody(recordId, "H48", base.minusMinutes(1), 2, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("assessedAt"));
        createFollowup(admin, followupBody(recordId, "H48",
                OffsetDateTime.now(ZoneOffset.ofHours(8)).plusHours(2), 2, 0))
                .andExpect(status().isBadRequest());

        // patient_id 必须与父麻醉记录单一致（评审 S-4）：数据库直插不一致 → 55000。
        assertSqlState55000(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.followup_record
                    (anesthesia_record_id, patient_id, followup_type, assessed_at,
                     nrs_score, ponv_score, complications, followed_up_by)
                VALUES (?::uuid, ?::uuid, 'H24', now(), 2, 1, '[]'::jsonb, '演示随访（合成）')
                """, recordId, "54000000-0000-4000-8000-0000000000ff"),
                "patient_id 与麻醉记录单不一致");

        // 记录单未结束：API 409，数据库直插由 BEFORE INSERT 守卫拒绝（55000）。
        // 复用同一记录单，把 ended_at 清空模拟“仍在术中”。
        clearRecordEnd(recordId);
        createFollowup(admin, followupBody(recordId, "H24",
                OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(5), 2, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE action = 'FOLLOWUP_CREATE' AND result = 0
                   AND detail->>'rejection' = 'RECORD_NOT_ENDED'
                """, Integer.class)).isEqualTo(1);
        assertSqlState55000(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.followup_record
                    (anesthesia_record_id, patient_id, followup_type, assessed_at,
                     nrs_score, ponv_score, complications, followed_up_by)
                VALUES (?::uuid, ?::uuid, 'H24', now(), 2, 1, '[]'::jsonb, '演示随访（合成）')
                """, recordId, PATIENT_ANES), "尚未结束");
    }

    // ------------------------------------- 参数校验、权限与数据范围 --

    @Test
    void painValidationPermissionsAndDataScopeAreEnforced() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-FU-0004");
        String recordId = createRecord(admin, created.scheduleId());
        OffsetDateTime base = endRecordInPast(recordId);
        String followupId = createFollowup(admin,
                followupBody(recordId, "H24", base.plusMinutes(20), 3, 1))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse().getContentAsString().transform(this::followupIdOf);

        // 镇痛参数校验 400：bolus ≤0、background <0、lockout ≤0、方式/效果缺失、调整记录超长。
        postPain(admin, followupId, painBody("PCIA", 0.0, 1.0, 15, "PARTIAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("bolusDose"));
        postPain(admin, followupId, painBody("PCIA", 2.0, -0.1, 15, "PARTIAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("backgroundDose"));
        postPain(admin, followupId, painBody("PCIA", 2.0, 1.0, 0, "PARTIAL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("lockoutMinutes"));
        Map<String, Object> noModality = painBody(null, 2.0, 1.0, 15, "PARTIAL");
        postPain(admin, followupId, noModality)
                .andExpect(status().isBadRequest());
        Map<String, Object> noEffect = painBody("PCEA", 2.0, 1.0, 15, null);
        postPain(admin, followupId, noEffect)
                .andExpect(status().isBadRequest());
        Map<String, Object> longAdjustment = painBody("PCIA", 2.0, 1.0, 15, "PARTIAL");
        longAdjustment.put("adjustments", List.of(Map.of(
                "adjustedAt", base.plusMinutes(30).toString(),
                "description", "调".repeat(201))));
        postPain(admin, followupId, longAdjustment)
                .andExpect(status().isBadRequest());

        // 合法记录可追加多条（参数调整以新增表达，append-only）。
        postPain(admin, followupId, painBody("PCIA", 2.0, 1.0, 15, "PARTIAL"))
                .andExpect(status().isCreated());
        postPain(admin, followupId, painBody("PCEA", 3.0, 2.0, 20, "EFFECTIVE"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/v1/anesthesia/followups/" + followupId + "/pain-management")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));

        // 非法标识：随访不存在/非 UUID → 404；记录单非 UUID → 400；记录单不可见 → 422。
        mockMvc.perform(get("/v1/anesthesia/followups/not-a-uuid")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/followups/1c000000-0000-4000-8000-0000000000ff")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/followups").param("recordId", "not-a-uuid")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
        createFollowup(admin, followupBody("12000000-0000-4000-8000-0000000000ff", "H24",
                base.plusMinutes(20), 2, 1))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/v1/anesthesia/followups/" + followupId + "/pain-management")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                painBody("PCIA", 2.0, 1.0, 15, "PARTIAL"))))
                .andExpect(status().isCreated());

        // 无随访权限（术者）→ 403。
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/followups/" + followupId)
                        .header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/anesthesia/followups")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                followupBody(recordId, "H48", base.plusMinutes(60), 2, 1))))
                .andExpect(status().isForbidden());

        // 跨科室（DEPT 数据范围，有随访权限但台次不可见）：单对象 404、集合 403、录入 422。
        String deptUser = login(DEPT_USERNAME, DEPT_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/followups/" + followupId)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/followups/" + followupId + "/pain-management")
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/followups").param("recordId", recordId)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isForbidden());
        createFollowup(deptUser, followupBody(recordId, "H24", base.plusMinutes(90), 2, 1))
                .andExpect(status().isUnprocessableEntity());
        postPain(deptUser, followupId, painBody("PCIA", 2.0, 1.0, 15, "PARTIAL"))
                .andExpect(status().isNotFound());

        // 麻醉护士默认获得随访读写（V19）。
        Integer nurseGrants = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                 JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE rp.role_id = 'a0000000-0000-4000-8000-000000000003'
                   AND p.code IN ('m2:followup:read', 'm2:followup:write')
                """, Integer.class);
        assertThat(nurseGrants).isEqualTo(2);
    }

    // -------------------------------------- V19 结构、约束与数据库第二道防线 --

    @Test
    void v19SchemaConstraintsTriggersPermissionsAndCommentsExist() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT code FROM m0_platform.sys_permission
                 WHERE code LIKE 'm2:followup:%' ORDER BY code
                """, String.class)).containsExactly("m2:followup:read", "m2:followup:write");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                 JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE p.code LIKE 'm2:followup:%'
                   AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001',
                                      'a0000000-0000-4000-8000-000000000002',
                                      'a0000000-0000-4000-8000-000000000003')
                """, Integer.class)).isEqualTo(6);

        List<String> followupColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'm2_anes' AND table_name = 'followup_record'
                """, String.class);
        assertThat(followupColumns).contains("anesthesia_record_id", "patient_id", "followup_type",
                "assessed_at", "nrs_score", "ponv_score", "complications", "complication_notes",
                "patient_feedback", "followed_up_by", "created_at", "created_by", "version");
        List<String> painColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'm2_anes' AND table_name = 'pain_management'
                """, String.class);
        assertThat(painColumns).contains("followup_id", "modality", "drug", "bolus_dose",
                "background_dose", "lockout_minutes", "effect", "adjustments", "created_by");
        assertThat(triggerNames("followup_record")).containsExactly(
                "trg_followup_record_guard_insert", "trg_followup_record_no_delete",
                "trg_followup_record_no_truncate", "trg_followup_record_no_update");
        assertThat(triggerNames("pain_management")).containsExactly(
                "trg_pain_management_no_delete", "trg_pain_management_no_truncate",
                "trg_pain_management_no_update");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_followup_record_nrs'
                """, String.class)).contains("nrs_score", "0", "10");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_followup_record_ponv'
                """, String.class)).contains("ponv_score", "0", "3");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_pain_management_lockout'
                """, String.class)).contains("lockout_minutes", "> 0");

        // 数据库第二道防线：评分越界、bolus=0、lockout=0 由 CHECK 拒绝（23514）。
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-FU-0005");
        String recordId = createRecord(admin, created.scheduleId());
        OffsetDateTime base = endRecordInPast(recordId);
        String followupId = createFollowup(admin,
                followupBody(recordId, "H24", base.plusMinutes(20), 2, 1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString().transform(this::followupIdOf);

        assertSqlState(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.followup_record
                    (anesthesia_record_id, patient_id, followup_type, assessed_at,
                     nrs_score, ponv_score, complications, followed_up_by)
                VALUES (?::uuid, ?::uuid, 'H24', now() - interval '10 minutes',
                        11, 1, '[]'::jsonb, '演示随访（合成）')
                """, recordId, PATIENT_ANES), "23514");
        assertSqlState(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.followup_record
                    (anesthesia_record_id, patient_id, followup_type, assessed_at,
                     nrs_score, ponv_score, complications, followed_up_by)
                VALUES (?::uuid, ?::uuid, 'H24', now() - interval '10 minutes',
                        2, 4, '[]'::jsonb, '演示随访（合成）')
                """, recordId, PATIENT_ANES), "23514");
        assertSqlState(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.pain_management
                    (followup_id, modality, drug, bolus_dose, background_dose,
                     lockout_minutes, effect)
                VALUES (?::uuid, 'PCIA', '演示药（合成）', 0, 1, 15, 'PARTIAL')
                """, followupId), "23514");
        assertSqlState(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.pain_management
                    (followup_id, modality, drug, bolus_dose, background_dose,
                     lockout_minutes, effect)
                VALUES (?::uuid, 'PCIA', '演示药（合成）', 2, 1, 0, 'PARTIAL')
                """, followupId), "23514");

        // 中文注释：新增表与全部列均有 COMMENT。
        Integer tablesWithoutComment = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid
                 WHERE n.nspname = 'm2_anes' AND c.relkind = 'r'
                   AND c.relname IN ('followup_record', 'pain_management')
                   AND (obj_description(c.oid, 'pg_class') IS NULL
                        OR btrim(obj_description(c.oid, 'pg_class')) = '')
                """, Integer.class);
        assertThat(tablesWithoutComment).isZero();
        Integer columnsWithoutComment = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_attribute a
                 JOIN pg_class c ON a.attrelid = c.oid
                 JOIN pg_namespace n ON c.relnamespace = n.oid
                 WHERE n.nspname = 'm2_anes' AND c.relkind = 'r'
                   AND c.relname IN ('followup_record', 'pain_management')
                   AND a.attnum > 0 AND NOT a.attisdropped
                   AND (col_description(c.oid, a.attnum) IS NULL
                        OR btrim(col_description(c.oid, a.attnum)) = '')
                """, Integer.class);
        assertThat(columnsWithoutComment).isZero();
    }

    // ------------------------------------------------------------- helpers --

    private record Created(String applicationId, String scheduleId) {
    }

    private org.springframework.test.web.servlet.ResultActions createFollowup(String token,
            Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/v1/anesthesia/followups")
                .header("Authorization", "Bearer " + token)
                .contentType(JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions postPain(String token,
            String followupId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/v1/anesthesia/followups/" + followupId + "/pain-management")
                .header("Authorization", "Bearer " + token)
                .contentType(JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String followupIdOf(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.get("followupId").asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidJson) {
            throw new IllegalStateException("随访响应 JSON 解析失败", invalidJson);
        }
    }

    private Map<String, Object> followupBody(String recordId, String type,
            OffsetDateTime assessedAt, int nrs, int ponv) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anesthesiaRecordId", recordId);
        body.put("followupType", type);
        body.put("assessedAt", assessedAt.toString());
        body.put("nrsScore", nrs);
        body.put("ponvScore", ponv);
        body.put("complications", List.of());
        body.put("complicationNotes", "演示：无特殊不适（合成）");
        body.put("patientFeedback", "演示：恢复良好（合成）");
        body.put("followedUpBy", "演示随访护士（合成）");
        return body;
    }

    private Map<String, Object> painBody(String modality, double bolus, double background,
            int lockout, String effect) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (modality != null) {
            body.put("modality", modality);
        }
        body.put("drug", "演示镇痛方案（合成数据）");
        body.put("bolusDose", bolus);
        body.put("backgroundDose", background);
        body.put("lockoutMinutes", lockout);
        if (effect != null) {
            body.put("effect", effect);
        }
        body.put("adjustments", List.of());
        return body;
    }

    /**
     * 夹具：把记录单置为“已结束”且结束时间落在过去（SYNTHETIC 测试数据）。
     *
     * <p>记录单由 API 创建（created_at≈now），而随访用例需要过去的结束时间；这里临时禁用
     * 触发器回填 started_at/ended_at/status（仅测试夹具，生产不可这么写）。返回结束时间
     * （now − 2 小时）作为随访基准。
     */
    private OffsetDateTime endRecordInPast(String recordId) {
        jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("""
                    UPDATE m2_anes.anesthesia_record
                       SET created_at = now() - interval '4 hours',
                           started_at = now() - interval '3 hours',
                           ended_at = now() - interval '2 hours',
                           status = 'ONGOING'
                     WHERE id = ?::uuid
                    """, recordId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record ENABLE TRIGGER USER");
        }
        return OffsetDateTime.now(ZoneOffset.ofHours(8)).truncatedTo(ChronoUnit.MILLIS)
                .minusHours(2);
    }

    /** 夹具：清空 ended_at（模拟记录单仍在进行中）。 */
    private void clearRecordEnd(String recordId) {
        jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("""
                    UPDATE m2_anes.anesthesia_record
                       SET ended_at = NULL
                     WHERE id = ?::uuid
                    """, recordId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record ENABLE TRIGGER USER");
        }
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
        String[] tables = {"m2_anes.pain_management", "m2_anes.followup_record",
            "m2_anes.pacu_discharge", "m2_anes.pacu_assessment", "m2_anes.pacu_record",
            "m2_anes.record_revision", "m2_anes.fluid_record", "m2_anes.drug_administration",
            "m2_anes.record_event", "m2_anes.resuscitation_record", "m2_anes.anesthesia_record",
            "m2_anes.consent_record", "m2_anes.three_way_check"};
        for (String table : tables) {
            jdbcTemplate.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
        }
        try {
            jdbcTemplate.update("DELETE FROM m2_anes.pain_management");
            jdbcTemplate.update("DELETE FROM m2_anes.followup_record");
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
                VALUES (?::uuid, ?, '演示患者己', 'FEMALE', DATE '1972-08-20', 54, 'SYNT****9501', ?,
                        '随访合成科室', ?::uuid, 'f1000000-0000-4000-8000-000000000001'::uuid,
                        '演示病区', '08', now(), now(), 1, false)
                """, PATIENT_ANES, HIS_ANES, "hash-" + HIS_ANES, DEPT_ORG_ID);
    }

    private void ensureDeptOrg() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_org (id, code, name, org_type, path, sort, status)
                VALUES (?::uuid, 'SYN_FOLLOWUP_DEPT', '随访合成科室', 'DEPT', ?, 99, 1)
                ON CONFLICT (id) DO NOTHING
                """, DEPT_ORG_ID, "/" + DEPT_ORG_ID);
    }

    private void ensureDeptUser() {
        String hash = new BCryptPasswordEncoder().encode(DEPT_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_FOLLOWUP_DEPT', '合成随访科室角色',
                        'SYNTHETIC：M3-M11-01 随访数据范围用例', 'DEPT')
                ON CONFLICT (id) DO NOTHING
                """, DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试随访科室用户（合成）', 1, false, 0, 1, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, DEPT_USER_ID, DEPT_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, DEPT_USER_ID, DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_org (user_id, org_id, is_primary)
                VALUES (?::uuid, ?::uuid, true) ON CONFLICT DO NOTHING
                """, DEPT_USER_ID, DEPT_ORG_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                SELECT ?::uuid, id FROM m0_platform.sys_permission
                 WHERE code IN ('m2:record:read', 'm2:followup:read', 'm2:followup:write')
                ON CONFLICT DO NOTHING
                """, DEPT_ROLE_ID);
    }
}
