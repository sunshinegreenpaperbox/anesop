package com.anesop.m0.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 文件元数据实体，对应 m0_platform.sys_file（V27，M8-M0-04）。
 *
 * <p>内容不落库：{@code storageKey} 是存储适配器内部相对键（内容寻址），不出接口；
 * {@code sha256} 用于完整性校验与内容去重；{@code deleted=true} 为逻辑删除（接口按 404）。
 * 类型/大小白名单同时由库层 CHECK 与应用层校验保证。
 *
 * <p><b>不保存客户端原始文件名</b>（评审 B-2）：展示名由服务端按 {@code file-<id>.<ext>}
 * 生成，避免姓名/住院号/身份证明文件名进入库、备份与审计。
 */
@TableName("m0_platform.sys_file")
public class SysFile {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String storageKey;

    private String contentType;

    private Long sizeBytes;

    private String sha256;

    private String businessType;

    private String businessId;

    private UUID uploaderId;

    private String scanStatus;

    private Boolean deleted;

    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getBusinessType() {
        return businessType;
    }

    public void setBusinessType(String businessType) {
        this.businessType = businessType;
    }

    public String getBusinessId() {
        return businessId;
    }

    public void setBusinessId(String businessId) {
        this.businessId = businessId;
    }

    public UUID getUploaderId() {
        return uploaderId;
    }

    public void setUploaderId(UUID uploaderId) {
        this.uploaderId = uploaderId;
    }

    public String getScanStatus() {
        return scanStatus;
    }

    public void setScanStatus(String scanStatus) {
        this.scanStatus = scanStatus;
    }

    public Boolean getDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
