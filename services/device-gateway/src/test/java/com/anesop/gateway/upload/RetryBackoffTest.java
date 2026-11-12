package com.anesop.gateway.upload;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 指数退避：1→2→4→8 倍增长并封顶，失败次数归零后等待为 0。
 */
class RetryBackoffTest {

    private static final long BASE = 500L;
    private static final long MAX = 30_000L;

    @Test
    void doublesDelayPerConsecutiveFailure() {
        assertThat(RetryBackoff.delayMillis(1, BASE, MAX)).isEqualTo(500L);
        assertThat(RetryBackoff.delayMillis(2, BASE, MAX)).isEqualTo(1000L);
        assertThat(RetryBackoff.delayMillis(3, BASE, MAX)).isEqualTo(2000L);
        assertThat(RetryBackoff.delayMillis(4, BASE, MAX)).isEqualTo(4000L);
    }

    @Test
    void capsAtMaximumDelay() {
        assertThat(RetryBackoff.delayMillis(20, BASE, MAX)).isEqualTo(MAX);
        assertThat(RetryBackoff.delayMillis(100, BASE, MAX)).isEqualTo(MAX);
    }

    @Test
    void noDelayWithoutFailure() {
        assertThat(RetryBackoff.delayMillis(0, BASE, MAX)).isZero();
        assertThat(RetryBackoff.delayMillis(-1, BASE, MAX)).isZero();
    }
}
