package com.anesop.m4.api.qc;

/**
 * 周期指标结果查询（M4-M17-01）。
 *
 * @param from   起始本地日期 YYYY-MM-DD（含，必填）
 * @param to     结束本地日期 YYYY-MM-DD（含，必填；跨度 ≤ 366 天）
 * @param deptId 科室标识（可空；只收窄，不扩大数据范围）
 */
public record QcIndicatorResultQuery(String from, String to, String deptId) {
}
