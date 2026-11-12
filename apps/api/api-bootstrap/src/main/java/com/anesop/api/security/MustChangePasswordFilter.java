package com.anesop.api.security;

import com.anesop.api.error.ErrorResponses;
import com.anesop.api.web.RequestContexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 强制改密守卫（S-3）：{@code must_change_password=true} 的账号只允许访问
 * {@code /v1/auth/me}、{@code /v1/auth/logout}、{@code /v1/auth/password}，其余接口 403。
 *
 * <p>标记来自登录时签发的 JWT claim {@code mustChangePassword}；改密成功后需重新登录
 * 取得新令牌（旧令牌在有效期内仍受限，避免绕过）。本过滤器位于 Bearer 认证之后，
 * 未认证请求由 401 入口处理，不受影响。
 */
@Component
public class MustChangePasswordFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/v1/auth/me", "/v1/auth/logout", "/v1/auth/password");

    private final ObjectMapper objectMapper;

    public MustChangePasswordFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || ALLOWED_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Jwt jwt
                && Boolean.TRUE.equals(jwt.getClaim("mustChangePassword"))) {
            ErrorResponses.write(response, objectMapper, HttpStatus.FORBIDDEN.value(),
                    ErrorResponses.of("AUTH_FORBIDDEN", "首次登录必须先修改口令",
                            RequestContexts.traceId(request), null));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
