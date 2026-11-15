package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.contracts.anesthesia.model.CreateRevisionRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.record.AnesthesiaRecordService;
import com.anesop.m2.api.record.RecordLockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 锁单、修订与 CA 占位签名（M3-M2-03）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：锁单前置清单（三方核查/有效 SIGNED 同意/开始结束时间/至少 1 个事件）与 409 明细、
 * 未确认清单 400、锁单成功/重复锁单 409、锁后事件/用药/出入量/保存 409、数据库触发器直写
 * 55000、修订 append-only 与全量快照/差异/签名摘要、revision_no 并发递增、版本链签名、
 * 越权 403/跨科室 404、审计（无 PHI）与 V17 结构/权限/注释。全部数据为 SYNTHETIC。
 */
class RecordLockRevisionApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String PATIENT_ANES = "54000000-0000-4000-8000-000000000012";
    private static final String HIS_ANES = "SYNTH-HIS-P-9402";
    private static final String OR_01 = "f2000000-0000-4000-8000-0000000000c1";
    private static final String OR_02 = "f2000000-0000-4000-8000-0000000000c2";
    private static final String GENERAL_CONSENT_TEMPLATE_ID = "b1000000-0000-4000-8000-000000000002";
    private static final int GENERAL_CONSENT_TEMPLATE_VERSION = 2;

    private static final String LOCK_DEPT_USER_ID = "e0000000-0000-4000-8000-0000000000e3";
    private static final String LOCK_DEPT_USERNAME = "test.lock.dept";
    private static final String LOCK_DEPT_PASSWORD = "Synth@2026";
    private static final String LOCK_DEPT_ROLE_ID = "a0000000-0000-4000-8000-0000000000f5";
    private static final String LOCK_DEPT_ORG_ID = "f1000000-0000-4000-8000-0000000000ea";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordLockService recordLockService;

    @Autowired
    private AnesthesiaRecordService anesthesiaRecordService;

    @BeforeEach
    void prepareData() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        ensureLockDeptOrg();
        ensureLockDeptUser();
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
                LOCK_DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_role WHERE user_id = ?::uuid",
                LOCK_DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user WHERE id = ?::uuid",
                LOCK_DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_role_permission WHERE role_id = ?::uuid",
                LOCK_DEPT_ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_role WHERE id = ?::uuid",
                LOCK_DEPT_ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_org WHERE id = ?::uuid",
                LOCK_DEPT_ORG_ID);
    }

    // ------------------------------------------------ 锁单前置与签名占位 --

    @Test
    void lockPreconditionsRejectWithDetailsAndLockGeneratesSimulatedSignature() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-LOCK-0001");
        String recordId = createRecord(admin, created.scheduleId());

        // 前置全部未满足：返回清单（前端只展示服务端结论）。
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/lock-status")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.canLock").value(false))
                .andExpect(jsonPath("$.currentRevision").value(0))
                .andExpect(jsonPath("$.preconditions", hasSize(6)))
                .andExpect(jsonPath("$.preconditions[*].code", containsInAnyOrder(
                        "THREE_WAY_CHECK_COMPLETED", "CONSENT_SIGNED", "RECORD_STARTED",
                        "RECORD_ENDED", "AT_LEAST_ONE_EVENT", "NOT_LOCKED")))
                .andExpect(jsonPath("$.preconditions[?(@.code == 'NOT_LOCKED')].satisfied")
                        .value(true))
                .andExpect(jsonPath("$.preconditions[?(@.code == 'RECORD_ENDED')].satisfied")
                        .value(false));

        // 未确认清单 400（字段 confirmChecklist），不发锁单。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(lockBody(1, false))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("confirmChecklist"));

        // 前置未满足 409，details 逐项列出条件码；失败留痕 result=0。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(lockBody(1, true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.details[*].field", containsInAnyOrder(
                        "THREE_WAY_CHECK_COMPLETED", "CONSENT_SIGNED", "RECORD_STARTED",
                        "RECORD_ENDED", "AT_LEAST_ONE_EVENT")));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'ANESTHESIA_RECORD' AND resource_id = ?
                   AND action = 'RECORD_LOCK' AND result = 0
                """, Integer.class, recordId)).isEqualTo(1);

        // 补齐临床记录：麻醉开始/结束、三方核查完成、有效 SIGNED 同意。
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .truncatedTo(ChronoUnit.MILLIS).plusSeconds(30);
        appendEvent(admin, recordId, "ANESTHESIA_START", base);
        appendEvent(admin, recordId, "ANESTHESIA_END", base.plusMinutes(4));
        completeThreeWayCheck(admin, created.applicationId());
        String consentId = createConsent(admin, created.applicationId());
        signConsent(admin, consentId, 1);

        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/lock-status")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canLock").value(true))
                .andExpect(jsonPath("$.preconditions[?(@.satisfied == false)]", hasSize(0)));

        // version 过期 409（记录单已追加 2 个事件 → version=3）。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(lockBody(2, true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        MvcResult locked = mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(lockBody(3, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recordId))
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.lockedAt").isNotEmpty())
                .andExpect(jsonPath("$.lockedBy").value(SyntheticAccounts.DEMO_ADMIN_ID))
                .andExpect(jsonPath("$.signatureStatus").value("SIGNED"))
                .andExpect(jsonPath("$.caStatus").value("SIMULATED"))
                .andExpect(jsonPath("$.currentRevision").value(0))
                .andReturn();
        JsonNode lockResult = objectMapper.readTree(locked.getResponse().getContentAsString());
        String lockSignatureRef = lockResult.get("signatureRef").asText();
        String lockSignatureHash = lockResult.get("signatureHash").asText();
        assertThat(lockSignatureRef).startsWith("SIM-CA-").contains(recordId);
        assertThat(lockSignatureHash).matches("^[0-9a-f]{64}$");

        // 记录单详情带锁单留痕；重复锁单 409。
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.lockedAt").isNotEmpty())
                .andExpect(jsonPath("$.currentRevision").value(0))
                .andExpect(jsonPath("$.signatureRef").value(lockSignatureRef))
                .andExpect(jsonPath("$.signatureHash").value(lockSignatureHash))
                .andExpect(jsonPath("$.caStatus").value("SIMULATED"));
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(lockBody(4, true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'ANESTHESIA_RECORD' AND resource_id = ?
                   AND action IN ('RECORD_LOCK', 'RECORD_SIGN')
                 ORDER BY occurred_at
                """, recordId);
        assertThat(audits).extracting(row -> row.get("action"))
                .contains("RECORD_LOCK", "RECORD_SIGN");
        assertThat(audits).anyMatch(row -> Integer.valueOf(1).equals(row.get("result"))
                && String.valueOf(row.get("detail")).contains(lockSignatureRef));
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail"))
                .contains("演示患者戊"));
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail")).contains(HIS_ANES));
        // Q-3：SYS_ADMIN 的 break-glass 在审计 detail 标注管理员操作。
        assertThat(audits).anyMatch(row -> Integer.valueOf(1).equals(row.get("result"))
                && String.valueOf(row.get("detail")).contains("administratorOperation"));

        System.out.printf("[M3-M2-03][lock] record=%s preconditions=6 unmet=409 confirm=400 "
                + "lock=LOCKED caStatus=SIMULATED hash=%s duplicate=409 audits=%d noPhi=true%n",
                recordId, lockSignatureHash.substring(0, 12), audits.size());
    }

    // ------------------------------------------- 锁后不可变（API + DB） --

    @Test
    void lockedRecordRejectsClinicalWritesThroughApiAndDatabase() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-LOCK-0002");
        String recordId = lockRecord(admin, created);

        // API：事件/用药/出入量/保存全部 409（服务层先拒绝）。
        appendEvent(admin, recordId, "CUSTOM", OffsetDateTime.now(ZoneOffset.ofHours(8))
                .plusMinutes(1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                drugBody("丙泊酚（演示）", false))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/fluids")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "direction", "INTAKE", "fluidType", "复方氯化钠（演示）",
                                "amountMl", 500))))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", 4, "anesthesiaType", "COMBINED"))))
                .andExpect(status().isConflict());

        // DB 触发器：直写子记录/改内容/删除一律 55000（第二道防线，绕过服务层也拒绝）。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.record_event
                    (id, record_id, seq, event_type, occurred_at, source, payload, created_at)
                VALUES (gen_random_uuid(), ?::uuid, 99, 'CUSTOM', now(), 'MANUAL', '{}'::jsonb, now())
                """, recordId)), "immutable after lock");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.drug_administration
                    (id, record_id, drug_name, dose, unit, route, administered_at, narcotic)
                VALUES (gen_random_uuid(), ?::uuid, '丙泊酚（演示）', 10, 'mg', 'IV', now(), false)
                """, recordId)), "immutable after lock");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.fluid_record
                    (id, record_id, direction, fluid_type, amount_ml, recorded_at)
                VALUES (gen_random_uuid(), ?::uuid, 'INTAKE', '复方氯化钠（演示）', 500, now())
                """, recordId)), "immutable after lock");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                UPDATE m2_anes.anesthesia_record
                   SET anesthesia_type = 'COMBINED', version = version + 1
                 WHERE id = ?::uuid
                """, recordId)), "content columns are immutable");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                UPDATE m2_anes.anesthesia_record
                   SET locked_at = now(), version = version + 1
                 WHERE id = ?::uuid
                """, recordId)), "lock columns are write-once");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                DELETE FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, recordId)), "append-only");

        // 锁后仍可查询历史（不阻断读）。
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/events")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));

        System.out.printf("[M3-M2-03][immutable] record=%s event/drug/fluid/put=409 "
                + "directInsert/update/delete=55000 reads=ok%n", recordId);
    }

    // ------------------------------------- 修订快照、差异、签名与 append-only --

    @Test
    void revisionsStoreSnapshotsDiffsAndStableSignatureChain() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-LOCK-0003");
        String recordId = lockRecordWithClinicalData(admin, created);
        String lockHash = jdbcTemplate.queryForObject("""
                SELECT lock_signature_hash FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, String.class, recordId);

        // 非法原因码（契约枚举）→ 400；缺少原因 → 400 字段 reason。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/revisions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content("{\"reasonCode\":\"NOT_A_CODE\",\"reason\":\"演示\",\"changes\":\"演示\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/revisions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "ANESTHESIA_TYPE_CHANGE",
                                "changes", "演示变更摘要（合成）"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("reason"));

        // 修订 1：更正麻醉方式，仅写入快照，锁单原始行不变。
        MvcResult first = mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/revisions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(revisionBody(
                                "ANESTHESIA_TYPE_CHANGE", "锁单后发现麻醉方式记录笔误（演示）",
                                "将麻醉方式由 GENERAL 更正为 COMBINED（演示）", "COMBINED"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(1))
                .andExpect(jsonPath("$.reasonCode").value("ANESTHESIA_TYPE_CHANGE"))
                .andExpect(jsonPath("$.caStatus").value("SIMULATED"))
                .andExpect(jsonPath("$.snapshot.record.anesthesiaType").value("COMBINED"))
                .andExpect(jsonPath("$.snapshot.events", hasSize(2)))
                .andExpect(jsonPath("$.snapshot.drugs", hasSize(1)))
                .andExpect(jsonPath("$.snapshot.fluids", hasSize(2)))
                .andExpect(jsonPath("$.snapshot.ioSummary.intakeMl").value(1500))
                .andExpect(jsonPath("$.snapshot.ioSummary.balanceMl").value(1100))
                .andExpect(jsonPath("$.snapshot.signatures.lockedSignatureHash").value(lockHash))
                .andExpect(jsonPath("$.snapshot.signatures.previousRevisionSignatureHash")
                        .doesNotExist())
                .andExpect(jsonPath("$.diff.previousRevisionNo").value(0))
                .andExpect(jsonPath("$.diff.changedFields[0].field")
                        .value("record.anesthesiaType"))
                .andExpect(jsonPath("$.diff.changedFields[0].previousValue").value("GENERAL"))
                .andExpect(jsonPath("$.diff.changedFields[0].newValue").value("COMBINED"))
                .andReturn();
        JsonNode revision1 = objectMapper.readTree(first.getResponse().getContentAsString());
        String hash1 = revision1.get("signatureHash").asText();
        assertThat(revision1.get("signatureRef").asText()).startsWith("SIM-CA-");
        assertThat(hash1).matches("^[0-9a-f]{64}$").isNotEqualTo(lockHash);

        // 列表与详情一致；版本不存在 404。
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/revisions")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].revisionNo").value(1))
                .andExpect(jsonPath("$.items[0].signatureHash").value(hash1));
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/revisions/1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.record.anesthesiaType").value("COMBINED"))
                .andExpect(jsonPath("$.diff.previousRevisionNo").value(0));
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/revisions/99")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());

        // 修订 2：未提交 corrections 时沿用上一版更正（不会回退为锁单原值），差异为零。
        MvcResult second = mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/revisions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(revisionBody(
                                "OTHER", "补充说明：术中用药记录已核对（演示）",
                                "仅补充修订说明，不改变临床内容（演示）", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.snapshot.record.anesthesiaType").value("COMBINED"))
                .andExpect(jsonPath("$.snapshot.signatures.previousRevisionSignatureHash")
                        .value(hash1))
                .andExpect(jsonPath("$.diff.previousRevisionNo").value(1))
                .andExpect(jsonPath("$.diff.changedFields", hasSize(0)))
                .andExpect(jsonPath("$.diff.summary").value("无内容差异（仅修订说明）"))
                .andReturn();
        String hash2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .get("signatureHash").asText();
        assertThat(hash2).matches("^[0-9a-f]{64}$").isNotEqualTo(hash1);

        // 原记录（锁单行）内容不变，只有修订号/状态推进。
        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT status, anesthesia_type, current_revision, version, lock_signature_hash
                  FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, recordId);
        assertThat(stored.get("status")).isEqualTo("REVISED");
        assertThat(stored.get("anesthesia_type")).isEqualTo("GENERAL");
        assertThat(stored.get("current_revision")).isEqualTo(2);
        assertThat(stored.get("lock_signature_hash")).isEqualTo(lockHash);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.record_revision WHERE record_id = ?::uuid
                """, Integer.class, recordId)).isEqualTo(2);

        // 修订 append-only：UPDATE/DELETE 55000；原始修订摘要不变。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                UPDATE m2_anes.record_revision SET reason = '改写（演示）'
                 WHERE record_id = ?::uuid AND revision_no = 1
                """, recordId)), "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                DELETE FROM m2_anes.record_revision WHERE record_id = ?::uuid
                """, recordId)), "append-only");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT signature_hash FROM m2_anes.record_revision
                 WHERE record_id = ?::uuid AND revision_no = 1
                """, String.class, recordId)).isEqualTo(hash1);

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'ANESTHESIA_RECORD' AND resource_id = ?
                   AND action IN ('RECORD_REVISE', 'RECORD_SIGN') AND result = 1
                 ORDER BY occurred_at
                """, recordId);
        assertThat(audits).filteredOn(row -> "RECORD_REVISE".equals(row.get("action")))
                .hasSize(2);
        assertThat(audits).filteredOn(row -> "RECORD_SIGN".equals(row.get("action")))
                .hasSize(3);
        assertThat(audits).anyMatch(row -> String.valueOf(row.get("detail")).contains(hash1));
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail"))
                .contains("演示患者戊"));
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail"))
                .contains("记录笔误"));

        System.out.printf("[M3-M2-03][revision] record=%s revisions=2 hash1=%s hash2=%s "
                + "chain=true baseAnesthesiaUnchanged=true appendOnly=55000 audits=%d noPhi=true%n",
                recordId, hash1.substring(0, 12), hash2.substring(0, 12), audits.size());
    }

    // ------------------------------- 抢救内容入快照、有效值投影与冻结（B-1/Q-2） --

    @Test
    void rescueContentIsSignedFrozenAndProjectedAsEffective() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-LOCK-RESCUE");
        String recordId = createRecord(admin, created.scheduleId());
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .truncatedTo(ChronoUnit.MILLIS).plusSeconds(30);
        appendEvent(admin, recordId, "ANESTHESIA_START", base).andExpect(status().isCreated());
        appendEvent(admin, recordId, "ANESTHESIA_END", base.plusMinutes(4))
                .andExpect(status().isCreated());
        // 先录抢救（锁单前可写），抢救内容必须进入锁单/修订签名快照。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/resuscitations")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(rescueBody(base))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leader.name").value("演示负责人（合成）"))
                .andExpect(jsonPath("$.rescuers", hasSize(1)));
        completeThreeWayCheck(admin, created.applicationId());
        String consentId = createConsent(admin, created.applicationId());
        signConsent(admin, consentId, 1);

        // 对照记录：内容相同但没有抢救记录 → 锁单摘要不同（覆盖抢救内容）。
        Created withoutRescue = createSchedule(admin, "SYNTH-M3-LOCK-NORESCUE", OR_02,
                "2026-12-04");
        String plainRecordId = lockRecord(admin, withoutRescue);
        String plainHash = jdbcTemplate.queryForObject("""
                SELECT lock_signature_hash FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, String.class, plainRecordId);

        MvcResult locked = mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(lockBody(3, true))))
                .andExpect(status().isOk())
                .andReturn();
        String lockHash = objectMapper.readTree(locked.getResponse().getContentAsString())
                .get("signatureHash").asText();
        assertThat(lockHash).isNotEqualTo(plainHash);

        // 锁单后：有效值投影 = 原始值（尚无修订），corrections 为空。
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/effective")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.currentRevision").value(0))
                .andExpect(jsonPath("$.original.anesthesiaType").value("GENERAL"))
                .andExpect(jsonPath("$.effective.anesthesiaType").value("GENERAL"))
                .andExpect(jsonPath("$.corrections", hasSize(0)));

        // 锁单后抢救写入 409（Q-2 冻结），数据库直写 55000（第二道防线）。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/resuscitations")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(rescueBody(base.plusMinutes(1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.resuscitation_record
                    (id, record_id, resus_type, started_at, ended_at, event_summary, drugs,
                     leader, rescuers, outcome, created_at, created_by)
                VALUES (gen_random_uuid(), ?::uuid, 'CPR', now() - interval '5 min', now(),
                        '{"summary":"直插（演示）"}'::jsonb, '[]'::jsonb,
                        '{"name":"演示负责人（合成）"}'::jsonb,
                        '[{"name":"演示护士（合成）"}]'::jsonb, 'SUCCESS', now(), gen_random_uuid())
                """, recordId)), "immutable after lock");

        // 修订：快照包含抢救记录（含人员），签名摘要随之变化；有效值投影应用更正。
        MvcResult revised = mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/revisions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(revisionBody(
                                "ANESTHESIA_TYPE_CHANGE", "锁单后发现麻醉方式记录笔误（演示）",
                                "将麻醉方式由 GENERAL 更正为 COMBINED（演示）", "COMBINED"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshot.rescues", hasSize(1)))
                .andExpect(jsonPath("$.snapshot.rescues[0].resusType").value("CPR"))
                .andExpect(jsonPath("$.snapshot.rescues[0].leader.name")
                        .value("演示负责人（合成）"))
                .andExpect(jsonPath("$.snapshot.rescues[0].rescuers[0].name")
                        .value("演示护士（合成）"))
                .andExpect(jsonPath("$.snapshot.rescues[0].eventSummary.summary")
                        .value("演示：胸外按压后恢复自主循环（合成）"))
                .andExpect(jsonPath("$.snapshot.rescues[0].createdAt").doesNotExist())
                .andReturn();
        String revisionHash = objectMapper.readTree(revised.getResponse().getContentAsString())
                .get("signatureHash").asText();
        assertThat(revisionHash).isNotEqualTo(lockHash);

        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/effective")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRevision").value(1))
                .andExpect(jsonPath("$.original.anesthesiaType").value("GENERAL"))
                .andExpect(jsonPath("$.effective.anesthesiaType").value("COMBINED"))
                .andExpect(jsonPath("$.corrections", hasSize(1)))
                .andExpect(jsonPath("$.corrections[0].field").value("anesthesiaType"))
                .andExpect(jsonPath("$.corrections[0].revisionNo").value(1))
                .andExpect(jsonPath("$.corrections[0].reasonCode")
                        .value("ANESTHESIA_TYPE_CHANGE"));

        // 记录单原始行仍不可变（effective 只是读时投影）。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT anesthesia_type FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, String.class, recordId)).isEqualTo("GENERAL");

        System.out.printf("[M3-M2-03][rescue] record=%s lockHash=%s revisionHash=%s "
                + "rescueInSnapshot=true effective=COMBINED original=GENERAL freeze409=true "
                + "directInsert55000=true%n", recordId, lockHash.substring(0, 12),
                revisionHash.substring(0, 12));
    }

    @Test
    void revisionChainTriggerRejectsForgedVersions() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-LOCK-CHAIN");
        String recordId = createRecord(admin, created.scheduleId());
        String unlockedInsert = """
                INSERT INTO m2_anes.record_revision
                    (id, record_id, revision_no, reason_code, reason, changes, snapshot,
                     previous_revision_no, previous_signature_hash, signature_ref, signature_hash,
                     ca_status, created_at, created_by)
                VALUES (gen_random_uuid(), ?::uuid, 1, 'OTHER', '演示', '演示', '{}'::jsonb,
                        NULL, NULL, 'SIM-CA-FORGED', '%s', 'SIMULATED', now(), ?::uuid)
                """.formatted("a".repeat(64));
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(unlockedInsert, recordId,
                SyntheticAccounts.DEMO_ADMIN_ID)), "requires a locked parent record");

        Created chainLocked = createSchedule(admin, "SYNTH-M3-LOCK-CHAIN-2", OR_02, "2026-12-04");
        String lockedRecordId = lockRecord(admin, chainLocked);
        String lockHash = jdbcTemplate.queryForObject("""
                SELECT lock_signature_hash FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, String.class, lockedRecordId);
        // 跳过版本号：current_revision=0 时直插 revision_no=2。
        String skipped = """
                INSERT INTO m2_anes.record_revision
                    (id, record_id, revision_no, reason_code, reason, changes, snapshot,
                     previous_revision_no, previous_signature_hash, signature_ref, signature_hash,
                     ca_status, created_at, created_by)
                VALUES (gen_random_uuid(), ?::uuid, 2, 'OTHER', '演示', '演示', '{}'::jsonb,
                        1, ?::varchar, 'SIM-CA-FORGED', '%s', 'SIMULATED', now(), ?::uuid)
                """.formatted("b".repeat(64));
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(skipped, lockedRecordId,
                lockHash, SyntheticAccounts.DEMO_ADMIN_ID)),
                "revision_no must be current_revision + 1");

        // 正常修订 1 后，直插 revision 2 但 previous_signature_hash 断链 → 拒绝。
        mockMvc.perform(post("/v1/anesthesia/records/" + lockedRecordId + "/revisions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(revisionBody(
                                "OTHER", "演示修订（合成）", "演示修订说明（合成）", null))))
                .andExpect(status().isCreated());
        String brokenChain = """
                INSERT INTO m2_anes.record_revision
                    (id, record_id, revision_no, reason_code, reason, changes, snapshot,
                     previous_revision_no, previous_signature_hash, signature_ref, signature_hash,
                     ca_status, created_at, created_by)
                VALUES (gen_random_uuid(), ?::uuid, 2, 'OTHER', '演示', '演示', '{}'::jsonb,
                        1, '%s', 'SIM-CA-FORGED', '%s', 'SIMULATED', now(), ?::uuid)
                """.formatted("f".repeat(64), "c".repeat(64));
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(brokenChain, lockedRecordId,
                SyntheticAccounts.DEMO_ADMIN_ID)), "does not match version 1");

        System.out.printf("[M3-M2-03][chain] unlocked=55000 skippedRevision=55000 "
                + "brokenHash=55000 forgedVersionRejected=true%n");
    }

    // ------------------------------------------------- 并发修订版本号递增 --

    @Test
    void concurrentRevisionsSerializeRevisionNumbersUnderRowLock() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-LOCK-CONC");
        String recordId = lockRecord(admin, created);

        AuthPrincipal principal = new AuthPrincipal(
                UUID.fromString(SyntheticAccounts.DEMO_ADMIN_ID),
                SyntheticAccounts.DEMO_ADMIN_USERNAME, "jti-revise", Instant.now().plusSeconds(900),
                "sid-revise");
        AuthRequestContext context = new AuthRequestContext("127.0.0.1", "JUnit",
                "SYNTH-TRACE-REVISE");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                final int ordinal = index;
                tasks.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    CreateRevisionRequest request = new CreateRevisionRequest();
                    request.setReasonCode(CreateRevisionRequest.ReasonCodeEnum.OTHER);
                    request.setReason("并发修订（合成）第 " + (ordinal + 1) + " 条");
                    request.setChanges("并发修订说明（合成）");
                    return recordLockService.createRevision(recordId, request, principal, context)
                            .getRevisionNo();
                });
            }
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            List<Integer> revisionNos = new ArrayList<>();
            for (Future<Integer> future : futures) {
                revisionNos.add(future.get(20, TimeUnit.SECONDS));
            }
            assertThat(revisionNos).containsExactlyInAnyOrder(1, 2);
        } finally {
            pool.shutdownNow();
        }
        List<Integer> stored = jdbcTemplate.queryForList("""
                SELECT revision_no FROM m2_anes.record_revision
                 WHERE record_id = ?::uuid ORDER BY revision_no
                """, Integer.class, recordId);
        assertThat(stored).containsExactly(1, 2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT current_revision FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, Integer.class, recordId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, String.class, recordId)).isEqualTo("REVISED");
        System.out.printf("[M3-M2-03][concurrent] record=%s threads=2 revisionNo=%s "
                + "currentRevision=2 rowLock=serialized%n", recordId, stored);
    }

    // ------------------------------------------- 权限、数据范围与迁移结构 --

    @Test
    void permissionsScopeAndV17SchemaAreEnforced() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        Created created = createSchedule(admin, "SYNTH-M3-LOCK-0004");
        String recordId = lockRecord(admin, created);

        // 无锁单/修订权限 403（术者仅有排程/申请权限）。
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(lockBody(5, true))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/revisions")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(revisionBody(
                                "OTHER", "演示越权（合成）", "演示越权（合成）", null))))
                .andExpect(status().isForbidden());

        // 跨科室（DEPT 数据范围）→ 单记录语义 404，不泄露存在性。
        String deptUser = login(LOCK_DEPT_USERNAME, LOCK_DEPT_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/lock-status")
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/revisions")
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound());
        // 跨科室用户没有 m2:record:lock（角色仅读/写），方法级授权先拒绝 → 403。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + deptUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(lockBody(5, true))))
                .andExpect(status().isForbidden());

        // 护士默认不授修订/锁单（【待确认】：护士是否可修订由临床确认）。
        Integer nurseGrants = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                 JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE rp.role_id = 'a0000000-0000-4000-8000-000000000003'
                   AND p.code IN ('m2:record:lock', 'm2:record:revise')
                """, Integer.class);
        assertThat(nurseGrants).isZero();

        // V17 结构：锁列/修订表/约束/触发器/权限点/注释。
        List<String> recordColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'm2_anes' AND table_name = 'anesthesia_record'
                """, String.class);
        assertThat(recordColumns).contains("locked_at", "locked_by", "current_revision",
                "lock_signature_ref", "lock_signature_hash", "lock_ca_status");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT column_default FROM information_schema.columns
                 WHERE table_schema = 'm2_anes' AND table_name = 'anesthesia_record'
                   AND column_name = 'current_revision'
                """, String.class)).isEqualTo("0");
        List<String> revisionColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'm2_anes' AND table_name = 'record_revision'
                """, String.class);
        assertThat(revisionColumns).contains("id", "record_id", "revision_no", "reason_code",
                "reason", "changes", "snapshot", "previous_revision_no",
                "previous_signature_hash", "signature_ref", "signature_hash", "ca_status",
                "created_at", "created_by");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'uk_record_revision_record_no'
                """, String.class)).contains("UNIQUE", "record_id", "revision_no");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_anesthesia_record_status'
                """, String.class)).contains("DRAFT", "ONGOING", "LOCKED", "REVISED");
        assertThat(triggerNames("record_revision")).containsExactly(
                "trg_record_revision_chain_insert", "trg_record_revision_no_delete",
                "trg_record_revision_no_truncate", "trg_record_revision_no_update");
        assertThat(triggerNames("record_event")).contains(
                "trg_record_event_locked_insert", "trg_record_event_no_delete",
                "trg_record_event_no_truncate", "trg_record_event_no_update");
        assertThat(triggerNames("resuscitation_record"))
                .contains("trg_resuscitation_record_locked_insert");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                 WHERE conname = 'ck_record_revision_reason_code'
                """, String.class)).contains("ANESTHESIA_TYPE_CHANGE", "OTHER")
                .doesNotContain("OMISSION", "TIMING_CORRECTION");
        assertThat(jdbcTemplate.queryForList("""
                SELECT code FROM m0_platform.sys_permission
                 WHERE code LIKE 'm2:record:%' ORDER BY code
                """, String.class)).containsExactly("m2:record:lock", "m2:record:read",
                "m2:record:revise", "m2:record:write");
        Integer grants = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                 JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE p.code LIKE 'm2:record:%'
                   AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001',
                                      'a0000000-0000-4000-8000-000000000002',
                                      'a0000000-0000-4000-8000-000000000003')
                """, Integer.class);
        assertThat(grants).isEqualTo(10);
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

        System.out.printf("[M3-M2-03][v17] lockColumns=6 revisionColumns=14 permissions=4 "
                + "grants=%d triggers=record_revision4/record_event4/rescueFreeze1 "
                + "comments=true nurseRevise=denied%n", grants);
    }

    // ------------------------------------------------------------- helpers --

    private record Created(String applicationId, String scheduleId) {
    }

    private ResultActions appendEvent(String token, String recordId, String eventType,
            OffsetDateTime occurredAt) throws Exception {
        return mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/events")
                .header("Authorization", "Bearer " + token)
                .contentType(JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "eventType", eventType,
                        "occurredAt", occurredAt.toString(),
                        "note", "演示事件（合成）"))));
    }

    private Map<String, Object> lockBody(int version, boolean confirmChecklist) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("signatureRequested", true);
        body.put("confirmChecklist", confirmChecklist);
        return body;
    }

    private Map<String, Object> revisionBody(String reasonCode, String reason, String changes,
            String anesthesiaType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", reasonCode);
        body.put("reason", reason);
        body.put("changes", changes);
        if (anesthesiaType != null) {
            body.put("corrections", Map.of("anesthesiaType", anesthesiaType));
        }
        return body;
    }

    private Map<String, Object> rescueBody(OffsetDateTime start) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resusType", "CPR");
        body.put("startedAt", start.plusMinutes(1).toString());
        body.put("endedAt", start.plusMinutes(2).toString());
        body.put("eventSummary", Map.of(
                "summary", "演示：胸外按压后恢复自主循环（合成）",
                "interventions", List.of("胸外按压")));
        // M9-01：抢救用药经药品字典判定；演示字典内的非麻精药品（避免误触发第二核对）。
        body.put("drugs", List.of(Map.of(
                "drugName", "丙泊酚（演示）",
                "dose", 1,
                "unit", "mg",
                "route", "IV",
                "administeredAt", start.plusMinutes(1).toString())));
        body.put("leader", Map.of(
                "userId", SyntheticAccounts.DEMO_ADMIN_ID,
                "name", "演示负责人（合成）"));
        body.put("rescuers", List.of(Map.of("name", "演示护士（合成）")));
        body.put("outcome", "SUCCESS");
        return body;
    }

    private Map<String, Object> drugBody(String drugName, boolean narcotic) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drugName", drugName);
        body.put("dose", 10);
        body.put("unit", "mg");
        body.put("route", "IV");
        body.put("administeredAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
        body.put("narcotic", narcotic);
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

    /**
     * 构造“可锁单”记录：开始/结束事件 + 三方核查完成 + 有效 SIGNED 同意，返回已锁单记录标识。
     */
    private String lockRecord(String token, Created created) throws Exception {
        String recordId = createRecord(token, created.scheduleId());
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .truncatedTo(ChronoUnit.MILLIS).plusSeconds(30);
        appendEvent(token, recordId, "ANESTHESIA_START", base).andExpect(status().isCreated());
        appendEvent(token, recordId, "ANESTHESIA_END", base.plusMinutes(4))
                .andExpect(status().isCreated());
        completeThreeWayCheck(token, created.applicationId());
        String consentId = createConsent(token, created.applicationId());
        signConsent(token, consentId, 1);
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(lockBody(3, true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));
        return recordId;
    }

    /** 可锁单记录 + 1 条用药 + 2 条出入量，用于修订快照与差异断言。 */
    private String lockRecordWithClinicalData(String token, Created created) throws Exception {
        String recordId = createRecord(token, created.scheduleId());
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .truncatedTo(ChronoUnit.MILLIS).plusSeconds(30);
        appendEvent(token, recordId, "ANESTHESIA_START", base).andExpect(status().isCreated());
        appendEvent(token, recordId, "ANESTHESIA_END", base.plusMinutes(4))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                drugBody("丙泊酚（演示）", false))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/fluids")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "direction", "INTAKE", "fluidType", "复方氯化钠（演示）",
                                "amountMl", 1500))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/fluids")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "direction", "OUTPUT", "fluidType", "尿量（演示）",
                                "amountMl", 400))))
                .andExpect(status().isCreated());
        completeThreeWayCheck(token, created.applicationId());
        String consentId = createConsent(token, created.applicationId());
        signConsent(token, consentId, 1);
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(lockBody(3, true))))
                .andExpect(status().isOk());
        return recordId;
    }

    private void completeThreeWayCheck(String token, String applicationId) throws Exception {
        OffsetDateTime timeOutAt = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .minusHours(1).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime signOutAt = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .minusMinutes(10).truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeOutAt", timeOutAt.toString(),
                                "specimenChecked", true,
                                "implantChecked", true,
                                "issues", List.of()))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeOutAt", timeOutAt.toString(),
                                "signOutAt", signOutAt.toString(),
                                "specimenChecked", true,
                                "implantChecked", true,
                                "issues", List.of()))))
                .andExpect(status().isCreated());
    }

    private String createConsent(String token, String applicationId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "applicationId", applicationId,
                                "templateId", GENERAL_CONSENT_TEMPLATE_ID,
                                "templateVersion", GENERAL_CONSENT_TEMPLATE_VERSION,
                                "signerName", "演示患者",
                                "signerRelation", "SELF",
                                "signMethod", "ELECTRONIC"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("consentId").asText();
    }

    private void signConsent(String token, String consentId, int version) throws Exception {
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", version))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));
    }

    private Created createSchedule(String token, String hisApplicationNo) throws Exception {
        return createSchedule(token, hisApplicationNo, OR_01, "2026-12-03");
    }

    private Created createSchedule(String token, String hisApplicationNo, String roomId,
            String plannedDate) throws Exception {
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
        schedule.put("roomId", roomId);
        schedule.put("plannedStart", plannedDate + "T08:00:00+08:00");
        schedule.put("plannedEnd", plannedDate + "T10:00:00+08:00");
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
        String[] tables = {"m2_anes.record_revision", "m2_anes.fluid_record",
            "m2_anes.drug_administration", "m2_anes.record_event",
            "m2_anes.resuscitation_record", "m2_anes.anesthesia_record",
            "m2_anes.consent_record", "m2_anes.three_way_check"};
        for (String table : tables) {
            jdbcTemplate.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
        }
        try {
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
                        '锁单修订合成科室', ?::uuid, 'f1000000-0000-4000-8000-000000000001'::uuid,
                        '演示病区', '05', now(), now(), 1, false)
                """, PATIENT_ANES, HIS_ANES, "hash-" + HIS_ANES, LOCK_DEPT_ORG_ID);
    }

    private void ensureLockDeptOrg() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_org (id, code, name, org_type, path, sort, status)
                VALUES (?::uuid, 'SYN_LOCK_DEPT', '锁单修订合成科室', 'DEPT', ?, 99, 1)
                ON CONFLICT (id) DO NOTHING
                """, LOCK_DEPT_ORG_ID, "/" + LOCK_DEPT_ORG_ID);
    }

    private void ensureLockDeptUser() {
        String hash = new BCryptPasswordEncoder().encode(LOCK_DEPT_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_LOCK_DEPT', '合成锁单修订科室角色',
                        'SYNTHETIC：M3-M2-03 锁单/修订数据范围用例', 'DEPT')
                ON CONFLICT (id) DO NOTHING
                """, LOCK_DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试锁单修订科室用户（合成）', 1, false, 0, 1, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, LOCK_DEPT_USER_ID, LOCK_DEPT_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, LOCK_DEPT_USER_ID, LOCK_DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_org (user_id, org_id, is_primary)
                VALUES (?::uuid, ?::uuid, true) ON CONFLICT DO NOTHING
                """, LOCK_DEPT_USER_ID, LOCK_DEPT_ORG_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                SELECT ?::uuid, id FROM m0_platform.sys_permission
                 WHERE code IN ('m2:record:read', 'm2:record:write')
                ON CONFLICT DO NOTHING
                """, LOCK_DEPT_ROLE_ID);
    }
}
