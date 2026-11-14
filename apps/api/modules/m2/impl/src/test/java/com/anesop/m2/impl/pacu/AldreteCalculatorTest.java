package com.anesop.m2.impl.pacu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Aldrete 评分计算器单测（M3-M10-01）：五项边界 0/2、总分边界 0/10、越界拒绝与阈值判定。
 * 阈值本身来自配置（默认 9【待确认】），计算器只做分项合法性与求和。
 */
class AldreteCalculatorTest {

    @Test
    void totalComputesSumOfFiveItems() {
        assertThat(AldreteCalculator.total(2, 2, 2, 2, 2)).isEqualTo(10);
        assertThat(AldreteCalculator.total(0, 0, 0, 0, 0)).isZero();
        assertThat(AldreteCalculator.total(1, 2, 1, 2, 0)).isEqualTo(6);
    }

    @Test
    void itemBoundariesZeroAndTwoAreAccepted() {
        assertThat(AldreteCalculator.isItemValid(0)).isTrue();
        assertThat(AldreteCalculator.isItemValid(2)).isTrue();
        assertThat(AldreteCalculator.isItemValid(1)).isTrue();
        assertThat(AldreteCalculator.isItemValid(-1)).isFalse();
        assertThat(AldreteCalculator.isItemValid(3)).isFalse();
        assertThat(AldreteCalculator.isItemValid(null)).isFalse();
    }

    @Test
    void totalRejectsOutOfRangeItem() {
        assertThatThrownBy(() -> AldreteCalculator.total(3, 2, 2, 2, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activity");
        assertThatThrownBy(() -> AldreteCalculator.total(2, 2, 2, 2, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oxygenation");
    }

    @Test
    void thresholdIsInclusiveAndConfigurable() {
        assertThat(AldreteCalculator.meetsThreshold(9, 9)).isTrue();
        assertThat(AldreteCalculator.meetsThreshold(10, 9)).isTrue();
        assertThat(AldreteCalculator.meetsThreshold(8, 9)).isFalse();
        assertThat(AldreteCalculator.meetsThreshold(10, 10)).isTrue();
        assertThat(AldreteCalculator.meetsThreshold(0, 0)).isTrue();
    }
}
