package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.contracts.scheduling.model.EmergencyAddRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.notification.NotificationEvent;
import com.anesop.m1.api.notification.NotificationPort;
import com.anesop.m1.api.schedule.SchedulingConflictException;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.contracts.scheduling.model.UpdateScheduleRequest;
import com.anesop.m1.impl.notification.NotificationDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 停改台、急诊加台与变更留痕（M2-M2-02）端到端用例：MockMvc + 嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：停台状态机（PLANNED 直停 / IN_PROGRESS 需审批）、原因码白名单、改台 MODIFY
 * 变更、急诊加台审批与抢占（原择期 CANCELLED + 审计 + 通知 outbox）、无审批 403、
 * 冲突 409、变更历史查询与数据范围、并发急诊不超排、outbox 无 PHI（约束 + 实际载荷）。
 * 全部数据为 SYNTHETIC。
 */
class ScheduleChangeApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String DATE = "2026-12-03";
    private static final String PATIENT_A = "52000000-0000-4000-8000-000000000011";
    private static final String PATIENT_B = "52000000-0000-4000-8000-000000000012";
    private static final String PATIENT_C = "52000000-0000-4000-8000-000000000013";
    private static final String OR_01 = "f2000000-0000-4000-8000-0000000000c1";
    private static final String OR_03 = "f2000000-0000-4000-8000-0000000000c3";
    private static final String OR_05 = "f2000000-0000-4000-8000-0000000000c5";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SchedulingService schedulingService;

    @Autowired
    private NotificationDispatcher notificationDispatcher;

    @Autowired
    private NotificationPort notificationPort;

    @BeforeEach
    void prepareData() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        SyntheticAccounts.ensureRoomUser(jdbcTemplate);
        grantRoomUserSchedulePermissions();
        jdbcTemplate.update("DELETE FROM m1_sched.notification_outbox");
        deleteChangeRequestsForFixture();
        jdbcTemplate.update("DELETE FROM m1_sched.schedule");
        jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m1_sched.application_status_history");
            jdbcTemplate.update("DELETE FROM m1_sched.surgery_application");
            jdbcTemplate.update("DELETE FROM m1_sched.patient_snapshot");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history ENABLE TRIGGER USER");
        }
        insertPatient(PATIENT_A, "SYNTH-HIS-P-9201", "演示患者甲", SyntheticAccounts.DEMO_ANES_ORG_ID,
                "麻醉科（合成）");
        insertPatient(PATIENT_B, "SYNTH-HIS-P-9202", "演示患者乙", SyntheticAccounts.DEMO_ANES_ORG_ID,
                "麻醉科（合成）");
        insertPatient(PATIENT_C, "SYNTH-HIS-P-9203", "演示患者丙", SyntheticAccounts.DEMO_OR_ORG_ID,
                "手术室（合成）");
        // 通知端口确实为模拟实现（不接真实短信/企业微信）。
        assertThat(notificationPort).isNotNull();
    }

    /**
     * 用例结束后清空 M1 业务表，避免与其他集成测试类共享嵌入式 PG 时留下外键引用
     * （change_request → schedule → application）。删除顺序不可颠倒。
     */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM m1_sched.notification_outbox");
        deleteChangeRequestsForFixture();
        jdbcTemplate.update("DELETE FROM m1_sched.schedule");
        jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m1_sched.application_status_history");
            jdbcTemplate.update("DELETE FROM m1_sched.surgery_application");
            jdbcTemplate.update("DELETE FROM m1_sched.patient_snapshot");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history ENABLE TRIGGER USER");
        }
    }

    @Test
    void stopPlannedScheduleWritesChangeAuditAndOutbox() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M2-02-APP-0001", PATIENT_A, "演示术者");
        String scheduleId = createSchedule(admin, applicationId, OR_01,
                "2026-12-03T08:00:00+08:00", "2026-12-03T10:00:00+08:00", 1);

        mockMvc.perform(post("/v1/scheduling/schedules/" + scheduleId + "/stop")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "PATIENT_CONDITION",
                                "reason", "演示停台原因（合成数据）",
                                "version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.changeReason").value("演示停台原因（合成数据）"))
                .andExpect(jsonPath("$.version").value(2));

        Map<String, Object> change = jdbcTemplate.queryForMap("""
                SELECT change_type, reason_code, status, requested_by, approved_by, preempt_schedule_id
                FROM m1_sched.schedule_change_request WHERE schedule_id = ?::uuid
                """, scheduleId);
        assertThat(change.get("change_type")).isEqualTo("STOP");
        assertThat(change.get("reason_code")).isEqualTo("PATIENT_CONDITION");
        assertThat(change.get("status")).isEqualTo("APPROVED");
        assertThat(change.get("approved_by")).isNotNull();
        assertThat(change.get("preempt_schedule_id")).isNull();

        List<String> actions = jdbcTemplate.queryForList("""
                SELECT action FROM m0_platform.sys_audit_log
                WHERE resource_type = 'SCHEDULE' AND resource_id = ?
                ORDER BY occurred_at
                """, String.class, scheduleId);
        assertThat(actions).contains("SCHEDULE_STOP");

        List<Map<String, Object>> outbox = jdbcTemplate.queryForList("""
                SELECT event_type, status, payload::text AS payload
                FROM m1_sched.notification_outbox ORDER BY created_at
                """);
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).get("event_type")).isEqualTo("SCHEDULE_STOPPED");
        assertThat(outbox.get(0).get("status")).isEqualTo("PENDING");
        String payload = (String) outbox.get(0).get("payload");
        assertThat(payload).contains("scheduleId", "PATIENT_CONDITION")
                .doesNotContain("演示患者甲", "SYNTH-HIS-P-9201", "patientName", "diagnosis");

        assertThat(notificationDispatcher.drainOnce()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM m1_sched.notification_outbox", String.class)).isEqualTo("SENT");
        System.out.printf("[M2-M2-02][stop] schedule=%s change=STOP/APPROVED audit=SCHEDULE_STOP "
                + "outbox=SCHEDULE_STOPPED→SENT%n", scheduleId);
    }

    @Test
    void stopReasonCodeWhitelistAndInProgressApprovalAreEnforced() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M2-02-APP-0010", PATIENT_A, "演示术者");
        String scheduleId = createSchedule(admin, applicationId, OR_01,
                "2026-12-03T08:00:00+08:00", "2026-12-03T10:00:00+08:00", 1);

        mockMvc.perform(post("/v1/scheduling/schedules/" + scheduleId + "/stop")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "WHATEVER", "version", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("reasonCode"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM m1_sched.schedule WHERE id = ?::uuid", String.class, scheduleId))
                .isEqualTo("PLANNED");

        // 术中停台：仅持 write 的术间账号（test.room）无 approve → 403。
        jdbcTemplate.update("UPDATE m1_sched.schedule SET status = 'IN_PROGRESS' WHERE id = ?::uuid",
                scheduleId);
        String room = login(SyntheticAccounts.ROOM_USERNAME, SyntheticAccounts.ROOM_PASSWORD);
        mockMvc.perform(post("/v1/scheduling/schedules/" + scheduleId + "/stop")
                        .header("Authorization", "Bearer " + room)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "ANESTHESIA_RISK", "version", 1))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM m1_sched.schedule WHERE id = ?::uuid", String.class, scheduleId))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m1_sched.schedule_change_request", Integer.class)).isZero();

        // SYS_ADMIN 持 approve：术中停台通过并留痕。
        mockMvc.perform(post("/v1/scheduling/schedules/" + scheduleId + "/stop")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "ANESTHESIA_RISK", "version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        System.out.println("[M2-M2-02][stop-state] badReason=400 inProgressNoApprove=403 "
                + "inProgressApprove=200");
    }

    @Test
    void modifyWritesModifyChangeAndValidatesReasonCode() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M2-02-APP-0020", PATIENT_A, "演示术者");
        String scheduleId = createSchedule(admin, applicationId, OR_01,
                "2026-12-03T08:00:00+08:00", "2026-12-03T10:00:00+08:00", 1);

        // Q-1：改台原因码必填，缺失 400 且不落变更记录。
        Map<String, Object> missingReason = new LinkedHashMap<>();
        missingReason.put("roomId", OR_01);
        missingReason.put("plannedStart", "2026-12-03T10:00:00+08:00");
        missingReason.put("plannedEnd", "2026-12-03T12:00:00+08:00");
        missingReason.put("sequenceNo", 2);
        missingReason.put("version", 1);
        mockMvc.perform(put("/v1/scheduling/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(missingReason)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("reasonCode"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m1_sched.schedule_change_request", Integer.class)).isZero();

        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("roomId", OR_01);
        invalid.put("plannedStart", "2026-12-03T10:00:00+08:00");
        invalid.put("plannedEnd", "2026-12-03T12:00:00+08:00");
        invalid.put("sequenceNo", 2);
        invalid.put("version", 1);
        invalid.put("reasonCode", "NOT_ALLOWED");
        mockMvc.perform(put("/v1/scheduling/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("reasonCode"));

        Map<String, Object> valid = new LinkedHashMap<>(invalid);
        valid.put("reasonCode", "SURGEON_UNAVAILABLE");
        valid.put("reason", "演示改台原因（合成数据）");
        mockMvc.perform(put("/v1/scheduling/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(valid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        Map<String, Object> change = jdbcTemplate.queryForMap("""
                SELECT change_type, reason_code, status FROM m1_sched.schedule_change_request
                WHERE schedule_id = ?::uuid
                """, scheduleId);
        assertThat(change.get("change_type")).isEqualTo("MODIFY");
        assertThat(change.get("reason_code")).isEqualTo("SURGEON_UNAVAILABLE");
        assertThat(change.get("status")).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                WHERE resource_type = 'SCHEDULE' AND action = 'SCHEDULE_UPDATE'
                  AND resource_id = ?
                """, Integer.class, scheduleId)).isEqualTo(1);
        System.out.printf("[M2-M2-02][modify] schedule=%s reasonCode=400/200 change=MODIFY%n",
                scheduleId);
    }

    @Test
    void emergencyAddRequiresApprovalAndCreatesEmergencySchedule() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M2-02-EMG-0001", PATIENT_B, "急诊术者");

        String room = login(SyntheticAccounts.ROOM_USERNAME, SyntheticAccounts.ROOM_PASSWORD);
        mockMvc.perform(post("/v1/scheduling/emergency-cases")
                        .header("Authorization", "Bearer " + room)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(emergencyBody(
                                applicationId, OR_01, "2026-12-03T13:00:00+08:00",
                                "2026-12-03T14:00:00+08:00", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        MvcResult created = mockMvc.perform(post("/v1/scheduling/emergency-cases")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(emergencyBody(
                                applicationId, OR_03, "2026-12-03T13:00:00+08:00",
                                "2026-12-03T14:00:00+08:00", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.approvalRequired").value(true))
                .andExpect(jsonPath("$.approval.status").value("APPROVED"))
                .andExpect(jsonPath("$.approval.changeType").value("EMERGENCY"))
                .andReturn();
        String scheduleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("scheduleId").asText();
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT priority, status FROM m1_sched.schedule WHERE id = ?::uuid
                """, scheduleId);
        assertThat(row.get("priority")).isEqualTo(2);
        assertThat(row.get("status")).isEqualTo("PLANNED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m1_sched.notification_outbox
                WHERE event_type = 'EMERGENCY_SCHEDULED' AND status = 'PENDING'
                """, Integer.class)).isEqualTo(1);
        System.out.printf("[M2-M2-02][emergency] noApprove=403 approve=201 schedule=%s "
                + "priority=EMERGENCY outbox=EMERGENCY_SCHEDULED%n", scheduleId);
    }

    @Test
    void emergencyPreemptCancelsElectiveAndLeavesTraceAndNotifications() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String electiveApplication = createApplication(admin, "SYNTH-M2-02-APP-0030", PATIENT_A, "择期术者");
        String electiveSchedule = createSchedule(admin, electiveApplication, OR_01,
                "2026-12-03T08:00:00+08:00", "2026-12-03T10:00:00+08:00", 1);
        String emergencyApplication = createApplication(admin, "SYNTH-M2-02-EMG-0030", PATIENT_B, "急诊术者");

        // 未指定抢占：409 + conflicts[] 给出可抢占择期台次。
        mockMvc.perform(post("/v1/scheduling/emergency-cases")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(emergencyBody(
                                emergencyApplication, OR_01, "2026-12-03T08:30:00+08:00",
                                "2026-12-03T09:30:00+08:00", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts", hasSize(1)))
                .andExpect(jsonPath("$.conflicts[0].conflictType").value("ROOM_OVERLAP"))
                .andExpect(jsonPath("$.conflicts[0].conflictingScheduleId").value(electiveSchedule));

        MvcResult created = mockMvc.perform(post("/v1/scheduling/emergency-cases")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(emergencyBody(
                                emergencyApplication, OR_01, "2026-12-03T08:30:00+08:00",
                                "2026-12-03T09:30:00+08:00", electiveSchedule))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.approval.preemptScheduleId").value(electiveSchedule))
                .andReturn();
        String emergencySchedule = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("scheduleId").asText();

        Map<String, Object> preempted = jdbcTemplate.queryForMap("""
                SELECT status, reason, version FROM m1_sched.schedule WHERE id = ?::uuid
                """, electiveSchedule);
        assertThat(preempted.get("status")).isEqualTo("CANCELLED");
        assertThat(preempted.get("reason")).isEqualTo("EMERGENCY_PREEMPT");
        assertThat(((Number) preempted.get("version")).intValue()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT priority FROM m1_sched.schedule WHERE id = ?::uuid
                """, Integer.class, emergencySchedule)).isEqualTo(2);

        List<Map<String, Object>> changes = jdbcTemplate.queryForList("""
                SELECT schedule_id, change_type, reason_code, status, preempt_schedule_id
                FROM m1_sched.schedule_change_request ORDER BY requested_at, change_type
                """);
        assertThat(changes).hasSize(2);
        assertThat(changes).extracting(row -> row.get("change_type"))
                .containsExactlyInAnyOrder("EMERGENCY", "STOP");
        Map<String, Object> stopChange = changes.stream()
                .filter(row -> "STOP".equals(row.get("change_type"))).findFirst().orElseThrow();
        assertThat(stopChange.get("schedule_id").toString()).isEqualTo(electiveSchedule);
        assertThat(stopChange.get("reason_code")).isEqualTo("EMERGENCY_PREEMPT");
        assertThat(stopChange.get("preempt_schedule_id").toString()).isEqualTo(emergencySchedule);

        List<String> preemptAudits = jdbcTemplate.queryForList("""
                SELECT action FROM m0_platform.sys_audit_log
                WHERE resource_id = ? AND action = 'SCHEDULE_PREEMPT'
                """, String.class, electiveSchedule);
        assertThat(preemptAudits).containsExactly("SCHEDULE_PREEMPT");

        List<Map<String, Object>> outbox = jdbcTemplate.queryForList("""
                SELECT event_type, payload::text AS payload FROM m1_sched.notification_outbox
                ORDER BY created_at, event_type
                """);
        assertThat(outbox).extracting(row -> row.get("event_type"))
                .containsExactlyInAnyOrder("EMERGENCY_SCHEDULED", "SCHEDULE_PREEMPTED");
        for (Map<String, Object> event : outbox) {
            String payload = (String) event.get("payload");
            assertThat(payload).doesNotContain("演示患者", "SYNTH-HIS-P-9201", "patientName",
                    "patientNo", "hisPatientNo", "inpatientNo", "idCard", "phone", "diagnosis");
        }
        int drained = notificationDispatcher.drainAll(10);
        assertThat(drained).isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m1_sched.notification_outbox WHERE status = 'PENDING'
                """, Integer.class)).isZero();

        // changes 查询：择期台次可追溯到 STOP/EMERGENCY_PREEMPT 与急诊台次。
        mockMvc.perform(get("/v1/scheduling/schedules/" + electiveSchedule + "/changes")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].changeType").value("STOP"))
                .andExpect(jsonPath("$.items[0].reasonCode").value("EMERGENCY_PREEMPT"))
                .andExpect(jsonPath("$.items[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].preemptScheduleId").value(emergencySchedule));
        mockMvc.perform(get("/v1/scheduling/schedules/" + emergencySchedule + "/changes")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].changeType").value("EMERGENCY"));
        System.out.printf("[M2-M2-02][preempt] elective=%s→CANCELLED/EMERGENCY_PREEMPT "
                + "emergency=%s priority=EMERGENCY changes=2 audits=SCHEDULE_PREEMPT "
                + "outbox=SENT/noPHI%n", electiveSchedule, emergencySchedule);
    }

    @Test
    void changesQueryIsScopedByDataScopeAndUnknownScheduleIs404() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String otherDeptApplication = createApplication(admin, "SYNTH-M2-02-APP-0040", PATIENT_C, "其他术者");
        String otherDeptSchedule = createSchedule(admin, otherDeptApplication, OR_05,
                "2026-12-03T08:00:00+08:00", "2026-12-03T09:00:00+08:00", 1);
        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult result = mockMvc.perform(get("/v1/scheduling/schedules/" + otherDeptSchedule
                                + "/changes")
                        .header("Authorization", "Bearer " + dept))
                .andExpect(status().isNotFound())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("演示患者丙", "SYNTH-HIS-P-9203");
        mockMvc.perform(get("/v1/scheduling/schedules/"
                        + UUID.randomUUID() + "/changes")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        System.out.println("[M2-M2-02][changes-scope] deptOtherDept=404 unknown=404 noPHI=true");
    }

    @Test
    void outboxRejectsPhiKeysAtDatabaseLevel() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO m1_sched.notification_outbox (event_type, payload)
                VALUES ('SCHEDULE_STOPPED', '{"patientName":"演示患者"}'::jsonb)
                """))
                .isInstanceOf(DataAccessException.class);
        // 值级兜底：18 位身份证号即使换成中性键也被拒绝。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO m1_sched.notification_outbox (event_type, payload)
                VALUES ('SCHEDULE_STOPPED', '{"ref":"110101199001011234"}'::jsonb)
                """))
                .isInstanceOf(DataAccessException.class);
        System.out.println("[M2-M2-02][outbox-phi] patientName key / 18-digit value rejected by CHECK");
    }

    @Test
    void legacyCancelReusesStopTraceAndOutbox() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M2-02-APP-0060", PATIENT_A, "演示术者");
        String scheduleId = createSchedule(admin, applicationId, OR_01,
                "2026-12-03T08:00:00+08:00", "2026-12-03T10:00:00+08:00", 1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", "SCHEDULE_CHANGE");
        body.put("reason", "演示兼容取消（合成数据）");
        body.put("version", 1);
        mockMvc.perform(post("/v1/scheduling/schedules/" + scheduleId + "/cancel")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(scheduleId));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT change_type FROM m1_sched.schedule_change_request WHERE schedule_id = ?::uuid
                """, String.class, scheduleId)).isEqualTo("STOP");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m1_sched.notification_outbox
                WHERE event_type = 'SCHEDULE_STOPPED'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                WHERE resource_type = 'SCHEDULE' AND action = 'SCHEDULE_CANCEL' AND resource_id = ?
                """, Integer.class, scheduleId)).isEqualTo(1);
        System.out.printf("[M2-M2-02][legacy-cancel] schedule=%s change=STOP outbox=1 audit=%s%n",
                scheduleId, "SCHEDULE_CANCEL");
    }

    /**
     * B-1 并发用例：改台移出 + 急诊抢占。CyclicBarrier 同时进入，断言二者恰有一个成功：
     * 若急诊成功则原台次只停不移，若改台成功则急诊 409 且原台次不被误停。
     */
    @Test
    void concurrentRescheduleOutAndEmergencyPreemptNeverMisstop() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String electiveApplication = createApplication(adminToken, "SYNTH-M2-02-APP-0070",
                PATIENT_A, "择期术者");
        String electiveSchedule = createSchedule(adminToken, electiveApplication, OR_01,
                "2026-12-03T08:00:00+08:00", "2026-12-03T10:00:00+08:00", 1);
        String emergencyApplication = createApplication(adminToken, "SYNTH-M2-02-EMG-0070",
                PATIENT_B, "急诊术者");
        AuthPrincipal principal = new AuthPrincipal(UUID.fromString(SyntheticAccounts.DEMO_ADMIN_ID),
                "demo_admin", "jti", Instant.now().plusSeconds(900), "sid");

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Boolean> moveOut = () -> {
            UpdateScheduleRequest request = new UpdateScheduleRequest();
            request.setRoomId(OR_03);
            request.setPlannedStart(OffsetDateTime.parse("2026-12-03T12:00:00+08:00"));
            request.setPlannedEnd(OffsetDateTime.parse("2026-12-03T13:00:00+08:00"));
            request.setSequenceNo(2);
            request.setVersion(1);
            request.setReasonCode("SCHEDULE_CHANGE");
            barrier.await(30, TimeUnit.SECONDS);
            try {
                schedulingService.reschedule(electiveSchedule, request, principal,
                        AuthRequestContext.empty());
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
        Callable<Boolean> emergency = () -> {
            EmergencyAddRequest request = new EmergencyAddRequest();
            request.setApplicationId(emergencyApplication);
            request.setRoomId(OR_01);
            request.setReasonCode("EMERGENCY_PREEMPT");
            request.setReason("演示并发抢占（合成数据）");
            request.setExpectedStart(OffsetDateTime.parse("2026-12-03T08:30:00+08:00"));
            request.setExpectedEnd(OffsetDateTime.parse("2026-12-03T09:30:00+08:00"));
            request.setSequenceNo(2);
            request.setPreemptScheduleId(electiveSchedule);
            barrier.await(30, TimeUnit.SECONDS);
            try {
                schedulingService.addEmergency(request, principal, AuthRequestContext.empty(), true);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> results = new ArrayList<>();
        try {
            Future<Boolean> moveFuture = pool.submit(moveOut);
            Future<Boolean> emergencyFuture = pool.submit(emergency);
            results.add(moveFuture.get(60, TimeUnit.SECONDS));
            results.add(emergencyFuture.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(results).as("并发改台移出 + 急诊抢占恰有一个成功")
                .containsExactlyInAnyOrder(true, false);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT status, room_id, start_at, version FROM m1_sched.schedule WHERE id = ?::uuid
                """, electiveSchedule);
        boolean cancelled = "CANCELLED".equals(row.get("status"));
        if (cancelled) {
            // 急诊成功：原台次只停不移（不得出现既被移走又被停台的错误组合）。
            assertThat(row.get("room_id").toString()).isEqualTo(OR_01);
            assertThat(((Number) row.get("version")).intValue()).isEqualTo(2);
        } else {
            assertThat(row.get("status")).isEqualTo("PLANNED");
            assertThat(row.get("room_id").toString()).isEqualTo(OR_03);
        }
        int stopChanges = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m1_sched.schedule_change_request
                WHERE schedule_id = ?::uuid AND change_type = 'STOP'
                """, Integer.class, electiveSchedule);
        assertThat(stopChanges).isEqualTo(cancelled ? 1 : 0);
        System.out.printf("[M2-M2-02][concurrent-preempt] results=%s cancelled=%s room=%s "
                + "stopChanges=%d%n", results, cancelled, row.get("room_id"), stopChanges);
    }

    /**
     * 重复 stop 并发：同一 version 两个请求，恰有一个成功；变更/审计/outbox 均只留一条。
     */
    @Test
    void concurrentDuplicateStopLeavesSingleTrace() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(adminToken, "SYNTH-M2-02-APP-0080",
                PATIENT_A, "演示术者");
        String scheduleId = createSchedule(adminToken, applicationId, OR_01,
                "2026-12-03T08:00:00+08:00", "2026-12-03T10:00:00+08:00", 1);
        AuthPrincipal principal = new AuthPrincipal(UUID.fromString(SyntheticAccounts.DEMO_ADMIN_ID),
                "demo_admin", "jti", Instant.now().plusSeconds(900), "sid");

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Boolean> stopCall = () -> {
            com.anesop.contracts.scheduling.model.StopScheduleRequest request =
                    new com.anesop.contracts.scheduling.model.StopScheduleRequest();
            request.setReasonCode("PATIENT_CONDITION");
            request.setReason("演示重复停台（合成数据）");
            request.setVersion(1);
            barrier.await(30, TimeUnit.SECONDS);
            try {
                schedulingService.stop(scheduleId, request, principal, AuthRequestContext.empty(),
                        true);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> results = new ArrayList<>();
        try {
            Future<Boolean> first = pool.submit(stopCall);
            Future<Boolean> second = pool.submit(stopCall);
            results.add(first.get(60, TimeUnit.SECONDS));
            results.add(second.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(results).as("重复停台恰有一个成功").containsExactlyInAnyOrder(true, false);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m1_sched.schedule_change_request
                WHERE schedule_id = ?::uuid AND change_type = 'STOP'
                """, Integer.class, scheduleId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                WHERE resource_type = 'SCHEDULE' AND action = 'SCHEDULE_STOP' AND resource_id = ?
                """, Integer.class, scheduleId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m1_sched.notification_outbox
                WHERE event_type = 'SCHEDULE_STOPPED'
                """, Integer.class)).isEqualTo(1);
        System.out.printf("[M2-M2-02][duplicate-stop] results=%s changes=1 audit=1 outbox=1%n",
                results);
    }

    /**
     * 多实例派发（SKIP LOCKED）：两个线程各 drainOnce，两条待发各被处理一次，不重复投递。
     */
    @Test
    void concurrentDispatchersDrainEachOutboxRowOnce() throws Exception {
        notificationPort.publish(new NotificationEvent("SCHEDULE_STOPPED",
                Map.of("scheduleId", UUID.randomUUID().toString())));
        notificationPort.publish(new NotificationEvent("EMERGENCY_SCHEDULED",
                Map.of("scheduleId", UUID.randomUUID().toString())));

        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Boolean> drain = () -> {
            barrier.await(30, TimeUnit.SECONDS);
            return notificationDispatcher.drainOnce();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> results = new ArrayList<>();
        try {
            Future<Boolean> first = pool.submit(drain);
            Future<Boolean> second = pool.submit(drain);
            results.add(first.get(60, TimeUnit.SECONDS));
            results.add(second.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(results).containsExactlyInAnyOrder(true, true);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m1_sched.notification_outbox WHERE status = 'SENT'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT max(attempts) FROM m1_sched.notification_outbox
                """, Integer.class)).isEqualTo(1);
        System.out.printf("[M2-M2-02][dispatcher-multi-instance] drained=%s sent=2 attempts=1%n",
                results);
    }

    @Test
    void concurrentEmergencyAddsDoNotOversubscribeRoom() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String firstApplication = createApplication(adminToken, "SYNTH-M2-02-EMG-0050", PATIENT_A, "急诊术者甲");
        String secondApplication = createApplication(adminToken, "SYNTH-M2-02-EMG-0051", PATIENT_B, "急诊术者乙");

        AuthPrincipal principal = new AuthPrincipal(UUID.fromString(SyntheticAccounts.DEMO_ADMIN_ID),
                "demo_admin", "jti", Instant.now().plusSeconds(900), "sid");
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Boolean> first = emergencyCallable(principal, firstApplication, barrier);
        Callable<Boolean> second = emergencyCallable(principal, secondApplication, barrier);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> results = new ArrayList<>();
        try {
            Future<Boolean> firstFuture = pool.submit(first);
            Future<Boolean> secondFuture = pool.submit(second);
            results.add(firstFuture.get(60, TimeUnit.SECONDS));
            results.add(secondFuture.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(results).as("同室同窗并发急诊加台恰有一个成功").containsExactlyInAnyOrder(true, false);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m1_sched.schedule
                WHERE room_id = ?::uuid AND start_at = '2026-12-03T10:00:00+08:00'::timestamptz
                """, Integer.class, OR_01)).isEqualTo(1);
        System.out.printf("[M2-M2-02][concurrent-emergency] attempts=2 success=%d rows=1%n",
                results.stream().filter(Boolean::booleanValue).count());
    }

    private Callable<Boolean> emergencyCallable(AuthPrincipal principal, String applicationId,
            CyclicBarrier barrier) {
        return () -> {
            EmergencyAddRequest request = new EmergencyAddRequest();
            request.setApplicationId(applicationId);
            request.setRoomId(OR_01);
            request.setReasonCode("EMERGENCY_GREEN_CHANNEL");
            request.setReason("演示并发急诊加台（合成数据）");
            request.setExpectedStart(OffsetDateTime.parse("2026-12-03T10:00:00+08:00"));
            request.setExpectedEnd(OffsetDateTime.parse("2026-12-03T11:00:00+08:00"));
            request.setSequenceNo(1);
            barrier.await(30, TimeUnit.SECONDS);
            try {
                schedulingService.addEmergency(request, principal, AuthRequestContext.empty(), true);
                return true;
            } catch (SchedulingConflictException e) {
                return false;
            }
        };
    }

    private Map<String, Object> emergencyBody(String applicationId, String roomId, String start,
            String end, String preemptScheduleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId);
        body.put("roomId", roomId);
        body.put("reasonCode", preemptScheduleId == null
                ? "EMERGENCY_GREEN_CHANNEL" : "EMERGENCY_PREEMPT");
        body.put("reason", "演示急诊加台（合成数据）");
        body.put("expectedStart", start);
        body.put("expectedEnd", end);
        body.put("sequenceNo", 1);
        if (preemptScheduleId != null) {
            body.put("preemptScheduleId", preemptScheduleId);
        }
        return body;
    }

    private String createApplication(String token, String hisApplicationNo, String patientId,
            String surgeonName) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("hisApplicationNo", hisApplicationNo);
        request.put("patientId", patientId);
        request.put("surgeryName", "演示术式（腹腔镜胆囊切除术）");
        request.put("urgency", "EMERGENCY");
        request.put("plannedDurationMin", 60);
        request.put("surgeonName", surgeonName);
        MvcResult result = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("applicationId").asText();
    }

    private String createSchedule(String token, String applicationId, String roomId, String start,
            String end, int sequence) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId);
        body.put("roomId", roomId);
        body.put("plannedStart", start);
        body.put("plannedEnd", end);
        body.put("sequenceNo", sequence);
        body.put("priority", "ELECTIVE");
        MvcResult result = mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("scheduleId").asText();
    }

    private void grantRoomUserSchedulePermissions() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                VALUES (?::uuid, 'b0000000-0000-4000-8000-000000000027'),
                       (?::uuid, 'b0000000-0000-4000-8000-000000000028')
                ON CONFLICT DO NOTHING
                """, SyntheticAccounts.ROOM_ROLE_ID, SyntheticAccounts.ROOM_ROLE_ID);
    }

    /**
     * 审批留痕 append-only（V9 触发器）：测试夹具临时 DISABLE TRIGGER USER 清理后立即恢复；
     * 生产路径不得使用该开关。
     */
    private void deleteChangeRequestsForFixture() {
        jdbcTemplate.execute("ALTER TABLE m1_sched.schedule_change_request DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m1_sched.schedule_change_request");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.schedule_change_request ENABLE TRIGGER USER");
        }
    }

    private void insertPatient(String id, String hisPatientNo, String name, String deptId,
            String deptName) {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, ?, 'FEMALE', DATE '1978-03-15', 48, ?, ?, ?, ?::uuid,
                        'f1000000-0000-4000-8000-000000000001'::uuid, '演示病区', '12',
                        now(), now(), 1, false)
                """, id, hisPatientNo, name, "SYNT****" + hisPatientNo.substring(hisPatientNo.length() - 4),
                "hash-" + hisPatientNo.substring(hisPatientNo.length() - 4), deptName, deptId);
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

    @SuppressWarnings("unused")
    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
