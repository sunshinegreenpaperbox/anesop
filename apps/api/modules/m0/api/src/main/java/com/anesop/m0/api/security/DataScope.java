package com.anesop.m0.api.security;

/**
 * 数据权限范围（方案书 §3.9.2）：全院、院区、科室、术间、本人患者。
 *
 * <p>与 {@code m0_platform.sys_role.data_scope} 的取值一一对应；对外契约
 * {@code DataScope.type} 使用 ALL / DEPARTMENT / ROOM / OWN_PATIENTS，
 * 由 M0 在输出时做名称映射（DEPT→DEPARTMENT、SELF→OWN_PATIENTS、SITE→SITE）。
 *
 * <p>{@link #NONE} 是 fail-closed 拒绝标记：角色无有效 data_scope、取值未知或查询失败时
 * 返回该值，调用方必须按“无任何数据可见”处理，不得回退为 ALL/SELF。NONE 不输出到
 * 契约（auth.yaml 的 DataScope 枚举不含它），而是映射为空数组。
 *
 * <p>M1 之前由 {@code sys_role.data_scope} 解析；角色/用户与范围对象的绑定表在 M1 补齐
 * （见 docs/modules/m0/README.md）。
 */
public enum DataScope {
    ALL,
    SITE,
    DEPT,
    ROOM,
    SELF,
    /** 拒绝标记：未知/缺失范围，视为无任何数据权限。 */
    NONE
}
