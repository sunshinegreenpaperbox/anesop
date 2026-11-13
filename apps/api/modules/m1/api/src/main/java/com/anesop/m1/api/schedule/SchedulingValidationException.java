package com.anesop.m1.api.schedule;

/**
 * 排程字段级校验失败（M2-M2-01），Web 层映射 400 {@code PARAM_INVALID}。
 *
 * <p>{@code field} 为契约字段名（如 plannedStart），{@code message} 为固定安全文案。
 */
public class SchedulingValidationException extends RuntimeException {

    private final String field;

    public SchedulingValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
