package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * Aldrete 评估实体，对应 {@code m2_anes.pacu_assessment}（V18，M3-M10-01）。
 *
 * <p>append-only：UPDATE/DELETE/TRUNCATE 由触发器拒绝（SQLSTATE 55000）；五项各 0–2，
 * 总分由服务端计算且等于五项之和（数据库 CHECK 为第二道防线）。
 */
@TableName("m2_anes.pacu_assessment")
public class PacuAssessmentEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属复苏记录（FK）。 */
    private UUID pacuId;

    /** 评估时间。 */
    private Instant assessedAt;

    /** 活动能力 0–2。 */
    private Integer aldreteActivity;

    /** 呼吸 0–2。 */
    private Integer aldreteRespiration;

    /** 循环 0–2。 */
    private Integer aldreteCirculation;

    /** 意识 0–2。 */
    private Integer aldreteConsciousness;

    /** 氧合 0–2。 */
    private Integer aldreteOxygenation;

    /** 总分（服务端计算，五项之和）。 */
    private Integer aldreteTotal;

    /** 评估备注（只落库，不写日志/审计）。 */
    private String notes;

    /** 录入时间。 */
    private Instant createdAt;

    /** 录入人用户标识。 */
    private UUID createdBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPacuId() {
        return pacuId;
    }

    public void setPacuId(UUID pacuId) {
        this.pacuId = pacuId;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }

    public void setAssessedAt(Instant assessedAt) {
        this.assessedAt = assessedAt;
    }

    public Integer getAldreteActivity() {
        return aldreteActivity;
    }

    public void setAldreteActivity(Integer aldreteActivity) {
        this.aldreteActivity = aldreteActivity;
    }

    public Integer getAldreteRespiration() {
        return aldreteRespiration;
    }

    public void setAldreteRespiration(Integer aldreteRespiration) {
        this.aldreteRespiration = aldreteRespiration;
    }

    public Integer getAldreteCirculation() {
        return aldreteCirculation;
    }

    public void setAldreteCirculation(Integer aldreteCirculation) {
        this.aldreteCirculation = aldreteCirculation;
    }

    public Integer getAldreteConsciousness() {
        return aldreteConsciousness;
    }

    public void setAldreteConsciousness(Integer aldreteConsciousness) {
        this.aldreteConsciousness = aldreteConsciousness;
    }

    public Integer getAldreteOxygenation() {
        return aldreteOxygenation;
    }

    public void setAldreteOxygenation(Integer aldreteOxygenation) {
        this.aldreteOxygenation = aldreteOxygenation;
    }

    public Integer getAldreteTotal() {
        return aldreteTotal;
    }

    public void setAldreteTotal(Integer aldreteTotal) {
        this.aldreteTotal = aldreteTotal;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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
