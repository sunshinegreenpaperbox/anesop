package com.anesop.m1.api.security;

/**
 * M1 手术排程权限点（V7 新增，三段式 {@code <模块>:<资源>:<动作>}）。
 *
 * <p>数据库与 JWT claim 存原始编码；Spring Security 授权表达式使用 {@code PERM_} 前缀。
 * 权限点与端点映射见 docs/modules/m1/README.md，授权基线见 V7 迁移。
 */
public final class M1Permissions {

    /** 手术申请只读（列表/详情/患者摘要查询）。 */
    public static final String APPLICATION_READ = "m1:application:read";

    /** 手术申请维护（手工创建/模拟 HIS 导入/取消）。 */
    public static final String APPLICATION_WRITE = "m1:application:write";

    /** 患者摘要只读（Q-3 最小权限：SYS_ADMIN/SYS_SURGEON/SYS_OR_MANAGER）。 */
    public static final String PATIENT_READ = "m1:patient:read";

    /** Spring Security 授权表达式中的申请只读权限：{@code PERM_m1:application:read}。 */
    public static final String AUTHORITY_APPLICATION_READ = "PERM_" + APPLICATION_READ;

    /** Spring Security 授权表达式中的申请维护权限：{@code PERM_m1:application:write}。 */
    public static final String AUTHORITY_APPLICATION_WRITE = "PERM_" + APPLICATION_WRITE;

    /** Spring Security 授权表达式中的患者摘要只读权限：{@code PERM_m1:patient:read}。 */
    public static final String AUTHORITY_PATIENT_READ = "PERM_" + PATIENT_READ;

    private M1Permissions() {
    }
}
