package com.anesop.m4.api.error;

import java.util.List;

/**
 * M4 状态/版本冲突（编码/名称/别名重复、乐观锁过期、日结不平、重复日结），Web 层映射
 * 409 {@code STATE_CONFLICT}。
 *
 * <p>{@link Detail} 用于日结不平的字段级说明（field=数量字段，reason=期望值与实盘值），
 * 前端直接展示；details 为空时只返回统一文案。文案不含数据库约束名、SQL 实参与患者标识。
 */
public class M4ConflictException extends RuntimeException {

    /** 409 的字段级明细（如平账差异）；不可变。 */
    public record Detail(String field, String reason) {
    }

    private final List<Detail> details;

    public M4ConflictException(String message) {
        this(message, List.of());
    }

    public M4ConflictException(String message, List<Detail> details) {
        super(message);
        this.details = List.copyOf(details == null ? List.of() : details);
    }

    public List<Detail> details() {
        return details;
    }
}
