package com.anesop.m0.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.UUID;

/**
 * 用户实体，对应 m0_platform.sys_user（V1）。
 *
 * <p>口令哈希只用于认证服务内部校验，禁止序列化到任何 API 响应。
 *
 * <p>主键由应用层生成（{@code UUID.randomUUID()}）后写入；MyBatis-Plus 的
 * {@code ASSIGN_UUID} 仅支持 String 主键，故使用 {@code IdType.INPUT}，
 * 数据库 {@code gen_random_uuid()} 仅作直连 SQL 的兜底。
 */
@TableName("m0_platform.sys_user")
public class SysUser {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private String username;

    /** 口令哈希（BCrypt）；@JsonIgnore 防止误序列化，日志同样不得输出。 */
    @JsonIgnore
    private String passwordHash;

    private String displayName;

    private String employeeNo;

    /** 手机号密文（应用层 AES-256-GCM），永不解密后落盘/入日志；写路径 M1。 */
    private String phoneEnc;

    /** 手机号 HMAC-SHA-256 哈希（精确检索/去重）；V3 新增列，写路径 M2-M0-02。 */
    private String phoneHash;

    /** 手机号密文所用字段密钥版本标识（V6）；与 phone_enc/phone_hash 同时非空。 */
    private String phoneKeyId;

    /** 手机号密文/哈希最后写入时间（V6）；轮换与审计追溯用。 */
    private Instant phoneUpdatedAt;

    private Integer status;

    private Boolean mustChangePassword;

    private Integer failedAttempts;

    private Instant lockedUntil;

    private Instant lastLoginAt;

    private Instant createdAt;

    private UUID createdBy;

    private Instant updatedAt;

    private UUID updatedBy;

    /** 乐观锁版本号；updateById 自动追加 version 条件并自增，旧版本更新影响行数为 0。 */
    @Version
    private Long version;

    /** 软删除标记；select/update 自动过滤，deleteById 转为 UPDATE deleted=true。 */
    @TableLogic(value = "false", delval = "true")
    private Boolean deleted;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmployeeNo() {
        return employeeNo;
    }

    public void setEmployeeNo(String employeeNo) {
        this.employeeNo = employeeNo;
    }

    public String getPhoneEnc() {
        return phoneEnc;
    }

    public void setPhoneEnc(String phoneEnc) {
        this.phoneEnc = phoneEnc;
    }

    public String getPhoneHash() {
        return phoneHash;
    }

    public void setPhoneHash(String phoneHash) {
        this.phoneHash = phoneHash;
    }

    public String getPhoneKeyId() {
        return phoneKeyId;
    }

    public void setPhoneKeyId(String phoneKeyId) {
        this.phoneKeyId = phoneKeyId;
    }

    public Instant getPhoneUpdatedAt() {
        return phoneUpdatedAt;
    }

    public void setPhoneUpdatedAt(Instant phoneUpdatedAt) {
        this.phoneUpdatedAt = phoneUpdatedAt;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Boolean getMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(Boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Integer getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(Integer failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
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
