package com.anesop.api.security;

import com.anesop.m0.api.security.AuthPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 从已校验的 JWT 构造 {@link AuthPrincipal}（Web 适配层职责）。
 */
public final class AuthPrincipals {

    private AuthPrincipals() {
    }

    public static AuthPrincipal from(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        Instant expiresAt = jwt.getExpiresAt();
        return new AuthPrincipal(userId, jwt.getClaimAsString("username"), jwt.getId(), expiresAt,
                jwt.getClaimAsString("sid"));
    }

    /**
     * 令牌中的权限点集合（JWT {@code permissions} claim，签发时来自 {@code /v1/auth/me} 快照）。
     *
     * <p>用于需要“按业务权限收紧可见性”的接口（如文件下载的 SCHEDULE/RECORD/REPORT 关联）。
     * 缺 claim/空列表返回空集合（fail-closed：调用方据此判为无权限）。
     */
    public static Set<String> permissions(Jwt jwt) {
        return claimSet(jwt, "permissions");
    }

    /** 令牌中的角色码集合（JWT {@code roles} claim；字典维护与文件管理需要 SYS_ADMIN 判定）。 */
    public static Set<String> roles(Jwt jwt) {
        return claimSet(jwt, "roles");
    }

    private static Set<String> claimSet(Jwt jwt, String name) {
        List<String> values = jwt.getClaimAsStringList(name);
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
