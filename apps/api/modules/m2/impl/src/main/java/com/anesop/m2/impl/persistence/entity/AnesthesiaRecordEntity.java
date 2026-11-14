package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * 麻醉记录单实体，对应 {@code m2_anes.anesthesia_record}（V13）。
 *
 * <p>一个台次（scheduleId）唯一；状态 DRAFT/ONGOING（契约映射 DRAFT/IN_PROGRESS）。
 * 更新统一使用条件 SQL（{@code WHERE id=? AND version=?}），返回 0 行即 409；
 * {@code deleted} 为逻辑删除标记（本期不提供删除接口）。
 */
@TableName("m2_anes.anesthesia_record")
public class AnesthesiaRecordEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID scheduleId;
    private UUID applicationId;
    private UUID patientId;
    /** 创建时经 m1-api 快照的术间（V16）；采集落库事件按它匹配 ONGOING 记录。 */
    private UUID roomId;
    private String status;
    private String anesthesiaType;
    private Instant startedAt;
    private Instant endedAt;
    /** 锁单时间（V17）；非空表示已锁单，记录内容不可直接修改，修正只能新增修订版本。 */
    private Instant lockedAt;
    /** 锁单操作人用户标识（V17）；与 lockedAt 同时写入，写入后不可改。 */
    private UUID lockedBy;
    /** 当前修订版本号（V17，未锁单 0；每次修订 +1，对应 record_revision.revision_no）。 */
    private Integer currentRevision;
    /** 锁单签名占位标识（EXT-05：SIM-CA-&lt;记录单标识&gt;）。 */
    private String lockSignatureRef;
    /** 锁单签名摘要（规范化记录 JSON 的 SHA-256 十六进制 64 字符）。 */
    private String lockSignatureHash;
    /** 锁单 CA 状态（EXT-05 为 SIMULATED）。 */
    private String lockCaStatus;
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

    public UUID getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(UUID scheduleId) {
        this.scheduleId = scheduleId;
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

    public UUID getRoomId() {
        return roomId;
    }

    public void setRoomId(UUID roomId) {
        this.roomId = roomId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAnesthesiaType() {
        return anesthesiaType;
    }

    public void setAnesthesiaType(String anesthesiaType) {
        this.anesthesiaType = anesthesiaType;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public UUID getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(UUID lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Integer getCurrentRevision() {
        return currentRevision;
    }

    public void setCurrentRevision(Integer currentRevision) {
        this.currentRevision = currentRevision;
    }

    public String getLockSignatureRef() {
        return lockSignatureRef;
    }

    public void setLockSignatureRef(String lockSignatureRef) {
        this.lockSignatureRef = lockSignatureRef;
    }

    public String getLockSignatureHash() {
        return lockSignatureHash;
    }

    public void setLockSignatureHash(String lockSignatureHash) {
        this.lockSignatureHash = lockSignatureHash;
    }

    public String getLockCaStatus() {
        return lockCaStatus;
    }

    public void setLockCaStatus(String lockCaStatus) {
        this.lockCaStatus = lockCaStatus;
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
