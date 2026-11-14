package com.anesop.m4.api.qc;

/**
 * 病历质量抽查列表查询（M4-M17-01）。
 *
 * @param status 状态过滤（OPEN/RECTIFYING/CLOSED，可空）
 * @param from   抽查时间起始本地日期（可空）
 * @param to     抽查时间结束本地日期（可空；与 from 同时给出时跨度 ≤ 366 天）
 * @param deptId 科室标识（可空；越界 403）
 * @param page   页码（1 起，默认 1）
 * @param size   每页条数（1–200，默认 20）
 */
public record ChartReviewQuery(String status, String from, String to, String deptId,
        Integer page, Integer size) {
}
