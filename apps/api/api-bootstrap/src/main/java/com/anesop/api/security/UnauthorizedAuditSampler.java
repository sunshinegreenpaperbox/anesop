package com.anesop.api.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 未认证（401）审计采样器（S-6）：同一 IP + 路径每 {@value #WINDOW_MINUTES} 分钟最多 1 条。
 *
 * <p>未认证请求可能被扫描器刷量，逐条落库会淹没真正的审计事件。采样只作用于
 * {@code AUTH_UNAUTHORIZED} 留痕，不改变 401 响应；登录/刷新另有 IP 级限流。
 *
 * <p>由 {@link RestAuthenticationEntryPoint} 内部持有（非 Spring Bean），避免把可变状态
 * 注入多个组件；测试通过入口点提供的 reset 方法清理。
 */
public class UnauthorizedAuditSampler {

    /** 去重窗口（分钟）。 */
    public static final long WINDOW_MINUTES = 1;

    private static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);

    private final Map<String, Instant> lastRecorded = new ConcurrentHashMap<>();

    /** 是否应记录该 IP+路径的 401 审计。 */
    public boolean shouldRecord(String clientIp, String path) {
        String key = (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim())
                + "|" + (path == null || path.isBlank() ? "-" : path);
        Instant now = Instant.now();
        Instant previous = lastRecorded.put(key, now);
        return previous == null || !previous.plus(WINDOW).isAfter(now);
    }

    /** 测试辅助：清空采样状态。 */
    public void clear() {
        lastRecorded.clear();
    }

    /** 当前采样键数量（测试/观测辅助）。 */
    public int size() {
        return lastRecorded.size();
    }
}
