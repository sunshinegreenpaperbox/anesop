package com.anesop.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录/刷新端点内存令牌桶（{@code anesop.security.rate-limit.*}，S-6 / M5-M19-02）。
 *
 * <p>登录按“来源 IP + 用户名”一个桶，刷新按来源 IP 一个桶；桶容量 {@code capacity}，
 * 每 {@code refillPeriod} 补充 {@code refillTokens} 个令牌；桶表按 {@code maxBuckets}
 * 做 LRU 限制。单实例语义；多实例/集群限流登记为遗留（Redis 令牌桶）。
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

    /**
     * 桶数量上限（LRU 淘汰，默认 10000）：限制内存占用，防止攻击者用大量
     * IP/用户名组合把桶表撑爆。超出后淘汰最久未使用的桶（被淘汰者重新获得满桶）。
     */
    private int maxBuckets = 10000;

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

    public int getMaxBuckets() {
        return maxBuckets;
    }

    public void setMaxBuckets(int maxBuckets) {
        this.maxBuckets = maxBuckets;
    }
}
