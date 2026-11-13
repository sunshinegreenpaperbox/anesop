package com.anesop.m2.impl.persistence.entity;

/**
 * 体征批量插入结果（{@code INSERT ... ON CONFLICT DO NOTHING RETURNING quality} 的聚合）：
 * 只统计**本次实际插入**的行，重复帧不计入质量分布，质量比例分母与分子口径一致。
 */
public class VitalInsertOutcome {

    private Long inserted;
    private Long suspectRows;
    private Long missingRows;

    public Long getInserted() {
        return inserted;
    }

    public void setInserted(Long inserted) {
        this.inserted = inserted;
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
