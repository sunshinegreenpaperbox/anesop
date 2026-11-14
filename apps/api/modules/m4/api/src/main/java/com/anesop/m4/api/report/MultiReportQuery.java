package com.anesop.m4.api.report;

/**
 * 多维报表查询参数（契约 report.yaml 0.1.0，口径 report-1.0.0）。
 *
 * <p>维度 {@code dimensions} 与指标 {@code metrics} 均为逗号分隔白名单字符串，由服务层
 * 统一 trim/去重/白名单校验（违反 400）；分页 {@code page} 从 1 开始、{@code size} 1–200。
 * 参数保持原始类型（字符串/可空整数），便于服务层返回字段级 400 而不是依赖框架默认错误。
 *
 * @param from       起始本地日期（Asia/Shanghai，闭区间）
 * @param to         结束本地日期（闭区间，跨度 ≤ 366 天）
 * @param dimensions 逗号分隔维度（DEPT/STAFF/SURGERY/ROOM，去重后 1–4 个）
 * @param metrics    逗号分隔指标（caseCount/anesthesiaMinutes/weightedCaseCount，去重后 1–3 个）
 * @param deptId     可选科室过滤（UUID 字符串，受数据范围约束）
 * @param page       页码（可空，默认 1）
 * @param size       每页条数（可空，默认 20，上限 200）
 */
public record MultiReportQuery(String from, String to, String dimensions, String metrics,
        String deptId, Integer page, Integer size) {
}
