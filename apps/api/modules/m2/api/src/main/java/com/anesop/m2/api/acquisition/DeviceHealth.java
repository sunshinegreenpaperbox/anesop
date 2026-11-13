package com.anesop.m2.api.acquisition;

import java.time.Instant;
import java.util.List;

/**
 * 采集健康摘要（契约 device.yaml 的 DeviceHealth，运维视图，无 PHI）。
 *
 * @param generatedAt           摘要生成时间
 * @param offlineTimeoutSeconds 当前生效的离线超时阈值（秒）
 * @param status                HEALTHY/DEGRADED/NO_DATA
 * @param devices               设备数量汇总
 * @param ingest                近 24 小时上报汇总
 * @param gateways              按网关聚合的摘要，按 gatewayId 升序
 */
public record DeviceHealth(
        Instant generatedAt,
        long offlineTimeoutSeconds,
        String status,
        DeviceCounts devices,
        IngestSummary ingest,
        List<GatewayHealth> gateways) {

    /** 规范构造器：列表不可变。 */
    public DeviceHealth {
        gateways = gateways == null ? List.of() : List.copyOf(gateways);
    }
}
