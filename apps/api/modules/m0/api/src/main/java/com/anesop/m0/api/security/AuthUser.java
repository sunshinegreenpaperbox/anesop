package com.anesop.m0.api.security;

import java.util.List;
import java.util.UUID;

/**
 * 认证用户快照：签发 JWT 与组装 {@code CurrentUser} 所需的角色、权限点与数据范围。
 *
 * <p>权限点与角色均为数据库中的原始编码（如 {@code m0:auth:read}、{@code SYS_ADMIN}）；
 * Spring Security 授权时由适配层加 {@code PERM_} / {@code ROLE_} 前缀（见
 * {@link AuthPermissions}）。令牌与日志中不得出现口令或令牌明文。
 */
public record AuthUser(
        UUID userId,
        String username,
        String displayName,
        String staffId,
        boolean mustChangePassword,
        List<String> roles,
        List<String> permissions,
        List<DataScopeAssignment> dataScopes,
        List<String> siteIds) {

    public AuthUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        dataScopes = dataScopes == null ? List.of() : List.copyOf(dataScopes);
        siteIds = siteIds == null ? List.of() : List.copyOf(siteIds);
    }
}
