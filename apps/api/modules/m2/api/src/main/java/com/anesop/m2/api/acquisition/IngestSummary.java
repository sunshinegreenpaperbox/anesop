package com.anesop.m2.api.acquisition;

import java.time.Instant;

/**
 * 近 24 小时上报汇总（契约 device.yaml 的 IngestSummary）。
 *
 * @param batches        新批次数
 * @param acceptedRows   入库帧数
 * @param duplicateRows  重复帧数
 * @param rejectedRows   拒绝帧数
 * @param lastReceivedAt 最近一次接收时间；无数据为 null
 */
public record IngestSummary(
        long batches,
        long acceptedRows,
        long duplicateRows,
        long rejectedRows,
        Instant lastReceivedAt) {
}
