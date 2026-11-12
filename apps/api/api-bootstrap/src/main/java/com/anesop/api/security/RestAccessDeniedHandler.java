package com.anesop.api.security;

import com.anesop.api.error.ErrorResponses;
import com.anesop.api.web.RequestContexts;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.audit.AuditWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 403 处理器：已认证但权限/数据范围不足时返回契约错误，并把越权事实写入审计。
 *
 * <p>越权是安全事件（方案书 §3.9.1「越权访问记审计并告警」）：记录 actor、IP、
 * 请求路径与结果 FAIL，不含请求体与令牌。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger LOG = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(AuditWriter auditWriter, ObjectMapper objectMapper) {
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper.copy();
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        AuthRequestContext context = RequestContexts.from(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UUID actorId = null;
        String actorName = null;
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            actorId = parseUuid(jwt.getSubject());
            actorName = jwt.getClaimAsString("username");
        }
        try {
            auditWriter.record("ACCESS_DENIED", "API", request.getRequestURI(), false,
                    Map.of("path", request.getRequestURI(), "method", request.getMethod()),
                    actorId, actorName, context);
        } catch (RuntimeException auditFailure) {
            LOG.error("403 越权审计落库失败：path={}", request.getRequestURI(), auditFailure);
        }
        ErrorResponses.write(response, objectMapper, HttpStatus.FORBIDDEN.value(),
                ErrorResponses.of("AUTH_FORBIDDEN", "无权访问该资源或数据范围不足", context.traceId(), null));
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
