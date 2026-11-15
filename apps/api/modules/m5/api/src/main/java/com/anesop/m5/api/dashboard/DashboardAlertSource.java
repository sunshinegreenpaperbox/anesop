package com.anesop.m5.api.dashboard;

import java.time.Instant;
import java.util.List;

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

    /**
     * 统计窗口内按小时分桶的告警计数（趋势端点用，M5-M18-02）。
     *
     * <p>单条 {@code GROUP BY date_trunc('hour')} 聚合；桶起点为 UTC 整点，与
     * `Asia/Shanghai` 整点一一对应（上海无夏令时）。只返回桶起点与计数，不返回告警明细。
     *
     * @param from        窗口起点（含）
     * @param toExclusive 窗口终点（不含）
     * @param scope       数据范围断言（与 {@link #countAlerts} 同一裁剪规则）
     * @return 小时桶列表（按桶起点升序）；窗口内无告警返回空列表，不返回 null
     */
    List<DashboardAlertBucket> countAlertsByHour(Instant from, Instant toExclusive,
            DashboardScope scope);
}
