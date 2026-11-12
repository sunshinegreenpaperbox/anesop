package com.anesop.m0.api.security;

import java.time.Instant;

/**
 * 访问令牌吊销记录（登出立即失效）。
 *
 * <p>S0-07 使用进程内实现（单实例），接口留作 Redis 适配占位：
 * 多实例部署时替换为 Redis（key=jti，TTL=令牌剩余有效期）。见 docs/modules/m0/README.md。
 */
public interface AccessTokenRevocationStore {

    /** 吊销令牌：记录保留到 {@code expiresAt} 之后即可清理。 */
    void revoke(String tokenId, Instant expiresAt);

    /** 令牌是否已吊销。 */
    boolean isRevoked(String tokenId);
}
