package com.anesop.m5.api.dashboard;

import java.time.LocalDate;
import java.time.Instant;

/**
 * 当班人员只读端口（M5-M18-02）：由装配层适配器用**单条只读 SELECT**聚合 M4 人员排班。
 *
 * <p>只返回总人数与按班组人数，不返回人员标识/姓名/工号/科室明细。
 *
 * <p>数据范围语义（fail-closed，由调用方判定；适配器纵深防御）：
 * ALL 全院；SITE/DEPT 按排班所属科室；ROOM 按排班绑定术间；SELF 按“本人当日台次所涉
 * **科室**”的当班人数（人员排班无用户标识，无法按“本人”精确裁剪，见 ADR-0023 Q-6）。
 */
public interface DashboardStaffSource {

    /**
     * 统计当班人员。
     *
     * @param date        排班日期（Asia/Shanghai 自然日）
     * @param from        当日窗口起点（含；仅 SELF 科室派生使用）
     * @param toExclusive 当日窗口终点（不含；仅 SELF 科室派生使用）
     * @param scope       数据范围断言
     * @return 当班人数统计；无数据返回 {@link DashboardStaffStats#empty()}
     */
    DashboardStaffStats countOnDuty(LocalDate date, Instant from, Instant toExclusive,
            DashboardScope scope);
}
