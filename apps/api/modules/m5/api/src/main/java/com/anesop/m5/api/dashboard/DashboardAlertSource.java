package com.anesop.m5.api.dashboard;

import java.time.Instant;

/**
 * 看板告警只读端口：由装配层适配器用**单条只读 SELECT**聚合 M2 术中告警。
 *
 * <p>只返回计数与最近触发时间，不返回告警值、规则原文、处置原因或患者/记录单标识
 * （看板只做态势与数量，钻取走 M2 告警详情接口并受其权限约束）。
 */
public interface DashboardAlertSource {

    /**
     * 统计窗口内可见告警。
     *
     * @param from        窗口起点（含）
     * @param toExclusive 窗口终点（不含）
     * @param scope       数据范围断言（经记录单→台次→术间关联裁剪）
     * @return 告警统计；无数据返回 {@link DashboardAlertStats#empty()}
     */
    DashboardAlertStats countAlerts(Instant from, Instant toExclusive, DashboardScope scope);
}
