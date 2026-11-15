package com.anesop.m5.api.dashboard;

/**
 * 当班人数（按班组，M5-M18-02）：单个班次的人数计数。
 *
 * <p>来源为 M4 人员排班（`m4_staff.staff_schedule`）当日 `status=PLANNED` 的记录；
 * 只输出班次与人数，**不含人员标识、姓名与工号**（看板无人员维度 PHI 风险）。
 *
 * @param shift 班次原文（DAY=白班、NIGHT=夜班、ON_CALL=备班；与 M4 排班口径一致）
 * @param count 该班次人数（同一人同日多班次按 1 人计）
 */
public record DashboardStaffShift(String shift, int count) {
}
