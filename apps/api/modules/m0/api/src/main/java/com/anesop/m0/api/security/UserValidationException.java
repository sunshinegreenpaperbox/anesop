package com.anesop.m0.api.security;

/**
 * 用户管理字段级校验失败（M2-M0-02）：字段名明确，供 Web 层映射为
 * 400 {@code PARAM_INVALID} 的 {@code details[{field, reason}]}。
 *
 * <p>只承载固定安全文案：不得包含手机号明文、口令、密文或哈希（AGENTS 患者数据与安全红线）。
 * 继承 {@link IllegalArgumentException} 以复用“参数不合法”的语义，但在 Web 层优先按本类型
 * 处理，避免把字段名硬编码成 {@code newPassword}。
 */
public class UserValidationException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public UserValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** 契约错误明细中的字段名（如 {@code phone}、{@code initialPassword}）。 */
    public String field() {
        return field;
    }
}
