package com.anesop.m1.impl.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InpatientNoMaskerTest {

    @Test
    void masksMiddleOfNormalLengthInpatientNo() {
        assertThat(InpatientNoMasker.mask("SYNTH-ZY-0001")).isEqualTo("SYNT****0001");
    }

    @Test
    void keepsOnlyHeadAndTailForShortValue() {
        assertThat(InpatientNoMasker.mask("ZY0001")).isEqualTo("ZY****01");
    }

    @Test
    void handlesBlankAndNull() {
        assertThat(InpatientNoMasker.mask(null)).isEmpty();
        assertThat(InpatientNoMasker.mask("   ")).isEmpty();
    }
}
