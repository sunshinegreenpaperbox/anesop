package com.anesop.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录/刷新 IP 级限流（{@code anesop.security.rate-limit.*}，S-6）。
 *
 * <p>内存令牌桶：每个来源 IP 一个桶，容量 {@code capacity}，每 {@code refillPeriod}
 * 补充 {@code refillTokens} 个令牌。单实例语义；多实例/集群限流登记为遗留（Redis 令牌桶）。
 */
@ConfigurationProperties(prefix = "anesop.security.rate-limit")
public class RateLimitProperties {

    /** 是否启用（默认 true）。 */
    private boolean enabled = true;

    /** 每个 IP 的桶容量（突发上限）。 */
    private int capacity = 20;

    /** 每个补充周期补充的令牌数。 */
    private int refillTokens = 20;

    /** 补充周期，默认 1 分钟。 */
    private Duration refillPeriod = Duration.ofMinutes(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getRefillTokens() {
        return refillTokens;
    }

    public void setRefillTokens(int refillTokens) {
        this.refillTokens = refillTokens;
    }

    public Duration getRefillPeriod() {
        return refillPeriod;
    }

    public void setRefillPeriod(Duration refillPeriod) {
        this.refillPeriod = refillPeriod;
    }
}
