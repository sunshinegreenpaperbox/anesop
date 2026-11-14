package com.anesop.m5.api.dashboard;

import java.time.Instant;

/**
 * 看板告警统计（只读聚合，无患者标识与处置原因）。
 *
 * <p>计数口径：{@code triggered} 为未确认（status=TRIGGERED）总数，
 * {@code critical + warn + info = triggered}；{@code acknowledged}/{@code ignored} 单列参考。
 *
 * @param triggered        未确认告警数
 * @param critical         未确认且严重（CRITICAL）告警数
 * @param warn             未确认且警告（WARN）告警数
 * @param info             未确认且提示（INFO）告警数
 * @param acknowledged     已确认告警数
 * @param ignored          已忽略告警数
 * @param lastTriggeredAt  窗口内最近一次告警触发时间；无告警为 null（数据延迟依据）
 */
public record DashboardAlertStats(
        int triggered,
        int critical,
        int warn,
        int info,
        int acknowledged,
        int ignored,
        Instant lastTriggeredAt) {

    /** 无告警的零值（空数据语义：全部计数 0、无最近触发时间）。 */
    public static DashboardAlertStats empty() {
        return new DashboardAlertStats(0, 0, 0, 0, 0, 0, null);
    }
}
