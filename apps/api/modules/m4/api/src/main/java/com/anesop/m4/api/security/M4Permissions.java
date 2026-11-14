package com.anesop.m4.api.security;

/**
 * M4 药品域权限点（V20 新增，三段式 {@code <模块>:<资源>:<动作>}）。
 *
 * <p>数据库与 JWT claim 存原始编码；Spring Security 授权表达式使用 {@code PERM_} 前缀。
 * 人员域（V21/V22）新增 {@code m4:staff:read|write|authorize}、
 * {@code m4:schedule:manage}、{@code m4:training:manage}：SYS_ADMIN 四项 + 审批；
 * SYS_AUTH_MANAGER（DEPT）承载档案/培训/审批；SYS_ANESTHESIOLOGIST 自 V22 起只读；
 * 排班管理仍归 SYS_OR_MANAGER。
 * 授权基线见 V20：{@code m4:drug:read} 授予 SYS_ADMIN/SYS_PHARMACIST/SYS_ANESTHESIOLOGIST
 * （药品目录全院可读，麻醉医师用药录入需要读）；{@code m4:drug:write} 仅授予 SYS_ADMIN 与
 * SYS_PHARMACIST；{@code m4:narcotic:manage} 授予 SYS_ADMIN/SYS_PHARMACIST/
 * SYS_ANESTHESIOLOGIST（五专登记由临床与药房共同完成，麻醉护士暂不授予维护权）。
 */
public final class M4Permissions {

    /** 药品目录只读（列表、检索、详情、药房适配器状态）。 */
    public static final String DRUG_READ = "m4:drug:read";

    /** 药品目录维护（创建/更新；编码不可改）。 */
    public static final String DRUG_WRITE = "m4:drug:write";

    /** 麻精五专维护（专册登记、专柜交接、基数、日结/班结）。 */
    public static final String NARCOTIC_MANAGE = "m4:narcotic:manage";

    /** Spring Security 授权表达式中的药品只读权限：{@code PERM_m4:drug:read}。 */
    public static final String AUTHORITY_DRUG_READ = "PERM_" + DRUG_READ;

    /** Spring Security 授权表达式中的药品维护权限：{@code PERM_m4:drug:write}。 */
    public static final String AUTHORITY_DRUG_WRITE = "PERM_" + DRUG_WRITE;

    /** Spring Security 授权表达式中的五专维护权限：{@code PERM_m4:narcotic:manage}。 */
    public static final String AUTHORITY_NARCOTIC_MANAGE = "PERM_" + NARCOTIC_MANAGE;

    /** 人员档案只读（列表、详情、授权历史、科室选项、排班/培训查询、授权校验）。 */
    public static final String STAFF_READ = "m4:staff:read";

    /** 人员档案与分级授权维护（创建/更新档案、授予/撤销授权）。 */
    public static final String STAFF_WRITE = "m4:staff:write";

    /** 人员排班管理（新建/更新/取消排班）。 */
    public static final String SCHEDULE_MANAGE = "m4:schedule:manage";

    /** 培训记录管理（登记培训记录）。 */
    public static final String TRAINING_MANAGE = "m4:training:manage";

    /** 分级授权审批（授予/撤销的审批人资格；V22 新增）。 */
    public static final String STAFF_AUTHORIZE = "m4:staff:authorize";

    /** Spring Security 授权表达式中的档案只读权限：{@code PERM_m4:staff:read}。 */
    public static final String AUTHORITY_STAFF_READ = "PERM_" + STAFF_READ;

    /** Spring Security 授权表达式中的档案/授权维护权限：{@code PERM_m4:staff:write}。 */
    public static final String AUTHORITY_STAFF_WRITE = "PERM_" + STAFF_WRITE;

    /** Spring Security 授权表达式中的排班管理权限：{@code PERM_m4:schedule:manage}。 */
    public static final String AUTHORITY_SCHEDULE_MANAGE = "PERM_" + SCHEDULE_MANAGE;

    /** Spring Security 授权表达式中的培训管理权限：{@code PERM_m4:training:manage}。 */
    public static final String AUTHORITY_TRAINING_MANAGE = "PERM_" + TRAINING_MANAGE;

    /** Spring Security 授权表达式中的授权审批权限：{@code PERM_m4:staff:authorize}。 */
    public static final String AUTHORITY_STAFF_AUTHORIZE = "PERM_" + STAFF_AUTHORIZE;

    private M4Permissions() {
    }
}
