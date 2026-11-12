package com.anesop.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置（{@code anesop.security.jwt.*}）。
 *
 * <p>密钥的生成/校验规则见 {@link JwtSecretProvider}。安全默认（fail-closed）：
 * {@code allow-dev-generated=false}，未配置密钥即启动失败；只有 dev/test profile 的
 * {@code application-dev.yml} / {@code application-test.yml} 显式置 true。
 */
@ConfigurationProperties(prefix = "anesop.security.jwt")
public class JwtProperties {

    /** HS256 密钥（Base64 编码，解码后 >= 32 字节）；prod 必填。 */
    private String secret;

    /**
     * 是否允许未配置密钥时自动生成进程内随机密钥（仅 dev/test；默认 false）。
     *
     * <p>不依赖 profile 判断，避免漏设 {@code SPRING_PROFILES_ACTIVE} 时以宽松默认启动。
     */
    private boolean allowDevGenerated;

    /** 签发者（iss claim）。 */
    private String issuer = "anesop";

    /** 访问令牌有效期，默认 15 分钟。 */
    private Duration accessTtl = Duration.ofMinutes(15);

    /** 刷新令牌有效期，默认 7 天。 */
    private Duration refreshTtl = Duration.ofDays(7);

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public boolean isAllowDevGenerated() {
        return allowDevGenerated;
    }

    public void setAllowDevGenerated(boolean allowDevGenerated) {
        this.allowDevGenerated = allowDevGenerated;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }
}
