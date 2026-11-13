package com.anesop.m2.api.security;

/**
 * M2 麻醉信息权限点（V10 新增，三段式 {@code <模块>:<资源>:<动作>}）。
 *
 * <p>数据库与 JWT claim 存原始编码；Spring Security 授权表达式使用 {@code PERM_} 前缀。
 * 权限点与端点映射见 docs/modules/m2/README.md，授权基线见 V10 迁移：
 * read/write 均授予 SYS_ADMIN、SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST
 * （术前访视由麻醉医师/麻醉护士执行，系统管理员用于运维排障）。
 */
public final class M2Permissions {

    /** 术前访视只读（按申请查询列表、访视详情）。 */
    public static final String PREOP_READ = "m2:preop:read";

    /** 术前访视维护（创建/更新草稿、最终化）。 */
    public static final String PREOP_WRITE = "m2:preop:write";

    /** Spring Security 授权表达式中的术前访视只读权限：{@code PERM_m2:preop:read}。 */
    public static final String AUTHORITY_PREOP_READ = "PERM_" + PREOP_READ;

    /** Spring Security 授权表达式中的术前访视维护权限：{@code PERM_m2:preop:write}。 */
    public static final String AUTHORITY_PREOP_WRITE = "PERM_" + PREOP_WRITE;

    private M2Permissions() {
    }
}
