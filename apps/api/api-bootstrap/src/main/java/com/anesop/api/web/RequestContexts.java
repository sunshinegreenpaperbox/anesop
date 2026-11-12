package com.anesop.api.web;

import com.anesop.m0.api.security.AuthRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;

/**
 * 从 Servlet 请求提取审计上下文（IP / User-Agent / traceId）。
 *
 * <p>只取网络元数据，不读取请求体；{@code X-Forwarded-For} 取首段（院内 Nginx 追加），
 * traceId 由 {@link TraceIdFilter} 写入请求属性与 MDC。
 */
public final class RequestContexts {

    /** 请求属性名：traceId（与响应头 X-Trace-Id、MDC traceId 一致）。 */
    public static final String TRACE_ID_ATTRIBUTE = "anesop.traceId";

    /** 响应/请求头：链路追踪 ID。 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private RequestContexts() {
    }

    public static AuthRequestContext from(HttpServletRequest request) {
        return new AuthRequestContext(clientIp(request), request.getHeader(HttpHeaders.USER_AGENT), traceId(request));
    }

    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    public static String traceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(TRACE_ID_ATTRIBUTE);
        if (attribute instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        String fromMdc = MDC.get("traceId");
        return fromMdc == null || fromMdc.isBlank() ? "-" : fromMdc;
    }
}
