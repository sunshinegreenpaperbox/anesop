package com.anesop.m2.impl.acquisition;

import java.time.Instant;

/**
 * 采集落库成功的领域事件（M3-M2-02 评审 B-1，ADR-0017）。
 *
 * <p>由 {@link AcquisitionServiceImpl#ingest} 在事务内、且本批实际插入行数 > 0 时发布；
 * m2 告警监听器以 {@code @TransactionalEventListener(AFTER_COMMIT)} 消费，保证“先落库、
 * 后评估”，采集事务回滚时不产生告警。
 *
 * @param gatewayId   网关标识（已在采集服务校验）
 * @param deviceId    设备标识（已在采集服务校验）
 * @param batchKey    批次键（仅用于日志关联，不含患者/报文内容）
 * @param insertedRows 本批实际插入的体征行数（>0 才发布）
 * @param receivedAt  本批接收时间（服务端时间）
 */
public record VitalsIngestedEvent(String gatewayId, String deviceId, String batchKey,
        long insertedRows, Instant receivedAt) {
}
