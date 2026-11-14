package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 术后随访实体，对应 {@code m2_anes.followup_record}（V19，M3-M11-01）。
 *
 * <p>append-only：表只 INSERT/查询，UPDATE/DELETE/TRUNCATE 由触发器拒绝（55000）；
 * 同一记录单同一随访类型允许多次随访（时间序列只增）。{@code complications} 以 jsonb 数组
 * 保存并发症码，{@code complicationNotes}/{@code patientFeedback} 为脱敏后的文本。
 * {@code version} 为通用审计字段，append-only 语义下恒为 1。
 */
@TableName("m2_anes.followup_record")
public class FollowupRecordEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属麻醉记录单（FK，写入后不可改；必须已结束）。 */
    private UUID anesthesiaRecordId;

    /** 患者标识（由记录单解析，不存姓名/证件号明文）。 */
    private UUID patientId;

    /** 随访类型 H24/H48。 */
    private String followupType;

    /** 随访评估时间。 */
    private Instant assessedAt;

    /** NRS 疼痛评分 0–10。 */
    private Integer nrsScore;

    /** PONV 评分 0–3（演示口径【待确认】）。 */
    private Integer ponvScore;

    /** 并发症码 jsonb 数组。 */
    private String complications;

    /** 脱敏后的并发症说明。 */
    private String complicationNotes;

    /** 脱敏后的患者反馈。 */
    private String patientFeedback;

    /** 随访人姓名摘要。 */
    private String followedUpBy;

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

    public String getFollowupType() {
        return followupType;
    }

    public void setFollowupType(String followupType) {
        this.followupType = followupType;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }

    public void setAssessedAt(Instant assessedAt) {
        this.assessedAt = assessedAt;
    }

    public Integer getNrsScore() {
        return nrsScore;
    }

    public void setNrsScore(Integer nrsScore) {
        this.nrsScore = nrsScore;
    }

    public Integer getPonvScore() {
        return ponvScore;
    }

    public void setPonvScore(Integer ponvScore) {
        this.ponvScore = ponvScore;
    }

    public String getComplications() {
        return complications;
    }

    public void setComplications(String complications) {
        this.complications = complications;
    }

    public String getComplicationNotes() {
        return complicationNotes;
    }

    public void setComplicationNotes(String complicationNotes) {
        this.complicationNotes = complicationNotes;
    }

    public String getPatientFeedback() {
        return patientFeedback;
    }

    public void setPatientFeedback(String patientFeedback) {
        this.patientFeedback = patientFeedback;
    }

    public String getFollowedUpBy() {
        return followedUpBy;
    }

    public void setFollowedUpBy(String followedUpBy) {
        this.followedUpBy = followedUpBy;
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
