package com.anesop.m2.impl.persistence.entity;

import java.time.Instant;

/**
 * 按网关聚合的上报统计行（健康摘要用，{@code device_ingest_log}）。
 */
public class GatewayIngestStatsRow {

    private String gatewayId;
    private Long batches;
    private Long acceptedRows;
    private Long duplicateRows;
    private Long rejectedRows;
    private Instant lastReceivedAt;

    public String getGatewayId() {
        return gatewayId;
    }

    public void setGatewayId(String gatewayId) {
        this.gatewayId = gatewayId;
    }

    public Long getBatches() {
        return batches;
    }

    public void setBatches(Long batches) {
        this.batches = batches;
    }

    public Long getAcceptedRows() {
        return acceptedRows;
    }

    public void setAcceptedRows(Long acceptedRows) {
        this.acceptedRows = acceptedRows;
    }

    public Long getDuplicateRows() {
        return duplicateRows;
    }

    public void setDuplicateRows(Long duplicateRows) {
        this.duplicateRows = duplicateRows;
    }

    public Long getRejectedRows() {
        return rejectedRows;
    }

    public void setRejectedRows(Long rejectedRows) {
        this.rejectedRows = rejectedRows;
    }

    public Instant getLastReceivedAt() {
        return lastReceivedAt;
    }

    public void setLastReceivedAt(Instant lastReceivedAt) {
        this.lastReceivedAt = lastReceivedAt;
    }
}
