package com.anesop.api.security;

import com.anesop.api.error.ErrorResponses;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.api.web.RequestContexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 401 入口：未携带令牌、令牌过期或签名无效时返回契约错误并留痕。
 *
 * <p>审计失败不改变 401 响应（避免安全响应被审计故障放大为 500），但必须记 ERROR 日志。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger LOG = LoggerFactory.getLogger(RestAuthenticationEntryPoint.class);

    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    /** 采样器为内部状态，不注入外部可变 Bean（避免 SpotBugs EI_EXPOSE_REP2）。 */
    private final UnauthorizedAuditSampler auditSampler = new UnauthorizedAuditSampler();

    public RestAuthenticationEntryPoint(AuditWriter auditWriter, ObjectMapper objectMapper) {
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper.copy();
    }

    /** 测试辅助：清空 401 审计采样状态。 */
    public void resetAuditSampling() {
        auditSampler.clear();
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        AuthRequestContext context = RequestContexts.from(request);
        // S-6：同一 IP+路径 1 分钟内最多 1 条，避免未认证请求刷量淹没审计。
        if (auditSampler.shouldRecord(context.clientIp(), request.getRequestURI())) {
            try {
                auditWriter.record("AUTH_UNAUTHORIZED", "API", request.getRequestURI(), false,
                        Map.of("path", request.getRequestURI()), null, null, context);
            } catch (RuntimeException auditFailure) {
                LOG.error("401 审计落库失败：path={}", request.getRequestURI(), auditFailure);
            }
        }
        ErrorResponses.write(response, objectMapper, HttpStatus.UNAUTHORIZED.value(),
                ErrorResponses.of("AUTH_UNAUTHORIZED", "未认证或令牌已失效", context.traceId(), null));
    }
}
