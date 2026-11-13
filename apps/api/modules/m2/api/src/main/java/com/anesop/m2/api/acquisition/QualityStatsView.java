package com.anesop.m2.api.acquisition;

import java.time.Instant;

/**
 * 设备质量统计（契约 device.yaml 的 QualityStats，无 PHI）。
 *
 * @param batches       已接收新批次数（重复批次不计）
 * @param acceptedRows  成功入库帧数
 * @param duplicateRows 行级唯一键冲突帧数
 * @param rejectedRows  校验拒绝帧数
 * @param suspectRows   质量可疑帧数（quality=1）
 * @param missingRows   质量缺失/超时帧数（quality=2/3）
 * @param qualityRate   质量异常比例 = (suspect+missing)/accepted；无数据为 0
 * @param rejectedRate  拒绝比例 = rejected/(accepted+rejected)；无数据为 0
 * @param lastBatchKey  最近一次新批次幂等键；从未上报为 null
 * @param lastBatchAt   最近一次新批次接收时间；从未上报为 null
 */
public record QualityStatsView(
        long batches,
        long acceptedRows,
        long duplicateRows,
        long rejectedRows,
        long suspectRows,
        long missingRows,
        double qualityRate,
        double rejectedRate,
        String lastBatchKey,
        Instant lastBatchAt) {
}
