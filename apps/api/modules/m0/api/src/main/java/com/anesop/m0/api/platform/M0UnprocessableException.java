package com.anesop.m0.api.platform;

/**
 * 平台能力业务规则拒绝（422 {@code BUSINESS_RULE_VIOLATION}，M8-M0-04）。
 *
 * <p>用于文件类型不在白名单、大小超过 20 MiB 或与实际内容不一致等“参数格式合法但业务
 * 不允许”的场景；文案由服务端固定提供，不含文件内容与患者标识。
 */
public class M0UnprocessableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public M0UnprocessableException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** 出错字段名（契约 {@code details[].field}）。 */
    public String field() {
        return field;
    }
}
