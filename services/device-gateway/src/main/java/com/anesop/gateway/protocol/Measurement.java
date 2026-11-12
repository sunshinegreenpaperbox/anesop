package com.anesop.gateway.protocol;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单个数值型体征字段的原始值：数值 + 可选单位后缀。
 *
 * <p>协议 v1 允许 {@code 76}、{@code 76bpm}、{@code 16.0kPa}、{@code 98.6F} 三种写法，
 * 也允许缺省符 {@code -} 表示该指标本次未采到。单位换算与合理性判定在
 * {@code VitalNormalizer} 中做（语法与语义分离，便于排障时区分"帧坏了"与"值可疑"）。
 *
 * @param value 数值；缺省时为 {@code null}
 * @param unit  单位后缀；无后缀时为空串
 */
public record Measurement(BigDecimal value, String unit) {

    /** 数值 + 可选字母/百分号单位；不允许科学计数法与千分位。 */
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile("^([+-]?\\d{1,9}(?:\\.\\d{1,6})?)([A-Za-z%]{0,8})$");

    /** 缺省符。 */
    private static final String ABSENT = "-";

    /** 最大整数字符数，与正则、数据库护栏保持一致。 */
    private static final int MAX_INTEGER_DIGITS = 9;

    /**
     * 规范构造器：保证 unit 非 null，数值位数不超限。
     *
     * @param value 数值
     * @param unit  单位
     */
    public Measurement {
        unit = unit == null ? "" : unit;
        if (value != null && value.precision() - value.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("数值整数位超限");
        }
    }

    /**
     * 缺省值（本次未采到）。
     *
     * @return 值为 null、单位为空的实例
     */
    public static Measurement missing() {
        return new Measurement(null, "");
    }

    /**
     * 解析字段文本。
     *
     * @param token 字段原文
     * @param field 字段名（用于错误定位）
     * @return 解析结果
     * @throws FrameParseException 文本为空、非数值或格式非法
     */
    public static Measurement parse(String token, String field) {
        if (token == null || token.isEmpty()) {
            throw new FrameParseException(FrameError.MISSING_VALUE, field);
        }
        if (ABSENT.equals(token)) {
            return missing();
        }
        Matcher matcher = NUMBER_WITH_UNIT.matcher(token);
        if (!matcher.matches()) {
            throw new FrameParseException(FrameError.BAD_NUMBER, field);
        }
        return new Measurement(new BigDecimal(matcher.group(1)), matcher.group(2));
    }

    /**
     * 是否缺省（本次未采到）。
     *
     * @return true 表示缺省
     */
    public boolean absent() {
        return value == null;
    }
}
