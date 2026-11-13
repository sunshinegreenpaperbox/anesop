package com.anesop.m2.api.acquisition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 一帧标准化体征（契约 device.yaml 的 IngestFrame；与网关协议 v1 对齐，单位已规范化）。
 *
 * @param seq                  设备侧帧序号（≥0），与 measuredAt 组成行级幂等键
 * @param measuredAt           设备测量时间原值（幂等键，平台不得改写）
 * @param measuredAtCorrected  时钟校正后的测量时间；缺省视为等于 measuredAt
 * @param receivedAt           网关接收时间；缺省由平台接收时间填充
 * @param hr                   心率 bpm
 * @param spo2                 血氧 %
 * @param nibpSys              无创收缩压 mmHg
 * @param nibpDia              无创舒张压 mmHg
 * @param etco2                呼气末二氧化碳 mmHg
 * @param temp                 体温 ℃
 * @param quality              质量码 0–3
 * @param flags                质量标记明细
 * @param source               数据来源 AUTO/MANUAL/REPLAY；缺省 AUTO
 */
public record IngestFrame(
        Long seq,
        Instant measuredAt,
        Instant measuredAtCorrected,
        Instant receivedAt,
        Integer hr,
        Integer spo2,
        Integer nibpSys,
        Integer nibpDia,
        BigDecimal etco2,
        BigDecimal temp,
        Integer quality,
        List<String> flags,
        String source) {

    /** 规范构造器：标记列表不可变。 */
    public IngestFrame {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }
}
