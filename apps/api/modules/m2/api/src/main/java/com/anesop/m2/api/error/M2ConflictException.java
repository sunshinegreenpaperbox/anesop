package com.anesop.m2.api.error;

/**
 * M2 状态/版本冲突（知情同意/三方核查，M2-M5-01），Web 层映射 409 {@code STATE_CONFLICT}。
 *
 * <p>典型场景：重复签署、已撤销后再次签署、版本过期、同一模板版本重复创建、
 * 三方核查并发版本冲突。异常 message 只用于服务端判断，不直接回显数据库约束名或 SQL 实参。
 */
public class M2ConflictException extends RuntimeException {

    public M2ConflictException(String message) {
        super(message);
    }
}
