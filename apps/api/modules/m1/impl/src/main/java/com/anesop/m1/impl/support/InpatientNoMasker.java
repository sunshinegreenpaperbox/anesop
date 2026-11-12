package com.anesop.m1.impl.support;

/**
 * 住院号脱敏（方案书 §3.9.3）：保留前 4 位与后 4 位，中间固定 4 个 {@code *}。
 *
 * <p>示例：{@code SYNTH-ZY-0001 → SYNT****0001}。长度不足 9 位时保留首尾各 2 位；
 * 空白返回空字符串。脱敏只用于展示，哈希由 {@link InpatientNoHasher} 负责。
 */
public final class InpatientNoMasker {

    private static final String MASK = "****";

    private InpatientNoMasker() {
    }

    public static String mask(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() <= 8) {
            int head = Math.min(2, value.length() / 2);
            int tail = Math.min(2, value.length() - head);
            return value.substring(0, head) + MASK + value.substring(value.length() - tail);
        }
        return value.substring(0, 4) + MASK + value.substring(value.length() - 4);
    }
}
