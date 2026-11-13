package com.anesop.m2.impl.alert;

import com.anesop.m2.impl.config.AlertProperties;
import com.anesop.m2.impl.persistence.entity.AlertRuleEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 术中预警规则引擎（M3-M2-02）：纯判定逻辑，不触碰数据库，便于边界单测。
 *
 * <p>规则口径（阈值与间隔容忍均标【待确认】，最终以临床顾问确认为准）：
 * <ul>
 *   <li>LT：{@code value < thresholdLow}；GT：{@code value > thresholdHigh}；
 *       OUT_OF_RANGE：{@code value < thresholdLow || value > thresholdHigh}；
 *       <b>等于阈值不触发</b>（严格不等）；</li>
 *   <li>持续时长只用<b>该指标非空样本</b>的测量时间衡量：该指标为空（NIBP 两次袖带测量之间
 *       的空帧）或质量码 ≥2 的帧<b>跳过且不重置窗口</b>，由每类指标的间隔容忍判定是否切窗；</li>
 *   <li>每类指标独立间隔容忍（{@link AlertProperties#gapSecondsOf(String)}，默认连续波形
 *       HR/SpO2 10s、NIBP（MAP）/EtCO2/体温 300s）：相邻两次有效测量的间隔超过容忍即视为
 *       <b>新窗口</b>；空帧不重置窗口，但长时间空洞会因间隔超限自然切窗；</li>
 *   <li>重复值不累加：间歇指标（MAP/ETCO2/TEMP）在间隔容忍内重复上报同一测得值时，视为
 *       同一次测量的重复帧，不推进“最后一次有效测量时间”、不累加持续时长；</li>
 *   <li>每个越界窗口最多产出一条判定（幂等）；去重键 = recordId:ruleCode:窗口起始秒；
 *       处置后复发（新窗口）生成新告警；</li>
 *   <li>增量评估：{@code emitAfter}（已评估水位）之后触发的判定才会返回，避免用当前规则
 *       回评历史窗口；水位之前的采样仅用于保持跨事件窗口连续。</li>
 * </ul>
 *
 * <p>本类无状态、线程安全；指标值统一用 {@link BigDecimal#compareTo} 比较，避免浮点误差
 * 影响“等于阈值”的边界判定。
 */
@Component
public class AlertRuleEngine {

    private static final Map<String, String> UNITS = Map.of(
            "HR", "bpm",
            "SPO2", "%",
            "MAP", "mmHg",
            "ETCO2", "mmHg",
            "TEMP", "℃");

    /** 间歇测量指标：监护仪会在两次袖带/采样之间重复上报同一测得值。 */
    private static final Set<String> INTERMITTENT_METRICS = Set.of("MAP", "ETCO2", "TEMP");

    private final AlertProperties properties;

    public AlertRuleEngine(AlertProperties properties) {
        this.properties = properties;
    }

    /**
     * 评估启用规则（全窗口，不做水位过滤；仅供调试/单测）。
     *
     * @param recordId 记录单标识（根去重键）
     * @param rules    启用规则（当前版本）
     * @param samples  评估窗口内的体征采样（顺序不限，内部按 measuredAt 升序）
     */
    public List<AlertDecision> evaluate(UUID recordId, List<AlertRuleEntity> rules,
            List<AlertSample> samples) {
        return evaluate(recordId, rules, samples, null);
    }

    /**
     * 评估启用规则，只返回 {@code triggeredAt > emitAfter} 的判定（增量/水位语义）。
     *
     * @param emitAfter 已评估水位；为 null 表示全量（仅调试）。水位之前的采样仍参与窗口
     *                  连续性计算，但不会重复产出历史判定
     */
    public List<AlertDecision> evaluate(UUID recordId, List<AlertRuleEntity> rules,
            List<AlertSample> samples, Instant emitAfter) {
        if (recordId == null || rules == null || rules.isEmpty()
                || samples == null || samples.isEmpty()) {
            return List.of();
        }
        List<AlertSample> ordered = new ArrayList<>(samples);
        ordered.sort(Comparator.comparing(AlertSample::measuredAt));
        List<AlertDecision> decisions = new ArrayList<>();
        for (AlertRuleEntity rule : rules) {
            decisions.addAll(evaluateRule(recordId, rule, ordered, emitAfter));
        }
        decisions.sort(Comparator.comparing(AlertDecision::ruleCode)
                .thenComparing(AlertDecision::triggeredAt));
        return decisions;
    }

    private List<AlertDecision> evaluateRule(UUID recordId, AlertRuleEntity rule,
            List<AlertSample> samples, Instant emitAfter) {
        List<AlertDecision> decisions = new ArrayList<>();
        if (rule == null || rule.getId() == null || rule.getMetric() == null
                || rule.getOperator() == null) {
            return decisions;
        }
        long durationSeconds = rule.getDurationSeconds() == null ? 0
                : Math.max(0, rule.getDurationSeconds());
        long gapSeconds = properties.gapSecondsOf(rule.getMetric());
        Duration gapTolerance = Duration.ofSeconds(gapSeconds);
        boolean intermittent = isIntermittent(rule.getMetric());
        Instant windowStart = null;
        Instant lastAdvanceAt = null;
        Instant emittedWindowStart = null;
        BigDecimal lastValue = null;
        for (AlertSample sample : samples) {
            Instant measuredAt = sample.measuredAt();
            if (measuredAt == null || sample.quality() >= 2) {
                // 缺失/超时帧不可判定；跳过但不重置窗口（窗口由指标间隔容忍切分）。
                continue;
            }
            BigDecimal value = metricValue(sample, rule.getMetric());
            if (value == null) {
                // 该指标空帧（如 NIBP 两次测量之间）：不参与判定，也不重置窗口。
                continue;
            }
            if (intermittent && lastValue != null && lastAdvanceAt != null
                    && value.compareTo(lastValue) == 0
                    && Duration.between(lastAdvanceAt, measuredAt).compareTo(gapTolerance) < 0) {
                // 间歇指标在间隔容忍内重复上报同一测得值：同一次测量，不推进、不累加。
                continue;
            }
            lastValue = value;
            if (!breaches(rule, value)) {
                windowStart = null;
                lastAdvanceAt = null;
                emittedWindowStart = null;
                continue;
            }
            if (windowStart == null || lastAdvanceAt == null
                    || Duration.between(lastAdvanceAt, measuredAt).compareTo(gapTolerance) > 0) {
                windowStart = measuredAt;
            }
            boolean durationReached = durationSeconds == 0
                    || !measuredAt.isBefore(windowStart.plusSeconds(durationSeconds));
            boolean afterWatermark = emitAfter == null || measuredAt.isAfter(emitAfter);
            if (durationReached && afterWatermark && !windowStart.equals(emittedWindowStart)) {
                decisions.add(new AlertDecision(rule.getId(), rule.getCode(),
                        rule.getVersion() == null ? 1 : rule.getVersion(), rule.getMetric(), value,
                        UNITS.getOrDefault(rule.getMetric(), ""), rule.getSeverity(),
                        measuredAt, dedupeKey(recordId, rule.getCode(), windowStart)));
                emittedWindowStart = windowStart;
            }
            lastAdvanceAt = measuredAt;
        }
        return decisions;
    }

    /** 严格不等：等于阈值不越界（OUT_OF_RANGE 的区间为闭区间）。 */
    static boolean breaches(AlertRuleEntity rule, BigDecimal value) {
        BigDecimal low = rule.getThresholdLow();
        BigDecimal high = rule.getThresholdHigh();
        return switch (rule.getOperator()) {
            case "LT" -> low != null && value.compareTo(low) < 0;
            case "GT" -> high != null && value.compareTo(high) > 0;
            case "OUT_OF_RANGE" -> (low != null && value.compareTo(low) < 0)
                    || (high != null && value.compareTo(high) > 0);
            default -> false;
        };
    }

    /** 间歇测量指标（重复值不累加持续时长）。 */
    public static boolean isIntermittent(String metric) {
        return metric != null && INTERMITTENT_METRICS.contains(metric);
    }

    private static BigDecimal metricValue(AlertSample sample, String metric) {
        return switch (metric) {
            case "HR" -> sample.hr();
            case "SPO2" -> sample.spo2();
            case "MAP" -> sample.map();
            case "ETCO2" -> sample.etco2();
            case "TEMP" -> sample.temp();
            default -> null;
        };
    }

    private static String dedupeKey(UUID recordId, String ruleCode, Instant windowStart) {
        return recordId + ":" + ruleCode + ":" + windowStart.getEpochSecond();
    }

    /** 指标单位（展示用）；未知指标返回空串。 */
    public static String unitOf(String metric) {
        return UNITS.getOrDefault(metric, "");
    }
}
