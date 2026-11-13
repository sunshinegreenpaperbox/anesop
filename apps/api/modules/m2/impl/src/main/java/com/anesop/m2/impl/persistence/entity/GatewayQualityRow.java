package com.anesop.m2.impl.persistence.entity;

/**
 * 按网关聚合的体征质量统计行（健康摘要近 24 小时比例用，{@code anesthesia_vital}）。
 */
public class GatewayQualityRow {

    private String gatewayId;
    private Long totalRows;
    private Long suspectRows;
    private Long missingRows;

    public String getGatewayId() {
        return gatewayId;
    }

    public void setGatewayId(String gatewayId) {
        this.gatewayId = gatewayId;
    }

    public Long getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(Long totalRows) {
        this.totalRows = totalRows;
    }

    public Long getSuspectRows() {
        return suspectRows;
    }

    public void setSuspectRows(Long suspectRows) {
        this.suspectRows = suspectRows;
    }

    public Long getMissingRows() {
        return missingRows;
    }

    public void setMissingRows(Long missingRows) {
        this.missingRows = missingRows;
    }
}
