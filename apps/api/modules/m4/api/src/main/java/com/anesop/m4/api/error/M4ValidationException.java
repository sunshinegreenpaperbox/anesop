package com.anesop.m4.api.error;

/**
 * M4 字段级校验失败（药品目录/麻精五专），Web 层映射 400 {@code PARAM_INVALID}。
 *
 * <p>{@code field} 为契约字段名；{@code message} 为固定安全文案，不含用户输入原文、
 * 人员姓名、处方号原文或患者标识。
 */
public class M4ValidationException extends RuntimeException {

    private final String field;

    public M4ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
