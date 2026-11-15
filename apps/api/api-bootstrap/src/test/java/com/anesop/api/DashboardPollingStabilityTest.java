package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.ClinicalApiFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
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
 * M5-QA-01 看板稳定性：10s 轮询的后端必须是无状态读（并发 20 请求 &lt;2s），且不使用 SSE。
 *
 * <p>看板刷新采用 10s 轮询（M5-M18-01 决策：只读聚合 + 无新依赖），前端
 * {@code REFRESH_INTERVAL_MS = 10_000}；后端每次请求独立计算快照，不持有会话级订阅。
 * 本类把这一约束变成可失败断言：
 * <ol>
 *   <li>20 路并发 GET {@code /v1/dashboard/realtime} 全部 200 且总耗时 &lt;2s；</li>
 *   <li>除 {@code updatedAt}/{@code dataDelaySeconds}/{@code dataQuality}（请求时点量）外，
 *       术间/台次/告警/设备聚合在所有并发响应中<b>逐字段一致</b>——后端不因并发累积状态；</li>
 *   <li>看板 4 个 GET 端点均返回 {@code application/json}，响应头与响应体都不出现
 *       {@code text/event-stream}——确认未使用 SSE/长连接（轮询语义）。</li>
 * </ol>
 *
 * <p>数据隔离：专用科室/术间/账号（DEPT 数据范围），因此计数是确定值（1 术间 / 2 台次 /
 * 1 台在线设备），不受其他用例残留的同日台次或设备影响；全部 SYNTHETIC
 * （{@code SYNTH-M5-QA-POLL-} 前缀），用例结束清理。不新增生产代码/契约/迁移。
 */
