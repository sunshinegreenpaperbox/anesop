package com.anesop.api.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M5-QA-01 性能/并发专项共用的 SYNTHETIC 临床夹具构造。
 *
 * <p>只做两件事：按既有 API 契约创建可锁单的麻醉记录前置链（申请 → 台次 → 记录单 →
 * 事件 → 三方核查 → 知情同意 → 签署），以及把并发线程收敛到同一起跑线。所有数据均为
 * {@code SYNTH-} 前缀的合成数据；不新增生产代码、契约或迁移。
 *
 * <p>与 {@code ClinicalRecordAcceptanceTest} / {@code RecordLockRevisionApiIntegrationTest}
 * 内的私有 helper 同源，但抽到 support 是为了让性能基准与并发回归使用同一套夹具语义，
 * 避免“基准测的流程”和“准入测的流程”不一致。
 */
public final class ClinicalApiFixtures {

    public static final MediaType JSON = MediaType.APPLICATION_JSON;

    /** V11 合成用知情同意模板（通用模板，版本 2）。 */
    public static final String CONSENT_TEMPLATE_ID = "b1000000-0000-4000-8000-000000000002";

    /** 知情同意模板版本（与 {@link #CONSENT_TEMPLATE_ID} 配套）。 */
    public static final int CONSENT_TEMPLATE_VERSION = 2;

    /** 东八区偏移：记录单/事件时间统一按业务时区构造。 */
    public static final ZoneOffset ZONE_OFFSET = ZoneOffset.ofHours(8);

    private ClinicalApiFixtures() {
    }

