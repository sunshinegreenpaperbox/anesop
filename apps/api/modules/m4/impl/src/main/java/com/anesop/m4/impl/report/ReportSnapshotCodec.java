package com.anesop.m4.impl.report;

import com.anesop.contracts.report.model.MultiRow;
import com.anesop.contracts.report.model.ReportTotals;
import com.anesop.contracts.report.model.WorkloadReport;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 报表锁定快照编解码（V28，契约 report.yaml 0.2.0）。
 *
 * <p>职责：
 * <ul>
 *   <li>锁定参数规范化：固定键集合 + 键排序 → 稳定 JSON（同一参数组合必须得到同一哈希，
 *       否则“同参数查询命中快照”不成立）；</li>
 *   <li>sha256（十六进制 64 位）用于 {@code params_hash}/{@code payload_hash}/{@code scope_hash}；</li>
 *   <li>payload 编解码：workload 存完整 {@link WorkloadReport}（不含分页）；multi 存**全部分组行**
 *       与合计（分页在读取时重放），保证不同 page/size 命中同一快照。</li>
 * </ul>
 *
 * <p>payload 只含聚合值与维度标签（科室/术间/术式/院内在册人员工号姓名），不含患者标识。
 * 读取失败视为数据损坏（500），**不静默降级为空结果**——静默降级会让锁定报表变成 0，
 * 属于运营口径留痕完整性问题。
 */
@Component
public class ReportSnapshotCodec {

    private static final String TYPE_WORKLOAD = "workload";
    private static final String TYPE_MULTI = "multi";
    private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    /**
     * 使用注入 ObjectMapper 的**副本**并补齐 java.time 的 ISO 编解码。
     *
     * <p>副本避免修改 Spring 共享 ObjectMapper 的配置；显式注册 LocalDate/OffsetDateTime 的
     * {@code toString}/{@code parse} 编解码，使快照 payload 的日期字段在不上 jsr310 模块的
     * 模块单测环境下也能稳定往返（生产环境与 jsr310 的 ISO 输出一致）。
     */
    public ReportSnapshotCodec(ObjectMapper objectMapper) {
        SimpleModule timeModule = new SimpleModule("report-snapshot-time");
        timeModule.addSerializer(LocalDate.class, ToStringSerializer.instance);
        timeModule.addSerializer(OffsetDateTime.class, ToStringSerializer.instance);
        timeModule.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override
            public LocalDate deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                return LocalDate.parse(parser.getValueAsString());
            }
        });
        timeModule.addDeserializer(OffsetDateTime.class, new JsonDeserializer<>() {
            @Override
            public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                return OffsetDateTime.parse(parser.getValueAsString());
            }
        });
        this.objectMapper = objectMapper.copy().registerModule(timeModule);
    }

    /** 锁定参数规范化 JSON：键按字典序（嵌套仅含标量与数组，数组顺序有业务含义、不排序）。 */
    public String canonical(Map<String, Object> params) {
        Map<String, Object> sorted = new TreeMap<>(params == null ? Map.of() : params);
        try {
            return objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(sorted);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("报表锁定参数序列化失败", failure);
        }
    }

    /** sha256 十六进制（小写 64 位）。 */
    public String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                hex.append(Character.forDigit((current >> 4) & 0xF, 16));
                hex.append(Character.forDigit(current & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", missing);
        }
    }

    /** 参数 JSON → Map（详情展示用；与写入时的规范化 JSON 完全一致）。 */
    public Map<String, Object> readParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> params = objectMapper.readValue(json, PARAMS_TYPE);
            return params == null ? Map.of() : params;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("报表锁定参数 jsonb 解析失败", failure);
        }
    }

    /** workload 快照 payload（含完整维度桶与术式分布，不含分页）。 */
    public String writeWorkload(WorkloadReport report, int rowCount) {
        return write(new WorkloadPayload(TYPE_WORKLOAD, report, rowCount));
    }

    /** 读取 workload 快照；类型不符或 JSON 损坏抛 IllegalStateException（500）。 */
    public WorkloadReport readWorkload(String payload) {
        WorkloadPayload parsed = read(payload, WorkloadPayload.class);
        if (parsed.workload() == null || !TYPE_WORKLOAD.equals(parsed.report())) {
            throw new IllegalStateException("报表快照 payload 类型不匹配：期望 workload");
        }
        return parsed.workload();
    }

    /** multi 快照 payload（全部分组行 + 合计；分页由读取方重放）。 */
    public String writeMulti(MultiSnapshot snapshot) {
        return write(new MultiPayload(TYPE_MULTI, snapshot));
    }

    /** 读取 multi 快照；类型不符或 JSON 损坏抛 IllegalStateException（500）。 */
    public MultiSnapshot readMulti(String payload) {
        MultiPayload parsed = read(payload, MultiPayload.class);
        if (parsed.multi() == null || !TYPE_MULTI.equals(parsed.report())) {
            throw new IllegalStateException("报表快照 payload 类型不匹配：期望 multi");
        }
        return parsed.multi();
    }

    private String write(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("报表快照 payload 序列化失败", failure);
        }
    }

    private <T> T read(String payload, Class<T> type) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalStateException("报表快照 payload 为空");
        }
        try {
            T parsed = objectMapper.readValue(payload, type);
            if (parsed == null) {
                throw new IllegalStateException("报表快照 payload 解析为空");
            }
            return parsed;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("报表快照 payload 解析失败", failure);
        }
    }

    /**
     * multi 快照数据：期间、维度/指标、全部分组行与合计。
     *
     * <p>内部值对象：由本编解码器从 payload 构造，只在 {@code ReportServiceImpl} 内单次读取；
     * 字段为不可变快照语义（不做防御性拷贝，避免每页查询复制全部行）。缺少这份说明时
     * SpotBugs 会按“可变列表外泄”告警，抑制仅限本记录。
     */
    @SuppressFBWarnings(value = "EI_EXPOSE_REP",
            justification = "内部快照值对象：仅由编解码器构造并在服务内只读消费，不对外暴露")
    public record MultiSnapshot(LocalDate from, LocalDate to, List<String> dimensions,
            List<String> metrics, List<MultiRow> rows, ReportTotals totals, int rowCount) {
    }

    record WorkloadPayload(String report, WorkloadReport workload, int rowCount) {
    }

    record MultiPayload(String report, MultiSnapshot multi) {
    }
}
