package com.anesop.m4.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 报表锁定快照实体，对应 {@code m4_report.report_snapshot}（V28，append-only）。
 *
 * <p>{@code params}/{@code payload} 为 jsonb 文本（由 {@code ReportSnapshotCodec} 编解码），
 * {@code paramsHash}/{@code payloadHash}/{@code scopeHash} 为 sha256 十六进制；
 * 唯一 (report, period, params_hash)。库层触发器禁止 UPDATE/DELETE/TRUNCATE（55000），
 * 快照只增不改，保留期与归档策略【待确认】。
 */
@TableName("m4_report.report_snapshot")
public class ReportSnapshotEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    private String report;
    private String period;
    private LocalDate fromDate;
    private LocalDate toDate;
    private String params;
    private String paramsHash;
    private String payload;
    private String payloadHash;
    private String dataScope;
    private String scopeHash;
    private Integer rowCount;
    private UUID lockedBy;
    private String lockedByName;
    private Instant lockedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public void setFromDate(LocalDate fromDate) {
        this.fromDate = fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public String getParamsHash() {
        return paramsHash;
    }

    public void setParamsHash(String paramsHash) {
        this.paramsHash = paramsHash;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public String getDataScope() {
        return dataScope;
    }

    public void setDataScope(String dataScope) {
        this.dataScope = dataScope;
    }

    public String getScopeHash() {
        return scopeHash;
    }

    public void setScopeHash(String scopeHash) {
        this.scopeHash = scopeHash;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public UUID getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(UUID lockedBy) {
        this.lockedBy = lockedBy;
    }

    public String getLockedByName() {
        return lockedByName;
    }

    public void setLockedByName(String lockedByName) {
        this.lockedByName = lockedByName;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }
}
