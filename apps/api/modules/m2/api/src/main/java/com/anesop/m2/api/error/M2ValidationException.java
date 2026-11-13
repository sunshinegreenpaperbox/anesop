package com.anesop.m2.api.error;

/**
 * M2 字段级校验失败（知情同意/三方核查，M2-M5-01），Web 层映射 400 {@code PARAM_INVALID}。
 *
 * <p>{@code field} 为契约字段名；{@code message} 为固定安全文案，不含用户输入原文、
 * 签署人姓名、问题项原文或患者标识。
 */
public class M2ValidationException extends RuntimeException {

    private final String field;

    public M2ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
