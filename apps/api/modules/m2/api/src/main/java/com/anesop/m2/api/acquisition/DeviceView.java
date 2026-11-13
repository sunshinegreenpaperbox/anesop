package com.anesop.m2.api.acquisition;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备登记视图（契约 device.yaml 0.1.0 的 Device）。
 *
 * @param gatewayId    采集网关实例标识
 * @param deviceId     设备标识
 * @param roomId       绑定术间（逻辑外键）；未绑定为 null
 * @param deviceType   设备类型（MONITOR/ANESTHESIA_MACHINE/INFUSION_PUMP/VENTILATOR/OTHER）
 * @param status       推导后的状态（ONLINE/OFFLINE/MAINTENANCE）
 * @param lastSeenAt   最近一次成功接收体征的服务端时间；从未上报为 null
 * @param qualityStats 质量统计
 * @param createdAt    登记时间
 * @param updatedAt    最后更新时间
 * @param version      乐观锁版本号
 */
public record DeviceView(
        String gatewayId,
        String deviceId,
        UUID roomId,
        String deviceType,
        String status,
        Instant lastSeenAt,
        QualityStatsView qualityStats,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
