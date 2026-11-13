package com.anesop.m2.api.preop;

/**
 * 术前访视状态/版本冲突（M2-M4-01），Web 层映射 409 {@code STATE_CONFLICT}。
 *
 * <p>典型场景：访视单已最终化（只能新建版本）、乐观锁版本过期、同一申请已有草稿、
 * 申请已取消/驳回。异常 message 只用于服务端判断，不直接回显数据库约束名或 SQL 实参。
 */
public class PreopConflictException extends RuntimeException {

    public PreopConflictException(String message) {
        super(message);
    }
}
