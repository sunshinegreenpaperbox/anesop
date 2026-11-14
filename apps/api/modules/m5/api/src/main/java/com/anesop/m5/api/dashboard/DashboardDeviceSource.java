package com.anesop.m5.api.dashboard;

import java.time.Instant;

/**
 * 看板设备只读端口：由装配层适配器用**单条只读 SELECT**聚合 M2 设备登记。
 *
 * <p>在线/离线为查询期推导（不写回），维护中为显式状态；不返回设备序列号、网关标识与
 * 质量统计明细。
 */
public interface DashboardDeviceSource {

    /**
     * 统计可见设备在线态势。
     *
     * @param now                   服务端当前时刻（离线推导基准）
     * @param from                  当日窗口起点（含；仅 SELF 术间派生使用）
     * @param toExclusive           当日窗口终点（不含；仅 SELF 术间派生使用）
     * @param scope                 数据范围断言
     * @param offlineTimeoutSeconds 离线判定阈值（秒，必须 &gt; 0）
     * @return 设备统计；无数据返回 {@link DashboardDeviceStats#empty()}
     */
    DashboardDeviceStats countDevices(Instant now, Instant from, Instant toExclusive,
            DashboardScope scope, int offlineTimeoutSeconds);
}
