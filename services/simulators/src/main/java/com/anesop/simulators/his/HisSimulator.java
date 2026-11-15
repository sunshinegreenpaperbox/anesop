package com.anesop.simulators.his;

import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.http.HttpResult;
import com.anesop.simulators.http.SimulatedHttpServer;
import com.anesop.simulators.http.SimulatedRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * HIS 模拟器（EXT-01，SYNTHETIC）：患者主索引 + 手术申请查询 + 创建/变更回调。
 *
 * <p>字段使用 HIS 侧命名（{@code applicationNo}/{@code sex}/{@code admissionNo}/中文紧急度等），
 * 与平台内部模型不同名，用于验证适配器的<b>字段映射</b>；映射表见
 * {@code docs/integration/README.md} §3.1。全部数据由种子确定性生成，同一
 * {@code (count, seed)} 返回相同申请号（幂等导入的模拟前提）。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /his/v1/patients/{patientNo}} — 患者主索引（I-01a）</li>
 *   <li>{@code GET /his/v1/surgery-applications?count=1..5&seed=} — 手术申请（I-01b）</li>
 *   <li>{@code POST /his/v1/surgery-applications/{applicationNo}/status} — 创建结果回调</li>
 *   <li>{@code POST /his/v1/surgery-applications/{applicationNo}/change} — 变更回调</li>
 *   <li>{@code GET /sim/v1/requests} — 请求留痕（排障，仅回环地址）</li>
 * </ul>
 */
public final class HisSimulator implements AutoCloseable {

    /** 申请号/患者号格式（与 SYNTHETIC 演示数据一致）。 */
    public static final String APPLICATION_PREFIX = "SYNTH-HIS-APP-";
    public static final String PATIENT_PREFIX = "SYNTH-P-";
    public static final String SOURCE = "HIS-SIM";
    public static final long DEFAULT_SEED = 20260101L;
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 5;

    private static final String[] PATIENT_NAMES = {
        "演示患者甲", "演示患者乙", "演示患者丙", "演示患者丁", "演示患者戊"
    };
    private static final String[] SEXES = {"F", "M", "U"};
    private static final String[] SURGERIES = {
        "演示术式（全膝关节置换术）",
        "演示术式（腹腔镜胆囊切除术）",
        "演示术式（腰椎间盘髓核摘除术）",
        "演示术式（甲状腺次全切除术）",
        "演示术式（剖宫产术）"
    };
    private static final String[] SURGERY_CODES = {
        "SYNTH-OP-8101", "SYNTH-OP-8202", "SYNTH-OP-8303", "SYNTH-OP-8404", "SYNTH-OP-8505"
    };
    private static final String[] DIAGNOSES = {
        "演示诊断（膝关节退行性病变）",
        "演示诊断（胆囊结石伴慢性胆囊炎）",
        "演示诊断（腰椎间盘突出症）",
        "演示诊断（甲状腺结节）",
        "演示诊断（妊娠状态）"
    };
    private static final String[] URGENCIES = {"择期", "限期", "急诊"};
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final LocalDate AGE_REFERENCE_DATE = LocalDate.of(2026, 11, 1);

    private final SimulatedHttpServer server;

    public HisSimulator(SimulatorConfig.Endpoint config) {
        this.server = new SimulatedHttpServer("his", config);
        server.get("/his/v1/patients/{patientNo}", this::patient);
        server.get("/his/v1/surgery-applications", this::applications);
        server.post("/his/v1/surgery-applications/{applicationNo}/status", this::statusCallback);
        server.post("/his/v1/surgery-applications/{applicationNo}/change", this::changeCallback);
    }

    public void start() throws IOException {
        server.start();
    }

