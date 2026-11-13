package com.anesop.m2.api.preop;

/**
 * 术前访视语义拒绝（M2-M4-01），Web 层映射 422 {@code BUSINESS_RULE_VIOLATION}。
 *
 * <p>用于创建时手术申请不存在/不可见（请求体传申请标识、无资源路径参数，按 422 语义返回，
 * 不回显申请是否存在）；字段级格式错误仍用 {@link PreopValidationException}（400）。
 */
public class PreopUnprocessableException extends RuntimeException {

    private final String field;

    public PreopUnprocessableException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
