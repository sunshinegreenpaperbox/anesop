package com.anesop.m5.api.dashboard;

import java.time.Instant;

/**
 * 告警小时桶（M5-M18-02）：趋势端点用的按小时聚合计数。
 *
 * <p>只含桶起点与计数：不返回告警值、规则原文、处置原因与患者/记录单标识。
 * 桶起点为 `Asia/Shanghai` 整点对应的 UTC 时刻（`date_trunc('hour')`；上海无夏令时，
 * UTC 整点与本地整点一一对应，适配器无需时区换算）。
 *
 * @param hourStart 桶起点（该小时的第 0 分 0 秒）
 * @param triggered 桶内触发的告警数（不限处置状态）
 * @param critical  桶内触发的严重（CRITICAL）告警数
 */
public record DashboardAlertBucket(Instant hourStart, int triggered, int critical) {
}
