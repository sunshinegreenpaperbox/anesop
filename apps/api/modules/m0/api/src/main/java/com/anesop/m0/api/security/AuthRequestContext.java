package com.anesop.m0.api.security;

/**
 * 认证请求的审计上下文：来源 IP、User-Agent 与链路追踪 ID。
 *
 * <p>由 Web 适配层从 {@code HttpServletRequest} 提取后显式传入认证服务，
 * 使 M0 实现层不依赖 Servlet API；审计落库时写入 {@code sys_audit_log}。
 * 不得包含口令、令牌或患者标识。
 */
public record AuthRequestContext(String clientIp, String userAgent, String traceId) {

    public static AuthRequestContext empty() {
        return new AuthRequestContext(null, null, null);
    }
}
