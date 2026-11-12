package com.anesop.m0.api.security;

import java.util.List;
import java.util.UUID;

/**
 * 数据权限范围解析器（方案书 §3.9.2）。
 *
 * <p>S0-07 从 {@code sys_role.data_scope} 解析（联表 sys_user_role）；未知/空值 fail-closed
 * 返回 {@link DataScope#NONE}。角色/用户与科室/术间/患者的绑定表在 M1 补齐。
 */
public interface DataScopeResolver {

    /**
     * 解析用户的生效数据范围。
     *
     * <p>无角色、角色 data_scope 为空/未知或查询失败时返回
     * {@code List.of(new DataScopeAssignment(DataScope.NONE, List.of()))}，调用方必须拒绝；
     * 可用 {@link DataScopeAssignments#isDenied(List)} 判断。
     *
     * @param userId 用户标识
     */
    List<DataScopeAssignment> resolve(UUID userId);
}
