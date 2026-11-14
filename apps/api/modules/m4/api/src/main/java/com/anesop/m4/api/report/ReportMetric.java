package com.anesop.m4.api.report;

import com.anesop.m4.api.error.M4ValidationException;
import java.util.Locale;

/**
 * 报表指标白名单（契约 report.yaml 0.1.0，口径 report-1.0.0）。
 *
 * <ul>
 *   <li>{@link #CASE_COUNT}：台次（统计边界内非取消台次）；</li>
 *   <li>{@link #ANESTHESIA_MINUTES}：麻醉时长（分钟，记录单实际起止差向下取整）；</li>
 *   <li>{@link #WEIGHTED_CASE_COUNT}：加权台次（Σ 台次权重，默认 1.0，绩效规则院方另定）。</li>
 * </ul>
 *
 * <p>{@code unrecordedCaseCount}（无可用时长的台次）不是可选指标，而是随结果固定返回的
 * 数据质量字段；这样多维报表不会因为未选择该指标而丢失“有台次无时长”的差异。
 */
public enum ReportMetric {

    /** 台次。 */
    CASE_COUNT("caseCount", "台次"),

    /** 麻醉时长（分钟）。 */
    ANESTHESIA_MINUTES("anesthesiaMinutes", "麻醉时长（分钟）"),

    /** 加权台次。 */
    WEIGHTED_CASE_COUNT("weightedCaseCount", "加权台次");

    private final String code;
    private final String label;

    ReportMetric(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 契约中的指标编码（camelCase，如 {@code caseCount}）。 */
    public String code() {
        return code;
    }

    /** 指标中文名。 */
    public String label() {
        return label;
    }

    /**
     * 解析查询参数指标：trim + 忽略大小写，非法值 400（固定安全文案，不回显用户输入）。
     *
     * @param value 原始指标编码
     * @return 指标枚举
     */
    public static ReportMetric parse(String value) {
        if (value == null || value.isBlank()) {
            throw new M4ValidationException("metrics", "metrics 不能为空");
        }
        String normalized = value.trim();
        for (ReportMetric metric : values()) {
            if (metric.code.equalsIgnoreCase(normalized)
                    || metric.name().equalsIgnoreCase(normalized.replace('-', '_'))) {
                return metric;
            }
        }
        throw new M4ValidationException("metrics", "metrics 必须为 caseCount/anesthesiaMinutes/"
                + "weightedCaseCount");
    }

    /** 便于日志/审计输出的稳定编码（小写常量）。 */
    public String auditCode() {
        return code.toLowerCase(Locale.ROOT);
    }
}
