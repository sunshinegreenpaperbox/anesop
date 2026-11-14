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
 * 报表域（V24，data-only）新增 {@code m4:report:read|export}：授予 SYS_ADMIN/SYS_OR_MANAGER/
 * SYS_QC_MANAGER（管理口径，D-21）；临床个人工作量视图待绩效方案确认后另开。
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

    /** 耗材目录与批次库存只读（V23；目录全院可读，库存/流水按科室数据范围过滤）。 */
    public static final String MATERIAL_READ = "m4:material:read";

    /** 耗材目录维护与普通出入库（V23；高值耗材裸数量出入库被服务层拒绝）。 */
    public static final String MATERIAL_WRITE = "m4:material:write";

    /** UDI 一物一码登记、使用与追溯（V23；重复登记/重复使用 409）。 */
    public static final String UDI_MANAGE = "m4:udi:manage";

    /** Spring Security 授权表达式中的耗材只读权限：{@code PERM_m4:material:read}。 */
    public static final String AUTHORITY_MATERIAL_READ = "PERM_" + MATERIAL_READ;

    /** Spring Security 授权表达式中的耗材维护权限：{@code PERM_m4:material:write}。 */
    public static final String AUTHORITY_MATERIAL_WRITE = "PERM_" + MATERIAL_WRITE;

    /** Spring Security 授权表达式中的 UDI 管理权限：{@code PERM_m4:udi:manage}。 */
    public static final String AUTHORITY_UDI_MANAGE = "PERM_" + UDI_MANAGE;

    /** 工作量统计与多维报表只读（V24，data-only；按数据范围裁剪）。 */
    public static final String REPORT_READ = "m4:report:read";

    /** 报表 CSV 导出（V24，data-only；导出另写审计 REPORT_EXPORT）。 */
    public static final String REPORT_EXPORT = "m4:report:export";

    /** Spring Security 授权表达式中的报表只读权限：{@code PERM_m4:report:read}。 */
    public static final String AUTHORITY_REPORT_READ = "PERM_" + REPORT_READ;

    /** Spring Security 授权表达式中的报表导出权限：{@code PERM_m4:report:export}。 */
    public static final String AUTHORITY_REPORT_EXPORT = "PERM_" + REPORT_EXPORT;

    /** 质控指标/不良事件/病历抽查只读（V25；按科室或全院数据范围裁剪）。 */
    public static final String QC_READ = "m4:qc:read";

    /** 质控登记与提交（不良事件登记、RCA 提交、病历抽查、整改提交；V25）。 */
    public static final String QC_WRITE = "m4:qc:write";

    /** 质控复核与闭环（RCA 复核/闭环、整改闭环；V25；须独立复核人）。 */
    public static final String QC_REVIEW = "m4:qc:review";

    /** 质控上报导出（V25；导出另写审计 QC_EXPORT，不含 PHI）。 */
    public static final String QC_EXPORT = "m4:qc:export";

    /** Spring Security 授权表达式中的质控只读权限：{@code PERM_m4:qc:read}。 */
    public static final String AUTHORITY_QC_READ = "PERM_" + QC_READ;

    /** Spring Security 授权表达式中的质控登记权限：{@code PERM_m4:qc:write}。 */
    public static final String AUTHORITY_QC_WRITE = "PERM_" + QC_WRITE;

    /** Spring Security 授权表达式中的质控复核权限：{@code PERM_m4:qc:review}。 */
    public static final String AUTHORITY_QC_REVIEW = "PERM_" + QC_REVIEW;

    /** Spring Security 授权表达式中的质控导出权限：{@code PERM_m4:qc:export}。 */
    public static final String AUTHORITY_QC_EXPORT = "PERM_" + QC_EXPORT;

    private M4Permissions() {
    }
}
