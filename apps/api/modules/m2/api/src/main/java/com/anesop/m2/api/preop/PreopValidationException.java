package com.anesop.m2.api.preop;

/**
 * 术前访视字段级校验失败（M2-M4-01），Web 层映射 400 {@code PARAM_INVALID}。
 *
 * <p>{@code field} 为契约字段名（如 asaClass、scoreInput.rcri.highRiskSurgery），
 * {@code message} 为固定安全文案，不含用户输入原文、患者姓名或病史文本。
 */
public class PreopValidationException extends RuntimeException {

    private final String field;

    public PreopValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
