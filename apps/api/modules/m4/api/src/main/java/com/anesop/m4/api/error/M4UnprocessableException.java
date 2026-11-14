package com.anesop.m4.api.error;

/**
 * M4 业务规则拒绝（如药品未收录于字典、科室不在数据范围），Web 层映射 422
 * {@code BUSINESS_RULE_VIOLATION}。
 *
 * <p>{@code field} 为契约字段名；文案为固定安全文案，不回显资源存在性或数据库细节。
 */
public class M4UnprocessableException extends RuntimeException {

    private final String field;

    public M4UnprocessableException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
