package com.anesop.m0.api.platform;

/**
 * 平台能力字段级校验失败（400 {@code PARAM_INVALID}，M8-M0-04）。
 *
 * <p>只携带字段名与服务端固定文案，不回显原始入参（避免把 Base64 内容、文件内容片段或
 * 患者标识写入响应/日志）。由 api-bootstrap 的全局异常处理器映射为契约 {@code Error}。
 */
public class M0ValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public M0ValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** 出错字段名（契约 {@code details[].field}）。 */
    public String field() {
        return field;
    }
}
