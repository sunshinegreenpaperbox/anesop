package com.anesop.m4.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 不良事件实体（append-only 业务内容 + 状态机）。
 *
 * <p>对应 {@code m4_qc.adverse_event}（V25）；实体只做字段载体，业务规则在服务层与数据库触发器中实现。
 */
@TableName("m4_qc.adverse_event")
public class AdverseEventEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 事件号 */
    private String eventNo;

    /** 事件类型 */
    private String eventType;

    /** 事件等级 */
    private String severity;

    /** 发生时间 */
    private Instant occurredAt;

    /** 科室标识 */
    private UUID deptId;

    /** 科室名称快照 */
    private String deptName;

    /** 关联台次（可空） */
    private UUID scheduleId;

    /** 已脱敏描述 */
    private String description;

    /** 上报人标识 */
    private UUID reporterId;

    /** 上报人姓名摘要 */
    private String reporterName;

    /** 是否匿名上报 */
    private Boolean anonymous;

    /** 状态 REPORTED/RCA/RECTIFYING/CLOSED */
    private String status;

    /** 状态变更时间 */
    private Instant statusChangedAt;

    /** 状态机版本 */
    private Integer version;

    /** 创建时间 */
    private Instant createdAt;

    /** 创建人 */
    private UUID createdBy;

    /** 当前 RCA 标识（只读投影，非物理列） */
    private UUID rcaId;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEventNo() {
        return eventNo;
    }

    public void setEventNo(String eventNo) {
        this.eventNo = eventNo;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public UUID getDeptId() {
        return deptId;
    }

    public void setDeptId(UUID deptId) {
        this.deptId = deptId;
    }

    public String getDeptName() {
        return deptName;
    }

    public void setDeptName(String deptName) {
        this.deptName = deptName;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(UUID scheduleId) {
        this.scheduleId = scheduleId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getReporterId() {
        return reporterId;
    }

    public void setReporterId(UUID reporterId) {
        this.reporterId = reporterId;
    }

    public String getReporterName() {
        return reporterName;
    }

    public void setReporterName(String reporterName) {
        this.reporterName = reporterName;
    }

    public Boolean getAnonymous() {
        return anonymous;
    }

    public void setAnonymous(Boolean anonymous) {
        this.anonymous = anonymous;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public void setStatusChangedAt(Instant statusChangedAt) {
        this.statusChangedAt = statusChangedAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public UUID getRcaId() {
        return rcaId;
    }

    public void setRcaId(UUID rcaId) {
        this.rcaId = rcaId;
    }
}
