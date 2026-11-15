package com.anesop.api.security;

import com.anesop.api.config.RateLimitProperties;
import com.anesop.api.error.ErrorResponses;
import com.anesop.api.web.RequestContexts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 登录/刷新端点内存令牌桶限流（S-6，M5-M19-02 加固）。
 *
 * <p>计数键（配置见 {@link RateLimitProperties}，{@code anesop.security.rate-limit.*}）：
 * <ul>
 *   <li>{@code POST /v1/auth/login}：来源 IP + 小写用户名（用户名只从请求体解析，
 *       不落日志/审计，不记录口令）；用户名无法解析时退化为 IP；</li>
 *   <li>{@code POST /v1/auth/refresh}：来源 IP（刷新请求体含令牌，过滤器<b>不读取</b>）。</li>
 * </ul>
 *
 * <p>超限返回 429 {@code RATE_LIMITED}（契约 components/responses/TooManyRequests），
 * 并带 {@code Retry-After}（秒，向上取整到下一次可用令牌）。桶表按 LRU 限制容量，
 * 避免攻击者用大量 IP/用户名组合无限占用内存。
 *
 * <p>单实例语义：多实例/集群需改为 Redis 令牌桶（已登记遗留）；nginx 另按来源 IP
 * 做流量兜底（{@code limit_req}），两层互补。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/v1/auth/login";
    private static final String REFRESH_PATH = "/v1/auth/refresh";

    /** 登录请求体解析上限：超过即不解析用户名，退化为 IP 维度（不消费大请求体）。 */
    private static final int MAX_PARSED_BODY_BYTES = 8192;

    /** 用户名键截断长度：避免超长输入放大桶键内存。 */
    private static final int MAX_USERNAME_KEY_LENGTH = 64;

    private final boolean enabled;
    private final int capacity;
    private final int refillTokens;
    private final long refillPeriodNanos;
    private final ObjectMapper objectMapper;
    private final Map<String, Bucket> buckets;

    public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
        // 只保留不可变配置值，不持有外部可变配置 Bean（SpotBugs EI_EXPOSE_REP2）。
        this.enabled = properties.isEnabled();
        this.capacity = Math.max(1, properties.getCapacity());
        this.refillTokens = Math.max(1, properties.getRefillTokens());
        this.refillPeriodNanos = Math.max(1L, properties.getRefillPeriod().toNanos());
        this.objectMapper = objectMapper.copy();
        this.buckets = Collections.synchronizedMap(new BucketMap(Math.max(1, properties.getMaxBuckets())));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!enabled || !isLimitedRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = clientKey(request);
        HttpServletRequest effectiveRequest = request;
        String bucketKey;
        if (LOGIN_PATH.equals(request.getRequestURI())) {
            byte[] body = readLoginBody(request);
            if (body != null) {
                effectiveRequest = new BodyReplayingRequest(request, body);
            }
            bucketKey = "login|" + clientIp + "|" + usernameKey(body);
        } else {
            // 刷新请求体含令牌：不读取、不缓存、不记录。
            bucketKey = "refresh|" + clientIp;
        }

        Bucket bucket = buckets.computeIfAbsent(bucketKey, ignored -> new Bucket(capacity));
        if (tryConsume(bucket)) {
            filterChain.doFilter(effectiveRequest, response);
            return;
        }

        long retryAfterSeconds = retryAfterSeconds(bucket);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
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

    /**
     * 只读取长度已知且不超过上限的 JSON 登录体，用于提取用户名；其余情况返回 null，
     * 请求体保持原样交给后续过滤器/控制器。绝不记录请求体内容。
     */
    private byte[] readLoginBody(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("json")) {
            return null;
        }
        int contentLength = request.getContentLength();
        if (contentLength <= 0 || contentLength > MAX_PARSED_BODY_BYTES) {
            return null;
        }
        byte[] body = request.getInputStream().readNBytes(contentLength);
        return body.length == 0 ? null : body;
    }

    /** 从登录体提取用户名键；解析失败/无用户名一律返回 {@code "-"}，绝不回显原文。 */
    private String usernameKey(byte[] body) {
        if (body == null) {
            return "-";
        }
        try {
            JsonNode username = objectMapper.readTree(body).get("username");
            if (username == null || !username.isTextual()) {
                return "-";
            }
            String value = username.asText().trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                return "-";
            }
            return value.length() > MAX_USERNAME_KEY_LENGTH ? value.substring(0, MAX_USERNAME_KEY_LENGTH) : value;
        } catch (IOException | RuntimeException parseFailure) {
            return "-";
        }
    }

    private boolean tryConsume(Bucket bucket) {
        synchronized (bucket) {
            long now = System.nanoTime();
            double elapsed = now - bucket.lastRefillNanos;
            if (elapsed > 0) {
                double refill = elapsed / (double) refillPeriodNanos * refillTokens;
                bucket.tokens = Math.min(capacity, bucket.tokens + refill);
                bucket.lastRefillNanos = now;
            }
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** 下一次可用令牌的等待秒数（向上取整，至少 1 秒）。 */
    private long retryAfterSeconds(Bucket bucket) {
        synchronized (bucket) {
            double missing = Math.max(0.0, 1.0 - bucket.tokens);
            double nanosPerToken = refillPeriodNanos / (double) refillTokens;
            long seconds = (long) Math.ceil(missing * nanosPerToken / 1_000_000_000d);
            return Math.max(1L, seconds);
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

    /** 有界 LRU 桶表：超出上限淘汰最久未使用的键。 */
    private static final class BucketMap extends LinkedHashMap<String, Bucket> {

        private static final long serialVersionUID = 1L;

        private final int maxEntries;

        private BucketMap(int maxEntries) {
            super(64, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > maxEntries;
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos = System.nanoTime();

        private Bucket(int capacity) {
            this.tokens = Math.max(1, capacity);
        }
    }

    /**
     * 回放已缓存请求体的包装器：过滤器解析用户名后，下游仍能完整读取 JSON 体。
     * 只用于登录接口的小请求体；不缓存刷新令牌等其它请求体。
     */
    private static final class BodyReplayingRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BodyReplayingRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return bytes.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("同步过滤器不支持异步读取");
                }

                @Override
                public int read() {
                    return bytes.read();
                }

                @Override
                public int read(byte[] destination, int offset, int length) {
                    return bytes.read(destination, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
