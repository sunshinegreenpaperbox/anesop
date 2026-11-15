package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
 * 风险与资源大屏（M5-M18-02）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16 + 真实 Flyway
 * （含 V26 data-only 权限），覆盖契约 dashboard.yaml 0.2.0 的三个新端点。
 *
 * <p>固定 SYNTHETIC 数据（只落在专用科室/院区/术间，日期取服务端当日，避免与其他用例交叉）：
 * <ul>
 *   <li>术间 OR-72（专用科室“风险资源合成科”，状态 SURGERY）；</li>
 *   <li>C1 00:10–00:40 PLANNED 无记录单，术前评估 ASA II / LOW；</li>
 *   <li>C2 00:50–01:10 PLANNED 无记录单，**无**术前评估（未访视，计入未评估）；</li>
 *   <li>C3 01:20–01:50 COMPLETED 记录单 01:25→01:45，术前评估 ASA III / HIGH；</li>
 *   <li>C4 02:00–02:30 CANCELLED，术前评估 ASA IV / HIGH（必须被风险分层排除）；</li>
 *   <li>设备 SIM-DASH-02 绑定 OR-72，last_seen_at=now（在线）；</li>
 *   <li>当日排班：白班 2 人、夜班 1 人（同一科室）；</li>
 *   <li>一条 CRITICAL 未确认告警（当日）。</li>
 * </ul>
 * 手算期望：非取消 3、已评估 2、未评估 1、高危 1（C3）、ASA II 1 / III 1、
 * 风险 LOW 1 / HIGH 1、严重告警 1；利用率 = (30+20+30) 分钟 / (1 术间 × 600) = 0.1333。
 * 超时台次数随时钟变化（复用真实时钟），按契约口径在同处独立推算后断言。
 */
