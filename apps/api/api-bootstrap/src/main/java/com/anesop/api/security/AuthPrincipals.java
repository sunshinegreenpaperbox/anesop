package com.anesop.api.security;

import com.anesop.m0.api.security.AuthPrincipal;
import java.time.Instant;
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
}
