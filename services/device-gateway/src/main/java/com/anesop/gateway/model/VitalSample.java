package com.anesop.gateway.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 标准化后的体征样本：解析 → 单位换算 → 范围/质量标记之后的统一模型。
 *
 * <p>这是缓冲（JSONL）、上传（JDBC）与指标统计共用的唯一数据形态，避免同一帧在不同
 * 环节被重复解释；单位一律为规范单位（bpm / % / mmHg / ℃），见 {@code Metric}。
 *
 * @param deviceId     设备标识
 * @param seq          设备帧序号
 * @param measuredAt          设备测量时间原值（幂等键组成部分，永不改写）
 * @param measuredAtCorrected 时钟校正后的测量时间（展示/趋势用；无偏差时等于 measuredAt）
 * @param receivedAt          网关接收时间
 * @param hr           心率 bpm，缺省为 null
 * @param spo2         血氧 %，缺省为 null
 * @param nibpSys      收缩压 mmHg，缺省为 null
 * @param nibpDia      舒张压 mmHg，缺省为 null
 * @param etco2        呼气末二氧化碳 mmHg，缺省为 null
 * @param temp         体温 ℃，缺省为 null
 * @param quality      质量码：0=正常，1=可疑，2=缺失，3=超时
 * @param qualityFlags 质量标记明细，如 {@code hr:RANGE}、{@code ts:BACKFILL:-600000}
 * @param source       数据来源：AUTO/MANUAL/REPLAY
 */
public record VitalSample(
        String deviceId,
        long seq,
        Instant measuredAt,
        Instant measuredAtCorrected,
        Instant receivedAt,
        Integer hr,
        Integer spo2,
        Integer nibpSys,
        Integer nibpDia,
        BigDecimal etco2,
        BigDecimal temp,
        int quality,
        List<String> qualityFlags,
        String source) {

    /**
     * 规范构造器：必填字段校验，质量标记不可变复制。
     *
     * @param deviceId     设备标识
     * @param seq          序号
     * @param measuredAt          设备测量时间原值
     * @param measuredAtCorrected 校正后的测量时间
     * @param receivedAt          接收时间
     * @param hr           心率
     * @param spo2         血氧
     * @param nibpSys      收缩压
     * @param nibpDia      舒张压
     * @param etco2        呼气末二氧化碳
     * @param temp         体温
     * @param quality      质量码
     * @param qualityFlags 质量标记
     * @param source       来源
     */
    public VitalSample {
        if (deviceId == null || deviceId.isEmpty()) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        if (measuredAt == null || measuredAtCorrected == null || receivedAt == null) {
            throw new IllegalArgumentException("时间戳不能为空");
        }
        if (quality < 0 || quality > 3) {
            throw new IllegalArgumentException("quality 必须在 0–3");
        }
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("source 不能为空");
        }
        qualityFlags = qualityFlags == null ? List.of() : List.copyOf(qualityFlags);
    }

    /**
     * 质量标记拼为入库文本。
     *
     * @return 逗号分隔标记；无标记为空串
     */
    public String qualityFlagsText() {
        return String.join(",", qualityFlags);
    }
}