class M5DashboardRiskResourceApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private static final String DEPT_ID = "f1000000-0000-4000-8000-0000000000e1";
    private static final String SITE_ID = "f1000000-0000-4000-8000-0000000000e2";
    private static final String ROOM_ID = "f2000000-0000-4000-8000-0000000000e1";
    private static final String ROLE_ID = "a0000000-0000-4000-8000-0000000000e1";
    private static final String USER_ID = "e0000000-0000-4000-8000-0000000000e1";
    private static final String USERNAME = "synth.dashboard.risk";
    private static final String UNBOUND_ROLE_ID = "a0000000-0000-4000-8000-0000000000e2";
    private static final String UNBOUND_USER_ID = "e0000000-0000-4000-8000-0000000000e2";
    private static final String UNBOUND_USERNAME = "synth.dashboard.risk.unbound";
    private static final String PASSWORD = "Synth@2026";
    private static final String PATIENT_NAME = "演示患者风险（合成）";
    private static final String MASKED_INPATIENT = "SYNR****D";
    private static final String RULE_ID = "1b000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LocalDate today;
    private Instant dayStart;

    @BeforeEach
    void prepareData() {
        today = LocalDate.now(ZONE);
        dayStart = today.atStartOfDay(ZONE).toInstant();
        ensureOrg();
        ensureDashboardUser(ROLE_ID, USER_ID, USERNAME, true);
        ensureDashboardUser(UNBOUND_ROLE_ID, UNBOUND_USER_ID, UNBOUND_USERNAME, false);
        insertRoom();
        // C1：无评估前先建整组台次，再补评估（顺序无关，均为 INSERT）。
        insertCase("e1", "PLANNED", 0, 10, 0, 40, null);
        insertCase("e2", "PLANNED", 0, 50, 1, 10, null);
        insertCase("e3", "COMPLETED", 1, 20, 1, 50, "RECORD_0125_0145");
        insertCase("e4", "CANCELLED", 2, 0, 2, 30, null);
        insertAssessment("e1", "II", "LOW");
        insertAssessment("e3", "III", "HIGH");
        insertAssessment("e4", "IV", "HIGH");
        insertDevice();
        insertAlert();
        insertStaffSchedule("f1", "DAY");
        insertStaffSchedule("f2", "DAY");
        insertStaffSchedule("f3", "NIGHT");
    }

    @AfterEach
    void cleanUpFixtures() {
        // 告警/麻醉记录单/术前评估均 append-only：夹具临时停用触发器后立即恢复。
        jdbcTemplate.execute("ALTER TABLE m2_anes.alert DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.preop_assessment DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update(
                    "DELETE FROM m2_anes.alert WHERE dedupe_key LIKE 'SYNTH-M5-RISK-%'");
            jdbcTemplate.update(
                    "DELETE FROM m2_anes.anesthesia_record WHERE schedule_id IN "
                            + "(SELECT id FROM m1_sched.schedule WHERE reason = 'SYNTH-M5-RISK')");
            jdbcTemplate.update(
                    "DELETE FROM m2_anes.preop_assessment WHERE application_id IN "
                            + "(SELECT id FROM m1_sched.surgery_application "
                            + "WHERE remark = 'SYNTH-M5-RISK')");
            jdbcTemplate.update("DELETE FROM m1_sched.schedule WHERE reason = 'SYNTH-M5-RISK'");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.surgery_application WHERE remark = 'SYNTH-M5-RISK'");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.patient_snapshot WHERE his_patient_no LIKE 'SYNTH-M5-RISK-%'");
            jdbcTemplate.update(
                    "DELETE FROM m2_anes.device_registry WHERE gateway_id = 'GW-SYN-RISK'");
            jdbcTemplate.update(
                    "DELETE FROM m4_staff.staff_schedule WHERE department_id = ?::uuid", DEPT_ID);
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
            jdbcTemplate.execute("ALTER TABLE m2_anes.preop_assessment ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.alert ENABLE TRIGGER USER");
        }
    }

    // ------------------------------------------------------------------
    // 风险 / 资源 / 趋势：手算聚合 + 审计 + 无 PHI
    // ------------------------------------------------------------------

    @Test
    void riskResourcesAndTrendsMatchHandCalculatedAggregates() throws Exception {
        String token = login(USERNAME, PASSWORD);

        MvcResult riskResult = mockMvc.perform(get("/v1/dashboard/risk")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.dataScope").value("DEPT"))
                .andExpect(jsonPath("$.totalCaseCount").value(3))
                .andExpect(jsonPath("$.assessedCaseCount").value(2))
                .andExpect(jsonPath("$.unassessedCaseCount").value(1))
                .andExpect(jsonPath("$.highRiskCaseCount").value(1))
                .andExpect(jsonPath("$.asaClassCounts.length()").value(2))
                .andExpect(jsonPath("$.asaClassCounts[0].asaClass").value("II"))
                .andExpect(jsonPath("$.asaClassCounts[0].count").value(1))
                .andExpect(jsonPath("$.asaClassCounts[1].asaClass").value("III"))
                .andExpect(jsonPath("$.asaClassCounts[1].count").value(1))
                .andExpect(jsonPath("$.riskLevelCounts.length()").value(2))
                .andExpect(jsonPath("$.riskLevelCounts[0].riskLevel").value("LOW"))
                .andExpect(jsonPath("$.riskLevelCounts[1].riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.riskLevelCounts[1].count").value(1))
                .andExpect(jsonPath("$.severeAlertCount").value(1))
                .andExpect(jsonPath("$.overdueThresholdMinutes").value(0))
                .andExpect(jsonPath("$.dataQuality").value("OK"))
                .andExpect(jsonPath("$.pendingConfirmations[0]").isNotEmpty())
                .andReturn();

        // 超时台次按契约口径独立推算（宽限 0，取消台次不参与；两类并集去重）。
        Overdue expected = expectedOverdue();
        JsonNode risk = objectMapper.readTree(riskResult.getResponse().getContentAsString());
        assertThat(risk.get("delayedStartCount").asInt()).isEqualTo(expected.delayedStart());
        assertThat(risk.get("overrunCaseCount").asInt()).isEqualTo(expected.overrun());
        assertThat(risk.get("overdueCaseCount").asInt()).isEqualTo(expected.union());
        assertThat(risk.get("overdueCaseCount").asInt())
                .isLessThanOrEqualTo(risk.get("totalCaseCount").asInt());

        MvcResult resourcesResult = mockMvc.perform(get("/v1/dashboard/resources")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataScope").value("DEPT"))
                .andExpect(jsonPath("$.roomUtilization.roomCount").value(1))
                .andExpect(jsonPath("$.roomUtilization.surgeryMinutes").value(80))
                .andExpect(jsonPath("$.roomUtilization.availableMinutes").value(600))
                .andExpect(jsonPath("$.roomUtilization.utilizationRate").value(0.1333))
                .andExpect(jsonPath("$.roomStatusCounts.total").value(1))
                .andExpect(jsonPath("$.roomStatusCounts.inSurgery").value(1))
                .andExpect(jsonPath("$.devices.total").value(1))
                .andExpect(jsonPath("$.devices.online").value(1))
                .andExpect(jsonPath("$.devices.offline").value(0))
                .andExpect(jsonPath("$.deviceOnlineRate").value(1.0))
                .andExpect(jsonPath("$.staffOnDuty.total").value(3))
                .andExpect(jsonPath("$.staffOnDuty.byShift.length()").value(2))
                .andExpect(jsonPath("$.staffOnDuty.byShift[0].shift").value("DAY"))
                .andExpect(jsonPath("$.staffOnDuty.byShift[0].count").value(2))
                .andExpect(jsonPath("$.staffOnDuty.byShift[1].shift").value("NIGHT"))
                .andExpect(jsonPath("$.staffOnDuty.byShift[1].count").value(1))
                .andExpect(jsonPath("$.resourceGaps.length()").value(0))
                .andExpect(jsonPath("$.dataQuality").value("OK"))
                .andReturn();

        MvcResult trendsResult = mockMvc.perform(get("/v1/dashboard/trends")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.windowMinutes").value(1440))
                .andExpect(jsonPath("$.bucketMinutes").value(60))
                .andExpect(jsonPath("$.roomCount").value(1))
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.dataScope").value("DEPT"))
                .andReturn();

        JsonNode trends = objectMapper.readTree(trendsResult.getResponse().getContentAsString());
        JsonNode points = trends.get("points");
        assertThat(points.size()).isBetween(24, 25);
        assertThat(OffsetDateTime.parse(points.get(0).get("startsAt").asText()))
                .isBefore(OffsetDateTime.parse(
                        points.get(points.size() - 1).get("startsAt").asText()));
        assertThat(OffsetDateTime.parse(trends.get("to").asText())).isEqualTo(
                OffsetDateTime.parse(trends.get("from").asText()).plusMinutes(1440));
        int casesInWindow = 0;
        for (JsonNode point : points) {
            assertThat(point.get("startsAt").asText()).isNotBlank();
            assertThat(point.get("utilizationRate").asDouble()).isGreaterThanOrEqualTo(0.0D);
            casesInWindow += point.get("caseCount").asInt();
        }
        // 24 小时窗口覆盖当日全部非取消台次（C1/C2/C3 均在当日凌晨）。
        assertThat(casesInWindow).isLessThanOrEqualTo(3);

        // 无 PHI：患者姓名/脱敏住院号/诊断/术前评估自由文本不进入响应。
        for (MvcResult result : java.util.List.of(riskResult, resourcesResult, trendsResult)) {
            String body = result.getResponse().getContentAsString();
            assertThat(body).doesNotContain(PATIENT_NAME, MASKED_INPATIENT, "演示诊断",
                    "patientName", "inpatientNo", "historySummary", "planNotes",
                    "staffId", "deviceId", "acknowledgeReason");
        }

        // 审计 DASHBOARD_VIEW：三个新看板类型各一条，detail 无 PHI。
        java.util.List<String> details = jdbcTemplate.queryForList("""
                SELECT detail::text FROM m0_platform.sys_audit_log
                 WHERE action = 'DASHBOARD_VIEW'
                   AND actor_id = ?::uuid
                 ORDER BY occurred_at
                """, String.class, USER_ID);
        assertThat(String.join("\n", details)).contains("\"kind\": \"risk\"",
                "\"kind\": \"resources\"", "\"kind\": \"trends\"");
        assertThat(String.join("\n", details))
                .doesNotContain(PATIENT_NAME, MASKED_INPATIENT, "演示诊断");
    }

    @Test
    void invalidWindowAndForbiddenScopesAreRejected() throws Exception {
        String token = login(USERNAME, PASSWORD);

        // hours 超出 0.5–24 → 400 PARAM_INVALID，field=hours。
        mockMvc.perform(get("/v1/dashboard/trends")
                        .header("Authorization", "Bearer " + token)
                        .param("hours", "0.4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("hours"));
        mockMvc.perform(get("/v1/dashboard/trends")
                        .header("Authorization", "Bearer " + token)
                        .param("hours", "25"))
                .andExpect(status().isBadRequest());

        // 无 m5:dashboard:read（SYS_SURGEON）→ 三个新端点均 403。
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        for (String path : java.util.List.of("/v1/dashboard/risk", "/v1/dashboard/resources",
                "/v1/dashboard/trends")) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + surgeon))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        }

        // DEPT 角色但无组织绑定 → 范围解析 fail-closed → 403（不返回全院数据）。
        String unbound = login(UNBOUND_USERNAME, PASSWORD);
        for (String path : java.util.List.of("/v1/dashboard/risk", "/v1/dashboard/resources",
                "/v1/dashboard/trends")) {
            mockMvc.perform(get(path).header("Authorization", "Bearer " + unbound))
                    .andExpect(status().isForbidden());
        }

        // 最小窗口 0.5 小时 = 30 分钟可用。
        mockMvc.perform(get("/v1/dashboard/trends")
                        .header("Authorization", "Bearer " + token)
                        .param("hours", "0.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowMinutes").value(30));
    }

    /**
     * 按契约口径独立推算超时台次（宽限 0；取消台次不参与；延迟开始 ∪ 超时未结束）。
     *
     * <p>精确到秒的手算断言在固定时钟单测
     * {@code DashboardRiskResourcesServiceImplTest#riskLayersMatchHandCalculatedBuckets}，
     * 集成用例在此只按时钟推算“当前时刻是否已超过计划时间”，避免用例依赖运行时刻。
     */
    private Overdue expectedOverdue() {
        Instant now = Instant.now();
        int delayedStart = 0;
        int overrun = 0;
        java.util.Set<String> union = new java.util.HashSet<>();
        Object[][] fixtures = {
                {"e1", "PLANNED", local(0, 10), local(0, 40)},
                {"e2", "PLANNED", local(0, 50), local(1, 10)},
                {"e3", "COMPLETED", local(1, 20), local(1, 50)},
                {"e4", "CANCELLED", local(2, 0), local(2, 30)}};
        for (Object[] fixture : fixtures) {
            String suffix = (String) fixture[0];
            String status = (String) fixture[1];
            if ("CANCELLED".equals(status)) {
                continue;
            }
            Instant start = ((OffsetDateTime) fixture[2]).toInstant();
            Instant end = ((OffsetDateTime) fixture[3]).toInstant();
            boolean started = "COMPLETED".equals(status);
            boolean completed = "COMPLETED".equals(status);
            boolean lateStart = start.isBefore(now) && !started;
            boolean lateEnd = end.isBefore(now) && !completed;
            if (lateStart) {
                delayedStart++;
            }
            if (lateEnd) {
                overrun++;
            }
            if (lateStart || lateEnd) {
                union.add(suffix);
            }
        }
        return new Overdue(delayedStart, overrun, union.size());
    }

    /** 超时推算中间值（与契约口径一一对应，测试内独立计算，不调用被测实现）。 */
    private record Overdue(int delayedStart, int overrun, int union) {
    }

    // ------------------------------------------------------------------
    // 固定数据构造与登录
    // ------------------------------------------------------------------

    private void ensureOrg() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_org (id, code, name, org_type, parent_id, path, sort,
                                                 status, deleted)
                VALUES (?::uuid, 'SYN-M5-RISK-DEPT', '风险资源合成科（合成）', 'DEPT', NULL,
                        '/SYN-M5-RISK', 1, 1, false)
                ON CONFLICT (id) DO NOTHING
                """, DEPT_ID);
    }

    private void ensureDashboardUser(String roleId, String userId, String username,
            boolean bound) {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, ?, ?, 'SYNTHETIC：风险资源看板数据范围用例', 'DEPT')
                ON CONFLICT (id) DO NOTHING
                """, roleId, "SYN_" + username.toUpperCase().replace('.', '_'), "风险资源合成角色");
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '风险资源看板账号（合成）', 1, false, 0, 0, false)
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
                VALUES (?::uuid, 'OR-72', '风险资源合成手术间（合成）', 'GENERAL', 'SURGERY',
                        '风险资源合成科（合成）', ?::uuid, ?::uuid, 0, false)
                ON CONFLICT (id) DO NOTHING
                """, ROOM_ID, DEPT_ID, SITE_ID);
    }

    private void insertDevice() {
        jdbcTemplate.update("""
                INSERT INTO m2_anes.device_registry
                    (id, gateway_id, device_id, room_id, device_type, status, last_seen_at, version)
                VALUES ('80000000-0000-4000-8000-00000000e101'::uuid, 'GW-SYN-RISK',
                        'SIM-RISK-01', ?::uuid, 'MONITOR', 'ONLINE', now(), 1)
                ON CONFLICT (id) DO NOTHING
                """, ROOM_ID);
    }

    private void insertAlert() {
        jdbcTemplate.update("""
                INSERT INTO m2_anes.alert
                    (id, record_id, rule_id, rule_code, rule_version, metric, value, unit,
                     severity, triggered_at, dedupe_key, status, version)
                VALUES ('81000000-0000-4000-8000-00000000e101'::uuid,
                        '57000000-0000-4000-8000-00000000e1e3'::uuid, ?::uuid, 'SPO2_LOW', 1,
                        'SPO2', 88.00, '%', 'CRITICAL', now(), 'SYNTH-M5-RISK-1', 'TRIGGERED', 1)
                ON CONFLICT (id) DO NOTHING
                """, RULE_ID);
    }

    private void insertStaffSchedule(String suffix, String shift) {
        jdbcTemplate.update("""
                INSERT INTO m4_staff.staff_schedule
                    (id, staff_id, schedule_date, shift, room_id, department_id, status, note,
                     version)
                VALUES (?::uuid, ?::uuid, ?, ?, NULL, ?::uuid, 'PLANNED', 'SYNTH-M5-RISK', 1)
                ON CONFLICT (id) DO NOTHING
                """, "8c000000-0000-4000-8000-00000000e1" + suffix,
                "8d000000-0000-4000-8000-00000000e1" + suffix, today, shift, DEPT_ID);
    }

    /**
     * 插入一条 SYNTHETIC 台次（时间按本地日期 + 时/分生成，避免固定日期与真实时钟冲突）。
     */
    private void insertCase(String suffix, String status, int startHour, int startMinute,
            int endHour, int endMinute, String recordMode) {
        String patientId = "54000000-0000-4000-8000-00000000e1" + suffix;
        String applicationId = "55000000-0000-4000-8000-00000000e1" + suffix;
        String scheduleId = "56000000-0000-4000-8000-00000000e1" + suffix;
        String recordId = "57000000-0000-4000-8000-00000000e1" + suffix;
        String hisNo = "SYNTH-M5-RISK-" + suffix;
        OffsetDateTime start = local(startHour, startMinute);
        OffsetDateTime end = local(endHour, endMinute);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, ?, 'FEMALE', DATE '1980-03-02', 46, ?, ?, '风险资源合成科（合成）',
                        ?::uuid, ?::uuid, '演示病区', '02', now(), now(), 1, false)
                ON CONFLICT (id) DO NOTHING
                """, patientId, hisNo, PATIENT_NAME, MASKED_INPATIENT, "hash-" + hisNo, DEPT_ID,
                SITE_ID);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, surgery_code, urgency,
                     planned_duration_min, dept_name, dept_id, site_id, surgeon_name,
                     preop_diagnosis, status, source, remark, version, deleted)
                VALUES (?::uuid, ?, ?::uuid, '风险资源合成术式（合成）', 'SYNTH-RISK-001', 'ELECTIVE',
                        30, '风险资源合成科（合成）', ?::uuid, ?::uuid, '演示术者（合成）',
                        '演示诊断（合成）', 'SCHEDULED', 'MANUAL', 'SYNTH-M5-RISK', 1, false)
                ON CONFLICT (id) DO NOTHING
                """, applicationId, hisNo + "-APP", patientId, DEPT_ID, SITE_ID);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority, status,
                     reason, version, deleted, created_at, updated_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 1, 0, ?, 'SYNTH-M5-RISK', 1, false,
                        now(), now())
                ON CONFLICT (id) DO NOTHING
                """, scheduleId, applicationId, ROOM_ID, start, end, status);
        if (recordMode == null) {
            return;
        }
        OffsetDateTime recordStart = local(1, 25);
        OffsetDateTime recordEnd = local(1, 45);
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

    /** 插入已最终化的术前评估（ASA 分级 + 综合风险等级），用于风险分层计数。 */
    private void insertAssessment(String suffix, String asaClass, String riskLevel) {
        String assessmentId = "9a000000-0000-4000-8000-00000000e1" + suffix;
        String applicationId = "55000000-0000-4000-8000-00000000e1" + suffix;
        String patientId = "54000000-0000-4000-8000-00000000e1" + suffix;
        jdbcTemplate.update("""
                INSERT INTO m2_anes.preop_assessment
                    (id, application_id, patient_id, patient_snapshot, history_summary, asa_class,
                     mallampati, airway_details, score_input, rcri_score, apfel_score,
                     stopbang_score, dasi_score, rcri_risk, apfel_risk, stopbang_risk,
                     functional_capacity_low, risk_level, calculator_version, scores, plan_type,
                     status, finalized_at, finalized_by, created_by, updated_by, version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid,
                        '{"synthetic": true}'::jsonb, 'SYNTHETIC 病史要点（合成）', ?, 'I',
                        '{}'::jsonb, '{}'::jsonb, 0, 0, 0, 40.00, 'LOW', 'LOW', 'LOW',
                        false, ?, 'SYNTH-1.0.0', '{}'::jsonb, 'GENERAL', 'FINALIZED',
                        now(), ?::uuid, ?::uuid, ?::uuid, 1, false)
                ON CONFLICT (id) DO NOTHING
                """, assessmentId, applicationId, patientId, asaClass, riskLevel, USER_ID,
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
