package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * 知情同意记录实体，对应 {@code m2_anes.consent_record}（V11）。
 *
 * <p>签署状态机 WAITING_SIGN → SIGNED | DECLINED；SIGNED → REVOKED。状态变更统一用显式
 * 条件 SQL（{@code WHERE id=? AND version=? AND status=?}），返回 0 行即 409（乐观锁 +
 * 状态双条件），不静默覆盖。{@code version} 为乐观锁版本号，{@code deleted} 为逻辑删除标记
 * （本期不提供删除接口）。
 */
@TableName("m2_anes.consent_record")
public class ConsentRecordEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID applicationId;
    private UUID patientId;
    private UUID templateId;
    private Integer templateVersion;
    private String status;
    private String signerName;
    private String signerRelation;
    private String signMethod;
    private Instant signedAt;
    private UUID signedBy;
    private String signatureRef;
    private String signatureHash;
    private String caStatus;
    private String declineReason;
    private Instant declinedAt;
    private Instant revokedAt;
    private UUID revokedBy;
    private String revokeReason;
    private Instant createdAt;
    private UUID createdBy;
    private Instant updatedAt;
    private UUID updatedBy;

    @Version
    private Long version;

    @TableLogic(value = "false", delval = "true")
    private Boolean deleted;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(UUID applicationId) {
        this.applicationId = applicationId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public void setPatientId(UUID patientId) {
        this.patientId = patientId;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public Integer getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(Integer templateVersion) {
        this.templateVersion = templateVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSignerName() {
        return signerName;
    }

    public void setSignerName(String signerName) {
        this.signerName = signerName;
    }

    public String getSignerRelation() {
        return signerRelation;
    }

    public void setSignerRelation(String signerRelation) {
        this.signerRelation = signerRelation;
    }

    public String getSignMethod() {
        return signMethod;
    }

    public void setSignMethod(String signMethod) {
        this.signMethod = signMethod;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(Instant signedAt) {
        this.signedAt = signedAt;
    }

    public UUID getSignedBy() {
        return signedBy;
    }

    public void setSignedBy(UUID signedBy) {
        this.signedBy = signedBy;
    }

    public String getSignatureRef() {
        return signatureRef;
    }

    public void setSignatureRef(String signatureRef) {
        this.signatureRef = signatureRef;
    }

    public String getSignatureHash() {
        return signatureHash;
    }

    public void setSignatureHash(String signatureHash) {
        this.signatureHash = signatureHash;
    }

    public String getCaStatus() {
        return caStatus;
    }

    public void setCaStatus(String caStatus) {
        this.caStatus = caStatus;
    }

    public String getDeclineReason() {
        return declineReason;
    }

    public void setDeclineReason(String declineReason) {
        this.declineReason = declineReason;
    }

    public Instant getDeclinedAt() {
        return declinedAt;
    }

    public void setDeclinedAt(Instant declinedAt) {
        this.declinedAt = declinedAt;
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

    public String getRevokeReason() {
        return revokeReason;
    }

    public void setRevokeReason(String revokeReason) {
        this.revokeReason = revokeReason;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
