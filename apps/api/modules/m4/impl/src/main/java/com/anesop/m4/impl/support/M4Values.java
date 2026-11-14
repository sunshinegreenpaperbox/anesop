package com.anesop.m4.impl.support;

import com.anesop.m4.api.error.M4ValidationException;
import java.util.UUID;

/**
 * 人员域字段校验工具（M4-M13-01）。
 *
 * <p>统一 trim + 长度校验与固定安全文案：错误信息不得回显用户输入原文（姓名/证书号），
 * 只回字段名与约束。
 */
public final class M4Values {

    private M4Values() {
    }

    /** 必填文本：trim 后非空且不超过 maxLength，否则 400。 */
    public static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new M4ValidationException(field, field + " 长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    /** 可选文本：null/空白返回 null，超长 400。 */
    public static String optionalText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new M4ValidationException(field, field + " 长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    /** 可选 UUID 字符串：null/空白返回 null，格式非法 400。 */
    public static UUID optionalUuid(String value, String field) {
        String trimmed = optionalText(value, field, 36);
        if (trimmed == null) {
            return null;
        }
        try {
            return UUID.fromString(trimmed);
        } catch (IllegalArgumentException invalid) {
            throw new M4ValidationException(field, field + " 格式非法");
        }
    }

    /** 路径/入参 UUID：格式非法返回 null（调用方按 404/400 处理）。 */
    public static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
