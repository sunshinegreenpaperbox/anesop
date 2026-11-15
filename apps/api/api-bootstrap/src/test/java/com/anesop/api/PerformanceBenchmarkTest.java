package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.ClinicalApiFixtures;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M5-QA-01 关键端点性能基准（JUnit 5 + {@link System#nanoTime()}，真实嵌入式 PostgreSQL 16）。
 *
 * <p>目的不是做精确压测，而是给发布准入一条**可重复、可失败**的本机基线：每个端点先预热
 * 1 次，再测量 3 次，断言中位耗时 ≤ 宽松上限（本机实测通常为上限的 1%–10%），并把
 * min/median/max 打印到 surefire stdout 供文档回填。断言放在中位数而非最大值，避免
 * GC/JIT 抖动造成 flaky；上限仍足以拦截“慢一个数量级”的回归（如 N+1、全表扫描）。
 *
 * <p>数据集（全部 SYNTHETIC，{@code SYNTH-M5-QA-PERF-} 前缀，不与其他用例日期交叉）：
 * <ul>
 *   <li>2036-01-01…2036-12-31（闰年，含首尾恰好 366 天）5000 个非取消台次，
 *       每台次 90 分钟麻醉记录单 → 报表 workload 手算 5000 台次 / 450000 分钟；</li>
 *   <li>其中 1 张记录单直接落 300 条时间轴事件（只读时间轴端点）；</li>
 *   <li>3 条可锁单记录（申请 → 台次 → 记录单 → 开始/结束事件 → 三方核查 → 同意签署）；</li>
 *   <li>2 个“今日”台次，使看板 realtime 聚合非空。</li>
 * </ul>
 * 用例结束按前缀清理（append-only 表临时 DISABLE TRIGGER USER 后立即恢复）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceBenchmarkTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int CASE_COUNT = 5000;
    private static final int SLOTS_PER_DAY = 14;
    private static final int TIMELINE_EVENTS = 300;
    private static final int RUNS = 3;

    private static final String PREFIX = "SYNTH-M5-QA-PERF-";
    private static final String PERF_PATIENT_ID = "5a000000-0000-4000-8000-0000000000f1";
    private static final String PERF_DEPT = SyntheticAccounts.DEMO_ANES_ORG_ID;
    private static final String PERF_DEPT_NAME = "性能基准合成科（合成）";
    private static final String PERF_SITE = "f1000000-0000-4000-8000-000000000001";
    private static final String PERF_ROOM = "f2000000-0000-4000-8000-0000000000c1";
    private static final String ADMIN = SyntheticAccounts.DEMO_ADMIN_USERNAME;
    private static final String ADMIN_PASSWORD = SyntheticAccounts.DEMO_ADMIN_PASSWORD;
    private static final String ADMIN_ID = SyntheticAccounts.DEMO_ADMIN_ID;
    private static final LocalDate PERF_FROM = LocalDate.of(2036, 1, 1);
    private static final LocalDate PERF_TO = LocalDate.of(2036, 12, 31);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String token;
    private String timelineRecordId;
    private final List<String> lockableRecordIds = new ArrayList<>();

    @BeforeAll
    void prepareBenchmarkData() throws Exception {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        deleteFixtures();
        ClinicalApiFixtures.insertPatient(jdbcTemplate, PERF_PATIENT_ID, PREFIX + "P-0001",
                PERF_DEPT_NAME, PERF_DEPT, PERF_SITE);
        insertLargeCaseSet();
        insertTodayCases();
        timelineRecordId = recordId(0);
        insertTimelineEvents(timelineRecordId);
        token = ClinicalApiFixtures.login(mockMvc, objectMapper, ADMIN, ADMIN_PASSWORD);
        for (int index = 1; index <= 3; index++) {
            lockableRecordIds.add(ClinicalApiFixtures.prepareLockableRecord(mockMvc, objectMapper,
                    jdbcTemplate, token, PERF_PATIENT_ID, PREFIX + "LOCK-" + index, PERF_ROOM,
                    "2026-12-0" + index + "T08:00:00+08:00",
                    "2026-12-0" + index + "T10:00:00+08:00"));
        }
    }

    @AfterAll
    void cleanBenchmarkData() {
        deleteFixtures();
    }

    // ------------------------------------------------------------------
    // 关键端点基准
    // ------------------------------------------------------------------

    @Test
    void loginBaseline() throws Exception {
        Timing timing = benchmark("login", "单账号 BCrypt 登录", 3000,
                () -> mockMvc.perform(post("/v1/auth/login")
                                .contentType(JSON)
                                .content(ClinicalApiFixtures.write(objectMapper, Map.of(
                                        "username", ADMIN, "password", ADMIN_PASSWORD,
                                        "authType", "LOCAL"))))
                        .andExpect(status().isOk())
                        .andReturn());
        assertThat(timing.medianMs()).isLessThanOrEqualTo(3000);
    }

    @Test
    void applicationListBaseline() throws Exception {
        Timing timing = benchmark("application-list", CASE_COUNT + " 台次 / page=1&size=100", 3000,
                () -> mockMvc.perform(get("/v1/scheduling/applications")
                                .header("Authorization", "Bearer " + token)
                                .param("page", "1").param("size", "100"))
                        .andExpect(status().isOk())
                        .andExpect(result -> {
                            JsonNode body = ClinicalApiFixtures.json(objectMapper, result);
                            assertThat(body.path("meta").path("total").asInt())
                                    .isGreaterThanOrEqualTo(CASE_COUNT);
                            assertThat(body.path("items").size()).isEqualTo(100);
                        })
                        .andReturn());
        assertThat(timing.medianMs()).isLessThanOrEqualTo(3000);
    }

    @Test
    void scheduleConflictQueryBaseline() throws Exception {
        String body = ClinicalApiFixtures.write(objectMapper, Map.of(
                "roomId", PERF_ROOM,
                "plannedStart", "2036-06-01T08:00:00+08:00",
                "plannedEnd", "2036-06-01T09:00:00+08:00"));
        Timing timing = benchmark("schedule-conflict", CASE_COUNT + " 台次 / 单术间 1h 预检", 3000,
                () -> mockMvc.perform(post("/v1/scheduling/schedules/conflicts")
                                .header("Authorization", "Bearer " + token)
                                .contentType(JSON)
                                .content(body))
                        .andExpect(status().isOk())
                        .andExpect(result -> assertThat(ClinicalApiFixtures.json(objectMapper, result)
                                .path("hasConflict").asBoolean()).isTrue())
                        .andReturn());
        assertThat(timing.medianMs()).isLessThanOrEqualTo(3000);
    }

    @Test
    void recordTimelineBaseline() throws Exception {
        Timing timing = benchmark("record-timeline", TIMELINE_EVENTS + " 条事件时间轴", 3000,
                () -> mockMvc.perform(get("/v1/anesthesia/records/" + timelineRecordId + "/events")
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andExpect(result -> assertThat(ClinicalApiFixtures.json(objectMapper, result)
                                .path("items").size()).isEqualTo(TIMELINE_EVENTS))
                        .andReturn());
        assertThat(timing.medianMs()).isLessThanOrEqualTo(3000);
    }

    @Test
    void recordLockBaseline() throws Exception {
        long[] samples = new long[lockableRecordIds.size()];
        int signatureChecks = 0;
        for (String recordId : lockableRecordIds) {
            int version = recordVersion(recordId);
            long start = System.nanoTime();
            MvcResult locked = mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                            .header("Authorization", "Bearer " + token)
                            .contentType(JSON)
                            .content(ClinicalApiFixtures.write(objectMapper, Map.of(
                                    "version", version, "signatureRequested", true,
                                    "confirmChecklist", true))))
                    .andExpect(status().isOk())
                    .andReturn();
            samples[signatureChecks] = System.nanoTime() - start;
            JsonNode body = ClinicalApiFixtures.json(objectMapper, locked);
            assertThat(body.path("status").asText()).isEqualTo("LOCKED");
            assertThat(body.path("signatureHash").asText()).matches("^[0-9a-f]{64}$");
            signatureChecks++;
        }
        Timing timing = printTiming("record-lock",
                "3 条锁单前置完整的记录单（SHA-256 摘要）", samples, 3000);
        assertThat(signatureChecks).isEqualTo(3);
        assertThat(timing.medianMs()).isLessThanOrEqualTo(3000);
    }

    @Test
    void reportWorkloadBaselineOver366Days() throws Exception {
        Timing timing = benchmark("report-workload", CASE_COUNT + " 台次 / 366 天 / DEPT", 5000,
                () -> mockMvc.perform(get("/v1/reports/workload")
                                .header("Authorization", "Bearer " + token)
                                .param("from", PERF_FROM.toString())
                                .param("to", PERF_TO.toString())
                                .param("dimension", "DEPT"))
                        .andExpect(status().isOk())
                        .andExpect(result -> {
                            JsonNode body = ClinicalApiFixtures.json(objectMapper, result);
                            assertThat(body.path("caseCount").asInt()).isEqualTo(CASE_COUNT);
                            assertThat(body.path("anesthesiaMinutes").asLong())
                                    .isEqualTo(90L * CASE_COUNT);
                            assertThat(body.path("unrecordedCaseCount").asInt()).isZero();
                        })
                        .andReturn());
        assertThat(timing.medianMs()).isLessThanOrEqualTo(5000);
    }

    @Test
    void qcIndicatorBaselineOver366Days() throws Exception {
        Timing timing = benchmark("qc-indicator", CASE_COUNT + " 台次 / 366 天 / 7 指标", 5000,
                () -> mockMvc.perform(get("/v1/qc/indicator-results")
                                .header("Authorization", "Bearer " + token)
                                .param("from", PERF_FROM.toString())
                                .param("to", PERF_TO.toString()))
                        .andExpect(status().isOk())
                        .andExpect(result -> {
                            JsonNode body = ClinicalApiFixtures.json(objectMapper, result);
                            assertThat(body.path("items").size()).isEqualTo(7);
                            JsonNode recordRate = null;
                            for (JsonNode item : body.path("items")) {
                                if ("QC-SYN-STRUCT-001".equals(item.path("code").asText())) {
                                    recordRate = item;
                                }
                            }
                            assertThat(recordRate).as("麻醉记录单完成率指标必须存在").isNotNull();
                            assertThat(recordRate.path("numerator").asInt())
                                    .isEqualTo(CASE_COUNT);
                            assertThat(recordRate.path("denominator").asInt())
                                    .isEqualTo(CASE_COUNT);
                        })
                        .andReturn());
        assertThat(timing.medianMs()).isLessThanOrEqualTo(5000);
    }

    @Test
    void dashboardRealtimeBaseline() throws Exception {
        Timing timing = benchmark("dashboard-realtime", "今日 " + 2 + " 台次 + 术间/告警/设备聚合",
                2000,
                () -> mockMvc.perform(get("/v1/dashboard/realtime")
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn());
        assertThat(timing.medianMs()).isLessThanOrEqualTo(2000);
    }

    /**
     * SQL 聚合专项：同一 366 天 / 5000 台次窗口下，报表投影 + 聚合 SQL 本身必须 &lt;5s。
     *
     * <p>与 {@link #reportWorkloadBaselineOver366Days()} 互补：端点测的是“HTTP + 服务层 + 序列化”
     * 全链路，本用例只测数据库聚合，二者同时超限才能定位到 SQL 层。
     */
    @Test
    void sqlAggregationOver5000CasesAnd366DaysStaysUnderFiveSeconds() throws Exception {
        String sql = """
                SELECT a.dept_id,
                       count(*) AS case_count,
                       coalesce(sum(floor(extract(epoch FROM (rec.ended_at - rec.started_at)) / 60)), 0)
                           AS anesthesia_minutes,
                       count(DISTINCT a.surgery_code) AS surgery_kinds
                  FROM m1_sched.schedule s
                  JOIN m1_sched.surgery_application a
                    ON a.id = s.application_id AND a.deleted = false
                  JOIN m1_sched.operating_room r ON r.id = s.room_id
                  LEFT JOIN m2_anes.anesthesia_record rec
                    ON rec.schedule_id = s.id AND rec.deleted = false
                 WHERE s.deleted = false
                   AND s.status <> 'CANCELLED'
                   AND s.start_at >= ?
                   AND s.start_at < ?
                 GROUP BY a.dept_id
                """;
        Timestamp from = Timestamp.from(PERF_FROM.atStartOfDay(ZONE).toInstant());
        Timestamp to = Timestamp.from(PERF_TO.plusDays(1).atStartOfDay(ZONE).toInstant());
        Timing timing = benchmark("sql-aggregate", CASE_COUNT + " 台次 / 366 天 / 按科室聚合", 5000,
                () -> {
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, from, to);
                    assertThat(rows).hasSize(1);
                    assertThat(((Number) rows.get(0).get("case_count")).intValue())
                            .isEqualTo(CASE_COUNT);
                    assertThat(((Number) rows.get(0).get("anesthesia_minutes")).longValue())
                            .isEqualTo(90L * CASE_COUNT);
                });
        assertThat(timing.medianMs()).isLessThanOrEqualTo(5000);
    }

    // ------------------------------------------------------------------
    // 数据集构造
    // ------------------------------------------------------------------

    private void insertLargeCaseSet() {
        List<Object[]> applications = new ArrayList<>(CASE_COUNT);
        List<Object[]> schedules = new ArrayList<>(CASE_COUNT);
        List<Object[]> records = new ArrayList<>(CASE_COUNT);
        for (int index = 0; index < CASE_COUNT; index++) {
            OffsetDateTime start = caseStart(index);
            Timestamp startAt = Timestamp.from(start.toInstant());
            Timestamp endAt = Timestamp.from(start.plusMinutes(90).toInstant());
            String suffix = String.format("%012x", index);
            String applicationId = "5b000000-0000-4000-8000-" + suffix;
            String scheduleId = "5c000000-0000-4000-8000-" + suffix;
            String recordId = "5d000000-0000-4000-8000-" + suffix;
            applications.add(new Object[] {
                applicationId, String.format("%s%04d", PREFIX, index), PERF_PATIENT_ID,
                "演示性能基准术式（合成）", String.format("SYNTH-PERF-%03d", index % 5),
                PERF_DEPT_NAME, PERF_DEPT, PERF_SITE});
            schedules.add(new Object[] {scheduleId, applicationId, PERF_ROOM, startAt, endAt});
            records.add(new Object[] {recordId, scheduleId, applicationId, PERF_PATIENT_ID,
                startAt, endAt, ADMIN_ID, ADMIN_ID});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, surgery_code, urgency,
                     planned_duration_min, dept_name, dept_id, site_id, surgeon_name, status,
                     source, remark, version, deleted)
                VALUES (?::uuid, ?, ?::uuid, ?, ?, 'ELECTIVE', 120, ?, ?::uuid, ?::uuid,
                        '演示术者（合成）', 'SCHEDULED', 'MANUAL', 'SYNTH-M5-QA-PERF', 1, false)
                """, applications);
        jdbcTemplate.batchUpdate("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority, status,
                     reason, version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 1, 0, 'COMPLETED', 'SYNTH-M5-QA-PERF', 1,
                        false)
                """, schedules);
        jdbcTemplate.batchUpdate("""
                INSERT INTO m2_anes.anesthesia_record
                    (id, schedule_id, application_id, patient_id, status, anesthesia_type,
                     started_at, ended_at, created_by, updated_by, version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'ONGOING', 'GENERAL', ?, ?,
                        ?::uuid, ?::uuid, 1, false)
                """, records);
    }

    /** 今日 2 个台次：让看板 realtime 的术间/台次聚合非空。 */
    private void insertTodayCases() {
        LocalDate today = LocalDate.now(ZONE);
        for (int index = 1; index <= 2; index++) {
            String suffix = String.format("%012x", 0xd0 + index);
            String applicationId = "5e000000-0000-4000-8000-" + suffix;
            String scheduleId = "5f000000-0000-4000-8000-" + suffix;
            String hisNo = PREFIX + "TODAY-" + index;
            OffsetDateTime start = today.atTime(LocalTime.of(8 + index, 0)).atZone(ZONE)
                    .toOffsetDateTime();
            jdbcTemplate.update("""
                    INSERT INTO m1_sched.surgery_application
                        (id, his_application_no, patient_id, surgery_name, surgery_code, urgency,
                         planned_duration_min, dept_name, dept_id, site_id, surgeon_name, status,
                         source, remark, version, deleted)
                    VALUES (?::uuid, ?, ?::uuid, '演示性能基准术式（合成）', 'SYNTH-PERF-000',
                            'ELECTIVE', 120, ?, ?::uuid, ?::uuid, '演示术者（合成）',
                            'SCHEDULED', 'MANUAL', 'SYNTH-M5-QA-PERF', 1, false)
                    """, applicationId, hisNo, PERF_PATIENT_ID, PERF_DEPT_NAME, PERF_DEPT,
                    PERF_SITE);
            jdbcTemplate.update("""
                    INSERT INTO m1_sched.schedule
                        (id, application_id, room_id, start_at, end_at, sequence, priority,
                         status, reason, version, deleted)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 1, 0, 'PLANNED', 'SYNTH-M5-QA-PERF',
                            1, false)
                    """, scheduleId, applicationId, PERF_ROOM,
                    Timestamp.from(start.toInstant()),
                    Timestamp.from(start.plusMinutes(90).toInstant()));
        }
    }

    /**
     * 300 条时间轴事件：直接落库（只读端点，不走服务层事件时间校验；append-only 触发器
     * 只拒绝 UPDATE/DELETE/TRUNCATE）。
     */
    private void insertTimelineEvents(String recordId) {
        OffsetDateTime base = caseStart(0).plusSeconds(1);
        List<Object[]> events = new ArrayList<>(TIMELINE_EVENTS);
        for (int seq = 1; seq <= TIMELINE_EVENTS; seq++) {
            events.add(new Object[] {recordId, seq,
                Timestamp.from(base.plusSeconds(seq).toInstant()), ADMIN_ID});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO m2_anes.record_event
                    (id, record_id, seq, event_type, occurred_at, source, payload, created_by)
                VALUES (gen_random_uuid(), ?::uuid, ?, 'CUSTOM', ?, 'MANUAL', '{}'::jsonb,
                        ?::uuid)
                """, events);
    }

    private OffsetDateTime caseStart(int index) {
        int day = index / SLOTS_PER_DAY;
        int slot = index % SLOTS_PER_DAY;
        return PERF_FROM.plusDays(day).atTime(7, 0).plusMinutes(slot * 60L).atZone(ZONE)
                .toOffsetDateTime();
    }

    private static String recordId(int index) {
        return "5d000000-0000-4000-8000-" + String.format("%012x", index);
    }

    private int recordVersion(String recordId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return ClinicalApiFixtures.json(objectMapper, result).path("version").asInt();
    }

    private void deleteFixtures() {
        List<String> triggerTables = List.of("m2_anes.record_event", "m2_anes.anesthesia_record",
                "m2_anes.consent_record", "m2_anes.three_way_check",
                "m1_sched.application_status_history");
        for (String table : triggerTables) {
            jdbcTemplate.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
        }
        try {
            for (String table : triggerTables) {
                if ("m1_sched.application_status_history".equals(table)) {
                    jdbcTemplate.update("""
                            DELETE FROM m1_sched.application_status_history
                             WHERE application_id IN (
                                   SELECT id FROM m1_sched.surgery_application
                                    WHERE his_application_no LIKE ?)
                            """, PREFIX + "%");
                } else if (!"m2_anes.record_event".equals(table)
                        && !"m2_anes.anesthesia_record".equals(table)) {
                    jdbcTemplate.update("DELETE FROM " + table
                            + " WHERE application_id IN (SELECT id FROM m1_sched.surgery_application"
                            + " WHERE his_application_no LIKE ?)", PREFIX + "%");
                }
            }
            jdbcTemplate.update("""
                    DELETE FROM m2_anes.record_event
                     WHERE record_id IN (
                           SELECT id FROM m2_anes.anesthesia_record
                            WHERE application_id IN (
                                  SELECT id FROM m1_sched.surgery_application
                                   WHERE his_application_no LIKE ?))
                    """, PREFIX + "%");
            jdbcTemplate.update("""
                    DELETE FROM m2_anes.anesthesia_record
                     WHERE application_id IN (
                           SELECT id FROM m1_sched.surgery_application
                            WHERE his_application_no LIKE ?)
                    """, PREFIX + "%");
            jdbcTemplate.update("""
                    DELETE FROM m1_sched.schedule
                     WHERE application_id IN (
                           SELECT id FROM m1_sched.surgery_application
                            WHERE his_application_no LIKE ?)
                    """, PREFIX + "%");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.surgery_application WHERE his_application_no LIKE ?",
                    PREFIX + "%");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.patient_snapshot WHERE his_patient_no LIKE ?",
                    PREFIX + "%");
        } finally {
            for (String table : triggerTables) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ENABLE TRIGGER USER");
            }
        }
    }

    // ------------------------------------------------------------------
    // 计时：预热 1 次 + 测量 runs 次，中位数对上限断言
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 单端点耗时样本（毫秒）。 */
    private record Timing(long minMs, long medianMs, long maxMs) {
    }

    private Timing benchmark(String endpoint, String dataset, long limitMs, ThrowingRunnable action)
            throws Exception {
        action.run();
        long[] nanos = new long[RUNS];
        for (int index = 0; index < RUNS; index++) {
            long begin = System.nanoTime();
            action.run();
            nanos[index] = System.nanoTime() - begin;
        }
        return printTiming(endpoint, dataset, nanos, limitMs);
    }

    private Timing printTiming(String endpoint, String dataset, long[] nanos, long limitMs) {
        Arrays.sort(nanos);
        Timing timing = new Timing(nanos[0] / 1_000_000, nanos[nanos.length / 2] / 1_000_000,
                nanos[nanos.length - 1] / 1_000_000);
        System.out.printf("[M5-QA-01][perf] endpoint=%-20s dataset=%-42s runs=%d min=%dms "
                        + "median=%dms max=%dms limit=%dms%n", endpoint, dataset, nanos.length,
                timing.minMs(), timing.medianMs(), timing.maxMs(), limitMs);
        return timing;
    }
}
