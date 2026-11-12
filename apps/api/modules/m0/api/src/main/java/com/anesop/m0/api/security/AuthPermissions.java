package com.anesop.m0.api.security;

/**
 * 角色与权限点的编码约定（方案书 §2.1.2 角色 R1–R11、§3.9.2 RBAC）。
 *
 * <p>数据库与 JWT claim 存原始编码（如 {@code m0:auth:read}、{@code SYS_ADMIN}）；
 * Spring Security 授权表达式使用加前缀的形式（{@code PERM_m0:auth:read}、{@code ROLE_SYS_ADMIN}）。
 * 角色 R1–R11 与角色码的完整映射见 docs/modules/m0/README.md。
 */
public final class AuthPermissions {

    /** 角色权限前缀：{@code ROLE_<code>}。 */
    public static final String ROLE_PREFIX = "ROLE_";

    /** 功能权限前缀：{@code PERM_<code>}。 */
    public static final String PERMISSION_PREFIX = "PERM_";

    /**
     * M0 认证子域只读权限点（示例受保护接口 /v1/auth/sessions 使用）。
     *
     * <p>编码为冒号分隔的三段式 {@code <模块>:<资源>:<动作>}；V2 历史权限点为
     * {@code <模块>:<动作>}（如 auth:login），M1 起新增权限点统一三段式。
     */
    public static final String M0_READ = "m0:auth:read";

    /** M0 认证子域维护权限点（后续 M0 写接口使用，S0-07 先定稿编码）。 */
    public static final String M0_WRITE = "m0:auth:write";

    /**
     * M0 组织只读权限点（V5 新增，数据范围示例接口 {@code GET /v1/auth/data-scope/users} 使用）。
     *
     * <p>数据范围只决定“能看到哪些对象”，不替代功能权限：调用方必须同时具备该权限点，
     * 否则即使数据范围非空也返回 403。
     */
    public static final String M0_ORG_READ = "m0:org:read";

    /** 平台系统管理员角色码；数据范围为 ALL，是唯一预置的高权限角色。 */
    public static final String ROLE_SYS_ADMIN = "SYS_ADMIN";

    /** Spring Security 授权表达式中的 M0 只读权限。 */
    public static final String AUTHORITY_M0_READ = PERMISSION_PREFIX + M0_READ;

    /** Spring Security 授权表达式中的 M0 组织只读权限。 */
    public static final String AUTHORITY_M0_ORG_READ = PERMISSION_PREFIX + M0_ORG_READ;

    private AuthPermissions() {
    }

    public static String roleAuthority(String roleCode) {
        return ROLE_PREFIX + roleCode;
    }

    public static String permissionAuthority(String permissionCode) {
        return PERMISSION_PREFIX + permissionCode;
    }
}