    public int port() {
        return server.port();
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    /** 已处理请求数（契约测试/脚本排障）。 */
    public int requestCount() {
        return server.requestCount();
    }

    /** 最近请求留痕。 */
    public java.util.List<com.anesop.simulators.http.RecordedRequest> requests() {
        return server.requests();
    }

    @Override
    public void close() {
        server.close();
    }

    private HttpResult patient(SimulatedRequest request) {
        String patientNo = request.pathParams().get("patientNo");
        Integer serial = serialOf(patientNo, PATIENT_PREFIX);
        if (serial == null) {
            return HttpResult.json(404, error("PATIENT_NOT_FOUND", "HIS 患者不存在: " + patientNo));
        }
        return HttpResult.ok(patientFor(serial));
    }

    private HttpResult applications(SimulatedRequest request) {
        Integer count = intParam(request, "count", 3);
        if (count == null) {
            return HttpResult.json(400, error("INVALID_COUNT", "count 必须是 1–5 的整数"));
        }
        if (count < MIN_COUNT || count > MAX_COUNT) {
            return HttpResult.json(400, error("INVALID_COUNT", "count 必须是 1–5 的整数"));
        }
        Long seed = longParam(request, "seed", DEFAULT_SEED);
        if (seed == null) {
            return HttpResult.json(400, error("INVALID_SEED", "seed 必须是整数"));
        }
        List<HisApplication> applications = applications(count, seed);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("simulated", true);
        body.put("source", SOURCE);
        body.put("seed", seed);
        body.put("applications", applications);
        return HttpResult.ok(body);
    }

    private HttpResult statusCallback(SimulatedRequest request) throws IOException {
        String applicationNo = request.pathParams().get("applicationNo");
        if (serialOf(applicationNo, APPLICATION_PREFIX) == null) {
            return HttpResult.json(404, error("APPLICATION_NOT_FOUND", "HIS 申请不存在: " + applicationNo));
        }
        JsonNode body = parseBody(request);
        String status = body == null ? null : body.path("status").asText(null);
        if (!"ACCEPTED".equals(status) && !"REJECTED".equals(status)) {
            return HttpResult.json(400, error("INVALID_STATUS", "status 只允许 ACCEPTED/REJECTED"));
        }
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("applicationNo", applicationNo);
        ack.put("accepted", "ACCEPTED".equals(status));
        ack.put("receivedAt", OffsetDateTime.now(DISPLAY_OFFSET).toString());
        ack.put("synthetic", true);
        return HttpResult.ok(ack);
    }

    private HttpResult changeCallback(SimulatedRequest request) throws IOException {
        String applicationNo = request.pathParams().get("applicationNo");
        if (serialOf(applicationNo, APPLICATION_PREFIX) == null) {
            return HttpResult.json(404, error("APPLICATION_NOT_FOUND", "HIS 申请不存在: " + applicationNo));
        }
        JsonNode body = parseBody(request);
        String reason = body == null ? null : body.path("reason").asText(null);
        if (reason == null || reason.isBlank()) {
            return HttpResult.json(400, error("INVALID_CHANGE", "变更回调必须带 reason"));
        }
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("applicationNo", applicationNo);
        ack.put("changed", true);
        ack.put("reason", reason);
        ack.put("receivedAt", OffsetDateTime.now(DISPLAY_OFFSET).toString());
        ack.put("synthetic", true);
        return HttpResult.ok(ack);
    }

    /**
     * 确定性生成一批 HIS 手术申请（SYNTHETIC）。
     *
     * @param count 条数（1–5，调用方已校验）
     * @param seed  种子；相同种子生成相同申请号
     * @return 申请列表
     */
    public static List<HisApplication> applications(int count, long seed) {
        if (count < MIN_COUNT || count > MAX_COUNT) {
            throw new IllegalArgumentException("模拟 HIS 条数必须为 1–5");
        }
        Random random = new Random(seed);
        int offset = Math.floorMod((int) (seed % 9000), 9000) + 1;
        List<HisApplication> applications = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int serial = offset + index;
            HisPatient patient = patientFor(serial);
            applications.add(new HisApplication(
                    APPLICATION_PREFIX + String.format("%04d", serial),
                    patient,
                    SURGERIES[index % SURGERIES.length],
                    SURGERY_CODES[index % SURGERY_CODES.length],
                    URGENCIES[(index + random.nextInt(URGENCIES.length)) % URGENCIES.length],
                    60 + index * 30,
                    "演示术者" + (char) ('A' + index),
                    DIAGNOSES[index % DIAGNOSES.length],
                    "模拟 HIS 申请（SYNTHETIC 合成数据）"));
        }
        return applications;
    }

    /** 患者主索引（按患者号确定性推导，与申请中的患者字段一致）。 */
    public static HisPatient patientFor(int serial) {
        String suffix = String.format("%04d", serial);
        LocalDate birthDate = LocalDate.of(1955 + (serial % 5) * 4, 1 + (serial % 12), 5 + (serial % 5));
        int age = Period.between(birthDate, AGE_REFERENCE_DATE).getYears();
        return new HisPatient(
                PATIENT_PREFIX + suffix,
                PATIENT_NAMES[serial % PATIENT_NAMES.length],
                SEXES[serial % SEXES.length],
                birthDate.toString(),
                age,
                "SYNTH-ZY-" + suffix,
                "ANES",
                "演示外科",
                "演示病区",
                String.valueOf(10 + (serial % 12)));
    }

    private static Integer serialOf(String identifier, String prefix) {
        if (identifier == null || !identifier.startsWith(prefix)) {
            return null;
        }
        String suffix = identifier.substring(prefix.length());
        if (suffix.length() != 4 || !suffix.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int serial = Integer.parseInt(suffix);
        // 生成范围：offset ∈ [1,9000] + index ≤ 4，超出即视为“HIS 中不存在”。
        return serial >= 1 && serial <= 9005 ? serial : null;
    }

    private JsonNode parseBody(SimulatedRequest request) throws IOException {
        return server.parseJson(request.body());
    }

    private static Integer intParam(SimulatedRequest request, String name, int defaultValue) {
        String raw = request.queryParam(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Long longParam(SimulatedRequest request, String name, long defaultValue) {
        String raw = request.queryParam(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("synthetic", true);
        return body;
    }

    /** HIS 患者主索引字段（HIS 侧命名）。 */
    public record HisPatient(
            String patientNo,
            String patientName,
            String sex,
            String birthday,
            int age,
            String admissionNo,
            String departmentCode,
            String department,
            String ward,
            String bed) {
    }

    /** HIS 手术申请字段（HIS 侧命名；适配器负责映射到平台内部模型）。 */
    public record HisApplication(
            String applicationNo,
            HisPatient patient,
            String operationName,
            String operationCode,
            String urgency,
            int plannedMinutes,
            String surgeon,
            String diagnosis,
            String note) {
    }
}
