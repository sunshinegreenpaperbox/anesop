package com.anesop.m2.api.security;

/**
 * M2 麻醉信息权限点（V10 新增，三段式 {@code <模块>:<资源>:<动作>}）。
 *
 * <p>数据库与 JWT claim 存原始编码；Spring Security 授权表达式使用 {@code PERM_} 前缀。
 * 权限点与端点映射见 docs/modules/m2/README.md，授权基线见 V10（术前访视）、V11
 * （知情同意/三方核查）与 V13（麻醉记录单）迁移：read/write 均授予 SYS_ADMIN、
 * SYS_ANESTHESIOLOGIST、SYS_NURSE_ANESTHETIST（访视、签署、核查与记录由麻醉医师/
 * 麻醉护士执行，系统管理员用于运维与排障）。
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

    /** 设备登记与体征只读（设备台账、体征窗口查询、采集健康摘要）。 */
    public static final String DEVICE_READ = "m2:device:read";

    /** 设备登记与术间绑定维护。 */
    public static final String DEVICE_WRITE = "m2:device:write";

    /** Spring Security 授权表达式中的设备只读权限：{@code PERM_m2:device:read}。 */
    public static final String AUTHORITY_DEVICE_READ = "PERM_" + DEVICE_READ;

    /** Spring Security 授权表达式中的设备维护权限：{@code PERM_m2:device:write}。 */
    public static final String AUTHORITY_DEVICE_WRITE = "PERM_" + DEVICE_WRITE;

    /** 麻醉记录单只读（按台次查询、记录单详情、时间轴/用药/出入量列表、出入量汇总）。 */
    public static final String RECORD_READ = "m2:record:read";

    /** 麻醉记录单书写（创建记录单、保存记录单、追加事件、用药、输液/出入量）。 */
    public static final String RECORD_WRITE = "m2:record:write";

    /** Spring Security 授权表达式中的麻醉记录单只读权限：{@code PERM_m2:record:read}。 */
    public static final String AUTHORITY_RECORD_READ = "PERM_" + RECORD_READ;

    /** Spring Security 授权表达式中的麻醉记录单书写权限：{@code PERM_m2:record:write}。 */
    public static final String AUTHORITY_RECORD_WRITE = "PERM_" + RECORD_WRITE;

    /** 麻醉记录单锁单（V17 授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST）。 */
    public static final String RECORD_LOCK = "m2:record:lock";

    /** 麻醉记录单修订（锁单后修正唯一入口；V17 授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST）。 */
    public static final String RECORD_REVISE = "m2:record:revise";

    /** Spring Security 授权表达式中的锁单权限：{@code PERM_m2:record:lock}。 */
    public static final String AUTHORITY_RECORD_LOCK = "PERM_" + RECORD_LOCK;

    /** Spring Security 授权表达式中的修订权限：{@code PERM_m2:record:revise}。 */
    public static final String AUTHORITY_RECORD_REVISE = "PERM_" + RECORD_REVISE;

    /** 术中预警规则与告警只读（规则列表、记录单告警列表）。 */
    public static final String ALERT_READ = "m2:alert:read";

    /** 告警确认/忽略（临床处置留痕）。 */
    public static final String ALERT_ACK = "m2:alert:ack";

    /** 预警规则配置（仅 SYS_ADMIN；阈值须临床顾问确认后录入）。 */
    public static final String ALERT_CONFIGURE = "m2:alert:configure";

    /** 抢救记录书写（append-only）。 */
    public static final String RESCUE_WRITE = "m2:rescue:write";

    /** Spring Security 授权表达式中的预警只读权限：{@code PERM_m2:alert:read}。 */
    public static final String AUTHORITY_ALERT_READ = "PERM_" + ALERT_READ;

    /** Spring Security 授权表达式中的告警处置权限：{@code PERM_m2:alert:ack}。 */
    public static final String AUTHORITY_ALERT_ACK = "PERM_" + ALERT_ACK;

    /** Spring Security 授权表达式中的规则配置权限：{@code PERM_m2:alert:configure}。 */
    public static final String AUTHORITY_ALERT_CONFIGURE = "PERM_" + ALERT_CONFIGURE;

    /** Spring Security 授权表达式中的抢救记录书写权限：{@code PERM_m2:rescue:write}。 */
    public static final String AUTHORITY_RESCUE_WRITE = "PERM_" + RESCUE_WRITE;

    private M2Permissions() {
    }
}
