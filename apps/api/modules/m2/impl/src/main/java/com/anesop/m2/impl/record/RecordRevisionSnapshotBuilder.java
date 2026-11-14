package com.anesop.m2.impl.record;

import com.anesop.contracts.anesthesia.model.RevisionDiff;
import com.anesop.contracts.anesthesia.model.RevisionFieldChange;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.DrugAdministrationEntity;
import com.anesop.m2.impl.persistence.entity.FluidRecordEntity;
import com.anesop.m2.impl.persistence.entity.RecordEventEntity;
import com.anesop.m2.impl.persistence.entity.ResuscitationRecordEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 修订版本快照构造、规范化摘要与差异计算（M3-M2-03，契约 anesthesia.yaml 0.8.0）。
 *
 * <p>稳定序列化规则（文档见 docs/modules/m2/README.md §14.3、docs/api/README.md §3.10）：
 * <ul>
 *   <li>快照只含临床内容：记录单临床字段（麻醉方式/开始/结束时间）、全部时间轴事件、用药、
 *       出入量明细、出入量汇总、抢救记录（含负责人/参与人员），以及 {@code signatures} 下的
 *       锁单/上一修订签名摘要；设备体征明细不入快照（高频采集由采集链路独立留痕）；</li>
 *   <li>排除患者标识（patientId/patientNo/patientName）、台次/申请标识、审计字段
 *       （createdAt/createdBy/updatedAt/updatedBy/recordedAt 等录入时间）与签名字段本身；</li>
 *   <li>摘要输入为 JSON 规范化文本：对象键按字典序排序、null 对象字段不参与、
 *       数字去尾零（BigDecimal.stripTrailingZeros().toPlainString()）后以字符串输出、
 *       时间统一 {@code +08:00} 毫秒精度 ISO-8601；数组按 (时间, 标识) 稳定升序
 *       （事件 occurredAt+seq+id、用药 administeredAt+id、出入量 recordedAt+id、
 *       抢救 startedAt+id），不依赖 Mapper 的查询顺序，避免同毫秒乱序导致摘要不稳定；</li>
 *   <li>摘要算法 SHA-256（十六进制 64 字符）。修订快照包含上一修订摘要，形成版本哈希链；
 *       锁单摘要不含签名摘要（锁单时尚未签名）。{@link #contentHash(Map)} 只对临床内容取摘要
 *       （排除 signatures），用于验证“改回旧内容 → 内容摘要复原”。</li>
 * </ul>
 */
@Component
public class RecordRevisionSnapshotBuilder {

    /** 展示/摘要统一时区（与契约示例一致）。 */
    static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);

    /** 差异字段上限，避免大记录单把响应撑爆。 */
    private static final int MAX_DIFF_FIELDS = 100;

    /** 差异比较时跳过的元数据子树（签名为版本链元数据，不是临床内容）。 */
    private static final String SIGNATURES_KEY = "signatures";

    private final ObjectMapper objectMapper;
    /** 快照读取：小数统一 BigDecimal，避免 10.000 读成 10.0 造成同内容差异误报。 */
    private final ObjectReader snapshotReader;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 ObjectMapper 由容器管理，本类不修改其配置")
    public RecordRevisionSnapshotBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.snapshotReader = objectMapper.readerFor(Object.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /**
     * 构造修订/锁单快照。
     *
     * @param anesthesiaType        记录单麻醉方式（修订可经 corrections 更正，仅写入快照）
     * @param lockedSignatureHash   锁单签名摘要（锁单时未知，传 null）
     * @param previousSignatureHash 上一修订签名摘要（首版/锁单传 null）
     */
    public Map<String, Object> build(AnesthesiaRecordEntity record,
            List<RecordEventEntity> events, List<DrugAdministrationEntity> drugs,
            List<FluidRecordEntity> fluids, List<ResuscitationRecordEntity> rescues,
            Map<String, Object> fluidTotals, String anesthesiaType, String lockedSignatureHash,
            String previousSignatureHash) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        Map<String, Object> recordPart = new LinkedHashMap<>();
        recordPart.put("anesthesiaType", anesthesiaType);
        recordPart.put("startedAt", format(record.getStartedAt()));
        recordPart.put("endedAt", format(record.getEndedAt()));
        snapshot.put("record", recordPart);

        List<Map<String, Object>> eventPart = new ArrayList<>();
        if (events != null) {
            List<RecordEventEntity> orderedEvents = new ArrayList<>(events);
            orderedEvents.sort(Comparator
                    .comparing(RecordEventEntity::getOccurredAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(RecordEventEntity::getSeq,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(event -> idText(event.getId())));
            for (RecordEventEntity event : orderedEvents) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("eventId", string(event.getId()));
                item.put("seq", event.getSeq());
                item.put("eventType", event.getEventType());
                item.put("occurredAt", format(event.getOccurredAt()));
                item.put("source", event.getSource());
                // payload 原文不入快照：只取结构化的 note/entryReason 两个白名单字段。
                item.put("note", payloadText(event.getPayload(), "note"));
                item.put("entryReason", payloadText(event.getPayload(), "entryReason"));
                eventPart.add(item);
            }
        }
        snapshot.put("events", eventPart);

        List<Map<String, Object>> drugPart = new ArrayList<>();
        if (drugs != null) {
            List<DrugAdministrationEntity> orderedDrugs = new ArrayList<>(drugs);
            orderedDrugs.sort(Comparator
                    .comparing(DrugAdministrationEntity::getAdministeredAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(drug -> idText(drug.getId())));
            for (DrugAdministrationEntity drug : orderedDrugs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("drugId", string(drug.getId()));
                item.put("drugName", drug.getDrugName());
                item.put("dose", drug.getDose());
                item.put("unit", drug.getUnit());
                item.put("route", drug.getRoute());
                item.put("administeredAt", format(drug.getAdministeredAt()));
                item.put("narcotic", drug.getNarcotic());
                item.put("secondCheckerId", string(drug.getSecondCheckerId()));
                item.put("secondCheckerName", drug.getSecondCheckerName());
                item.put("note", drug.getNote());
                drugPart.add(item);
            }
        }
        snapshot.put("drugs", drugPart);

        List<Map<String, Object>> fluidPart = new ArrayList<>();
        if (fluids != null) {
            List<FluidRecordEntity> orderedFluids = new ArrayList<>(fluids);
            orderedFluids.sort(Comparator
                    .comparing(FluidRecordEntity::getRecordedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(fluid -> idText(fluid.getId())));
            for (FluidRecordEntity fluid : orderedFluids) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fluidId", string(fluid.getId()));
                item.put("direction", fluid.getDirection());
                item.put("fluidType", fluid.getFluidType());
                item.put("amountMl", fluid.getAmountMl());
                item.put("recordedAt", format(fluid.getRecordedAt()));
                item.put("note", fluid.getNote());
                fluidPart.add(item);
            }
        }
        snapshot.put("fluids", fluidPart);

        List<Map<String, Object>> rescuePart = new ArrayList<>();
        if (rescues != null) {
            List<ResuscitationRecordEntity> orderedRescues = new ArrayList<>(rescues);
            orderedRescues.sort(Comparator
                    .comparing(ResuscitationRecordEntity::getStartedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(rescue -> idText(rescue.getId())));
            for (ResuscitationRecordEntity rescue : orderedRescues) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rescueId", idText(rescue.getId()));
                item.put("resusType", rescue.getResusType());
                item.put("startedAt", format(rescue.getStartedAt()));
                item.put("endedAt", format(rescue.getEndedAt()));
                // 结构化 jsonb 内容全部纳入摘要；createdAt/createdBy 属审计字段，不入快照。
                item.put("eventSummary", jsonValue(rescue.getEventSummary()));
                item.put("drugs", jsonValue(rescue.getDrugs()));
                item.put("leader", jsonValue(rescue.getLeader()));
                item.put("rescuers", jsonValue(rescue.getRescuers()));
                item.put("outcome", rescue.getOutcome());
                rescuePart.add(item);
            }
        }
        snapshot.put("rescues", rescuePart);

        int intake = intOf(fluidTotals, "intake");
        int output = intOf(fluidTotals, "output");
        int count = intOf(fluidTotals, "fluid_count");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("intakeMl", intake);
        summary.put("outputMl", output);
        summary.put("balanceMl", intake - output);
        summary.put("fluidCount", count);
        snapshot.put("ioSummary", summary);

        Map<String, Object> signatures = new LinkedHashMap<>();
        signatures.put("lockedSignatureHash", lockedSignatureHash);
        signatures.put("previousRevisionSignatureHash", previousSignatureHash);
        snapshot.put("signatures", signatures);
        return snapshot;
    }

    /** 快照 JSON 文本（jsonb 落库与响应返回使用，不参与摘要）。 */
    public String toJson(Map<String, Object> snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException invalidSnapshot) {
            throw new IllegalStateException("修订快照序列化失败", invalidSnapshot);
        }
    }

    /** 读取 jsonb 快照文本为有序 Map（保持键顺序稳定）。 */
    public Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = snapshotReader.readValue(json);
            if (!(parsed instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (JsonProcessingException invalidSnapshot) {
            throw new IllegalStateException("修订快照解析失败", invalidSnapshot);
        }
    }

    /** 规范化 JSON 摘要（SHA-256 十六进制 64 字符）。 */
    public String hash(Map<String, Object> snapshot) {
        JsonNode node = objectMapper.valueToTree(snapshot);
        return sha256Hex(canonicalJson(node));
    }

    /**
     * 临床内容摘要（不含 {@code signatures} 版本链元数据）。
     *
     * <p>用于验证“同内容同摘要”：把记录改回旧内容（即使位于修订链的不同位置）时内容摘要复原；
     * 正式签名摘要由 {@link #hash(Map)} 计算并包含版本链，二者用途不同。
     */
    public String contentHash(Map<String, Object> snapshot) {
        Map<String, Object> content = new LinkedHashMap<>();
        if (snapshot != null) {
            for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                if (!SIGNATURES_KEY.equals(entry.getKey())) {
                    content.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return hash(content);
    }

    /**
     * 规范化 JSON 文本：对象键字典序、null 对象字段省略、数字去尾零、字符串标准转义。
     *
     * <p>公开为包级可见以便单测直接断言“同内容同摘要/不同内容不同摘要”的输入稳定性。
     */
    public String canonicalJson(JsonNode node) {
        StringBuilder out = new StringBuilder();
        appendCanonical(node, out);
        return out.toString();
    }

    /**
     * 计算快照差异（首版与锁单原始记录比较；{@code signatures} 元数据不参与比较）。
     * 字段路径按字典序输出，最多 {@value #MAX_DIFF_FIELDS} 项。
     */
    public RevisionDiff diff(Map<String, Object> previous, Map<String, Object> current) {
        Map<String, String> before = flatten(previous);
        Map<String, String> after = flatten(current);
        List<String> fields = new ArrayList<>();
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                fields.add(key);
            }
        }
        for (Map.Entry<String, String> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey())
                    || !before.get(entry.getKey()).equals(entry.getValue())) {
                fields.add(entry.getKey());
            }
        }
        fields.sort(Comparator.naturalOrder());
        List<RevisionFieldChange> changes = new ArrayList<>();
        for (String field : fields.subList(0, Math.min(fields.size(), MAX_DIFF_FIELDS))) {
            RevisionFieldChange change = new RevisionFieldChange();
            change.setField(field);
            change.setPreviousValue(before.get(field));
            change.setNewValue(after.get(field));
            changes.add(change);
        }
        RevisionDiff diff = new RevisionDiff();
        diff.setPreviousRevisionNo(0);
        diff.setChangedFields(changes);
        diff.setSummary(fields.isEmpty()
                ? "无内容差异（仅修订说明）"
                : "共 " + fields.size() + " 项字段变更");
        return diff;
    }

    private Map<String, String> flatten(Map<String, Object> snapshot) {
        Map<String, String> leaves = new TreeMap<>();
        if (snapshot != null) {
            for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                if (SIGNATURES_KEY.equals(entry.getKey())) {
                    continue;
                }
                flattenValue(entry.getKey(), entry.getValue(), leaves);
            }
        }
        return leaves;
    }

    private void flattenValue(String path, Object value, Map<String, String> leaves) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                flattenValue(path + "." + entry.getKey(), entry.getValue(), leaves);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                flattenValue(path + "[" + index + "]", list.get(index), leaves);
            }
            return;
        }
        leaves.put(path, scalarText(value));
    }

    private static String scalarText(Object value) {
        if (value instanceof BigDecimal decimal) {
            return normalizeDecimal(decimal);
        }
        return String.valueOf(value);
    }

    private void appendCanonical(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            out.append('{');
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            boolean first = true;
            for (String name : names) {
                JsonNode child = node.get(name);
                if (child == null || child.isNull()) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                out.append(quote(name)).append(':');
                appendCanonical(child, out);
                first = false;
            }
            out.append('}');
            return;
        }
        if (node.isArray()) {
            out.append('[');
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                appendCanonical(node.get(index), out);
            }
            out.append(']');
            return;
        }
        if (node.isNumber()) {
            out.append(normalizeDecimal(node.decimalValue()));
            return;
        }
        if (node.isBoolean()) {
            out.append(node.booleanValue());
            return;
        }
        out.append(quote(node.asText()));
    }

    private static String normalizeDecimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0);
        }
        return normalized.toPlainString();
    }

    /** JSON 字符串转义（RFC 8259；非 ASCII 原样保留，保证中文说明稳定且可读）。 */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    /** 解析落库的 jsonb 文本为有序对象/数组；内容非法时拒绝生成摘要（不能静默漏签）。 */
    private Object jsonValue(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return snapshotReader.readValue(json);
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalStateException("抢救记录 jsonb 内容无法解析，拒绝生成签名摘要",
                    invalidJson);
        }
    }

    private static String idText(Object value) {
        return value == null ? "" : value.toString();
    }

    private String payloadText(String payload, String field) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode value = node == null ? null : node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (JsonProcessingException invalidPayload) {
            return null;
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String format(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.atOffset(DISPLAY_OFFSET).truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static int intOf(Map<String, Object> row, String key) {
        if (row == null) {
            return 0;
        }
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
