package com.anesop.m1.api.application;

/**
 * 患者不在本系统患者快照内（HIS 主索引未同步），Web 层映射 422 {@code BUSINESS_RULE_VIOLATION}。
 *
 * <p>创建/导入接口没有资源路径参数，按契约错误语义使用 422 而不是 404；
 * 单对象查询（患者详情/申请详情）仍使用 404。
 */
public class PatientNotInIndexException extends RuntimeException {

    public PatientNotInIndexException(String message) {
        super(message);
    }
}
