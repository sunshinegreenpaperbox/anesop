package com.anesop.m4.api.security;

/**
 * M4 药品域权限点（V20 新增，三段式 {@code <模块>:<资源>:<动作>}）。
 *
 * <p>数据库与 JWT claim 存原始编码；Spring Security 授权表达式使用 {@code PERM_} 前缀。
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

    private M4Permissions() {
    }
}
