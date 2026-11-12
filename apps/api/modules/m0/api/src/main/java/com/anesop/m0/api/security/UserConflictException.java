package com.anesop.m0.api.security;

/**
 * 用户管理唯一性冲突（M2-M0-02）：登录账号（大小写不敏感）或工号已存在。
 *
 * <p>Web 层映射为 409 {@code STATE_CONFLICT}。服务层先做判重是为了在事务内“干净地失败”：
 * 直接撞库唯一索引会让 PostgreSQL 事务进入 aborted 状态，后续审计写入只能 fail-open；
 * 数据库唯一索引仍是最终防线（并发创建时由 {@code DuplicateKeyException} 兜底）。
 *
 * <p>文案固定，不回显冲突值、SQL 实参与约束名。
 */
public class UserConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UserConflictException(String message) {
        super(message);
    }
}
