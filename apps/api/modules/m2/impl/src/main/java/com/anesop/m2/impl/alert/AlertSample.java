package com.anesop.m2.impl.alert;

import com.anesop.m2.impl.persistence.entity.VitalRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 告警评估的体征采样（与持久层解耦，便于纯单测）。
 *
 * <p>只承载判定所需数值与质量码：quality ≥ 2（缺失/超时）的帧不参与判定；
 * MAP 由无创血压收缩/舒张压推导（{@code dia + (sys - dia) / 3}，保留 1 位小数），
 * 因为 V4 的 {@code anesthesia_vital} 只落收缩压/舒张压。
 *
 * @param measuredAt 采样时间（设备测量时间，评估窗口与去重窗口均以它为准）
 * @param hr         心率（bpm）
 * @param spo2       血氧饱和度（%）
 * @param map        平均动脉压（mmHg）
 * @param etco2      呼气末二氧化碳（mmHg）
 * @param temp       体温（℃）
 * @param quality    数据质量码（0 正常/1 可疑/2 缺失/3 超时）
 */
public record AlertSample(Instant measuredAt, BigDecimal hr, BigDecimal spo2, BigDecimal map,
        BigDecimal etco2, BigDecimal temp, int quality) {

    private static final BigDecimal THREE = BigDecimal.valueOf(3);

    /**
     * 由体征行构造评估采样；质量码 ≥ 2 的帧保留（由引擎跳过），以便统计采样帧数。
     */
    public static AlertSample from(VitalRow row) {
        return new AlertSample(row.getMeasuredAt(),
                decimal(row.getHr()),
                decimal(row.getSpo2()),
                meanArterialPressure(row.getNibpSys(), row.getNibpDia()),
                row.getEtco2(),
                row.getTemp(),
                row.getQuality() == null ? 0 : row.getQuality());
    }

    /** MAP = dia + (sys - dia) / 3（保留 1 位小数）；任一缺失则不可判定。 */
    static BigDecimal meanArterialPressure(Integer systolic, Integer diastolic) {
        if (systolic == null || diastolic == null) {
            return null;
        }
        BigDecimal sys = BigDecimal.valueOf(systolic);
        BigDecimal dia = BigDecimal.valueOf(diastolic);
        return dia.add(sys.subtract(dia).divide(THREE, 1, RoundingMode.HALF_UP));
    }

    private static BigDecimal decimal(Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