    /** 登录并返回 accessToken（失败即测试失败，不做静默兜底）。 */
    public static String login(MockMvc mockMvc, ObjectMapper mapper, String username, String password)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(write(mapper, Map.of("username", username, "password", password,
                                "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return json(mapper, result).get("accessToken").asText();
    }

    /** 读取响应体为 JSON 树。 */
    public static JsonNode json(ObjectMapper mapper, MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    /** 序列化请求体；失败向上抛（不吞异常）。 */
    public static String write(ObjectMapper mapper, Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 插入患者快照（SYNTHETIC，脱敏住院号，不写真实标识）。 */
    public static void insertPatient(JdbcTemplate jdbcTemplate, String patientId,
            String hisPatientNo, String deptName, String deptId, String siteId) {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, '演示患者性能（合成）', 'MALE', DATE '1972-03-08', 54, ?, ?, ?,
                        ?::uuid, ?::uuid, '演示病区', '07', now(), now(), 1, false)
                ON CONFLICT (id) DO NOTHING
                """, patientId, hisPatientNo, "SYNT****PERF", "hash-" + hisPatientNo, deptName,
                deptId, siteId);
    }

    /** 创建手术申请，返回 applicationId。 */
    public static String createApplication(MockMvc mockMvc, ObjectMapper mapper, String token,
            String hisApplicationNo, String patientId, String surgeryName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("hisApplicationNo", hisApplicationNo);
        body.put("patientId", patientId);
        body.put("surgeryName", surgeryName);
        body.put("urgency", "ELECTIVE");
        body.put("plannedDurationMin", 120);
        body.put("surgeonName", "演示术者（合成）");
        MvcResult created = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(mapper, body)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(mapper, created).get("applicationId").asText();
    }

    /** 创建排程台次，返回 scheduleId。 */
    public static String createSchedule(MockMvc mockMvc, ObjectMapper mapper, String token,
            String applicationId, String roomId, String plannedStart, String plannedEnd)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId);
        body.put("roomId", roomId);
        body.put("plannedStart", plannedStart);
        body.put("plannedEnd", plannedEnd);
        body.put("sequenceNo", 1);
        body.put("priority", "ELECTIVE");
        MvcResult created = mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(mapper, body)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(mapper, created).get("scheduleId").asText();
    }

    /** 创建麻醉记录单（DRAFT），返回 recordId。 */
    public static String createRecord(MockMvc mockMvc, ObjectMapper mapper, String token,
            String scheduleId) throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(mapper, Map.of("scheduleId", scheduleId,
                                "anesthesiaType", "GENERAL"))))
                .andExpect(status().isCreated())
                .andReturn();
        return json(mapper, created).get("recordId").asText();
    }

    /** 追加时间轴事件，返回服务端分配的 seq。 */
    public static int appendEvent(MockMvc mockMvc, ObjectMapper mapper, String token,
            String recordId, String eventType, OffsetDateTime occurredAt) throws Exception {
        return appendEvent(mockMvc, mapper, token, recordId, eventType, occurredAt, null);
    }

    /**
     * 追加时间轴事件（可带补录原因），返回服务端分配的 seq。
     *
     * <p>记录已结束后追加、或事件时间早于最新事件时属于“补录”，服务层要求
     * {@code entryReason} 非空（400 否则）；并发/基准用例按真实语义传补录原因。
     */
    public static int appendEvent(MockMvc mockMvc, ObjectMapper mapper, String token,
            String recordId, String eventType, OffsetDateTime occurredAt, String entryReason)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", eventType);
        body.put("occurredAt", occurredAt.toString());
        body.put("note", "性能/并发专项事件（合成）");
        if (entryReason != null) {
            body.put("entryReason", entryReason);
        }
        MvcResult created = mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/events")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(mapper, body)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(mapper, created).get("seq").asInt();
    }

    /** 完成三方核查（先 Time-out，再 Sign-out）。 */
    public static void completeThreeWayCheck(MockMvc mockMvc, ObjectMapper mapper, String token,
            String applicationId) throws Exception {
        OffsetDateTime timeOutAt = OffsetDateTime.now(ZONE_OFFSET).minusHours(1)
                .truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime signOutAt = OffsetDateTime.now(ZONE_OFFSET).minusMinutes(10)
                .truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(mapper, Map.of("timeOutAt", timeOutAt.toString(),
                                "specimenChecked", true, "implantChecked", true,
                                "issues", List.of()))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(mapper, Map.of("timeOutAt", timeOutAt.toString(),
                                "signOutAt", signOutAt.toString(),
                                "specimenChecked", true, "implantChecked", true,
                                "issues", List.of()))))
                .andExpect(status().isCreated());
    }

    /** 创建并签署知情同意（合成签署人，无真实标识）。 */
    public static void createAndSignConsent(MockMvc mockMvc, ObjectMapper mapper, String token,
            String applicationId) throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(mapper, Map.of(
                                "applicationId", applicationId,
                                "templateId", CONSENT_TEMPLATE_ID,
                                "templateVersion", CONSENT_TEMPLATE_VERSION,
                                "signerName", "演示患者",
                                "signerRelation", "SELF",
                                "signMethod", "ELECTRONIC"))))
                .andExpect(status().isCreated())
                .andReturn();
        String consentId = json(mapper, created).get("consentId").asText();
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(write(mapper, Map.of("version", 1))))
                .andExpect(status().isOk());
    }

    /**
     * 构造一条可锁单记录：申请 → 台次 → 记录单 → 开始/结束事件 → 三方核查 → 知情同意签署。
     *
     * <p>锁单前置条件（三方核查完成、同意已签署、已开始/已结束、≥1 事件、未锁）全部满足，
     * 使调用方可以直接测 {@code POST /v1/anesthesia/records/{id}/lock} 的成功路径。
     *
     * @return recordId
     */
    public static String prepareLockableRecord(MockMvc mockMvc, ObjectMapper mapper,
            JdbcTemplate jdbcTemplate, String token, String patientId, String hisApplicationNo,
            String roomId, String plannedStart, String plannedEnd) throws Exception {
        String applicationId = createApplication(mockMvc, mapper, token, hisApplicationNo,
                patientId, "演示性能基准术式（合成）");
        String scheduleId = createSchedule(mockMvc, mapper, token, applicationId, roomId,
                plannedStart, plannedEnd);
        String recordId = createRecord(mockMvc, mapper, token, scheduleId);
        OffsetDateTime base = OffsetDateTime.now(ZONE_OFFSET).truncatedTo(ChronoUnit.MILLIS)
                .plusSeconds(30);
        appendEvent(mockMvc, mapper, token, recordId, "ANESTHESIA_START", base);
        appendEvent(mockMvc, mapper, token, recordId, "ANESTHESIA_END", base.plusMinutes(4));
        completeThreeWayCheck(mockMvc, mapper, token, applicationId);
        createAndSignConsent(mockMvc, mapper, token, applicationId);
        return recordId;
    }

    /** 并发动作：允许抛受检异常，异常原样上抛（不吞异常、不伪造结果）。 */
    @FunctionalInterface
    public interface ConcurrentAction<T> {
        T apply(int index) throws Exception;
    }

    /**
     * 用 {@link CyclicBarrier} 把 {@code threads} 个线程收敛到同一起跑线后并发执行。
     *
     * <p>与既有临床准入用例同一实现：barrier 超时 10s、future 超时 30s，任一线程异常都在
     * 汇总时抛出（不做“部分成功即通过”的兜底），保证并发断言不是恒真。
     */
    public static <T> List<T> runConcurrently(int threads, ConcurrentAction<T> action)
            throws Exception {
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
}