class DashboardPollingStabilityTest extends AbstractApiIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String PREFIX = "SYNTH-M5-QA-POLL-";
    private static final String DEPT_ID = "f1000000-0000-4000-8000-0000000000e1";
    private static final String SITE_ID = "f1000000-0000-4000-8000-0000000000e2";
    private static final String ROOM_ID = "f2000000-0000-4000-8000-0000000000e1";
    private static final String ROLE_ID = "a0000000-0000-4000-8000-0000000000e1";
    private static final String USER_ID = "e0000000-0000-4000-8000-0000000000e1";
    private static final String USERNAME = "synth.qa.poll.dept";
    private static final String PASSWORD = "Synth@2026";
    private static final String ROOM_NO = "OR-QA-POLL";
    private static final String PATIENT_ID = "54000000-0000-4000-8000-0000000000e1";
    private static final String GATEWAY_ID = "GW-SYN-QA-POLL";
    private static final String DEVICE_ID = "SIM-QA-POLL-01";
    /** V26 种子：m5:dashboard:read 权限标识。 */
    private static final String DASHBOARD_PERMISSION_ID = "b0000000-0000-4000-8000-00000000007d";
    private static final int POLL_THREADS = 20;

    private static final List<String> DASHBOARD_PATHS = List.of("/v1/dashboard/realtime",
            "/v1/dashboard/risk", "/v1/dashboard/resources", "/v1/dashboard/trends");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String token;

    @BeforeEach
    void prepareData() throws Exception {
        deleteFixtures();
        ensureDepartmentScopeUser();
        insertRoom();
        ClinicalApiFixtures.insertPatient(jdbcTemplate, PATIENT_ID, PREFIX + "P-0001",
                "轮询合成科（合成）", DEPT_ID, SITE_ID);
        insertTodayCase("01", "PLANNED", 8, 0);
        insertTodayCase("02", "COMPLETED", 10, 0);
        insertDevice();
        token = ClinicalApiFixtures.login(mockMvc, objectMapper, USERNAME, PASSWORD);
    }

    @AfterEach
    void cleanUpAfterTest() {
        deleteFixtures();
    }

    @Test
    void concurrentPollingIsStatelessAndFast() throws Exception {
        long begin = System.nanoTime();
        List<PollSample> samples = ClinicalApiFixtures.runConcurrently(POLL_THREADS, ordinal -> {
            MvcResult result = mockMvc.perform(get("/v1/dashboard/realtime")
                            .header("Authorization", "Bearer " + token))
                    .andReturn();
            return new PollSample(result.getResponse().getStatus(),
                    result.getResponse().getContentType(),
                    result.getResponse().getContentAsString());
        });
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertThat(samples).extracting(PollSample::status).containsOnly(200);
        assertThat(samples).extracting(PollSample::contentType)
                .allSatisfy(contentType -> assertThat(contentType)
                        .contains(MediaType.APPLICATION_JSON_VALUE)
                        .doesNotContain("event-stream"));

        JsonNode first = objectMapper.readTree(samples.get(0).body());
        for (PollSample sample : samples) {
            JsonNode body = objectMapper.readTree(sample.body());
            // 请求时点量（updatedAt/dataDelaySeconds/dataQuality）可以不同；聚合结果是纯读，
            // 必须逐字段一致——否则说明后端在并发下持有了会话级状态。
            assertThat(body.path("dataScope").asText()).isEqualTo("DEPT");
            assertThat(body.path("roomStatusCounts")).isEqualTo(first.path("roomStatusCounts"));
            assertThat(body.path("caseProgress")).isEqualTo(first.path("caseProgress"));
            assertThat(body.path("alerts")).isEqualTo(first.path("alerts"));
            assertThat(body.path("devices")).isEqualTo(first.path("devices"));
            assertThat(body.path("rooms")).isEqualTo(first.path("rooms"));
        }
        // 专用 DEPT 数据：确定值而非“>=1”，避免断言退化为恒真。
        assertThat(first.path("roomStatusCounts").path("total").asInt()).isEqualTo(1);
        assertThat(first.path("rooms").size()).isEqualTo(1);
        assertThat(first.path("rooms").get(0).path("roomNo").asText()).isEqualTo(ROOM_NO);
        assertThat(first.path("caseProgress").path("totalCount").asInt()).isEqualTo(2);
        assertThat(first.path("alerts").path("unacknowledgedCount").asInt()).isZero();
        assertThat(first.path("devices").path("total").asInt()).isEqualTo(1);
        assertThat(first.path("devices").path("online").asInt()).isEqualTo(1);
        assertThat(first.path("devices").path("offline").asInt()).isZero();
        assertThat(elapsedMs).isLessThan(2000);

        System.out.printf("[M5-QA-01][stability] dashboard realtime threads=%d all200=true "
                        + "aggregatesIdentical=true rooms=1 cases=2 devices=1 elapsed=%dms "
                        + "limit=2000ms sse=false%n", POLL_THREADS, elapsedMs);
    }

    @Test
    void dashboardEndpointsAreJsonAndDoNotUseSse() throws Exception {
        for (String path : DASHBOARD_PATHS) {
            MvcResult result = mockMvc.perform(get(path)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            assertThat(result.getResponse().getContentType())
                    .as("%s 必须是普通 JSON 轮询响应（不是 SSE 长连接）", path)
                    .contains(MediaType.APPLICATION_JSON_VALUE)
                    .doesNotContain("event-stream");
            assertThat(result.getResponse().getHeader("Content-Type"))
                    .doesNotContain("event-stream");
            assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).isObject())
                    .isTrue();
        }
        System.out.printf("[M5-QA-01][stability] dashboard endpoints=%d contentType=json "
                + "sse=false%n", DASHBOARD_PATHS.size());
    }

    // ------------------------------------------------------------------
    // 专用科室/术间/账号/设备（DEPT 数据范围，计数确定）
    // ------------------------------------------------------------------

    private void ensureDepartmentScopeUser() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_org (id, code, name, org_type, parent_id, path, sort,
                                                 status, deleted)
                VALUES (?::uuid, 'SYN-M5-QA-POLL-DEPT', '轮询合成科（合成）', 'DEPT', NULL,
                        '/SYN-M5-QA-POLL', 1, 1, false)
                ON CONFLICT (id) DO NOTHING
                """, DEPT_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_M5_QA_POLL', '轮询专项角色（合成）',
                        'SYNTHETIC：M5-QA-01 轮询稳定性用例', 'DEPT')
                ON CONFLICT (id) DO NOTHING
                """, ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '轮询专项账号（合成）', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, USER_ID, USERNAME, new BCryptPasswordEncoder().encode(PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, USER_ID, ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, ROLE_ID, DASHBOARD_PERMISSION_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_org (user_id, org_id, is_primary)
                VALUES (?::uuid, ?::uuid, true) ON CONFLICT DO NOTHING
                """, USER_ID, DEPT_ID);
    }

    private void insertRoom() {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.operating_room
                    (id, room_no, name, room_type, status, dept_name, dept_id, site_id, version,
                     deleted)
                VALUES (?::uuid, ?, '轮询合成手术间（合成）', 'GENERAL', 'SURGERY',
                        '轮询合成科（合成）', ?::uuid, ?::uuid, 0, false)
                ON CONFLICT (id) DO NOTHING
                """, ROOM_ID, ROOM_NO, DEPT_ID, SITE_ID);
    }

    private void insertTodayCase(String suffix, String status, int hour, int minute) {
        String idSuffix = String.format("%012x", 0xe0 + Integer.parseInt(suffix));
        String applicationId = "5b000000-0000-4000-8000-" + idSuffix;
        String scheduleId = "5c000000-0000-4000-8000-" + idSuffix;
        String hisNo = PREFIX + suffix;
        LocalDate today = LocalDate.now(ZONE);
        OffsetDateTime start = today.atTime(hour, minute).atZone(ZONE).toOffsetDateTime();
        jdbcTemplate.update("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, surgery_code, urgency,
                     planned_duration_min, dept_name, dept_id, site_id, surgeon_name, status,
                     source, remark, version, deleted)
                VALUES (?::uuid, ?, ?::uuid, '轮询合成术式（合成）', 'SYNTH-POLL-001', 'ELECTIVE',
                        120, '轮询合成科（合成）', ?::uuid, ?::uuid, '演示术者（合成）',
                        'SCHEDULED', 'MANUAL', 'SYNTH-M5-QA-POLL', 1, false)
                """, applicationId, hisNo + "-APP", PATIENT_ID, DEPT_ID, SITE_ID);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority, status,
                     reason, version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 1, 0, ?, 'SYNTH-M5-QA-POLL', 1, false)
                """, scheduleId, applicationId, ROOM_ID, Timestamp.from(start.toInstant()),
                Timestamp.from(start.plusMinutes(120).toInstant()), status);
    }

    private void insertDevice() {
        jdbcTemplate.update("""
                INSERT INTO m2_anes.device_registry
                    (id, gateway_id, device_id, room_id, device_type, status, last_seen_at,
                     version)
                VALUES ('80000000-0000-4000-8000-0000000000e1'::uuid, ?, ?, ?::uuid, 'MONITOR',
                        'ONLINE', now(), 1)
                ON CONFLICT (id) DO NOTHING
                """, GATEWAY_ID, DEVICE_ID, ROOM_ID);
    }

    private void deleteFixtures() {
        jdbcTemplate.update("DELETE FROM m2_anes.device_registry WHERE gateway_id = ?",
                GATEWAY_ID);
        jdbcTemplate.update("""
                DELETE FROM m1_sched.schedule
                 WHERE application_id IN (
                       SELECT id FROM m1_sched.surgery_application
                        WHERE his_application_no LIKE ?)
                """, PREFIX + "%");
        jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history "
                + "DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("""
                    DELETE FROM m1_sched.application_status_history
                     WHERE application_id IN (
                           SELECT id FROM m1_sched.surgery_application
                            WHERE his_application_no LIKE ?)
                    """, PREFIX + "%");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history "
                    + "ENABLE TRIGGER USER");
        }
        jdbcTemplate.update(
                "DELETE FROM m1_sched.surgery_application WHERE his_application_no LIKE ?",
                PREFIX + "%");
        jdbcTemplate.update("DELETE FROM m1_sched.patient_snapshot WHERE his_patient_no LIKE ?",
                PREFIX + "%");
        jdbcTemplate.update("DELETE FROM m1_sched.operating_room WHERE id = ?::uuid", ROOM_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_org WHERE user_id = ?::uuid",
                USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_role WHERE role_id = ?::uuid",
                ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_role_permission WHERE role_id = ?::uuid",
                ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user WHERE id = ?::uuid", USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_role WHERE id = ?::uuid", ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_org WHERE id = ?::uuid", DEPT_ID);
    }

    private record PollSample(int status, String contentType, String body) {
    }
}
