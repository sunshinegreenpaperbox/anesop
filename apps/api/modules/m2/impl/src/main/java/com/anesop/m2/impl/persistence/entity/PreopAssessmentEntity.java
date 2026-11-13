package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * 术前访视与评估实体，对应 {@code m2_anes.preop_assessment}（V10）。
 *
 * <p>评分输入/患者快照/气道明细/评分结果均以 JSON 文本承载（列类型 jsonb，读写时由
 * 显式 SQL {@code CAST(... AS jsonb)} / {@code getString} 完成映射）；不跨模块复用 m0 的
 * jsonb TypeHandler（模块边界：m2-impl 只依赖 m0-api）。
 *
 * <p>{@code scoreInputJson} 是复算与审计的事实源；{@code scoresJson} 为服务端计算结果快照。
 */
@TableName("m2_anes.preop_assessment")
public class PreopAssessmentEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID applicationId;
    private UUID patientId;

    @TableField("patient_snapshot")
    private String patientSnapshotJson;

    private String historySummary;
    private String asaClass;
    private String mallampati;

    @TableField("airway_details")
    private String airwayDetailsJson;

    @TableField("score_input")
    private String scoreInputJson;

    private Integer rcriScore;
    private Integer apfelScore;
    private Integer stopbangScore;
    private Double dasiScore;
    private String rcriRisk;
    private String apfelRisk;
    private String stopbangRisk;
    private Boolean functionalCapacityLow;
    private String riskLevel;
    private String calculatorVersion;

    @TableField("scores")
    private String scoresJson;

    private String planType;
    private String airwayPlan;
    private String planNotes;
    private String status;
    private String finalizeNote;
    private Instant finalizedAt;
    private UUID finalizedBy;
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

    public String getPatientSnapshotJson() {
        return patientSnapshotJson;
    }

    public void setPatientSnapshotJson(String patientSnapshotJson) {
        this.patientSnapshotJson = patientSnapshotJson;
    }

    public String getHistorySummary() {
        return historySummary;
    }

    public void setHistorySummary(String historySummary) {
        this.historySummary = historySummary;
    }

    public String getAsaClass() {
        return asaClass;
    }

    public void setAsaClass(String asaClass) {
        this.asaClass = asaClass;
    }

    public String getMallampati() {
        return mallampati;
    }

    public void setMallampati(String mallampati) {
        this.mallampati = mallampati;
    }

    public String getAirwayDetailsJson() {
        return airwayDetailsJson;
    }

    public void setAirwayDetailsJson(String airwayDetailsJson) {
        this.airwayDetailsJson = airwayDetailsJson;
    }

    public String getScoreInputJson() {
        return scoreInputJson;
    }

    public void setScoreInputJson(String scoreInputJson) {
        this.scoreInputJson = scoreInputJson;
    }

    public Integer getRcriScore() {
        return rcriScore;
    }

    public void setRcriScore(Integer rcriScore) {
        this.rcriScore = rcriScore;
    }

    public Integer getApfelScore() {
        return apfelScore;
    }

    public void setApfelScore(Integer apfelScore) {
        this.apfelScore = apfelScore;
    }

    public Integer getStopbangScore() {
        return stopbangScore;
    }

    public void setStopbangScore(Integer stopbangScore) {
        this.stopbangScore = stopbangScore;
    }

    public Double getDasiScore() {
        return dasiScore;
    }

    public void setDasiScore(Double dasiScore) {
        this.dasiScore = dasiScore;
    }

    public String getRcriRisk() {
        return rcriRisk;
    }

    public void setRcriRisk(String rcriRisk) {
        this.rcriRisk = rcriRisk;
    }

    public String getApfelRisk() {
        return apfelRisk;
    }

    public void setApfelRisk(String apfelRisk) {
        this.apfelRisk = apfelRisk;
    }

    public String getStopbangRisk() {
        return stopbangRisk;
    }

    public void setStopbangRisk(String stopbangRisk) {
        this.stopbangRisk = stopbangRisk;
    }

    public Boolean getFunctionalCapacityLow() {
        return functionalCapacityLow;
    }

    public void setFunctionalCapacityLow(Boolean functionalCapacityLow) {
        this.functionalCapacityLow = functionalCapacityLow;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getCalculatorVersion() {
        return calculatorVersion;
    }

    public String getScoresJson() {
        return scoresJson;
    }

    public void setScoresJson(String scoresJson) {
        this.scoresJson = scoresJson;
    }

    public void setCalculatorVersion(String calculatorVersion) {
        this.calculatorVersion = calculatorVersion;
    }

    public String getPlanType() {
        return planType;
    }

    public void setPlanType(String planType) {
        this.planType = planType;
    }

    public String getAirwayPlan() {
        return airwayPlan;
    }

    public void setAirwayPlan(String airwayPlan) {
        this.airwayPlan = airwayPlan;
    }

    public String getPlanNotes() {
        return planNotes;
    }

    public void setPlanNotes(String planNotes) {
        this.planNotes = planNotes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFinalizeNote() {
        return finalizeNote;
    }

    public void setFinalizeNote(String finalizeNote) {
        this.finalizeNote = finalizeNote;
    }

    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public void setFinalizedAt(Instant finalizedAt) {
        this.finalizedAt = finalizedAt;
    }

    public UUID getFinalizedBy() {
        return finalizedBy;
    }

    public void setFinalizedBy(UUID finalizedBy) {
        this.finalizedBy = finalizedBy;
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
