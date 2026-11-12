package com.anesop.gateway.normalize;

import com.anesop.gateway.model.Quality;
import com.anesop.gateway.model.VitalSample;
import com.anesop.gateway.protocol.DeviceFrame;
import com.anesop.gateway.protocol.Measurement;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 体征标准化：单位换算、物理范围判定、质量标记、时钟偏差处理。
 *
 * <p>规则（方案书 §3.6.3 / §3.6.4）：
 * <ul>
 *   <li>单位：按 {@link Metric} 换算到规范单位；不可识别单位 → 值置空 + {@code <指标>:UNIT} + 可疑。</li>
 *   <li>范围：超物理范围 → <b>保留数值</b>，标记 {@code <指标>:RANGE} + 可疑（标记而非丢弃）。</li>
 *   <li>缺省：字段为 {@code -} → 值为空 + {@code <指标>:MISSING}；全部缺省时质量码升级为缺失。</li>
 *   <li>时钟：偏差 ≤ 阈值不动。超过阈值时<b>永不改写 measured_at</b>（它是幂等键的一部分，
 *       改写会让同一帧的重放插入新行）；校正结果写入 {@code measuredAtCorrected}：
 *       未来时钟或 ≤ backfill 阈值的过去偏差取接收时间，记 {@code ts:FUTURE/ts:SKEW:偏差毫秒}；
 *       偏差更大视为补传/回放，校正值等于原值，记 {@code ts:BACKFILL:偏差毫秒} + 可疑。</li>
 *   <li>质量码只降不升：设备标 2（缺失）不会被标准化"修好"。</li>
 * </ul>
 */
public final class VitalNormalizer {

    private final long maxSkewMillis;
    private final long backfillThresholdMillis;

    /**
     * 构造标准化器。
     *
     * @param maxClockSkewMillis      时钟偏差阈值（毫秒），超过即处理并标记
     * @param backfillThresholdMillis 超过该偏差的"过去时间"视为补传，保留设备时间
     */
    public VitalNormalizer(long maxClockSkewMillis, long backfillThresholdMillis) {
        if (maxClockSkewMillis < 0 || backfillThresholdMillis < maxClockSkewMillis) {
            throw new IllegalArgumentException("时钟阈值必须满足 0 <= maxSkew <= backfillThreshold");
        }
        this.maxSkewMillis = maxClockSkewMillis;
        this.backfillThresholdMillis = backfillThresholdMillis;
    }

    /**
     * 标准化一帧。
     *
     * @param frame  解析后的设备帧
     * @param source 数据来源（AUTO/MANUAL/REPLAY）
     * @return 标准化样本
     */
    public VitalSample normalize(DeviceFrame frame, String source) {
        Map<Metric, Measurement> inputs = new EnumMap<>(Metric.class);
        inputs.put(Metric.HR, frame.hr());
        inputs.put(Metric.SPO2, frame.spo2());
        inputs.put(Metric.NIBP_SYS, frame.nibpSys());
        inputs.put(Metric.NIBP_DIA, frame.nibpDia());
        inputs.put(Metric.ETCO2, frame.etco2());
        inputs.put(Metric.TEMP, frame.temp());

        Map<Metric, BigDecimal> values = new EnumMap<>(Metric.class);
        List<String> flags = new ArrayList<>();
        int quality = frame.quality();

        for (Map.Entry<Metric, Measurement> entry : inputs.entrySet()) {
            Metric metric = entry.getKey();
            Measurement raw = entry.getValue();
            if (raw.absent()) {
                flags.add(metric.code() + ":MISSING");
                continue;
            }
            Optional<BigDecimal> converted = metric.convert(raw.value(), raw.unit());
            if (converted.isEmpty()) {
                flags.add(metric.code() + ":UNIT");
                quality = Quality.worst(quality, Quality.SUSPECT);
                continue;
            }
            BigDecimal canonical = metric.canonical(converted.get());
            if (!metric.inRange(canonical)) {
                flags.add(metric.code() + ":RANGE");
                quality = Quality.worst(quality, Quality.SUSPECT);
            }
            values.put(metric, canonical);
        }

        if (values.isEmpty()) {
            quality = Quality.worst(quality, Quality.MISSING);
        }

        Instant measuredAt = frame.measuredAt();
        Instant measuredAtCorrected = measuredAt;
        long skewMillis = Duration.between(frame.receivedAt(), measuredAt).toMillis();
        if (Math.abs(skewMillis) > maxSkewMillis) {
            quality = Quality.worst(quality, Quality.SUSPECT);
            if (skewMillis > 0) {
                measuredAtCorrected = frame.receivedAt();
                flags.add("ts:FUTURE:" + skewMillis);
            } else if (-skewMillis <= backfillThresholdMillis) {
                measuredAtCorrected = frame.receivedAt();
                flags.add("ts:SKEW:" + skewMillis);
            } else {
                flags.add("ts:BACKFILL:" + skewMillis);
            }
        }

        return new VitalSample(
                frame.deviceId(),
                frame.seq(),
                measuredAt,
                measuredAtCorrected,
                frame.receivedAt(),
                toInteger(values.get(Metric.HR)),
                toInteger(values.get(Metric.SPO2)),
                toInteger(values.get(Metric.NIBP_SYS)),
                toInteger(values.get(Metric.NIBP_DIA)),
                values.get(Metric.ETCO2),
                values.get(Metric.TEMP),
                quality,
                flags,
                source);
    }

    private static Integer toInteger(BigDecimal value) {
        return value == null ? null : value.intValueExact();
    }
}
