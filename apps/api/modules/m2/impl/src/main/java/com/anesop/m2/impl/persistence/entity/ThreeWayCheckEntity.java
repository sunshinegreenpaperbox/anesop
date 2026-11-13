package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 三方核查版本实体，对应 {@code m2_anes.three_way_check}（V11）。
 *
 * <p>append-only：每次录入新增一行 {@code checkVersion}，记录不可 UPDATE/DELETE；
 * 因此本实体没有乐观锁 {@code version} 与逻辑删除列，{@code checkVersion} 即版本标识。
 * {@code issuesJson} 对应 jsonb 字符串数组，读写用显式 {@code CAST(... AS jsonb)}。
 */
@TableName("m2_anes.three_way_check")
public class ThreeWayCheckEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID applicationId;
    private Integer checkVersion;
    private Instant timeOutAt;
    private UUID timeOutBy;
    private Instant signOutAt;
    private UUID signOutBy;
    private Boolean specimenChecked;
    private Boolean implantChecked;

    @TableField("issues")
    private String issuesJson;

    private Instant createdAt;
    private UUID createdBy;
    private Instant updatedAt;
    private UUID updatedBy;

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

    public Integer getCheckVersion() {
        return checkVersion;
    }

    public void setCheckVersion(Integer checkVersion) {
        this.checkVersion = checkVersion;
    }

    public Instant getTimeOutAt() {
        return timeOutAt;
    }

    public void setTimeOutAt(Instant timeOutAt) {
        this.timeOutAt = timeOutAt;
    }

    public UUID getTimeOutBy() {
        return timeOutBy;
    }

    public void setTimeOutBy(UUID timeOutBy) {
        this.timeOutBy = timeOutBy;
    }

    public Instant getSignOutAt() {
        return signOutAt;
    }

    public void setSignOutAt(Instant signOutAt) {
        this.signOutAt = signOutAt;
    }

    public UUID getSignOutBy() {
        return signOutBy;
    }

    public void setSignOutBy(UUID signOutBy) {
        this.signOutBy = signOutBy;
    }

    public Boolean getSpecimenChecked() {
        return specimenChecked;
    }

    public void setSpecimenChecked(Boolean specimenChecked) {
        this.specimenChecked = specimenChecked;
    }

    public Boolean getImplantChecked() {
        return implantChecked;
    }

    public void setImplantChecked(Boolean implantChecked) {
        this.implantChecked = implantChecked;
    }

    public String getIssuesJson() {
        return issuesJson;
    }

    public void setIssuesJson(String issuesJson) {
        this.issuesJson = issuesJson;
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
