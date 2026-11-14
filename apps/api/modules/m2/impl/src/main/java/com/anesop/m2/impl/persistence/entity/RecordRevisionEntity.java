package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 麻醉记录单修订版本实体，对应 {@code m2_anes.record_revision}（V17，M3-M2-03）。
 *
 * <p>append-only：只提供 INSERT 与查询，不提供 UPDATE/DELETE（数据库触发器为第二道防线）。
 * {@code revisionNo} 由服务层在记录单行锁（{@code SELECT ... FOR UPDATE}）内分配，从 1 递增；
 * {@code (record_id, revision_no)} 唯一。{@code snapshot} 为全量快照 JSON 文本（jsonb 列）。
 */
@TableName("m2_anes.record_revision")
public class RecordRevisionEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID recordId;
    private Integer revisionNo;
    private String reasonCode;
    private String reason;
    private String changes;
    /** 全量快照 jsonb（读取/写入均以 JSON 文本传递；不含患者标识与审计时间）。 */
    private String snapshot;
    private Integer previousRevisionNo;
    private String previousSignatureHash;
    private String signatureRef;
    private String signatureHash;
    private String caStatus;
    private Instant createdAt;
    private UUID createdBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRecordId() {
        return recordId;
    }

    public void setRecordId(UUID recordId) {
        this.recordId = recordId;
    }

    public Integer getRevisionNo() {
        return revisionNo;
    }

    public void setRevisionNo(Integer revisionNo) {
        this.revisionNo = revisionNo;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getChanges() {
        return changes;
    }

    public void setChanges(String changes) {
        this.changes = changes;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(String snapshot) {
        this.snapshot = snapshot;
    }

    public Integer getPreviousRevisionNo() {
        return previousRevisionNo;
    }

    public void setPreviousRevisionNo(Integer previousRevisionNo) {
        this.previousRevisionNo = previousRevisionNo;
    }

    public String getPreviousSignatureHash() {
        return previousSignatureHash;
    }

    public void setPreviousSignatureHash(String previousSignatureHash) {
        this.previousSignatureHash = previousSignatureHash;
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
