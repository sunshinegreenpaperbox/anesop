/**
 * M0 审计对外接口：{@link Audited} 注解与 {@link AuditWriter} 落库契约（S0-07）。
 *
 * <p>跨模块只依赖本包所在 artifact（com.anesop:m0-api）；AOP 切面与 JDBC 实现留在 m0-impl。
 */
package com.anesop.m0.api.audit;
