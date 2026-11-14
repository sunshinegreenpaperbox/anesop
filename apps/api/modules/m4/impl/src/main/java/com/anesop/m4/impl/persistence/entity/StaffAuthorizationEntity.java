package com.anesop.m4.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 分级授权版本实体，对应 {@code m4_staff.staff_authorization}（V21，append-only）。
 *
 * <p>只 INSERT 与查询；每次授予/撤销写入新 version，旧版本保留（数据库触发器 55000 兜底）。
 * {@code surgeryScopes} 为 jsonb 字符串数组的文本形式（服务层用 Jackson 序列化）。
 */
@TableName("m4_staff.staff_authorization")
public class StaffAuthorizationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    private UUID staffId;
    private Integer version;
    private String level;
    private String surgeryScopes;
    private Integer ageMin;
    private Integer ageMax;
    private String asaMax;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String status;
    private UUID approvedBy;
    private Instant approvedAt;
    private UUID secondApproverId;
    private Instant secondApprovedAt;
    private String secondApproverReason;
    private Instant revokedAt;
    private UUID revokedBy;
    private String changeReason;
    private Instant createdAt;
    private UUID createdBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getStaffId() {
        return staffId;
    }

    public void setStaffId(UUID staffId) {
        this.staffId = staffId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getSurgeryScopes() {
        return surgeryScopes;
    }

    public void setSurgeryScopes(String surgeryScopes) {
        this.surgeryScopes = surgeryScopes;
    }

    public Integer getAgeMin() {
        return ageMin;
    }

    public void setAgeMin(Integer ageMin) {
        this.ageMin = ageMin;
    }

    public Integer getAgeMax() {
        return ageMax;
    }

    public void setAgeMax(Integer ageMax) {
        this.ageMax = ageMax;
    }

    public String getAsaMax() {
        return asaMax;
    }

    public void setAsaMax(String asaMax) {
        this.asaMax = asaMax;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(UUID approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public UUID getSecondApproverId() {
        return secondApproverId;
    }

    public void setSecondApproverId(UUID secondApproverId) {
        this.secondApproverId = secondApproverId;
    }

    public Instant getSecondApprovedAt() {
        return secondApprovedAt;
    }

    public void setSecondApprovedAt(Instant secondApprovedAt) {
        this.secondApprovedAt = secondApprovedAt;
    }

    public String getSecondApproverReason() {
        return secondApproverReason;
    }

    public void setSecondApproverReason(String secondApproverReason) {
        this.secondApproverReason = secondApproverReason;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(UUID revokedBy) {
        this.revokedBy = revokedBy;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
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
}
