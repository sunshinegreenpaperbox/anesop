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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 临床记录发布准入聚合用例（M3-QA-01）：MockMvc + 真实嵌入式 PostgreSQL 16 + Flyway。
 *
 * <p>把发布准入矩阵收敛为两条跨域端到端流程，而不是重复既有类里的边界枚举：
 *
 * <ol>
 *   <li>时间轴一致性（seq 连续、occurredAt 有序、append-only 55000）＋断网补传幂等
 *       （同一 batchKey 重放不重复行、质量标记保留、新批次补齐）＋并发修改
 *       （并发事件 seq、并发 PUT 乐观锁 409）；</li>
 *   <li>签名锁定（锁单后 API 409 / 数据库直写 55000、锁单摘要稳定）＋并发修订
 *       revision_no 连续且版本链 previousSignatureHash 逐版衔接。</li>
 * </ol>
 *
 * <p>更细的边界（重复开始事件、未来时间、麻精双人核对、ACL/数据范围、V13/V14/V17 结构）
 * 仍由 {@code AnesthesiaRecordApiIntegrationTest}、{@code DeviceAcquisitionApiIntegrationTest}、
 * {@code RecordLockRevisionApiIntegrationTest} 覆盖；本类只做发布前的跨域聚合回归。
 * 全部数据为 SYNTHETIC（设备标识 ACCEPT-*，批次键含随机 UUID）。
 */
class ClinicalRecordAcceptanceTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String PATIENT_ID = "54000000-0000-4000-8000-0000000000a1";
    private static final String HIS_PATIENT_NO = "SYNTH-ACCEPT-P-0001";
    private static final String OR_01 = "f2000000-0000-4000-8000-0000000000c1";
    private static final String GENERAL_CONSENT_TEMPLATE_ID = "b1000000-0000-4000-8000-000000000002";
    private static final int GENERAL_CONSENT_TEMPLATE_VERSION = 2;
    private static final String GATEWAY = "GW-ACCEPT-01";
    private static final String DEVICE = "ACCEPT-MON-01";

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
        clearClinicalRows();
        jdbcTemplate.update("DELETE FROM m2_anes.device_ingest_log");
        jdbcTemplate.update("DELETE FROM m2_anes.device_registry");
        jdbcTemplate.update("DELETE FROM m2_anes.anesthesia_vital");
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
        jdbcTemplate.update("DELETE FROM m2_anes.device_ingest_log");
        jdbcTemplate.update("DELETE FROM m2_anes.device_registry");
        jdbcTemplate.update("DELETE FROM m2_anes.anesthesia_vital");
    }

    // ------------------------------------------------ 时间轴/补传/并发修改 --

    @Test
    void timelineIngestReplayAndConcurrentMutationsStayConsistent() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ACCEPT-TIMELINE");
        String recordId = createRecord(admin, scheduleId);
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .truncatedTo(ChronoUnit.MILLIS).plusSeconds(30);

        // 时间轴起点。
        appendEvent(admin, recordId, "ANESTHESIA_START", base)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(1));

        // 并发追加两个 CUSTOM 事件：行锁串行化 seq，不得唯一键冲突/500。
        List<Integer> concurrentSeqs = runConcurrently(2, ordinal -> {
            MvcResult result = appendEvent(admin, recordId, "CUSTOM", base.plusSeconds(30))
                    .andExpect(status().isCreated())
                    .andReturn();
            return json(result).get("seq").asInt();
        });
        assertThat(concurrentSeqs).containsExactlyInAnyOrder(2, 3);

        MvcResult eventsResult = mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/events")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].seq").value(1))
                .andExpect(jsonPath("$.items[1].seq").value(2))
                .andExpect(jsonPath("$.items[2].seq").value(3))
                .andReturn();
        JsonNode events = json(eventsResult).path("items");
        Instant previousOccurredAt = null;
        for (JsonNode event : events) {
            Instant occurredAt = Instant.parse(event.path("occurredAt").asText());
            if (previousOccurredAt != null) {
                assertThat(occurredAt).isAfterOrEqualTo(previousOccurredAt);
            }
            previousOccurredAt = occurredAt;
        }
        assertThat(jdbcTemplate.queryForList("""
                SELECT seq FROM m2_anes.record_event WHERE record_id = ?::uuid ORDER BY seq
                """, Integer.class, recordId)).containsExactly(1, 2, 3);

        // append-only：事件与记录单内容均不可改写/物理删除（数据库第二道防线，55000）。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.record_event SET event_type = 'CUSTOM' WHERE record_id = ?::uuid",
                recordId)), "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.record_event WHERE record_id = ?::uuid", recordId)),
                "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.anesthesia_record SET updated_at = now() WHERE id = ?::uuid",
                recordId)), "version");

        // 设备登记到记录单同一术间；补传两帧，其中一帧带质量标记。
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(Map.of("gatewayId", GATEWAY, "deviceId", DEVICE,
                                "roomId", OR_01, "deviceType", "MONITOR"))))
                .andExpect(status().isCreated());

        String batchKey = "BATCH-ACCEPT-" + UUID.randomUUID();
        Instant measuredAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String firstBatch = ingestBody(batchKey, List.of(
                frame(1, measuredAt, 76, 0, List.of()),
                frame(2, measuredAt.plusSeconds(1), 132, 1, List.of("hr:RANGE"))));

        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", "synth-test-gateway-token")
                        .contentType(JSON)
                        .content(firstBatch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedRows").value(2))
                .andExpect(jsonPath("$.duplicateRows").value(0))
                .andExpect(jsonPath("$.duplicateBatch").value(false));

        // 断网恢复后网关重放同一 batchKey：整批判重，不新增行、不重复计数。
        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", "synth-test-gateway-token")
                        .contentType(JSON)
                        .content(firstBatch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedRows").value(0))
                .andExpect(jsonPath("$.duplicateRows").value(2))
                .andExpect(jsonPath("$.duplicateBatch").value(true));
        assertThat(vitalCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT quality FROM m2_anes.anesthesia_vital
                 WHERE gateway_id = ? AND device_id = ? AND seq = 2
                """, Integer.class, GATEWAY, DEVICE)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT quality_flags FROM m2_anes.anesthesia_vital
                 WHERE gateway_id = ? AND device_id = ? AND seq = 2
                """, String.class, GATEWAY, DEVICE)).isEqualTo("hr:RANGE");

        // 新 batchKey 补一帧：只补新增，行数 2 → 3。
        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", "synth-test-gateway-token")
                        .contentType(JSON)
                        .content(ingestBody("BATCH-ACCEPT-" + UUID.randomUUID(),
                                List.of(frame(3, measuredAt.plusSeconds(2), 78, 0, List.of())))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedRows").value(1))
                .andExpect(jsonPath("$.duplicateBatch").value(false));
        assertThat(vitalCount()).isEqualTo(3);

        String from = measuredAt.minusSeconds(60).toString();
        String to = measuredAt.plusSeconds(60).toString();
        MvcResult vitals = mockMvc.perform(get("/v1/devices/" + GATEWAY + "/" + DEVICE + "/vitals")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(3))
                .andReturn();
        // 平台按帧展开为指标行：这里只筛 HR，断言 3 帧（含质量标记）不重复、顺序稳定。
        List<JsonNode> heartRates = new ArrayList<>();
        for (JsonNode item : json(vitals).path("items")) {
            if ("HR".equals(item.path("metric").asText())) {
                heartRates.add(item);
            }
        }
        assertThat(heartRates).hasSize(3);
        assertThat(heartRates).extracting(item -> item.path("value").asInt())
                .containsExactly(76, 132, 78);
        assertThat(heartRates).extracting(item -> item.path("quality").asInt())
                .containsExactly(0, 1, 0);
        assertThat(heartRates.get(1).path("qualityFlags").get(0).asText()).isEqualTo("hr:RANGE");
        mockMvc.perform(get("/v1/devices/health").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                // 重放批次不新增 ingest_log 行：健康摘要只计 2 个不同 batchKey。
                .andExpect(jsonPath("$.ingest.batches").value(2))
                .andExpect(jsonPath("$.ingest.acceptedRows").value(3))
                .andExpect(jsonPath("$.ingest.duplicateRows").value(0))
                .andExpect(jsonPath("$.devices.total").value(1));
        // 整批判重在批次层完成，重放不进入行级质量统计（quality_stats 仍为 0）。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT quality_stats->>'duplicateRows' FROM m2_anes.device_registry
                 WHERE gateway_id = ? AND device_id = ?
                """, String.class, GATEWAY, DEVICE)).isEqualTo("0");

        // 并发 PUT：同一 version 两个请求，恰好一个成功、一个 409（不覆盖、不双写）。
        int version = currentVersion(admin, recordId);
        List<Integer> putStatuses = runConcurrently(2, ordinal -> mockMvc.perform(put(
                        "/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(Map.of("version", version, "anesthesiaType",
                                ordinal == 0 ? "COMBINED" : "REGIONAL"))))
                .andReturn().getResponse().getStatus());
        assertThat(putStatuses).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT version FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, Long.class, recordId)).isEqualTo((long) version + 1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT anesthesia_type FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, String.class, recordId)).isIn("COMBINED", "REGIONAL");

        // 过期版本再 PUT：稳定 409 STATE_CONFLICT。
        mockMvc.perform(put("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(Map.of("version", version, "anesthesiaType", "GENERAL"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        System.out.printf("[M3-QA-01][acceptance-timeline] record=%s events=3 seq=1..3 "
                + "appendOnly=55000 batchReplay=noDup rows=3 qualityFlag=hr:RANGE "
                + "concurrentPut=200/409 version=%d→%d%n", recordId,
                version, version + 1);
    }

    // ------------------------------------------ 签名锁定/修订链/并发修订号 --

    @Test
    void lockFreezesWritesAndConcurrentRevisionsKeepSignatureChain() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ACCEPT-LOCK");
        String recordId = createRecord(admin, scheduleId);
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .truncatedTo(ChronoUnit.MILLIS).plusSeconds(30);
        appendEvent(admin, recordId, "ANESTHESIA_START", base)
                .andExpect(status().isCreated());
        appendEvent(admin, recordId, "ANESTHESIA_END", base.plusMinutes(4))
                .andExpect(status().isCreated());

        String applicationId = jdbcTemplate.queryForObject("""
                SELECT application_id::text FROM m1_sched.schedule WHERE id = ?::uuid
                """, String.class, scheduleId);
        completeThreeWayCheck(admin, applicationId);
        String consentId = createConsent(admin, applicationId);
        signConsent(admin, consentId, 1);

        int lockVersion = currentVersion(admin, recordId);
        MvcResult locked = mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/lock")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(Map.of("version", lockVersion,
                                "signatureRequested", true, "confirmChecklist", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.caStatus").value("SIMULATED"))
                .andReturn();
        JsonNode lockResult = json(locked);
        String lockHash = lockResult.get("signatureHash").asText();
        assertThat(lockHash).matches("^[0-9a-f]{64}$");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT lock_signature_hash FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, String.class, recordId)).isEqualTo(lockHash);

        // 锁单后所有临床写路径先被服务层拒绝（409），绕过 API 也被触发器拒绝（55000）。
        appendEvent(admin, recordId, "CUSTOM", base.plusMinutes(5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        mockMvc.perform(put("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(Map.of("version", currentVersion(admin, recordId),
                                "anesthesiaType", "GENERAL"))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(Map.of("drugName", "丙泊酚（演示）", "dose", 10,
                                "unit", "mg", "route", "IV",
                                "administeredAt", base.plusMinutes(1).toString(),
                                "narcotic", false))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/fluids")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(Map.of("direction", "INTAKE",
                                "fluidType", "复方氯化钠（演示）", "amountMl", 500))))
                .andExpect(status().isConflict());

        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.record_event
                    (id, record_id, seq, event_type, occurred_at, source, created_by)
                VALUES (gen_random_uuid(), ?::uuid, 3, 'CUSTOM', now(), 'MANUAL', ?::uuid)
                """, recordId, SyntheticAccounts.DEMO_ADMIN_ID)), "locked");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.fluid_record
                    (id, record_id, direction, fluid_type, amount_ml, recorded_at, created_by)
                VALUES (gen_random_uuid(), ?::uuid, 'INTAKE', '直写（演示）', 100, now(), ?::uuid)
                """, recordId, SyntheticAccounts.DEMO_ADMIN_ID)), "locked");

        // 并发修订：revision_no 连续（1、2），版本链逐版衔接锁单摘要。
        List<Integer> revisionNos = runConcurrently(2, ordinal -> {
            MvcResult result = mockMvc.perform(post(
                            "/v1/anesthesia/records/" + recordId + "/revisions")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(JSON)
                            .content(write(revisionBody("OTHER",
                                    "并发修订（合成）第 " + (ordinal + 1) + " 条",
                                    "补充说明（合成）", null))))
                    .andExpect(status().isCreated())
                    .andReturn();
            return json(result).get("revisionNo").asInt();
        });
        assertThat(revisionNos).containsExactlyInAnyOrder(1, 2);

        JsonNode revisions = json(mockMvc.perform(get(
                        "/v1/anesthesia/records/" + recordId + "/revisions")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andReturn()).path("items");
        assertThat(revisions.get(0).path("revisionNo").asInt()).isEqualTo(1);
        assertThat(revisions.get(1).path("revisionNo").asInt()).isEqualTo(2);

        JsonNode revision1 = json(mockMvc.perform(get(
                        "/v1/anesthesia/records/" + recordId + "/revisions/1")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode revision2 = json(mockMvc.perform(get(
                        "/v1/anesthesia/records/" + recordId + "/revisions/2")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn());
        String hash1 = revision1.get("signatureHash").asText();
        String hash2 = revision2.get("signatureHash").asText();
        assertThat(hash1).matches("^[0-9a-f]{64}$").isNotEqualTo(lockHash);
        assertThat(hash2).matches("^[0-9a-f]{64}$").isNotEqualTo(hash1);
        assertThat(revision1.path("snapshot").path("signatures")
                .path("lockedSignatureHash").asText()).isEqualTo(lockHash);
        assertThat(revision1.path("snapshot").path("signatures")
                .path("previousRevisionSignatureHash").isMissingNode()
                || revision1.path("snapshot").path("signatures")
                        .path("previousRevisionSignatureHash").isNull()).isTrue();
        assertThat(revision2.path("snapshot").path("signatures")
                .path("previousRevisionSignatureHash").asText()).isEqualTo(hash1);
        assertThat(revision2.path("diff").path("previousRevisionNo").asInt()).isEqualTo(1);

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT status, current_revision, lock_signature_hash
                  FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, recordId);
        assertThat(stored.get("status")).isEqualTo("REVISED");
        assertThat(stored.get("current_revision")).isEqualTo(2);
        assertThat(stored.get("lock_signature_hash")).isEqualTo(lockHash);
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.record_revision SET reason = '改写（演示）' WHERE record_id = ?::uuid",
                recordId)), "append-only");

        System.out.printf("[M3-QA-01][acceptance-lock] record=%s lockHash=%s "
                + "postLockWrite=409/55000 concurrentRevisionNo=1,2 chain=true "
                + "currentRevision=2 lockHashStable=true%n", recordId, lockHash.substring(0, 12));
    }

    // ------------------------------------------------------------- helpers --

    @FunctionalInterface
    private interface ConcurrentAction<T> {
        T apply(int index) throws Exception;
    }

    private <T> List<T> runConcurrently(int threads, ConcurrentAction<T> action) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                final int ordinal = index;
                tasks.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return action.apply(ordinal);
                });
            }
            List<Future<T>> futures = pool.invokeAll(tasks);
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private int currentVersion(String token, String recordId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("version").asInt();
    }

    private org.springframework.test.web.servlet.ResultActions appendEvent(String token,
            String recordId, String eventType, OffsetDateTime occurredAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", eventType);
        body.put("occurredAt", occurredAt.toString());
        body.put("note", "验收聚合事件（合成）");
        return mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/events")
                .header("Authorization", "Bearer " + token)
                .contentType(JSON)
                .content(write(body)));
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

    private void completeThreeWayCheck(String token, String applicationId) throws Exception {
        OffsetDateTime timeOutAt = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .minusHours(1).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime signOutAt = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .minusMinutes(10).truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(Map.of("timeOutAt", timeOutAt.toString(),
                                "specimenChecked", true, "implantChecked", true,
                                "issues", List.of()))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(Map.of("timeOutAt", timeOutAt.toString(),
                                "signOutAt", signOutAt.toString(),
                                "specimenChecked", true, "implantChecked", true,
                                "issues", List.of()))))
                .andExpect(status().isCreated());
    }

    private String createConsent(String token, String applicationId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(Map.of(
                                "applicationId", applicationId,
                                "templateId", GENERAL_CONSENT_TEMPLATE_ID,
                                "templateVersion", GENERAL_CONSENT_TEMPLATE_VERSION,
                                "signerName", "演示患者",
                                "signerRelation", "SELF",
                                "signMethod", "ELECTRONIC"))))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("consentId").asText();
    }

    private void signConsent(String token, String consentId, int version) throws Exception {
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(Map.of("version", version))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));
    }

    private String createSchedule(String token, String hisApplicationNo) throws Exception {
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("hisApplicationNo", hisApplicationNo);
        application.put("patientId", PATIENT_ID);
        application.put("surgeryName", "演示腹腔镜胆囊切除术（合成）");
        application.put("urgency", "ELECTIVE");
        application.put("plannedDurationMin", 120);
        application.put("surgeonName", "演示术者（合成）");
        MvcResult created = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(application)))
                .andExpect(status().isCreated())
                .andReturn();
        String applicationId = json(created).get("applicationId").asText();

        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("applicationId", applicationId);
        schedule.put("roomId", OR_01);
        schedule.put("plannedStart", "2026-12-05T08:00:00+08:00");
        schedule.put("plannedEnd", "2026-12-05T10:00:00+08:00");
        schedule.put("sequenceNo", 1);
        schedule.put("priority", "ELECTIVE");
        MvcResult scheduled = mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(schedule)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(scheduled).get("scheduleId").asText();
    }

    private String createRecord(String token, String scheduleId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(Map.of("scheduleId", scheduleId,
                                "anesthesiaType", "GENERAL"))))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).get("recordId").asText();
    }

    private String ingestBody(String batchKey, List<Map<String, Object>> frames) {
        return write(Map.of("batchKey", batchKey, "frames", frames));
    }

    private static Map<String, Object> frame(long seq, Instant measuredAt, int hr, int quality,
            List<String> flags) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("seq", seq);
        frame.put("measuredAt", measuredAt.toString());
        frame.put("measuredAtCorrected", measuredAt.toString());
        frame.put("receivedAt", measuredAt.plusSeconds(1).toString());
        frame.put("hr", hr);
        frame.put("spo2", 98);
        frame.put("nibpSys", 120);
        frame.put("nibpDia", 80);
        frame.put("etco2", 35.0);
        frame.put("temp", 36.7);
        frame.put("quality", quality);
        frame.put("flags", flags);
        frame.put("source", "AUTO");
        return frame;
    }

    private long vitalCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m2_anes.anesthesia_vital", Long.class);
        return count == null ? 0 : count;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String write(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(write(Map.of("username", username, "password", password,
                                "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).get("accessToken").asText();
    }

    private void insertPatient() {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, '演示患者庚', 'FEMALE', DATE '1972-03-08', 54, 'SYNT****0001', ?,
                        '临床记录验收合成科室', ?::uuid,
                        'f1000000-0000-4000-8000-000000000001'::uuid,
                        '演示病区', '07', now(), now(), 1, false)
                """, PATIENT_ID, HIS_PATIENT_NO, "hash-" + HIS_PATIENT_NO,
                SyntheticAccounts.DEMO_ANES_ORG_ID);
    }

    private void clearClinicalRows() {
        // append-only 临床表：测试夹具临时 DISABLE TRIGGER USER 清理后立即恢复。
        String[] tables = {"m2_anes.record_revision", "m2_anes.fluid_record",
            "m2_anes.drug_administration", "m2_anes.record_event",
            "m2_anes.resuscitation_record", "m2_anes.anesthesia_record",
            "m2_anes.consent_record", "m2_anes.three_way_check",
            "m2_anes.alert_watermark", "m2_anes.alert"};
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
            jdbcTemplate.update("DELETE FROM m2_anes.alert_watermark");
            jdbcTemplate.update("DELETE FROM m2_anes.alert");
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
}
