package com.anesop.m4.api.staff;

/**
 * 授权判定维度（进程内集成用，M4-M13-01 评审 B-3）。
 *
 * <p>调用方必须显式提供每个维度的值，或通过枚举声明“本次不适用”；缺失且未声明不适用时
 * 判定返回 {@code INPUT_INCOMPLETE}，不允许因缺字段而跳过校验（fail-closed）。
 */
public enum AuthorizationDimension {

    /** 术式标识/术式类别编码。 */
    SURGERY_TYPE,

    /** 患者年龄。 */
    AGE,

    /** ASA 分级。 */
    ASA
}
