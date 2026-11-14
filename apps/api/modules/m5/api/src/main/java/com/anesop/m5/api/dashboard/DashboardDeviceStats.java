package com.anesop.m5.api.dashboard;

import java.time.Instant;

/**
 * 看板设备在线统计（只读聚合，无设备序列号/患者标识）。
 *
 * <p>{@code online}/{@code offline} 是**查询期推导**状态（不写回设备登记）：
 * MAINTENANCE 显式状态优先，其余按 {@code last_seen_at + 超时阈值} 推导，
 * 与 M2 采集离线判定一致（默认 120s）。
 *
 * @param total          可见设备总数
 * @param online         在线设备数
 * @param offline        离线设备数（从未上报或超时；不含维护中）
 * @param maintenance    维护中设备数
 * @param lastSeenAt     可见设备最近上线时间；无设备为 null（数据延迟依据）
 */
public record DashboardDeviceStats(
        int total,
        int online,
        int offline,
        int maintenance,
        Instant lastSeenAt) {

    /** 无设备的零值。 */
    public static DashboardDeviceStats empty() {
        return new DashboardDeviceStats(0, 0, 0, 0, null);
    }
}
