package com.anesop.m2.impl.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 脱敏纯函数单测（M3-M11-01 评审修复 B-1/S-5）：手机号与身份证号容忍 +86、空格、短横线、
 * 括号等分隔写法；自由文本夹带姓名时只替换标识片段；日期/剂量/订单号等普通数字不受影响。
 * 全部为合成串，不含真实患者数据。
 */
class PhiMaskerTest {

    @Test
    void masksMobileNumbersWithSeparatorsAndCountryCode() {
        assertThat(PhiMasker.mask("13800138000")).isEqualTo("138****8000");
        assertThat(PhiMasker.mask("+8613800138000")).isEqualTo("138****8000");
        assertThat(PhiMasker.mask("008613800138000")).isEqualTo("138****8000");
        assertThat(PhiMasker.mask("138-0013-8000")).isEqualTo("138****8000");
        assertThat(PhiMasker.mask("138 0013 8000")).isEqualTo("138****8000");
        assertThat(PhiMasker.mask("138(0013)8000")).isEqualTo("138****8000");
        assertThat(PhiMasker.mask("+86 138 0013 8000")).isEqualTo("138****8000");
        assertThat(PhiMasker.mask("(+86) 138-0013-8000")).isEqualTo("138****8000");
        assertThat(PhiMasker.mask("(138) 0013-8000")).isEqualTo("138****8000");
    }

    @Test
    void masksIdCardsWithSeparatorsAndX() {
        assertThat(PhiMasker.mask("110101199003074567"))
                .isEqualTo("110101********4567");
        assertThat(PhiMasker.mask("110101 19900307 4567"))
                .isEqualTo("110101********4567");
        assertThat(PhiMasker.mask("110101-19900307-4567"))
                .isEqualTo("110101********4567");
        assertThat(PhiMasker.mask("11010119900307456X"))
                .isEqualTo("110101********456X");
        assertThat(PhiMasker.mask("110101 19900307 456X"))
                .isEqualTo("110101********456X");
        assertThat(PhiMasker.mask("110101900307456")).isEqualTo("110101******456");
        assertThat(PhiMasker.mask("110101 900307 456")).isEqualTo("110101******456");
    }

    @Test
    void masksIdentifiersInsideFreeTextAndPreservesOtherContent() {
        assertThat(PhiMasker.mask("张三，联系电话：138-0013-8000（合成）"))
                .isEqualTo("张三，联系电话：138****8000（合成）");
        assertThat(PhiMasker.mask(
                "身份证 110101 19900307 4567，电话 +86 138 0013 8000（合成）"))
                .isEqualTo("身份证 110101********4567，电话 138****8000（合成）");
        assertThat(PhiMasker.mask(" 演示：疼痛可耐受（合成） "))
                .isEqualTo("演示：疼痛可耐受（合成）");
    }

    @Test
    void doesNotAlterOrdinaryNumbersAndDates() {
        assertThat(PhiMasker.mask("2026-11-14 10:30，剂量 2.5-5 mg，订单号 202611140001"))
                .isEqualTo("2026-11-14 10:30，剂量 2.5-5 mg，订单号 202611140001");
        assertThat(PhiMasker.mask("批号 12345678901（合成）"))
                .isEqualTo("批号 12345678901（合成）");
        assertThat(PhiMasker.mask("住院号 12345678（合成）"))
                .isEqualTo("住院号 12345678（合成）");
        assertThat(PhiMasker.mask("电话 138****8000（已脱敏）"))
                .isEqualTo("电话 138****8000（已脱敏）");
    }

    @Test
    void detectsMaskableIdentifiersInAllSupportedFormats() {
        assertThat(PhiMasker.containsMaskableIdentifier("+8613800138000")).isTrue();
        assertThat(PhiMasker.containsMaskableIdentifier("138-0013-8000")).isTrue();
        assertThat(PhiMasker.containsMaskableIdentifier("138 0013 8000")).isTrue();
        assertThat(PhiMasker.containsMaskableIdentifier("110101 19900307 4567")).isTrue();
        assertThat(PhiMasker.containsMaskableIdentifier("110101 900307 456")).isTrue();
        assertThat(PhiMasker.containsMaskableIdentifier("11010119900307456X")).isTrue();
        assertThat(PhiMasker.containsMaskableIdentifier("2026-11-14 剂量 2.5 mg")).isFalse();
        assertThat(PhiMasker.containsMaskableIdentifier("订单号 202611140001")).isFalse();
        assertThat(PhiMasker.containsMaskableIdentifier("电话 138****8000")).isFalse();
        assertThat(PhiMasker.containsMaskableIdentifier(null)).isFalse();
    }

    @Test
    void returnsNullForNullOrBlankText() {
        assertThat(PhiMasker.mask(null)).isNull();
        assertThat(PhiMasker.mask("")).isNull();
        assertThat(PhiMasker.mask("   \n\t ")).isNull();
    }
}
