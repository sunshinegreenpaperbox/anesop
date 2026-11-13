package com.anesop.m2.api.security;

/**
 * M2 麻醉信息权限点（V10 新增，三段式 {@code <模块>:<资源>:<动作>}）。
 *
 * <p>数据库与 JWT claim 存原始编码；Spring Security 授权表达式使用 {@code PERM_} 前缀。
 * 权限点与端点映射见 docs/modules/m2/README.md，授权基线见 V10（术前访视）与 V11
 * （知情同意/三方核查）迁移：read/write 均授予 SYS_ADMIN、SYS_ANESTHESIOLOGIST、
 * SYS_NURSE_ANESTHETIST（访视、签署与核查由麻醉医师/麻醉护士执行，系统管理员用于运维排障）。
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

    /** 知情同意只读（模板列表、同意记录列表/详情）。 */
    public static final String CONSENT_READ = "m2:consent:read";

    /** 知情同意签署/拒绝/撤销。 */
    public static final String CONSENT_WRITE = "m2:consent:write";

    /** 三方核查只读（按申请查询核查版本与完成标记）。 */
    public static final String THREEWAY_READ = "m2:threeway:read";

    /** 三方核查录入（新增核查版本）。 */
    public static final String THREEWAY_WRITE = "m2:threeway:write";

    /** Spring Security 授权表达式中的知情同意只读权限：{@code PERM_m2:consent:read}。 */
    public static final String AUTHORITY_CONSENT_READ = "PERM_" + CONSENT_READ;

    /** Spring Security 授权表达式中的知情同意维护权限：{@code PERM_m2:consent:write}。 */
    public static final String AUTHORITY_CONSENT_WRITE = "PERM_" + CONSENT_WRITE;

    /** Spring Security 授权表达式中的三方核查只读权限：{@code PERM_m2:threeway:read}。 */
    public static final String AUTHORITY_THREEWAY_READ = "PERM_" + THREEWAY_READ;

    /** Spring Security 授权表达式中的三方核查录入权限：{@code PERM_m2:threeway:write}。 */
    public static final String AUTHORITY_THREEWAY_WRITE = "PERM_" + THREEWAY_WRITE;

    private M2Permissions() {
    }
}
