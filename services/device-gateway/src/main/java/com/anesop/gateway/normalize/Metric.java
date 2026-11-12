package com.anesop.gateway.normalize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

/**
 * 体征指标目录：规范单位、单位别名与换算、物理合理范围。
 *
 * <p>换算只覆盖协议 v1 模拟设备会出现的单位（kPa、℉、/min、pct 等）；
 * 未识别的单位返回 {@link #convert} 的空结果，由标准化层标记"单位不可信"，
 * 不允许带着未知单位入库。
 */
public enum Metric {

    /** 心率。 */
    HR("hr", "bpm", new BigDecimal("20"), new BigDecimal("300"), 0, Family.RATE),
    /** 脉搏血氧饱和度。 */
    SPO2("spo2", "%", new BigDecimal("50"), new BigDecimal("100"), 0, Family.PERCENT),
    /** 无创收缩压。 */
    NIBP_SYS("nibp_sys", "mmHg", new BigDecimal("40"), new BigDecimal("300"), 0, Family.PRESSURE),
    /** 无创舒张压。 */
    NIBP_DIA("nibp_dia", "mmHg", new BigDecimal("20"), new BigDecimal("200"), 0, Family.PRESSURE),
    /** 呼气末二氧化碳。 */
    ETCO2("etco2", "mmHg", new BigDecimal("0"), new BigDecimal("150"), 1, Family.PRESSURE),
    /** 体温。 */
    TEMP("temp", "C", new BigDecimal("30"), new BigDecimal("45"), 1, Family.TEMPERATURE);

    /** 1 kPa = 7.50062 mmHg（0 ℃、标准重力加速度）。 */
    private static final BigDecimal KPA_TO_MMHG = new BigDecimal("7.50062");

    private static final BigDecimal FIVE = new BigDecimal("5");
    private static final BigDecimal NINE = new BigDecimal("9");
    private static final BigDecimal THIRTY_TWO = new BigDecimal("32");

    private final String code;
    private final String canonicalUnit;
    private final BigDecimal low;
    private final BigDecimal high;
    private final int scale;
    private final Family family;

    Metric(String code, String canonicalUnit, BigDecimal low, BigDecimal high, int scale, Family family) {
        this.code = code;
        this.canonicalUnit = canonicalUnit;
        this.low = low;
        this.high = high;
        this.scale = scale;
        this.family = family;
    }

    /**
     * 指标编码（写入质量标记，与数据库列名一致）。
     *
     * @return 编码
     */
    public String code() {
        return code;
    }

    /**
     * 规范单位。
     *
     * @return 单位文本
     */
    public String canonicalUnit() {
        return canonicalUnit;
    }

    /**
     * 物理合理范围下限（含）。
     *
     * @return 下限
     */
    public BigDecimal low() {
        return low;
    }

    /**
     * 物理合理范围上限（含）。
     *
     * @return 上限
     */
    public BigDecimal high() {
        return high;
    }

    /**
     * 入库小数位。
     *
     * @return 小数位
     */
    public int scale() {
        return scale;
    }

    /**
     * 该数值是否落在物理合理范围。
     *
     * @param value 规范单位下的数值
     * @return true 表示合理
     */
    public boolean inRange(BigDecimal value) {
        return value.compareTo(low) >= 0 && value.compareTo(high) <= 0;
    }

    /**
     * 单位换算到规范单位。
     *
     * @param value 原始数值
     * @param unit  原始单位（空串表示按规范单位解释）
     * @return 换算后的数值；单位不可识别时为空
     */
    public Optional<BigDecimal> convert(BigDecimal value, String unit) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.equals(canonicalUnit.toLowerCase(Locale.ROOT))
                || isAlias(normalized)) {
            return Optional.of(value);
        }
        return switch (normalized) {
            case "kpa" -> family == Family.PRESSURE
                    ? Optional.of(value.multiply(KPA_TO_MMHG))
                    : Optional.empty();
            case "f", "°f", "fahrenheit" -> family == Family.TEMPERATURE
                    ? Optional.of(value.subtract(THIRTY_TWO).multiply(FIVE).divide(NINE, 4, RoundingMode.HALF_UP))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    /**
     * 按规范单位与小数位整理数值。
     *
     * @param value 数值
     * @return 整理后的数值
     */
    public BigDecimal canonical(BigDecimal value) {
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    private boolean isAlias(String normalized) {
        return switch (family) {
            case RATE -> "1/min".equals(normalized) || "/min".equals(normalized) || "min-1".equals(normalized);
            case PERCENT -> "pct".equals(normalized);
            case PRESSURE -> "torr".equals(normalized) || "mmhg".equals(normalized);
            case TEMPERATURE -> "c".equals(normalized) || "°c".equals(normalized) || "celsius".equals(normalized);
            default -> false;
        };
    }

    /** 单位族：决定哪些换算可用。 */
    private enum Family {
        RATE,
        PERCENT,
        PRESSURE,
        TEMPERATURE
    }
}
