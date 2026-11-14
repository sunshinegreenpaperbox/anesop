package com.anesop.m4.api.qc;

/**
 * 质控上报导出查询（M4-M17-01）。
 *
 * @param type   导出类型：indicator（指标结果）/adverse（不良事件）/review（病历抽查）
 * @param format 导出格式：当前仅 csv
 * @param from   起始本地日期 YYYY-MM-DD（含，必填）
 * @param to     结束本地日期 YYYY-MM-DD（含，必填；跨度 ≤ 366 天）
 * @param deptId 科室标识（可空；越界 403）
 */
public record QcExportQuery(String type, String format, String from, String to, String deptId) {
}
