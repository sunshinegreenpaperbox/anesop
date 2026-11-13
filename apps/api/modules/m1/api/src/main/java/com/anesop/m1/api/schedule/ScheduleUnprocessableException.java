package com.anesop.m1.api.schedule;

/**
 * 排程语义不合法（申请不存在/状态不允许/术间不可用），Web 层映射 422
 * {@code BUSINESS_RULE_VIOLATION}，与契约 422 语义一致。
 */
public class ScheduleUnprocessableException extends RuntimeException {

    private final String field;

    public ScheduleUnprocessableException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
