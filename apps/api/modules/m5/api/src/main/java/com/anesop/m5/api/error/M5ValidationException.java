package com.anesop.m5.api.error;

/**
 * 看板字段级校验异常（400 {@code PARAM_INVALID}）。
 *
 * <p>只携带字段名与服务端固定文案，不回显原始入参（避免把异常输入写入响应/日志）。
 * 由 api-bootstrap 的全局异常处理器映射为契约 {@code Error}。
 */
public class M5ValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    /**
     * 构造字段级校验异常。
     *
     * @param field   出错字段名
     * @param message 固定中文文案（不含用户输入原文）
     */
    public M5ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** 出错字段名（契约 {@code details[].field}）。 */
    public String field() {
        return field;
    }
}
