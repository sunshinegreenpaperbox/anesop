package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 术后镇痛实体，对应 {@code m2_anes.pain_management}（V19，M3-M11-01）。
 *
 * <p>append-only：同一次随访可追加多条镇痛记录（参数调整即新增），历史不可改写
 * （UPDATE/DELETE/TRUNCATE 由触发器拒绝，55000）。剂量使用 {@link BigDecimal} 对应
 * {@code numeric(10,2)}，单位随药物口径【待确认】，服务层只做物理校验（不设临床阈值）。
 */
@TableName("m2_anes.pain_management")
public class PainManagementEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属随访单（FK）。 */
    private UUID followupId;

    /** 镇痛方式 PCIA/PCEA/ORAL/IV/NERVE_BLOCK/OTHER。 */
    private String modality;

    /** 药物名称/方案。 */
    private String drug;

    /** 单次追加剂量（> 0）。 */
    private BigDecimal bolusDose;

    /** 背景输注剂量（≥ 0）。 */
    private BigDecimal backgroundDose;

    /** 锁定时间（分钟，> 0）。 */
    private Integer lockoutMinutes;

    /** 镇痛效果 EFFECTIVE/PARTIAL/INEFFECTIVE/NOT_ASSESSED。 */
    private String effect;

    /** 调整记录 jsonb 数组。 */
    private String adjustments;

    /** 录入时间。 */
    private Instant createdAt;

    /** 录入人用户标识。 */
    private UUID createdBy;

    /** 版本号（append-only 恒为 1）。 */
    private Long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getFollowupId() {
        return followupId;
    }

    public void setFollowupId(UUID followupId) {
        this.followupId = followupId;
    }

    public String getModality() {
        return modality;
    }

    public void setModality(String modality) {
        this.modality = modality;
    }

    public String getDrug() {
        return drug;
    }

    public void setDrug(String drug) {
        this.drug = drug;
    }

    public BigDecimal getBolusDose() {
        return bolusDose;
    }

    public void setBolusDose(BigDecimal bolusDose) {
        this.bolusDose = bolusDose;
    }

    public BigDecimal getBackgroundDose() {
        return backgroundDose;
    }

    public void setBackgroundDose(BigDecimal backgroundDose) {
        this.backgroundDose = backgroundDose;
    }

    public Integer getLockoutMinutes() {
        return lockoutMinutes;
    }

    public void setLockoutMinutes(Integer lockoutMinutes) {
        this.lockoutMinutes = lockoutMinutes;
    }

    public String getEffect() {
        return effect;
    }

    public void setEffect(String effect) {
        this.effect = effect;
    }

    public String getAdjustments() {
        return adjustments;
    }

    public void setAdjustments(String adjustments) {
        this.adjustments = adjustments;
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

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
