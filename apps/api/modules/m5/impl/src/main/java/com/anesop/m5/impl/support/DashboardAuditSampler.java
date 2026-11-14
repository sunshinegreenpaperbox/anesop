package com.anesop.m5.impl.support;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 看板审计采样器（M5-M18-01）：按“用户 + 看板类型”在采样窗口内只放行一次审计写入。
 *
 * <p>为什么需要采样：前端 10s 轮询意味着单个大屏用户每小时 360 次请求，逐次写审计会把
 * {@code sys_audit_log} 刷成噪声并掩盖真正的安全事件。这里按固定窗口采样（默认 300s），
 * 既保留“谁在何时看了哪类看板”的追溯，又不影响数据库与审计查询性能。
 *
 * <p>实现为进程内并发映射：单院单实例部署（方案书 §3.2 单院），不追求跨实例一致；
 * 看板读审计不是合规强留痕（写操作审计不受影响），采样丢失一次不构成留痕缺口。
 */
@Component
public class DashboardAuditSampler {

    /** 用户+看板类型 → 上次放行时间。 */
    private final ConcurrentHashMap<String, Instant> lastRecorded = new ConcurrentHashMap<>();

    /**
     * 判断本次是否应写审计，并在放行时记录时间（原子，避免并发重复写）。
     *
     * @param key    采样键（用户标识 + 看板类型）
     * @param now    当前时刻
     * @param window 采样窗口；小于等于 0 表示每次都放行（测试/排障）
     * @return true 表示本次应写审计
     */
    public boolean shouldRecord(String key, Instant now, Duration window) {
        if (key == null || now == null) {
            return false;
        }
        if (window == null || window.isZero() || window.isNegative()) {
            lastRecorded.put(key, now);
            return true;
        }
        AtomicBoolean allowed = new AtomicBoolean(false);
        lastRecorded.compute(key, (ignored, previous) -> {
            if (previous == null || !previous.plus(window).isAfter(now)) {
                allowed.set(true);
                return now;
            }
            return previous;
        });
        return allowed.get();
    }

    /** 清空采样状态（仅测试使用，避免用例之间互相抑制审计）。 */
    public void reset() {
        lastRecorded.clear();
    }
}
