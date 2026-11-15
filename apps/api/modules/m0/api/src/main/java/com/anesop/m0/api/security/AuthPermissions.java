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

    /**
     * M0 用户管理只读权限点（V6 新增，用户管理端点 {@code GET /v1/auth/users}、
     * {@code GET /v1/auth/users/{userId}} 使用），仅授予 SYS_ADMIN。
     *
     * <p>用户数据含手机号密文与账号状态，属核心域权限：数据范围不替代功能权限，
     * 缺失该权限点一律 403 并写 ACCESS_DENIED 审计。
     */
    public static final String M0_USER_READ = "m0:user:read";

    /** M0 用户管理维护权限点（V6 新增，创建/更新/重置口令），仅授予 SYS_ADMIN。 */
    public static final String M0_USER_WRITE = "m0:user:write";

    /**
     * M0 字典查询权限点（V27，M8-M0-04）：{@code GET /v1/dicts}、{@code GET /v1/dicts/{type}}。
     *
     * <p>字典是全院共享参考数据，V27 授予全部 6 个平台基线角色；不按数据范围裁剪。
     */
    public static final String M0_DICT_READ = "m0:dict:read";

    /**
     * M0 字典维护权限点（V27，M8-M0-04）：新增/更新/停用字典项。
     *
     * <p>V27 **仅授予 {@code SYS_ADMIN}**；服务层另有 {@code SYS_ADMIN} 角色纵深校验。
     */
    public static final String M0_DICT_WRITE = "m0:dict:write";

    /** M0 消息中心权限点（V27，M8-M0-04）：仅本人消息可见/可标记已读。 */
    public static final String M0_MESSAGE_READ = "m0:message:read";

    /** M0 文件下载/元数据权限点（V27，M8-M0-04）：可见性另按业务类型收紧。 */
    public static final String M0_FILE_READ = "m0:file:read";

    /** M0 文件上传权限点（V27，M8-M0-04）：非 PHI 文件，类型/大小白名单。 */
    public static final String M0_FILE_WRITE = "m0:file:write";

    /** Spring Security 授权表达式中的 M0 字典查询权限：{@code PERM_m0:dict:read}。 */
    public static final String AUTHORITY_M0_DICT_READ = PERMISSION_PREFIX + M0_DICT_READ;

    /** Spring Security 授权表达式中的 M0 字典维护权限：{@code PERM_m0:dict:write}。 */
    public static final String AUTHORITY_M0_DICT_WRITE = PERMISSION_PREFIX + M0_DICT_WRITE;

    /** Spring Security 授权表达式中的 M0 消息中心权限：{@code PERM_m0:message:read}。 */
    public static final String AUTHORITY_M0_MESSAGE_READ = PERMISSION_PREFIX + M0_MESSAGE_READ;

    /** Spring Security 授权表达式中的 M0 文件下载权限：{@code PERM_m0:file:read}。 */
    public static final String AUTHORITY_M0_FILE_READ = PERMISSION_PREFIX + M0_FILE_READ;

    /** Spring Security 授权表达式中的 M0 文件上传权限：{@code PERM_m0:file:write}。 */
    public static final String AUTHORITY_M0_FILE_WRITE = PERMISSION_PREFIX + M0_FILE_WRITE;

    /** 平台系统管理员角色码；数据范围为 ALL，是唯一预置的高权限角色。 */
    public static final String ROLE_SYS_ADMIN = "SYS_ADMIN";

    /** Spring Security 授权表达式中的 M0 只读权限。 */
    public static final String AUTHORITY_M0_READ = PERMISSION_PREFIX + M0_READ;

    /** Spring Security 授权表达式中的 M0 组织只读权限。 */
    public static final String AUTHORITY_M0_ORG_READ = PERMISSION_PREFIX + M0_ORG_READ;

    /** Spring Security 授权表达式中的 M0 用户管理只读权限：{@code PERM_m0:user:read}。 */
    public static final String AUTHORITY_M0_USER_READ = PERMISSION_PREFIX + M0_USER_READ;

    /** Spring Security 授权表达式中的 M0 用户管理维护权限：{@code PERM_m0:user:write}。 */
    public static final String AUTHORITY_M0_USER_WRITE = PERMISSION_PREFIX + M0_USER_WRITE;

    private AuthPermissions() {
    }

    public static String roleAuthority(String roleCode) {
        return ROLE_PREFIX + roleCode;
    }

    public static String permissionAuthority(String permissionCode) {
        return PERMISSION_PREFIX + permissionCode;
    }
}
