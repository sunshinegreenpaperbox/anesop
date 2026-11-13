package com.anesop.m2.api.acquisition;

import java.util.UUID;

/**
 * 设备登记/换台绑定请求（契约 device.yaml 的 DeviceRegisterRequest）。
 *
 * @param gatewayId  采集网关实例标识（[A-Za-z0-9._-]{1,64}）
 * @param deviceId   设备标识（[A-Za-z0-9._-]{1,64}）
 * @param roomId     绑定术间（逻辑外键，可空）
 * @param deviceType 设备类型
 * @param status     显式状态（缺省 ONLINE；MAINTENANCE 表示维护中）
 */
public record DeviceRegisterRequest(
        String gatewayId,
        String deviceId,
        UUID roomId,
        String deviceType,
        String status) {
}
