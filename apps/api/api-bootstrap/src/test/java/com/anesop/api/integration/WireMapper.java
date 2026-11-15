package com.anesop.api.integration;

import com.anesop.contracts.drug.model.PharmacyAdapterStatus;
import com.anesop.contracts.material.model.SpdAdapterStatus;
import com.anesop.contracts.material.model.SpdSyncResult;
import com.anesop.m1.api.his.HisSurgeryApplicationRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 外部系统字段 → 平台模型的参考映射（M5-M19-01 契约测试的唯一映射口径）。
 *
 * <p>模拟器（{@code services/simulators}）使用外部系统侧字段命名；本类按
 * {@code docs/integration/README.md} 的映射表转换为平台模型/请求体。契约测试断言：
 * <ol>
 *   <li>模拟器返回的字段集合与本文档登记的外部模式<b>完全一致</b>（多/少字段即失败）；</li>
 *   <li>映射后的每个字段值来自对应的外部字段（含代码值域转换）；</li>
 *   <li>映射结果能被真实 API 接受（创建成功），且适配器状态字段满足契约 required 列表。</li>
 * </ol>
 *
 * <p>只使用 SYNTHETIC 演示数据；不读取也不产生任何真实患者信息。
 */
final class WireMapper {

    static final String EXT_02 = "EXT-02";
    static final String EXT_03 = "EXT-03";
    static final String MODE_SIMULATED = "SIMULATED";

    /** HIS 手术申请（I-01b）外部字段。 */
    static final Set<String> HIS_APPLICATION_FIELDS = Set.of(
            "applicationNo", "patient", "operationName", "operationCode", "urgency",
            "plannedMinutes", "surgeon", "diagnosis", "note");

    /** HIS 患者主索引（I-01a）外部字段。 */
    static final Set<String> HIS_PATIENT_FIELDS = Set.of(
            "patientNo", "patientName", "sex", "birthday", "age", "admissionNo",
            "departmentCode", "department", "ward", "bed");

    /** 药房药品目录外部字段。 */
    static final Set<String> PHARMACY_DRUG_FIELDS = Set.of(
            "drugCode", "drugName", "commonName", "spec", "unit", "doseForm", "drugClass",
            "narcoticFlag", "highRiskFlag", "aliasList");

    /** SPD 耗材目录外部字段。 */
    static final Set<String> SPD_MATERIAL_FIELDS = Set.of(
            "materialCode", "materialName", "specification", "unit", "categoryCode", "udiDi",
            "highValueFlag", "expiryManagedFlag", "remark");

    /** 药房系统在线状态外部字段。 */
    static final Set<String> PHARMACY_STATUS_WIRE_FIELDS = Set.of(
            "system", "systemName", "online", "pendingOrders", "lastSyncAt", "message");

    /** SPD 系统在线状态外部字段。 */
    static final Set<String> SPD_STATUS_WIRE_FIELDS = Set.of(
            "system", "systemName", "online", "pendingReceipts", "lastSyncAt", "message");

    /** SPD 同步回执外部字段。 */
    static final Set<String> SPD_SYNC_WIRE_FIELDS = Set.of(
            "syncNo", "acknowledged", "pulled", "pushed", "syncedAt", "message", "idempotencyKey");

    /**
     * 契约 required：drug.yaml {@code PharmacyAdapterStatus}（0.1.0）。
     * lastSyncedAt 非 required（从未同步时为 null）。
     */
    static final List<String> PHARMACY_STATUS_CONTRACT_FIELDS = List.of(
            "adapterCode", "name", "mode", "available", "degraded", "pendingSyncCount", "message");

    /** 契约 required：material.yaml {@code SpdAdapterStatus}（0.1.0）。 */
    static final List<String> SPD_STATUS_CONTRACT_FIELDS = List.of(
            "adapterCode", "name", "mode", "available", "degraded", "pendingSyncCount", "message");

    /** 契约 required：material.yaml {@code SpdSyncResult}（0.1.0）。 */
    static final List<String> SPD_SYNC_CONTRACT_FIELDS = List.of(
            "adapterCode", "success", "degraded", "syncedAt", "pulledCount", "pushedCount", "message");

    private WireMapper() {
    }

    // ---------------------------------------------------------------- HIS

