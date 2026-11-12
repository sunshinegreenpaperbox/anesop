package com.anesop.m0.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 刷新令牌实体，对应 m0_platform.sys_refresh_token（V3）。
 *
 * <p>只存令牌的 SHA-256 哈希，不存明文；刷新即轮换：旧行 {@code revokedAt} 置值，
 * 新行插入。会话列表（GET /v1/auth/sessions）以本表为数据源。
 */
@TableName("m0_platform.sys_refresh_token")
public class SysRefreshToken {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID userId;

    /** 刷新令牌 SHA-256 十六进制哈希（64 字符）；禁止明文。 */
    private String tokenHash;

    private Instant issuedAt;

    private Instant expiresAt;

    private Instant revokedAt;

    /** 客户端信息 JSON（ip/userAgent），仅用于会话展示，不含患者信息。 */
    private String clientInfo;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getClientInfo() {
        return clientInfo;
    }

    public void setClientInfo(String clientInfo) {
        this.clientInfo = clientInfo;
    }
}
