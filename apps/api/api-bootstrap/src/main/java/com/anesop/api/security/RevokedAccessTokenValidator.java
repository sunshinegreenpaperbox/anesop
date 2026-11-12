package com.anesop.api.security;

import com.anesop.m0.api.security.AccessTokenRevocationStore;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 访问令牌吊销校验：JWT 签名/iss/exp 通过后，再查吊销表（登出即失效）。
 *
 * <p>JWT 本身无状态，登出后的立即失效依赖此校验；记录 TTL 不超过令牌有效期。
 */
@Component
public class RevokedAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final AccessTokenRevocationStore revocationStore;

    public RevokedAccessTokenValidator(AccessTokenRevocationStore revocationStore) {
        this.revocationStore = revocationStore;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (token != null && revocationStore.isRevoked(token.getId())) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "访问令牌已吊销", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
