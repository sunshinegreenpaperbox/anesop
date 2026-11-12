package com.anesop.gateway.buffer;

/**
 * 缓冲运行态快照，用于健康检查与运维观测。
 *
 * @param pendingRecords   待上传记录数（已打开设备队列合计）
 * @param bytesOnDisk      缓冲磁盘占用（字节）
 * @param devices          已打开的设备队列数
 * @param evictedSegments  因超限淘汰的段数累计
 * @param rejectedRecords  因容量拒收的记录数累计
 * @param corruptRecords   损坏记录/尾部修复次数累计
 */
public record BufferStats(
        long pendingRecords,
        long bytesOnDisk,
        int devices,
        long evictedSegments,
        long rejectedRecords,
        long corruptRecords) {
}
