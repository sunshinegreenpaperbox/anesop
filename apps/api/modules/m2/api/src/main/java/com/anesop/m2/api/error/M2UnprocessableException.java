package com.anesop.m2.api.error;

/**
 * M2 语义拒绝（知情同意/三方核查，M2-M5-01），Web 层映射 422 {@code BUSINESS_RULE_VIOLATION}。
 *
 * <p>用于创建同意记录时申请不可见（请求体传申请标识、无资源路径参数）或模板/版本不存在，
 * 按 422 语义返回，不回显申请或模板是否存在。
 */
public class M2UnprocessableException extends RuntimeException {

    private final String field;

    public M2UnprocessableException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