    /**
     * 映射 HIS 手术申请为平台入站记录（{@code HisSurgeryApplicationRecord}）。
     *
     * @param application 外部申请 JSON
     * @param deptId      科室标识（科室编码 → 平台组织的联调映射，见 docs/integration §3.1）
     * @param siteId      院区标识（同上）
     * @return 入站记录
     */
    static HisSurgeryApplicationRecord hisRecord(JsonNode application, UUID deptId, UUID siteId) {
        JsonNode patient = application.path("patient");
        return new HisSurgeryApplicationRecord(
                requireText(application, "applicationNo"),
                requireText(patient, "patientNo"),
                requireText(patient, "patientName"),
                mapSex(requireText(patient, "sex")),
                LocalDate.parse(requireText(patient, "birthday")),
                requireInt(patient, "age"),
                requireText(patient, "admissionNo"),
                requireText(patient, "department"),
                deptId,
                siteId,
                requireText(patient, "ward"),
                requireText(patient, "bed"),
                requireText(application, "operationName"),
                requireText(application, "operationCode"),
                mapUrgency(requireText(application, "urgency")),
                requireInt(application, "plannedMinutes"),
                requireText(application, "surgeon"),
                requireText(application, "diagnosis"),
                requireText(application, "note"));
    }

    /**
     * 映射为创建手术申请请求（patientId 由 I-01a 患者主索引映射得到）。
     *
     * @param application 外部申请 JSON
     * @param patientId   平台患者标识
     * @return 请求体
     */
    static Map<String, Object> hisCreateRequest(JsonNode application, String patientId) {
        HisSurgeryApplicationRecord record = hisRecord(application, null, null);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("hisApplicationNo", record.hisApplicationNo());
        request.put("patientId", patientId);
        request.put("surgeryName", record.surgeryName());
        request.put("surgeryCode", record.surgeryCode());
        request.put("urgency", record.urgency());
        request.put("plannedDurationMin", record.plannedDurationMin());
        request.put("surgeonName", record.surgeonName());
        request.put("preopDiagnosis", record.preopDiagnosis());
        request.put("remark", record.remark());
        return request;
    }

    /** HIS 性别代码 → 平台值域（F/M/U → FEMALE/MALE/UNKNOWN）；未知值 fail-closed。 */
    static String mapSex(String hisSex) {
        return switch (hisSex) {
            case "F" -> "FEMALE";
            case "M" -> "MALE";
            case "U" -> "UNKNOWN";
            default -> throw new IllegalArgumentException("未知 HIS 性别代码: " + hisSex);
        };
    }

    /** HIS 中文紧急度 → 平台值域（择期/限期/急诊）；未知值 fail-closed。 */
    static String mapUrgency(String hisUrgency) {
        return switch (hisUrgency) {
            case "择期" -> "ELECTIVE";
            case "限期" -> "URGENT";
            case "急诊" -> "EMERGENCY";
            default -> throw new IllegalArgumentException("未知 HIS 紧急度: " + hisUrgency);
        };
    }

    // ----------------------------------------------------------- 药房（EXT-02）

