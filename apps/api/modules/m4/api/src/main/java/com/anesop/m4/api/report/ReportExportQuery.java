package com.anesop.m4.api.report;

/**
 * 报表 CSV 导出参数（契约 report.yaml 0.1.0）。
 *
 * @param report     报表类型：{@code workload} 或 {@code multi}
 * @param format     导出格式，当前仅 {@code csv}
 * @param from       起始本地日期（Asia/Shanghai，闭区间）
 * @param to         结束本地日期（闭区间，跨度 ≤ 366 天）
 * @param dimension  工作量维度（{@code report=workload} 必填）
 * @param dimensions 多维维度（{@code report=multi} 必填，逗号分隔白名单）
 * @param metrics    多维指标（{@code report=multi} 必填，逗号分隔白名单）
 * @param deptId     可选科室过滤（UUID 字符串）
 */
public record ReportExportQuery(String report, String format, String from, String to,
        String dimension, String dimensions, String metrics, String deptId) {
}
