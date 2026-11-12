package com.anesop.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路追踪过滤器：生成 traceId，写入 MDC、请求属性与响应头 {@code X-Trace-Id}。
 *
 * <p>审计与错误响应都用同一个 traceId 关联，便于按链路排障。traceId 由服务端生成，
 * 不直接回显入站请求头（避免响应头注入；跨服务透传在 M1 由网关按白名单实现）。
 * 过滤器位于安全链之前，保证未认证请求也有 traceId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "");

        request.setAttribute(RequestContexts.TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(RequestContexts.TRACE_ID_HEADER, traceId);
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
