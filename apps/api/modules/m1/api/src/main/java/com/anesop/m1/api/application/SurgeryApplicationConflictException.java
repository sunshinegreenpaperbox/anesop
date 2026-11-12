package com.anesop.m1.api.application;

/**
 * 手术申请业务冲突（重复申请号、非法状态流转、乐观锁版本冲突），Web 层映射 409。
 *
 * <p>异常 message 只用于服务端判断，不直接回显数据库约束名或 SQL 实参。
 */
public class SurgeryApplicationConflictException extends RuntimeException {

    public SurgeryApplicationConflictException(String message) {
        super(message);
    }
}
