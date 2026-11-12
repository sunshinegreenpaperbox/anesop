package com.anesop.api.security;

import com.anesop.api.config.RateLimitProperties;
import com.anesop.api.error.ErrorResponses;
import com.anesop.api.web.RequestContexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 登录/刷新 IP 级限流（S-6）：内存令牌桶，按来源 IP 计数。
 *
 * <p>只拦截 {@code POST /v1/auth/login} 与 {@code POST /v1/auth/refresh}；超限返回
 * 429 {@code RATE_LIMITED}（契约 components/responses/TooManyRequests）。可配置项见
 * {@link RateLimitProperties}（{@code anesop.security.rate-limit.*}）。
 *
 * <p>单实例语义：多实例/集群需改为 Redis 令牌桶（已登记遗留）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/v1/auth/login";
    private static final String REFRESH_PATH = "/v1/auth/refresh";

    private final boolean enabled;
    private final int capacity;
    private final int refillTokens;
    private final long refillPeriodNanos;
    private final ObjectMapper objectMapper;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
        // 只保留不可变配置值，不持有外部可变配置 Bean（SpotBugs EI_EXPOSE_REP2）。
        this.enabled = properties.isEnabled();
        this.capacity = Math.max(1, properties.getCapacity());
        this.refillTokens = Math.max(1, properties.getRefillTokens());
        this.refillPeriodNanos = Math.max(1L, properties.getRefillPeriod().toNanos());
        this.objectMapper = objectMapper.copy();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!enabled || !isLimitedRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String clientIp = clientKey(request);
        if (tryConsume(clientIp)) {
            filterChain.doFilter(request, response);
            return;
        }
        ErrorResponses.write(response, objectMapper, HttpStatus.TOO_MANY_REQUESTS.value(),
                ErrorResponses.of("RATE_LIMITED", "请求过于频繁，请稍后重试",
                        RequestContexts.traceId(request), null));
    }

    private static boolean isLimitedRequest(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        return LOGIN_PATH.equals(uri) || REFRESH_PATH.equals(uri);
    }

    private String clientKey(HttpServletRequest request) {
        String ip = RequestContexts.clientIp(request);
        return ip == null || ip.isBlank() ? "unknown" : ip.trim().toLowerCase(Locale.ROOT);
    }

    private boolean tryConsume(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(capacity));
        synchronized (bucket) {
            long now = System.nanoTime();
            double refill = (now - bucket.lastRefillNanos) / (double) refillPeriodNanos * refillTokens;
            bucket.tokens = Math.min(capacity, bucket.tokens + Math.max(0.0, refill));
            bucket.lastRefillNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** 测试辅助：清空所有桶。 */
    public void reset() {
        buckets.clear();
    }

    /** 当前桶数量（测试/观测辅助）。 */
    public int bucketCount() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        private Bucket(int capacity) {
            this.tokens = Math.max(1, capacity);
        }
    }
}
