package com.anesop.m5.api.security;

/**
 * M5 看板域权限点（V26 data-only，三段式 {@code <模块>:<资源>:<动作>}）。
 *
 * <p>数据库与 JWT claim 存原始编码；Spring Security 授权表达式使用 {@code PERM_} 前缀。
 * V26 仅新增一个只读权限点 {@code m5:dashboard:read}，最小授予 SYS_ADMIN /
 * SYS_OR_MANAGER / SYS_ANESTHESIOLOGIST / SYS_QC_MANAGER；驾驶舱只读，不新增写权限。
 */
public final class M5Permissions {

    /** 实时态势与 KPI 看板只读（GET /v1/dashboard/*，按数据范围裁剪）。 */
    public static final String DASHBOARD_READ = "m5:dashboard:read";

    /** Spring Security 授权表达式中的看板只读权限：{@code PERM_m5:dashboard:read}。 */
    public static final String AUTHORITY_DASHBOARD_READ = "PERM_" + DASHBOARD_READ;

    private M5Permissions() {
    }
}
