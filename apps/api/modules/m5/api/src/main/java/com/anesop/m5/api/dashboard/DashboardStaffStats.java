package com.anesop.m5.api.dashboard;

import java.util.List;

/**
 * 当班人员统计（M5-M18-02）：总人数 + 按班组分组，只含计数。
 *
 * <p>`total` 为当日排班人数合计（按人员标识去重：同一人同日多班次只计 1 人），
 * `byShift` 为按班次的去重人数（同一人在白班与备班各出现时，两班各计 1，故各班组之和
 * 可能大于 `total`——这是“班组人数”与“在岗人数”的语义差异，前端不得用 buckets 求和替代
 * `total`）。
 *
 * @param total   当日当班人数合计（按人员去重）
 * @param byShift 按班次分组（顺序 DAY→NIGHT→ON_CALL，只含人数 &gt; 0 的班次）
 */
public record DashboardStaffStats(int total, List<DashboardStaffShift> byShift) {

    /** 规整为不可变列表，避免调用方后续修改影响响应。 */
    public DashboardStaffStats {
        byShift = byShift == null ? List.of() : List.copyOf(byShift);
    }

    /** 无排班的零值（空数据语义：总人数 0、无班组）。 */
    public static DashboardStaffStats empty() {
        return new DashboardStaffStats(0, List.of());
    }
}
