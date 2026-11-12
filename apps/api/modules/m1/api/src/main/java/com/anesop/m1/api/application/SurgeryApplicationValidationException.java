package com.anesop.m1.api.application;

/**
 * 手术申请字段级校验失败，Web 层映射 400 {@code PARAM_INVALID}。
 *
 * <p>{@code field} 为契约字段名（如 plannedDurationMin），{@code message} 为固定安全文案，
 * 不含患者标识或用户输入原文。
 */
public class SurgeryApplicationValidationException extends RuntimeException {

    private final String field;

    public SurgeryApplicationValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
