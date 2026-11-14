package com.anesop.m4.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 病历质量抽查实体（评分项 jsonb、总分/等级/缺陷计数）。
 *
 * <p>对应 {@code m4_qc.chart_review}（V25）；实体只做字段载体，业务规则在服务层与数据库触发器中实现。
 */
@TableName("m4_qc.chart_review")
public class ChartReviewEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 抽查号 */
    private String reviewNo;

    /** 关联台次（可空） */
    private UUID scheduleId;

    /** 关联记录单（可空） */
    private UUID recordId;

    /** 当前整改标识（只读投影，非物理列） */
    private UUID rectificationId;

    /** 科室标识 */
    private UUID deptId;

    /** 科室名称快照 */
    private String deptName;

    /** 抽查人标识 */
    private UUID reviewerId;

    /** 抽查人姓名摘要 */
    private String reviewerName;

    /** 抽查时间 */
    private Instant reviewedAt;

    /** 评分项 jsonb 文本 */
    private String items;

    /** 总分 */
    private Integer totalScore;

    /** 满分 */
    private Integer maxScore;

    /** 等级 A/B/C/D */
    private String grade;

    /** 缺陷项数 */
    private Integer defectCount;

    /** 状态 OPEN/RECTIFYING/CLOSED */
    private String status;

    /** 版本号 */
    private Integer version;

    /** 创建时间 */
    private Instant createdAt;

    /** 创建人 */
    private UUID createdBy;

    /** 更新时间 */
    private Instant updatedAt;

    /** 更新人 */
    private UUID updatedBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getReviewNo() {
        return reviewNo;
    }

    public void setReviewNo(String reviewNo) {
        this.reviewNo = reviewNo;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(UUID scheduleId) {
        this.scheduleId = scheduleId;
    }

    public UUID getRecordId() {
        return recordId;
    }

    public UUID getRectificationId() {
        return rectificationId;
    }

    public void setRectificationId(UUID rectificationId) {
        this.rectificationId = rectificationId;
    }

    public void setRecordId(UUID recordId) {
        this.recordId = recordId;
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

    public UUID getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(UUID reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getReviewerName() {
        return reviewerName;
    }

    public void setReviewerName(String reviewerName) {
        this.reviewerName = reviewerName;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getItems() {
        return items;
    }

    public void setItems(String items) {
        this.items = items;
    }

    public Integer getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(Integer totalScore) {
        this.totalScore = totalScore;
    }

    public Integer getMaxScore() {
        return maxScore;
    }

    public void setMaxScore(Integer maxScore) {
        this.maxScore = maxScore;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public Integer getDefectCount() {
        return defectCount;
    }

    public void setDefectCount(Integer defectCount) {
        this.defectCount = defectCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
}