    /** 药房药品目录 → 创建药品请求。 */
    static Map<String, Object> drugCreateRequest(JsonNode drug) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("code", requireText(drug, "drugCode"));
        request.put("name", requireText(drug, "drugName"));
        request.put("genericName", requireText(drug, "commonName"));
        request.put("specification", requireText(drug, "spec"));
        request.put("unit", requireText(drug, "unit"));
        request.put("dosageForm", requireText(drug, "doseForm"));
        request.put("category", requireText(drug, "drugClass"));
        request.put("narcotic", requireFlag(drug, "narcoticFlag"));
        request.put("highRisk", requireFlag(drug, "highRiskFlag"));
        request.put("aliases", textList(drug.path("aliasList")));
        request.put("remark", "SYNTHETIC：药房模拟器目录（EXT-02）");
        return request;
    }

    /**
     * 药房系统在线状态 → 契约 {@code PharmacyAdapterStatus}。
     *
     * <p>映射：{@code online→available/!degraded}、{@code pendingOrders→pendingSyncCount}、
     * {@code lastSyncAt→lastSyncedAt}；adapterCode/name/mode 由适配器自身填写（不来自外部）。
     *
     * @param wire 外部状态 JSON
     * @return 契约模型
     */
    static PharmacyAdapterStatus pharmacyStatus(JsonNode wire) {
        PharmacyAdapterStatus status = new PharmacyAdapterStatus();
        status.setAdapterCode(EXT_02);
        status.setName("药房系统适配器（模拟）");
        status.setMode(PharmacyAdapterStatus.ModeEnum.SIMULATED);
        boolean online = requireBoolean(wire, "online");
        status.setAvailable(online);
        status.setDegraded(!online);
        status.setLastSyncedAt(parseTime(requireText(wire, "lastSyncAt")));
        status.setPendingSyncCount(requireInt(wire, "pendingOrders"));
        status.setMessage(requireText(wire, "message"));
        return status;
    }

    /**
     * 适配器降级状态（外部系统不可用/超时/解析失败时由适配器生成，不阻塞临床）。
     *
     * @param reason 失败原因（不含凭据与患者信息）
     * @return 契约模型（available=false、degraded=true、pendingSyncCount=1）
     */
    static PharmacyAdapterStatus pharmacyDegraded(String reason) {
        PharmacyAdapterStatus status = new PharmacyAdapterStatus();
        status.setAdapterCode(EXT_02);
        status.setName("药房系统适配器（模拟）");
        status.setMode(PharmacyAdapterStatus.ModeEnum.SIMULATED);
        status.setAvailable(false);
        status.setDegraded(true);
        status.setLastSyncedAt(null);
        status.setPendingSyncCount(1);
        status.setMessage("药房适配器不可用，已降级为本地药品字典可用（" + reason + "）");
        return status;
    }

    // ------------------------------------------------------------- SPD（EXT-03）

    /** SPD 耗材目录 → 创建耗材请求。 */
    static Map<String, Object> materialCreateRequest(JsonNode material) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("code", requireText(material, "materialCode"));
        request.put("name", requireText(material, "materialName"));
        request.put("specification", requireText(material, "specification"));
        request.put("unit", requireText(material, "unit"));
        request.put("category", requireText(material, "categoryCode"));
        request.put("udiDi", requireText(material, "udiDi"));
        request.put("highValue", requireFlag(material, "highValueFlag"));
        request.put("expiryManaged", requireFlag(material, "expiryManagedFlag"));
        request.put("remark", requireText(material, "remark"));
        return request;
    }

    /** SPD 系统在线状态 → 契约 {@code SpdAdapterStatus}。 */
    static SpdAdapterStatus spdStatus(JsonNode wire) {
        SpdAdapterStatus status = new SpdAdapterStatus();
        status.setAdapterCode(EXT_03);
        status.setName("SPD 供应链适配器（模拟）");
        status.setMode(SpdAdapterStatus.ModeEnum.SIMULATED);
        boolean online = requireBoolean(wire, "online");
        status.setAvailable(online);
        status.setDegraded(!online);
        status.setLastSyncedAt(parseTime(requireText(wire, "lastSyncAt")));
        status.setPendingSyncCount(requireInt(wire, "pendingReceipts"));
        status.setMessage(requireText(wire, "message"));
        return status;
    }

    /** SPD 适配器降级状态。 */
    static SpdAdapterStatus spdDegraded(String reason) {
        SpdAdapterStatus status = new SpdAdapterStatus();
        status.setAdapterCode(EXT_03);
        status.setName("SPD 供应链适配器（模拟）");
        status.setMode(SpdAdapterStatus.ModeEnum.SIMULATED);
        status.setAvailable(false);
        status.setDegraded(true);
        status.setLastSyncedAt(null);
        status.setPendingSyncCount(1);
        status.setMessage("SPD 适配器不可用，已降级为本地库存可用（" + reason + "）");
        return status;
    }

    /** SPD 同步回执 → 契约 {@code SpdSyncResult}。 */
    static SpdSyncResult spdSync(JsonNode wire) {
        SpdSyncResult result = new SpdSyncResult();
        result.setAdapterCode(EXT_03);
        boolean acknowledged = requireBoolean(wire, "acknowledged");
        result.setSuccess(acknowledged);
        result.setDegraded(!acknowledged);
        result.setSyncedAt(parseTime(requireText(wire, "syncedAt")));
        result.setPulledCount(requireInt(wire, "pulled"));
        result.setPushedCount(requireInt(wire, "pushed"));
        result.setMessage(requireText(wire, "message"));
        return result;
    }

    /** SPD 同步降级结果（外部系统不可用时 counts=0）。 */
    static SpdSyncResult spdSyncDegraded(String reason) {
        SpdSyncResult result = new SpdSyncResult();
        result.setAdapterCode(EXT_03);
        result.setSuccess(false);
        result.setDegraded(true);
        result.setSyncedAt(OffsetDateTime.now());
        result.setPulledCount(0);
        result.setPushedCount(0);
        result.setMessage("SPD 同步不可用，已降级为本地库存可用（" + reason + "）");
        return result;
    }

    // --------------------------------------------------------------- helpers

    /** 读取必填文本字段；缺失/空白即失败（fail-closed，避免静默空值）。 */
    static String requireText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("外部字段缺失或为空: " + field + "（节点 " + node + "）");
        }
        return value.asText();
    }

    /** 读取必填整数字段。 */
    static int requireInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException("外部整数字段缺失: " + field);
        }
        return value.asInt();
    }

    /** 读取必填布尔字段。 */
    static boolean requireBoolean(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("外部布尔字段缺失: " + field);
        }
        return value.asBoolean();
    }

    /** 读取 0/1 标志并转布尔（外部系统常用 0/1）。 */
    static boolean requireFlag(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isInt() || (value.asInt() != 0 && value.asInt() != 1)) {
            throw new IllegalArgumentException("外部标志字段必须是 0/1: " + field);
        }
        return value.asInt() == 1;
    }

    static OffsetDateTime parseTime(String value) {
        return OffsetDateTime.parse(value);
    }

    static List<String> textList(JsonNode array) {
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("外部列表字段缺失");
        }
        List<String> values = new java.util.ArrayList<>(array.size());
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
