package com.anesop.gateway.upload;

/**
 * 上传失败的指数退避计算。
 *
 * <p>第 n 次连续失败后的等待时间为 {@code min(base * 2^(n-1), max)}：
 * 断网恢复瞬间不会把积压数据一次性打满数据库与带宽（方案书 §3.6.1 补传限速的简化）。
 * 成功一次即清零（由 {@code VitalUploader} 维护计数）。
 */
public final class RetryBackoff {

    /** 指数最大位移，避免长故障后溢出。 */
    private static final int MAX_EXPONENT = 20;

    private RetryBackoff() {
    }

    /**
     * 计算下一次重试前应等待的毫秒数。
     *
     * @param consecutiveFailures 连续失败次数（从 1 开始）
     * @param baseMillis          退避基数（毫秒，正数）
     * @param maxMillis           退避上限（毫秒，不小于基数）
     * @return 等待毫秒数；失败次数 ≤ 0 时为 0
     */
    public static long delayMillis(int consecutiveFailures, long baseMillis, long maxMillis) {
        if (consecutiveFailures <= 0) {
            return 0L;
        }
        int exponent = Math.min(consecutiveFailures - 1, MAX_EXPONENT);
        long delay = baseMillis << exponent;
        return Math.min(delay, maxMillis);
    }
}
