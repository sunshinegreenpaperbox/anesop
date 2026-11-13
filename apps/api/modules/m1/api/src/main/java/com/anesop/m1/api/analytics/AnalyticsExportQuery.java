package com.anesop.m1.api.analytics;

/**
 * 排程分析 CSV 导出参数（契约 scheduling.yaml 0.6.0）。
 *
 * @param metric 指标：utilization / on-time / cancellations
 * @param from   起始本地日期（Asia/Shanghai，闭区间）
 * @param to     结束本地日期（Asia/Shanghai，闭区间）
 * @param format 导出格式，当前仅 csv
 * @param deptId 可选科室标识
 */
public record AnalyticsExportQuery(String metric, String from, String to, String format, String deptId) {

    /** 转换为分析查询（保留原始字符串，由服务层统一校验）。 */
    public AnalyticsQuery range() {
        return new AnalyticsQuery(from, to, deptId);
    }
}
