package com.anesop.m2.api.acquisition;

import java.time.Instant;

/**
 * 单网关采集健康摘要（契约 device.yaml 的 GatewayHealth）。
 *
 * @param gatewayId          采集网关实例标识
 * @param status             ONLINE（阈值内有上报）/STALE（超出离线阈值）/NO_INGEST（从未上报）
 * @param devicesTotal       该网关登记设备数
 * @param devicesOnline      在线设备数（推导）
 * @param devicesOffline     离线设备数（推导）
 * @param devicesMaintenance 维护中设备数
 * @param lastSeenAt         该网关下设备最近上报时间；从未上报为 null
 * @param lastIngestAt       该网关最近批次接收时间；从未上报为 null
 * @param batches            近 24 小时批次数
 * @param acceptedRows       近 24 小时入库帧数
 * @param duplicateRows      近 24 小时重复帧数
 * @param rejectedRows       近 24 小时拒绝帧数
 * @param qualityRate        近 24 小时质量异常比例
 */
public record GatewayHealth(
        String gatewayId,
        String status,
        int devicesTotal,
        int devicesOnline,
        int devicesOffline,
        int devicesMaintenance,
        Instant lastSeenAt,
        Instant lastIngestAt,
        long batches,
        long acceptedRows,
        long duplicateRows,
        long rejectedRows,
        double qualityRate) {
}
