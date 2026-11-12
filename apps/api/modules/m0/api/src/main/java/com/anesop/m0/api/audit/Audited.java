package com.anesop.m0.api.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计留痕标记：被注解的方法由 m0-impl 的 {@link AuditAspect} 在成功/失败时写
 * {@code m0_platform.sys_audit_log}（action、actor、ip、result、detail）。
 *
 * <p>本注解位于 m0-api，供各模块（含 api-bootstrap Web 适配层）在不依赖 impl 的前提下
 * 标注安全相关方法；{@code detail} 由切面按白名单构造，不得记录口令、令牌明文或患者标识。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** 审计动作，如 LOGIN、TOKEN_REFRESH、LOGOUT（对应 sys_audit_log.action）。 */
    String action();

    /** 资源类型，如 USER、SESSION（对应 sys_audit_log.resource_type）。 */
    String resourceType();
}
