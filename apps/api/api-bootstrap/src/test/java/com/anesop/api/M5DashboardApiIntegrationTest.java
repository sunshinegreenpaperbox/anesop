package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * 实时态势与 KPI 看板（M5-M18-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16 +
 * 真实 Flyway（含 V26 data-only 权限）。
 *
 * <p>固定 SYNTHETIC 数据（只落在专用科室/院区/术间，日期取服务端当日，避免与其他用例交叉）：
 * <ul>
 *   <li>术间 OR-71（专用科室“看板合成科”，状态 SURGERY）；</li>
 *   <li>C1 08:00–10:00 IN_PROGRESS，记录单 08:05 开始未完（已开始、无时长）；</li>
 *   <li>C2 10:30–12:00 PLANNED（未开始）；</li>
 *   <li>C3 09:00–11:00 COMPLETED，记录单 09:40→10:05（25 分钟），一条 CRITICAL 未确认告警；</li>
 *   <li>C4 13:00–14:00 CANCELLED（计入总台次与取消率，不计非取消台次）；</li>
 *   <li>设备 SIM-DASH-01 绑定 OR-71，last_seen_at=now（在线）。</li>
 * </ul>
 * 手算（DEPT 数据范围，可见 1 术间 → 可用 600 分钟）：非取消 3、取消 1、已开始 2、已完成 1、
 * 准点首台 C1 08:00 计划 / 08:05 实际 → 1/1=1.0；取消率 1/4=0.25；
 * 利用率 330/600=0.55；平均麻醉时长 25.0；告警 1（严重 1）。
 */
