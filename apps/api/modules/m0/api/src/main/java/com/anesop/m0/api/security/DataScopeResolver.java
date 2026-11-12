package com.anesop.m0.api.security;

import java.util.UUID;

/**
 * 数据权限范围解析器（方案书 §3.9.2）。
 *
 * <p>从 {@code sys_role.data_scope} 读取角色范围（M2-M0-01 起联表 {@code sys_user_org} /
 * {@code sys_user_room} 填充 scopeIds）。未绑定、未知取值或查询异常一律 fail-closed
 * 返回 {@link DataScopeAssignments#denied()}（{@link DataScope#NONE}），调用方必须拒绝。
 */
public interface DataScopeResolver {

    /**
     * 解析用户的生效数据范围。
     *
     * <p>规则：任一角色 ALL → 全院可见；其余按 SITE/DEPT/ROOM/SELF 取并集，
     * SITE/DEPT 的 scopeIds 取自组织绑定，ROOM 取自术间绑定（仅 status=1），SELF 无对象标识。
     * 无角色、角色范围为空/未知、应绑定却未绑定（SITE/DEPT/ROOM 无绑定行）或查询失败时，
     * 返回 {@link DataScopeAssignments#denied()}，调用方用 {@link DataScopeAssignments#isDenied()}
     * 判断并拒绝，不得回退为 ALL/SELF。
     *
     * @param userId 用户标识；null 时直接拒绝
     * @return 不可变断言集合
     */
    DataScopeAssignments resolve(UUID userId);
}
