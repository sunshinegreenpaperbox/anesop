package com.anesop.m2.api.acquisition;

import java.time.Instant;
import java.util.List;

/**
 * 批量上报结果（契约 device.yaml 的 IngestResult）；重复批次 acceptedRows=0 且
 * duplicateBatch=true。
 *
 * @param gatewayId      采集网关实例标识
 * @param deviceId       设备标识
 * @param batchKey       本批幂等键
 * @param acceptedRows   本次新入库帧数
 * @param duplicateRows  行级唯一键冲突帧数
 * @param rejectedRows   校验拒绝帧数
 * @param duplicateBatch 是否为重复批次
 * @param rejections     拒绝明细（仅下标与原因码）
 * @param receivedAt     平台接收时间
 */
public record IngestResult(
        String gatewayId,
        String deviceId,
        String batchKey,
        long acceptedRows,
        long duplicateRows,
        long rejectedRows,
        boolean duplicateBatch,
        List<Rejection> rejections,
        Instant receivedAt) {

    /** 规范构造器：拒绝明细不可变。 */
    public IngestResult {
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
    }
}
