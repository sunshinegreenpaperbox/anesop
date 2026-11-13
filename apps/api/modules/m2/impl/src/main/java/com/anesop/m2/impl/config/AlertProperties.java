package com.anesop.m2.impl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 预警评估配置（{@code anesop.m2.alert.*}，M3-M2-02）。
 *
 * <p>M3-M2-02 评审 B-2：持续时长只用**该指标非空样本**的测量时间衡量，每类指标有独立的
 * 采样间隔容忍（连续波形与间歇测量差异过大，统一阈值必然误判）。默认值均为
 * 【待确认】临床口径，生产前须临床顾问逐条确认。
 *
 * @param hrGapSeconds    心率相邻有效采样的最大间隔（秒，1–3600，默认 10：连续波形）
 * @param spo2GapSeconds  血氧相邻有效采样的最大间隔（秒，1–3600，默认 10：连续波形）
 * @param mapGapSeconds   平均动脉压（由无创血压推导）相邻测量的最大间隔（秒，默认 300）
 * @param etco2GapSeconds 呼气末二氧化碳相邻有效采样的最大间隔（秒，默认 300）
 * @param tempGapSeconds  体温相邻测量的最大间隔（秒，默认 300）
 * @param maxSamples      单次评估每台设备读取的体征采样上限（100–200000，默认 20000）；
 *                        实时评估按水位回看窗口读取，超限保留最近采样并写 WARN 日志
 */
@ConfigurationProperties(prefix = "anesop.m2.alert")
public record AlertProperties(
        @DefaultValue("10") long hrGapSeconds,
        @DefaultValue("10") long spo2GapSeconds,
        @DefaultValue("300") long mapGapSeconds,
        @DefaultValue("300") long etco2GapSeconds,
        @DefaultValue("300") long tempGapSeconds,
        @DefaultValue("20000") int maxSamples) {

    /** 规则允许的最大持续时长（与 alert_rule.duration_seconds CHECK 一致）。 */
    public static final long MAX_RULE_DURATION_SECONDS = 3600;

    /** 配置自检：非法值启动即失败，避免运行期静默改变预警语义。 */
    public AlertProperties {
        requireGap(hrGapSeconds, "hr-gap-seconds");
        requireGap(spo2GapSeconds, "spo2-gap-seconds");
        requireGap(mapGapSeconds, "map-gap-seconds");
        requireGap(etco2GapSeconds, "etco2-gap-seconds");
        requireGap(tempGapSeconds, "temp-gap-seconds");
        if (maxSamples < 100 || maxSamples > 200_000) {
            throw new IllegalArgumentException(
                    "anesop.m2.alert.max-samples 必须在 100–200000 之间");
        }
    }

    /** 指标 → 相邻有效采样的最大间隔（秒）；未知指标按连续波形处理。 */
    public long gapSecondsOf(String metric) {
        if (metric == null) {
            return hrGapSeconds;
        }
        return switch (metric) {
            case "SPO2" -> spo2GapSeconds;
            case "MAP" -> mapGapSeconds;
            case "ETCO2" -> etco2GapSeconds;
            case "TEMP" -> tempGapSeconds;
            default -> hrGapSeconds;
        };
    }

    /**
     * 增量评估的回看窗口（秒）：最大规则时长 + 最大指标间隔容忍。
     *
     * <p>实时评估按“水位 − 回看窗口”读取采样以保持跨事件窗口连续；回看窗口足以覆盖
     * 任一越界窗口从起点到满足持续时长的全过程，避免水位推进把早期窗口起点截断。
     */
    public long lookbackSeconds() {
        long maxGap = Math.max(Math.max(hrGapSeconds, spo2GapSeconds),
                Math.max(Math.max(mapGapSeconds, etco2GapSeconds), tempGapSeconds));
        return MAX_RULE_DURATION_SECONDS + maxGap;
    }

    private static void requireGap(long value, String name) {
        if (value <= 0 || value > MAX_RULE_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                    "anesop.m2.alert." + name + " 必须在 1–3600 之间");
        }
    }
}