class M5DashboardApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private static final String DEPT_ID = "f1000000-0000-4000-8000-0000000000d1";
    private static final String SITE_ID = "f1000000-0000-4000-8000-0000000000d2";
    private static final String ROOM_ID = "f2000000-0000-4000-8000-0000000000d1";
    private static final String ROLE_ID = "a0000000-0000-4000-8000-0000000000d1";
    private static final String USER_ID = "e0000000-0000-4000-8000-0000000000d1";
    private static final String USERNAME = "synth.dashboard.dept";
    private static final String UNBOUND_ROLE_ID = "a0000000-0000-4000-8000-0000000000d2";
    private static final String UNBOUND_USER_ID = "e0000000-0000-4000-8000-0000000000d2";
    private static final String UNBOUND_USERNAME = "synth.dashboard.unbound";
    private static final String PASSWORD = "Synth@2026";
    private static final String PATIENT_NAME = "演示患者看板（合成）";
    private static final String MASKED_INPATIENT = "SYND****D";
    private static final String RULE_ID = "1b000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LocalDate today;
    private String roomNo;

    @BeforeEach
    void prepareData() {
        today = LocalDate.now(ZONE);
        roomNo = "OR-71";
        ensureOrg();
        ensureDashboardUser(ROLE_ID, USER_ID, USERNAME, true);
        ensureDashboardUser(UNBOUND_ROLE_ID, UNBOUND_USER_ID, UNBOUND_USERNAME, false);
        insertRoom();
        insertCase("01", "IN_PROGRESS", 8, 0, 10, 0, "COMPLETED_RECORD");
        insertCase("02", "PLANNED", 10, 30, 12, 0, null);
        insertCase("03", "COMPLETED", 9, 0, 11, 0, "RECORD_0940_1005");
        insertCase("04", "CANCELLED", 13, 0, 14, 0, null);
        insertDevice();
        insertAlert();
    }

    @AfterEach
    void cleanUpFixtures() {
        // 告警与麻醉记录单 append-only：夹具临时停用触发器后立即恢复（与既有集成测试一致）。
        jdbcTemplate.execute("ALTER TABLE m2_anes.alert DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update(
                    "DELETE FROM m2_anes.alert WHERE dedupe_key LIKE 'SYNTH-M5-DASH-%'");
            jdbcTemplate.update(
                    "DELETE FROM m2_anes.anesthesia_record WHERE schedule_id IN "
                            + "(SELECT id FROM m1_sched.schedule WHERE reason = 'SYNTH-M5-DASH')");
            jdbcTemplate.update("DELETE FROM m1_sched.schedule WHERE reason = 'SYNTH-M5-DASH'");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.surgery_application WHERE remark = 'SYNTH-M5-DASH'");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.patient_snapshot WHERE his_patient_no LIKE 'SYNTH-M5-DASH-%'");
            jdbcTemplate.update(
                    "DELETE FROM m2_anes.device_registry WHERE gateway_id = 'GW-SYN-DASH'");
            jdbcTemplate.update("DELETE FROM m1_sched.operating_room WHERE id = ?::uuid", ROOM_ID);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_user_org WHERE user_id IN "
                    + "(?::uuid, ?::uuid)", USER_ID, UNBOUND_USER_ID);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_user_role WHERE role_id IN "
                    + "(?::uuid, ?::uuid)", ROLE_ID, UNBOUND_ROLE_ID);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_role_permission WHERE role_id IN "
                    + "(?::uuid, ?::uuid)", ROLE_ID, UNBOUND_ROLE_ID);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_user WHERE id IN (?::uuid, ?::uuid)",
                    USER_ID, UNBOUND_USER_ID);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_role WHERE id IN (?::uuid, ?::uuid)",
                    ROLE_ID, UNBOUND_ROLE_ID);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_org WHERE id = ?::uuid", DEPT_ID);
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.alert ENABLE TRIGGER USER");
        }
    }

    // ------------------------------------------------------------------
    // 实时态势：手算聚合 + 延迟字段 + 无 PHI
    // ------------------------------------------------------------------

    @Test
    void realtimeAggregatesHandCalculatedSituationWithoutPhi() throws Exception {
        String token = login(USERNAME, PASSWORD);

        MvcResult result = mockMvc.perform(get("/v1/dashboard/realtime")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.dataScope").value("DEPT"))
                .andExpect(jsonPath("$.roomStatusCounts.total").value(1))
                .andExpect(jsonPath("$.roomStatusCounts.inSurgery").value(1))
                .andExpect(jsonPath("$.roomStatusCounts.idle").value(0))
                .andExpect(jsonPath("$.roomStatusCounts.cleaning").value(0))
                .andExpect(jsonPath("$.roomStatusCounts.outOfService").value(0))
                .andExpect(jsonPath("$.rooms.length()").value(1))
                .andExpect(jsonPath("$.rooms[0].roomNo").value("OR-71"))
                .andExpect(jsonPath("$.rooms[0].status").value("IN_SURGERY"))
                .andExpect(jsonPath("$.rooms[0].caseInProgress").value(true))
                .andExpect(jsonPath("$.rooms[0].plannedCaseCount").value(3))
                .andExpect(jsonPath("$.caseProgress.totalCount").value(4))
                .andExpect(jsonPath("$.caseProgress.activeCount").value(1))
                .andExpect(jsonPath("$.caseProgress.statusCounts.length()").value(4))
                .andExpect(jsonPath("$.alerts.unacknowledgedCount").value(1))
                .andExpect(jsonPath("$.alerts.criticalCount").value(1))
                .andExpect(jsonPath("$.alerts.acknowledgedCount").value(0))
                .andExpect(jsonPath("$.devices.total").value(1))
                .andExpect(jsonPath("$.devices.online").value(1))
                .andExpect(jsonPath("$.devices.offline").value(0))
                .andExpect(jsonPath("$.devices.maintenance").value(0))
                .andExpect(jsonPath("$.devices.offlineTimeoutSeconds").value(120))
                .andExpect(jsonPath("$.dataDelaySeconds").isNumber())
                .andExpect(jsonPath("$.dataQuality").value("OK"))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // 无 PHI：患者姓名/脱敏住院号/诊断/记录单标识都不出现在看板响应里。
        assertThat(body).doesNotContain(PATIENT_NAME, MASKED_INPATIENT, "演示诊断",
                "recordId", "record_id", "patientId", "acknowledgeReason");

        // 审计 DASHBOARD_VIEW 采样落库，detail 无 PHI。
        // 审计表为全局 append-only：按本用例操作人过滤，避免其它用例/类的
        // DASHBOARD_VIEW 采样行（M5-M19-02 新增安全用例）干扰“最近一条”断言。
        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE action = 'DASHBOARD_VIEW' AND resource_type = 'DASHBOARD'
                   AND actor_id = ?::uuid
                 ORDER BY occurred_at DESC LIMIT 1
                """, USER_ID);
        assertThat(audit.get("detail").toString())
                .contains("\"kind\": \"realtime\"")
                .contains("\"dataScope\": \"DEPT\"")
                .doesNotContain(PATIENT_NAME, MASKED_INPATIENT);
    }

    // ------------------------------------------------------------------
    // 当日 KPI：手算聚合
    // ------------------------------------------------------------------

    @Test
    void kpiMatchesHandCalculatedDailyAggregates() throws Exception {
        String token = login(USERNAME, PASSWORD);

        mockMvc.perform(get("/v1/dashboard/kpi")
                        .header("Authorization", "Bearer " + token)
                        .param("date", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.dataScope").value("DEPT"))
                .andExpect(jsonPath("$.totalCaseCount").value(4))
                .andExpect(jsonPath("$.caseCount").value(3))
                .andExpect(jsonPath("$.cancelledCount").value(1))
                .andExpect(jsonPath("$.startedCount").value(2))
                .andExpect(jsonPath("$.completedCount").value(1))
                .andExpect(jsonPath("$.onTimeEvaluatedCount").value(1))
                .andExpect(jsonPath("$.onTimeCount").value(1))
                .andExpect(jsonPath("$.onTimeRate").value(1.0))
                .andExpect(jsonPath("$.onTimeThresholdMinutes").value(15))
                .andExpect(jsonPath("$.cancellationRate").value(0.25))
                .andExpect(jsonPath("$.totalSurgeryMinutes").value(330))
                .andExpect(jsonPath("$.availableMinutes").value(600))
                .andExpect(jsonPath("$.roomCount").value(1))
                .andExpect(jsonPath("$.utilizationRate").value(0.55))
                .andExpect(jsonPath("$.alertCount").value(1))
                .andExpect(jsonPath("$.criticalAlertCount").value(1))
                .andExpect(jsonPath("$.recordedCaseCount").value(1))
                .andExpect(jsonPath("$.unrecordedCaseCount").value(2))
                .andExpect(jsonPath("$.averageAnesthesiaMinutes").value(25.0))
                .andExpect(jsonPath("$.formula").isNotEmpty())
                .andExpect(jsonPath("$.pendingConfirmations[0]").isNotEmpty())
                .andExpect(jsonPath("$.dataDelaySeconds").isNumber())
                .andExpect(jsonPath("$.dataQuality").value("OK"));
    }

    @Test
    void emptyDayReturnsZerosAndInvalidDateReturnsBadRequest() throws Exception {
        String token = login(USERNAME, PASSWORD);
        String emptyDay = today.minusDays(200).toString();

        mockMvc.perform(get("/v1/dashboard/kpi")
                        .header("Authorization", "Bearer " + token)
                        .param("date", emptyDay))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(0))
                .andExpect(jsonPath("$.totalCaseCount").value(0))
                .andExpect(jsonPath("$.startedCount").value(0))
                .andExpect(jsonPath("$.onTimeEvaluatedCount").value(0))
                .andExpect(jsonPath("$.onTimeRate").value(0.0))
                .andExpect(jsonPath("$.cancellationRate").value(0.0))
                .andExpect(jsonPath("$.utilizationRate").value(0.0))
                .andExpect(jsonPath("$.averageAnesthesiaMinutes").value(0.0));

        mockMvc.perform(get("/v1/dashboard/kpi")
                        .header("Authorization", "Bearer " + token)
                        .param("date", "2026/11/15"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("date"));
    }

    // ------------------------------------------------------------------
    // 权限与数据范围（fail-closed）
    // ------------------------------------------------------------------

    @Test
    void forbiddenWithoutPermissionOrWithoutDataScope() throws Exception {
        // 无 m5:dashboard:read（SYS_SURGEON）→ 403。
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/dashboard/realtime")
                        .header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        // DEPT 角色但无组织绑定 → 范围解析 fail-closed → 403（不返回全院数据）。
        String unbound = login(UNBOUND_USERNAME, PASSWORD);
        mockMvc.perform(get("/v1/dashboard/realtime")
                        .header("Authorization", "Bearer " + unbound))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mockMvc.perform(get("/v1/dashboard/kpi")
                        .header("Authorization", "Bearer " + unbound))
                .andExpect(status().isForbidden());
    }

    @Test
    void v26SeedsDashboardPermissionAndMinimalGrants() {
        Integer permissions = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_permission
                 WHERE code = 'm5:dashboard:read'
                """, Integer.class);
        assertThat(permissions).isEqualTo(1);

        String path = jdbcTemplate.queryForObject("""
                SELECT path FROM m0_platform.sys_permission WHERE code = 'm5:dashboard:read'
                """, String.class);
        assertThat(path).isEqualTo("/api/v1/dashboard/*");

        Integer grants = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM m0_platform.sys_role_permission rp
                  JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
                 WHERE sp.code = 'm5:dashboard:read'
                   AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001'::uuid,
                                      'a0000000-0000-4000-8000-000000000002'::uuid,
                                      'a0000000-0000-4000-8000-000000000005'::uuid,
                                      'a0000000-0000-4000-8000-000000000006'::uuid)
                """, Integer.class);
        assertThat(grants).isEqualTo(4);
    }

    // ------------------------------------------------------------------
    // 固定数据构造与登录
    // ------------------------------------------------------------------

    private void ensureOrg() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_org (id, code, name, org_type, parent_id, path, sort,
                                                 status, deleted)
                VALUES (?::uuid, 'SYN-M5-DASH-DEPT', '看板合成科（合成）', 'DEPT', NULL,
                        '/SYN-M5-DASH', 1, 1, false)
                ON CONFLICT (id) DO NOTHING
                """, DEPT_ID);
    }

    /**
     * 看板 DEPT 用户（有组织绑定）与无绑定用户（范围解析 fail-closed）；
     * 两者都持有 m5:dashboard:read，用于区分“缺权限 403”与“无数据范围 403”。
     */
    private void ensureDashboardUser(String roleId, String userId, String username,
            boolean bound) {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, ?, ?, 'SYNTHETIC：看板数据范围用例', 'DEPT')
                ON CONFLICT (id) DO NOTHING
                """, roleId, "SYN_" + username.toUpperCase().replace('.', '_'), "看板合成角色");
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '看板测试账号（合成）', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, userId, username, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, userId, roleId);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                VALUES (?::uuid, 'b0000000-0000-4000-8000-00000000007d')
                ON CONFLICT DO NOTHING
                """, roleId);
        if (bound) {
            jdbcTemplate.update("""
                    INSERT INTO m0_platform.sys_user_org (user_id, org_id, is_primary)
                    VALUES (?::uuid, ?::uuid, true) ON CONFLICT DO NOTHING
                    """, userId, DEPT_ID);
        }
    }

    private void insertRoom() {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.operating_room
                    (id, room_no, name, room_type, status, dept_name, dept_id, site_id, version,
                     deleted)
                VALUES (?::uuid, ?, '看板合成手术间（合成）', 'GENERAL', 'SURGERY',
                        '看板合成科（合成）', ?::uuid, ?::uuid, 0, false)
                ON CONFLICT (id) DO NOTHING
                """, ROOM_ID, roomNo, DEPT_ID, SITE_ID);
    }

    private void insertDevice() {
        jdbcTemplate.update("""
                INSERT INTO m2_anes.device_registry
                    (id, gateway_id, device_id, room_id, device_type, status, last_seen_at,
                     version)
                VALUES ('80000000-0000-4000-8000-00000000d101'::uuid, 'GW-SYN-DASH',
                        'SIM-DASH-01', ?::uuid, 'MONITOR', 'ONLINE', now(), 1)
                ON CONFLICT (id) DO NOTHING
                """, ROOM_ID);
    }

    private void insertAlert() {
        jdbcTemplate.update("""
                INSERT INTO m2_anes.alert
                    (id, record_id, rule_id, rule_code, rule_version, metric, value, unit,
                     severity, triggered_at, dedupe_key, status, version)
                VALUES ('81000000-0000-4000-8000-00000000d101'::uuid,
                        '57000000-0000-4000-8000-00000000d103'::uuid, ?::uuid, 'SPO2_LOW', 1,
                        'SPO2', 88.00, '%', 'CRITICAL', now(), 'SYNTH-M5-DASH-1', 'TRIGGERED', 1)
                ON CONFLICT (id) DO NOTHING
                """, RULE_ID);
    }

    /**
     * 插入一条 SYNTHETIC 台次；时间按本地日期 + 时/分生成（避免固定日期与真实时钟冲突）。
     *
     * @param recordMode null=无记录单；COMPLETED_RECORD=记录单 08:05 开始未结束；
     *                   RECORD_0940_1005=记录单 09:40→10:05（25 分钟）
     */
    private void insertCase(String suffix, String status, int startHour, int startMinute,
            int endHour, int endMinute, String recordMode) {
        String patientId = "54000000-0000-4000-8000-00000000d1" + suffix;
        String applicationId = "55000000-0000-4000-8000-00000000d1" + suffix;
        String scheduleId = "56000000-0000-4000-8000-00000000d1" + suffix;
        String recordId = "57000000-0000-4000-8000-00000000d1" + suffix;
        String hisNo = "SYNTH-M5-DASH-" + suffix;
        OffsetDateTime start = local(startHour, startMinute);
        OffsetDateTime end = local(endHour, endMinute);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, ?, 'MALE', DATE '1975-06-01', 56, ?, ?, '看板合成科（合成）',
                        ?::uuid, ?::uuid, '演示病区', '01', now(), now(), 1, false)
                ON CONFLICT (id) DO NOTHING
                """, patientId, hisNo, PATIENT_NAME, MASKED_INPATIENT, "hash-" + hisNo, DEPT_ID,
                SITE_ID);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, surgery_code, urgency,
                     planned_duration_min, dept_name, dept_id, site_id, surgeon_name,
                     preop_diagnosis, status, source, remark, version, deleted)
                VALUES (?::uuid, ?, ?::uuid, '看板合成术式（合成）', 'SYNTH-DASH-001', 'ELECTIVE',
                        120, '看板合成科（合成）', ?::uuid, ?::uuid, '演示术者（合成）',
                        '演示诊断（合成）', 'SCHEDULED', 'MANUAL', 'SYNTH-M5-DASH', 1, false)
                ON CONFLICT (id) DO NOTHING
                """, applicationId, hisNo + "-APP", patientId, DEPT_ID, SITE_ID);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority, status,
                     reason, version, deleted, created_at, updated_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 1, 0, ?, 'SYNTH-M5-DASH', 1, false,
                        now(), now())
                ON CONFLICT (id) DO NOTHING
                """, scheduleId, applicationId, ROOM_ID, start, end, status);
        if (recordMode == null) {
            return;
        }
        OffsetDateTime recordStart = "COMPLETED_RECORD".equals(recordMode)
                ? local(startHour, startMinute + 5)
                : local(9, 40);
        OffsetDateTime recordEnd = "COMPLETED_RECORD".equals(recordMode) ? null : local(10, 5);
        jdbcTemplate.update("""
                INSERT INTO m2_anes.anesthesia_record
                    (id, schedule_id, application_id, patient_id, status, anesthesia_type,
                     started_at, ended_at, created_by, updated_by, version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'ONGOING', 'GENERAL', ?, ?,
                        ?::uuid, ?::uuid, 1, false)
                ON CONFLICT (id) DO NOTHING
                """, recordId, scheduleId, applicationId, patientId, recordStart, recordEnd,
                USER_ID, USER_ID);
    }

    private OffsetDateTime local(int hour, int minute) {
        return today.atTime(hour, minute).atZone(ZONE).toOffsetDateTime();
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
