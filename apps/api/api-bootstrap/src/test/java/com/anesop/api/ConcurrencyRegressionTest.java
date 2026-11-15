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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M5-QA-01 并发回归（CyclicBarrier + ExecutorService，真实嵌入式 PostgreSQL 16 + 真实 API）。
 *
 * <p>四组并发各 1 条，断言“无 500、无数据错乱”，而不是“并发能跑通”：
 * <ol>
 *   <li>并发登录/刷新：8 个并发登录全部 200 且令牌互不相同；同一刷新令牌 8 路并发，
 *       <b>恰好一个</b> 200、其余 401（轮换后旧令牌立即失效），无 500；</li>
 *   <li>并发锁单：同一记录单同 version 两路并发，<b>恰好一个</b> 200、一个 409，
 *       库内只落一份锁单摘要（64 位 hex），不重复锁、不覆盖；</li>
 *   <li>并发事件 seq：两路并发追加，服务端行锁串行化后 seq 连续且不重（3、4），
 *       时间轴按 occurredAt/seq 稳定返回且 occurredAt 非降序；</li>
 *   <li>并发报表：4 路并发 workload 查询返回完全一致的手算聚合（3 台次 / 210 分钟），
 *       每次查询各写一条 REPORT_QUERY 审计，不因并发产生重复计数或数据错乱。</li>
 * </ol>
 *
 * <p>报表固定窗口 2033-08（手算：R1 60 + R2 120 + R3 30 = 210 分钟，R4 已取消、
 * R5 区间外均不计），全部 SYNTHETIC（{@code SYNTH-M5-QA-CONC-} 前缀），用例结束清理。
 */
class ConcurrencyRegressionTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private static final String PREFIX = "SYNTH-M5-QA-CONC-";
    private static final String PATIENT_ID = "5a000000-0000-4000-8000-0000000000a1";
    private static final String ROOM_ID = "f2000000-0000-4000-8000-0000000000c1";
    private static final String DEPT_ANES = SyntheticAccounts.DEMO_ANES_ORG_ID;
    private static final String DEPT_OR = SyntheticAccounts.DEMO_OR_ORG_ID;
    private static final String SITE = "f1000000-0000-4000-8000-000000000001";
    private static final String REPORT_FROM = "2033-08-01";
    private static final String REPORT_TO = "2033-08-31";
    private static final int LOGIN_THREADS = 8;
    private static final int REFRESH_THREADS = 8;
    private static final int REPORT_THREADS = 4;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareData() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        deleteFixtures();
        ClinicalApiFixtures.insertPatient(jdbcTemplate, PATIENT_ID, PREFIX + "P-0001",
                "并发回归合成科（合成）", DEPT_ANES, SITE);
        insertReportCase("01", DEPT_ANES, "麻醉科（合成）", "2033-08-02T08:00:00+08:00",
                "2033-08-02T09:00:00+08:00", "COMPLETED", 60);
        insertReportCase("02", DEPT_ANES, "麻醉科（合成）", "2033-08-03T08:00:00+08:00",
                "2033-08-03T10:00:00+08:00", "COMPLETED", 120);
        insertReportCase("03", DEPT_OR, "手术室（合成）", "2033-08-04T08:00:00+08:00",
                "2033-08-04T08:30:00+08:00", "COMPLETED", 30);
        insertReportCase("04", DEPT_ANES, "麻醉科（合成）", "2033-08-05T08:00:00+08:00",
                "2033-08-05T09:00:00+08:00", "CANCELLED", 0);
        insertReportCase("05", DEPT_ANES, "麻醉科（合成）", "2033-07-30T08:00:00+08:00",
                "2033-07-30T09:00:00+08:00", "COMPLETED", 60);
    }

    @AfterEach
    void cleanUpAfterTest() {
        deleteFixtures();
    }

    // ------------------------------------------------------------------
    // 1. 并发登录 / 刷新
    // ------------------------------------------------------------------

    @Test
    void concurrentLoginAndRefreshIssueExactlyOneRotatedToken() throws Exception {
        List<LoginSample> logins = ClinicalApiFixtures.runConcurrently(LOGIN_THREADS, ordinal -> {
            MvcResult result = mockMvc.perform(post("/v1/auth/login")
                            .contentType(JSON)
                            .content(ClinicalApiFixtures.write(objectMapper, Map.of(
                                    "username", SyntheticAccounts.DEMO_ADMIN_USERNAME,
                                    "password", SyntheticAccounts.DEMO_ADMIN_PASSWORD,
                                    "authType", "LOCAL"))))
                    .andReturn();
            JsonNode body = result.getResponse().getStatus() == 200
                    ? ClinicalApiFixtures.json(objectMapper, result) : objectMapper.createObjectNode();
            return new LoginSample(result.getResponse().getStatus(),
                    body.path("accessToken").asText(), body.path("refreshToken").asText());
        });
        assertThat(logins).extracting(LoginSample::status).containsOnly(200);
        assertThat(logins).extracting(LoginSample::accessToken).doesNotContain("")
                .doesNotHaveDuplicates().hasSize(LOGIN_THREADS);
        assertThat(logins).extracting(LoginSample::refreshToken).doesNotHaveDuplicates()
                .hasSize(LOGIN_THREADS);

        // 同一刷新令牌 8 路并发：条件撤销保证恰一个成功，其余 401（旧令牌已失效），不允许 500。
        String sharedRefresh = logins.get(0).refreshToken();
        List<RefreshSample> refreshes = ClinicalApiFixtures.runConcurrently(REFRESH_THREADS,
                ordinal -> {
                    MvcResult result = mockMvc.perform(post("/v1/auth/refresh")
                                    .contentType(JSON)
                                    .content(ClinicalApiFixtures.write(objectMapper,
                                            Map.of("refreshToken", sharedRefresh))))
                            .andReturn();
                    String refreshedAccess = result.getResponse().getStatus() == 200
                            ? ClinicalApiFixtures.json(objectMapper, result)
                                    .path("accessToken").asText() : "";
                    return new RefreshSample(result.getResponse().getStatus(), refreshedAccess);
                });
        List<Integer> statuses = refreshes.stream().map(RefreshSample::status).toList();
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 401).hasSize(REFRESH_THREADS - 1);
        assertThat(statuses).doesNotContain(500);

        String rotatedAccess = refreshes.stream().filter(item -> item.status() == 200)
                .findFirst().orElseThrow().accessToken();
        assertThat(rotatedAccess).isNotBlank();
        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + rotatedAccess))
                .andExpect(status().isOk());

        System.out.printf("[M5-QA-01][concurrent] login threads=%d all200=true "
                        + "distinctAccess=%d distinctRefresh=%d refresh threads=%d "
                        + "200=%d 401=%d 500=0%n",
                LOGIN_THREADS, LOGIN_THREADS, LOGIN_THREADS, REFRESH_THREADS,
                statuses.stream().filter(item -> item == 200).count(),
                statuses.stream().filter(item -> item == 401).count());
    }

    // ------------------------------------------------------------------
    // 2. 并发锁单：恰好一个成功
    // ------------------------------------------------------------------

    @Test
    void concurrentLockHasExactlyOneWinner() throws Exception {
        String token = ClinicalApiFixtures.login(mockMvc, objectMapper,
                SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String recordId = ClinicalApiFixtures.prepareLockableRecord(mockMvc, objectMapper,
                jdbcTemplate, token, PATIENT_ID, PREFIX + "LOCK-1", ROOM_ID,
                "2033-08-10T08:00:00+08:00", "2033-08-10T10:00:00+08:00");
        int version = recordVersion(recordId, token);

        List<LockSample> outcomes = ClinicalApiFixtures.runConcurrently(2, ordinal -> {
            MvcResult result = mockMvc.perform(post(
                            "/v1/anesthesia/records/" + recordId + "/lock")
                            .header("Authorization", "Bearer " + token)
                            .contentType(JSON)
                            .content(ClinicalApiFixtures.write(objectMapper, Map.of(
                                    "version", version, "signatureRequested", true,
                                    "confirmChecklist", true))))
                    .andReturn();
            String code = result.getResponse().getStatus() == 200 ? ""
                    : ClinicalApiFixtures.json(objectMapper, result).path("code").asText();
            return new LockSample(result.getResponse().getStatus(), code);
        });
        assertThat(outcomes).extracting(LockSample::status)
                .containsExactlyInAnyOrder(200, 409);
        assertThat(outcomes.stream().filter(item -> item.status() == 409)
                .map(LockSample::code)).containsExactly("STATE_CONFLICT");

        Map<String, Object> locked = jdbcTemplate.queryForMap("""
                SELECT status, locked_at, lock_signature_hash, current_revision
                  FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, recordId);
        assertThat(locked.get("status")).isEqualTo("LOCKED");
        assertThat(locked.get("locked_at")).isNotNull();
        assertThat((String) locked.get("lock_signature_hash")).matches("^[0-9a-f]{64}$");
        assertThat(((Number) locked.get("current_revision")).intValue()).isZero();
        // 锁单是单向状态：失败的一路不得把记录改回可写。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(ClinicalApiFixtures.write(objectMapper, Map.of(
                                "eventType", "CUSTOM",
                                "occurredAt", OffsetDateTime.now(OFFSET)
                                        .truncatedTo(ChronoUnit.SECONDS).plusSeconds(5)
                                        .toString()))))
                .andExpect(status().isConflict());

        System.out.printf("[M5-QA-01][concurrent] lock record=%s threads=2 200=1 409=1 "
                        + "status=LOCKED signatureHash64=true postLockWrite=409%n",
                recordId);
    }

    // ------------------------------------------------------------------
    // 3. 并发事件 seq：行锁串行化，不重不丢
    // ------------------------------------------------------------------

    @Test
    void concurrentEventAppendsSerializeSequence() throws Exception {
        String token = ClinicalApiFixtures.login(mockMvc, objectMapper,
                SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String recordId = ClinicalApiFixtures.prepareLockableRecord(mockMvc, objectMapper,
                jdbcTemplate, token, PATIENT_ID, PREFIX + "SEQ-1", ROOM_ID,
                "2033-08-11T08:00:00+08:00", "2033-08-11T10:00:00+08:00");
        OffsetDateTime base = OffsetDateTime.now(OFFSET).truncatedTo(ChronoUnit.MILLIS)
                .plusSeconds(30);

        List<Integer> seqs = ClinicalApiFixtures.runConcurrently(2, ordinal ->
                ClinicalApiFixtures.appendEvent(mockMvc, objectMapper, token, recordId,
                        "CUSTOM", base.plusSeconds(30 + ordinal), "并发回归补录（合成）"));
        assertThat(seqs).containsExactlyInAnyOrder(3, 4);

        MvcResult events = mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = ClinicalApiFixtures.json(objectMapper, events).path("items");
        assertThat(items.size()).isEqualTo(4);
        assertThat(items).extracting(node -> node.path("seq").asInt())
                .containsExactlyInAnyOrder(1, 2, 3, 4);
        String previousOccurredAt = "";
        for (JsonNode item : items) {
            String occurredAt = item.path("occurredAt").asText();
            assertThat(occurredAt.compareTo(previousOccurredAt)).isGreaterThanOrEqualTo(0);
            previousOccurredAt = occurredAt;
        }
        assertThat(jdbcTemplate.queryForList("""
                SELECT seq FROM m2_anes.record_event WHERE record_id = ?::uuid ORDER BY seq
                """, Integer.class, recordId)).containsExactly(1, 2, 3, 4);

        System.out.printf("[M5-QA-01][concurrent] events record=%s threads=2 seqs=%s "
                        + "unique=true timelineOrderedByOccurredAt=true%n", recordId, seqs);
    }

    // ------------------------------------------------------------------
    // 4. 并发报表：幂等、无重复计数
    // ------------------------------------------------------------------

    @Test
    void concurrentReportsStayIdempotent() throws Exception {
        String token = ClinicalApiFixtures.login(mockMvc, objectMapper,
                SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        List<JsonNode> reports = ClinicalApiFixtures.runConcurrently(REPORT_THREADS, ordinal -> {
            MvcResult result = mockMvc.perform(get("/v1/reports/workload")
                            .header("Authorization", "Bearer " + token)
                            .param("from", REPORT_FROM).param("to", REPORT_TO)
                            .param("dimension", "DEPT"))
                    .andExpect(status().isOk())
                    .andReturn();
            return ClinicalApiFixtures.json(objectMapper, result);
        });

        for (JsonNode report : reports) {
            assertThat(report.path("caseCount").asInt()).isEqualTo(3);
            assertThat(report.path("anesthesiaMinutes").asInt()).isEqualTo(210);
            assertThat(report.path("weightedCaseCount").decimalValue())
                    .isEqualByComparingTo("3.0");
            assertThat(report.path("unrecordedCaseCount").asInt()).isZero();
            JsonNode anesBucket = bucket(report, DEPT_ANES);
            assertThat(anesBucket.path("caseCount").asInt()).isEqualTo(2);
            assertThat(anesBucket.path("anesthesiaMinutes").asInt()).isEqualTo(180);
            JsonNode orBucket = bucket(report, DEPT_OR);
            assertThat(orBucket.path("caseCount").asInt()).isEqualTo(1);
            assertThat(orBucket.path("anesthesiaMinutes").asInt()).isEqualTo(30);
        }
        assertThat(reports).allSatisfy(report -> assertThat(report.path("caseCount").asInt())
                .isEqualTo(reports.get(0).path("caseCount").asInt()));

        Integer audits = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log WHERE action = 'REPORT_QUERY'
                """, Integer.class);
        assertThat(audits).isGreaterThanOrEqualTo(REPORT_THREADS);

        System.out.printf("[M5-QA-01][concurrent] report threads=%d caseCount=3 "
                        + "anesthesiaMinutes=210 identical=true REPORT_QUERY audits>=%d%n",
                REPORT_THREADS, REPORT_THREADS);
    }

    // ------------------------------------------------------------------
    // 固定报表数据与清理
    // ------------------------------------------------------------------

    private void insertReportCase(String suffix, String deptId, String deptName, String startAt,
            String endAt, String status, int minutes) {
        String applicationId = "5b000000-0000-4000-8000-0000000000" + suffix;
        String scheduleId = "5c000000-0000-4000-8000-0000000000" + suffix;
        String recordId = "5d000000-0000-4000-8000-0000000000" + suffix;
        String hisNo = PREFIX + suffix;
        OffsetDateTime start = OffsetDateTime.parse(startAt);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, surgery_code, urgency,
                     planned_duration_min, dept_name, dept_id, site_id, surgeon_name, status,
                     source, remark, version, deleted)
                VALUES (?::uuid, ?, ?::uuid, '并发回归术式（合成）', 'SYNTH-CONC-001', 'ELECTIVE',
                        120, ?, ?::uuid, ?::uuid, '演示术者（合成）', 'SCHEDULED', 'MANUAL',
                        'SYNTH-M5-QA-CONC', 1, false)
                """, applicationId, hisNo + "-APP", PATIENT_ID, deptName, deptId, SITE);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority, status,
                     reason, version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 1, 0, ?, 'SYNTH-M5-QA-CONC', 1, false)
                """, scheduleId, applicationId, ROOM_ID, Timestamp.from(start.toInstant()),
                Timestamp.from(OffsetDateTime.parse(endAt).toInstant()), status);
        if (minutes > 0) {
            jdbcTemplate.update("""
                    INSERT INTO m2_anes.anesthesia_record
                        (id, schedule_id, application_id, patient_id, status, anesthesia_type,
                         started_at, ended_at, created_by, updated_by, version, deleted)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'ONGOING', 'GENERAL', ?,
                            ?, ?::uuid, ?::uuid, 1, false)
                    """, recordId, scheduleId, applicationId, PATIENT_ID,
                    Timestamp.from(start.toInstant()),
                    Timestamp.from(start.plusMinutes(minutes).toInstant()),
                    SyntheticAccounts.DEMO_ADMIN_ID, SyntheticAccounts.DEMO_ADMIN_ID);
        }
    }

    private int recordVersion(String recordId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return ClinicalApiFixtures.json(objectMapper, result).path("version").asInt();
    }

    private static JsonNode bucket(JsonNode report, String key) {
        for (JsonNode bucket : report.path("buckets")) {
            if (key.equals(bucket.path("key").asText())) {
                return bucket;
            }
        }
        throw new AssertionError("报表缺少维度桶 " + key);
    }

    private void deleteFixtures() {
        List<String> triggerTables = List.of("m2_anes.record_event", "m2_anes.anesthesia_record",
                "m2_anes.consent_record", "m2_anes.three_way_check",
                "m1_sched.application_status_history");
        for (String table : triggerTables) {
            jdbcTemplate.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
        }
        try {
            for (String table : List.of("m2_anes.consent_record", "m2_anes.three_way_check")) {
                jdbcTemplate.update("DELETE FROM " + table
                        + " WHERE application_id IN (SELECT id FROM m1_sched.surgery_application"
                        + " WHERE his_application_no LIKE ?)", PREFIX + "%");
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
                    DELETE FROM m1_sched.application_status_history
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
    // 并发样本载体
    // ------------------------------------------------------------------

    private record LoginSample(int status, String accessToken, String refreshToken) {
    }

    private record RefreshSample(int status, String accessToken) {
    }

    private record LockSample(int status, String code) {
    }
}
