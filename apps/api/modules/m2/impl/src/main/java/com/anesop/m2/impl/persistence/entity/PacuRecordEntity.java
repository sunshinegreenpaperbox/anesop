package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * PACU 复苏记录实体，对应 {@code m2_anes.pacu_record}（V18，M3-M10-01）。
 *
 * <p>一条麻醉记录单同时最多一条未转出记录（部分唯一索引 uk_pacu_record_active）；
 * 状态 ADMITTED/MONITORING/READY/DISCHARGED（DISCHARGED 为终态）。入室生命体征以 jsonb
 * 保存（{@code admittedVitalSigns}），字段名与契约 PacuVitalSigns 一致。状态推进/转出使用
 * 条件更新（{@code WHERE id=? AND version=? AND status<>'DISCHARGED'}），返回 0 行即 409。
 */
@TableName("m2_anes.pacu_record")
public class PacuRecordEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属麻醉记录单（FK，写入后不可改）。 */
    private UUID anesthesiaRecordId;

    /** 患者标识（由记录单解析，不存姓名/证件号明文）。 */
    private UUID patientId;

    /** 入 PACU 时间。 */
    private Instant admittedAt;

    /** 转出时间；未转出为 null。 */
    private Instant dischargedAt;

    /** ADMITTED/MONITORING/READY/DISCHARGED。 */
    private String status;

    /** 入室生命体征 jsonb（可缺项）。 */
    private String admittedVitalSigns;

    /** 入室意识枚举。 */
    private String consciousness;

    /** 入室疼痛评分 NRS 0–10。 */
    private Integer painScore;

    /** 入室是否恶心/呕吐。 */
    private Boolean nausea;

    /** 入室气道枚举。 */
    private String airway;

    /** 创建时间。 */
    private Instant createdAt;

    /** 创建人用户标识。 */
    private UUID createdBy;

    /** 最后更新时间。 */
    private Instant updatedAt;

    /** 最后修改人用户标识。 */
    private UUID updatedBy;

    /** 乐观锁版本号（只增）。 */
    @Version
    private Long version;

    /** 逻辑删除标记（本期无删除接口）。 */
    @TableLogic(value = "false", delval = "true")
    private Boolean deleted;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAnesthesiaRecordId() {
        return anesthesiaRecordId;
    }

    public void setAnesthesiaRecordId(UUID anesthesiaRecordId) {
        this.anesthesiaRecordId = anesthesiaRecordId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public void setPatientId(UUID patientId) {
        this.patientId = patientId;
    }

    public Instant getAdmittedAt() {
        return admittedAt;
    }

    public void setAdmittedAt(Instant admittedAt) {
        this.admittedAt = admittedAt;
    }

    public Instant getDischargedAt() {
        return dischargedAt;
    }

    public void setDischargedAt(Instant dischargedAt) {
        this.dischargedAt = dischargedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAdmittedVitalSigns() {
        return admittedVitalSigns;
    }

    public void setAdmittedVitalSigns(String admittedVitalSigns) {
        this.admittedVitalSigns = admittedVitalSigns;
    }

    public String getConsciousness() {
        return consciousness;
    }

    public void setConsciousness(String consciousness) {
        this.consciousness = consciousness;
    }

    public Integer getPainScore() {
        return painScore;
    }

    public void setPainScore(Integer painScore) {
        this.painScore = painScore;
    }

    public Boolean getNausea() {
        return nausea;
    }

    public void setNausea(Boolean nausea) {
        this.nausea = nausea;
    }

    public String getAirway() {
        return airway;
    }

    public void setAirway(String airway) {
        this.airway = airway;
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
