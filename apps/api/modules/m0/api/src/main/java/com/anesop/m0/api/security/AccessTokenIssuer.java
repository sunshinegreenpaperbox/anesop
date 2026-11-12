package com.anesop.m0.api.security;

import java.time.Instant;

/**
 * 访问令牌签发适配器。
 *
 * <p>实现在 api-bootstrap（Nimbus JWT + Spring Security OAuth2 Resource Server），
 * 使 m0-impl 不直接依赖 JOSE 库；算法与密钥管理见 application.yml
 * {@code anesop.security.jwt.*}。
 */
public interface AccessTokenIssuer {

    /**
     * 签发访问令牌。
     *
     * @param user      用户角色/权限快照
     * @param sessionId 会话标识（写入 sid claim，用于按会话登出）
     */
    IssuedAccessToken issue(AuthUser user, String sessionId);

    /**
     * 签发结果。
     *
     * @param token     令牌字符串（禁止写入日志/审计）
     * @param tokenId   JWT jti，登出吊销用
     * @param expiresAt 过期时间
     */
    record IssuedAccessToken(String token, String tokenId, Instant expiresAt) {
    }
}
