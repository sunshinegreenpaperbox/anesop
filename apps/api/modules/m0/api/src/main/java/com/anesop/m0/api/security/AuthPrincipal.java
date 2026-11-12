package com.anesop.m0.api.security;

import java.time.Instant;
import java.util.UUID;

/**
 * 已认证请求的主体上下文（从 Bearer Token 解析）。
 *
 * @param userId         用户标识（JWT sub）
 * @param username       登录账号（JWT username claim）
 * @param tokenId        JWT jti，用于登出时吊销访问令牌
 * @param tokenExpiresAt 访问令牌过期时间，吊销记录只需保留到该时刻
 * @param sessionId      会话标识（JWT sid，对应 sys_refresh_token.id）；登出按会话撤销
 */
public record AuthPrincipal(UUID userId, String username, String tokenId, Instant tokenExpiresAt, String sessionId) {
}
