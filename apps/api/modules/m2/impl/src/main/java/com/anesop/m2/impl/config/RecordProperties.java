package com.anesop.m2.impl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 麻醉记录单配置（{@code anesop.m2.record.*}，M3-M2-01 复审 S-1/B-2）。
 *
 * <p>时间容差 {@code time-tolerance-seconds}（默认 600 秒）同时用于两个方向：
 * <ul>
 *   <li>未来时间容忍：{@code occurredAt} 晚于服务端当前时间 + 容差 → 400（防止客户端时钟
 *       偏差误报，也防止录入"未来事件"越过时间轴一致性检查）；</li>
 *   <li>补录判定：{@code occurredAt} 早于服务端当前时间 − 容差，或早于该记录单最新事件时间
 *       → 必须填写 {@code entryReason}（400 {@code PARAM_INVALID}，字段 {@code entryReason}）。</li>
 * </ul>
 *
 * <p>容差不是"正确性豁免"：记录单开始时间之前的硬约束（{@code occurredAt >= startedAt/
 * createdAt}）不受容差影响，仍直接 400。
 *
 * @param timeToleranceSeconds 服务端时钟/录入容差（秒，1–3600）
 */
@ConfigurationProperties(prefix = "anesop.m2.record")
public record RecordProperties(@DefaultValue("600") long timeToleranceSeconds) {

    /** 配置自检：非法容差启动即失败，避免运行期静默错误。 */
    public RecordProperties {
        if (timeToleranceSeconds <= 0 || timeToleranceSeconds > 3600) {
            throw new IllegalArgumentException(
                    "anesop.m2.record.time-tolerance-seconds 必须在 1–3600 之间");
        }
    }
}
