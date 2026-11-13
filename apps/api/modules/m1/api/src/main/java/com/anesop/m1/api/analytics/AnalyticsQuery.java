package com.anesop.m1.api.analytics;

/**
 * 排程分析查询参数（契约 scheduling.yaml 0.6.0）。
 *
 * <p>{@code from}/{@code to} 为 {@code Asia/Shanghai} 本地日期（闭区间，必填，跨度 ≤ 92 天）；
 * {@code deptId} 可选，用于按术间所属科室过滤，并受调用方数据范围约束。
 *
 * <p>字段保持原始字符串，由服务层统一解析与校验，确保非法日期/标识返回 400 而不是 500。
 */
public record AnalyticsQuery(String from, String to, String deptId) {

    /** 便捷构造：默认不带科室过滤。 */
    public AnalyticsQuery(String from, String to) {
        this(from, to, null);
    }
}
