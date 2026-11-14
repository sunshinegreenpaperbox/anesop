package com.anesop.m4.api.report;

/**
 * 工作量统计查询参数（契约 report.yaml 0.1.0，口径 report-1.0.0）。
 *
 * <p>{@code from}/{@code to} 为 {@code Asia/Shanghai} 本地日期（闭区间，必填，跨度 ≤ 366 天）；
 * {@code dimension} 为维度白名单（DEPT/STAFF/SURGERY/ROOM，必填）；{@code deptId} 可选，
 * 仅做收窄过滤并受调用方数据范围约束。
 *
 * <p>字段保持原始字符串，由服务层统一解析与校验，确保非法日期/维度/标识返回 400 而不是 500。
 */
public record ReportQuery(String from, String to, String dimension, String deptId) {

    /** 便捷构造：不带科室过滤。 */
    public ReportQuery(String from, String to, String dimension) {
        this(from, to, dimension, null);
    }
}
