package com.anesop.api.security;

import com.anesop.api.config.JwtProperties;
import com.anesop.m0.api.security.AccessTokenIssuer;
import com.anesop.m0.api.security.AuthUser;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

/**
 * 访问令牌签发（HS256 JWT）。
 *
 * <p>claim 约定：sub=用户 UUID、username、displayName、roles[]、permissions[]、
 * mustChangePassword=强制改密标记、sid=会话标识（sys_refresh_token.id）、
 * jti=令牌标识（登出吊销）。令牌不写日志、不入库。
 */
@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final java.time.Duration accessTtl;

    public JwtAccessTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        // 只保留签发所需的不可变配置值，不持有外部可变配置对象（SpotBugs EI_EXPOSE_REP2）。
        this.issuer = properties.getIssuer();
        this.accessTtl = properties.getAccessTtl();
    }

    @Override
    public IssuedAccessToken issue(AuthUser user, String sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTtl);
        String tokenId = UUID.randomUUID().toString();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.userId().toString())
                .id(tokenId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("username", user.username())
                .claim("displayName", user.displayName())
                .claim("roles", user.roles())
                .claim("permissions", user.permissions())
                .claim("mustChangePassword", user.mustChangePassword())
                .claim("sid", sessionId)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(token, tokenId, expiresAt);
    }
}
