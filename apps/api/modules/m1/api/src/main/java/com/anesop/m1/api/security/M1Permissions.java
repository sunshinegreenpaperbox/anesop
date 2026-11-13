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

    /** 排程工作台只读（术间列表/日排程/详情/冲突预检；V8 授 SYS_ADMIN/SYS_OR_MANAGER）。 */
    public static final String SCHEDULE_READ = "m1:schedule:read";

    /** 排程工作台维护（创建/调整/取消；V8 仅授 SYS_ADMIN/SYS_OR_MANAGER，最小权限）。 */
    public static final String SCHEDULE_WRITE = "m1:schedule:write";

    /**
     * 停台与急诊加台审批（V9 新增，M2-M2-02）：术中停台、急诊加台/抢占择期台次。
     * 仅授予 SYS_ADMIN、SYS_OR_MANAGER（与排程维护同权，最小权限；后续双人审批再拆分）。
     */
    public static final String SCHEDULE_APPROVE = "m1:schedule:approve";

    /** Spring Security 授权表达式中的申请只读权限：{@code PERM_m1:application:read}。 */
    public static final String AUTHORITY_APPLICATION_READ = "PERM_" + APPLICATION_READ;

    /** Spring Security 授权表达式中的申请维护权限：{@code PERM_m1:application:write}。 */
    public static final String AUTHORITY_APPLICATION_WRITE = "PERM_" + APPLICATION_WRITE;

    /** Spring Security 授权表达式中的患者摘要只读权限：{@code PERM_m1:patient:read}。 */
    public static final String AUTHORITY_PATIENT_READ = "PERM_" + PATIENT_READ;

    /** Spring Security 授权表达式中的排程只读权限：{@code PERM_m1:schedule:read}。 */
    public static final String AUTHORITY_SCHEDULE_READ = "PERM_" + SCHEDULE_READ;

    /** Spring Security 授权表达式中的排程维护权限：{@code PERM_m1:schedule:write}。 */
    public static final String AUTHORITY_SCHEDULE_WRITE = "PERM_" + SCHEDULE_WRITE;

    /** Spring Security 授权表达式中的停台/急诊加台审批权限：{@code PERM_m1:schedule:approve}。 */
    public static final String AUTHORITY_SCHEDULE_APPROVE = "PERM_" + SCHEDULE_APPROVE;

    private M1Permissions() {
    }
}
