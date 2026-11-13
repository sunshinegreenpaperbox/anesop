package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 知情同意模板与版本实体，对应 {@code m2_anes.consent_template}（V11）。
 *
 * <p>内容 append-only：{@code version} 是模板业务版本号（不是乐观锁），内容不可更新；
 * 新版本另建 (templateCode, version) 记录。{@code status} 仅允许 ACTIVE → RETIRED（停用一次），
 * 停用后不可用于新签署，历史同意记录仍引用旧版本。表内无 {@code deleted} 列（模板不删除）。
 */
@TableName("m2_anes.consent_template")
public class ConsentTemplateEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String templateCode;
    private Integer version;
    private String title;
    private String contentText;
    private Instant effectiveAt;
    private String status;
    private Instant retiredAt;
    private UUID retiredBy;
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

    public String getTemplateCode() {
        return templateCode;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContentText() {
        return contentText;
    }

    public void setContentText(String contentText) {
        this.contentText = contentText;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(Instant effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public void setRetiredAt(Instant retiredAt) {
        this.retiredAt = retiredAt;
    }

    public UUID getRetiredBy() {
        return retiredBy;
    }

    public void setRetiredBy(UUID retiredBy) {
        this.retiredBy = retiredBy;
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
