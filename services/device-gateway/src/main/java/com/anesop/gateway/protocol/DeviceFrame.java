package com.anesop.gateway.protocol;

import java.time.Instant;

/**
 * 解析后的设备帧（协议 v1，未经标准化）。
 *
 * @param deviceId   设备标识
 * @param seq        设备侧帧序号
 * @param measuredAt 设备测量时间（保留原值）
 * @param receivedAt 网关收到报文的服务端时间
 * @param hr         心率原始值
 * @param spo2       血氧原始值
 * @param nibpSys    无创收缩压原始值
 * @param nibpDia    无创舒张压原始值
 * @param etco2      呼气末二氧化碳原始值
 * @param temp       体温原始值
 * @param quality    设备侧质量码：0=正常，1=可疑，2=缺失
 */
public record DeviceFrame(
        String deviceId,
        long seq,
        Instant measuredAt,
        Instant receivedAt,
        Measurement hr,
        Measurement spo2,
        Measurement nibpSys,
        Measurement nibpDia,
        Measurement etco2,
        Measurement temp,
        int quality) {

    /**
     * 规范构造器：必填引用不得为 null。
     *
     * @param deviceId   设备标识
     * @param seq        序号
     * @param measuredAt 测量时间
     * @param receivedAt 接收时间
     * @param hr         心率
     * @param spo2       血氧
     * @param nibpSys    收缩压
     * @param nibpDia    舒张压
     * @param etco2      呼气末二氧化碳
     * @param temp       体温
     * @param quality    质量码
     */
    public DeviceFrame {
        if (deviceId == null || deviceId.isEmpty()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        if (measuredAt == null || receivedAt == null) {
            throw new IllegalArgumentException("时间戳不能为空");
        }
        hr = hr == null ? Measurement.missing() : hr;
        spo2 = spo2 == null ? Measurement.missing() : spo2;
        nibpSys = nibpSys == null ? Measurement.missing() : nibpSys;
        nibpDia = nibpDia == null ? Measurement.missing() : nibpDia;
        etco2 = etco2 == null ? Measurement.missing() : etco2;
        temp = temp == null ? Measurement.missing() : temp;
    }
}
