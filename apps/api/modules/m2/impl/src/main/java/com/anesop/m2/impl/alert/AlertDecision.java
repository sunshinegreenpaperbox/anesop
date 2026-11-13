package com.anesop.m2.impl.alert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次“越界窗口成立”的告警判定结果（尚未落库）。
 *
 * <p>{@code dedupeKey} = recordId:ruleCode:窗口起始秒，作为幂等键；
 * {@code value} 为越界持续达到规则时长的那一采样的指标值；{@code triggeredAt} 为同一时刻。
 *
 * @param ruleId       触发时的规则版本行
 * @param ruleCode     规则编码（快照）
 * @param ruleVersion  规则版本号（快照）
 * @param metric       指标
 * @param value        触发时实测值
 * @param unit         指标单位
 * @param severity     严重度（规则快照）
 * @param triggeredAt  触发时间（达到持续时长的那一采样时刻）
 * @param dedupeKey    幂等去重键
 */
public record AlertDecision(UUID ruleId, String ruleCode, int ruleVersion, String metric,
        BigDecimal value, String unit, String severity, Instant triggeredAt, String dedupeKey) {
}
