package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.auth.model.ChangePasswordRequest;
import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.LoginRequest;
import com.anesop.contracts.auth.model.RefreshRequest;
import com.anesop.contracts.auth.model.SessionPage;
import com.anesop.contracts.auth.model.TokenResponse;
import com.anesop.m0.api.security.AuthPermissions;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与令牌接口（契约 auth.yaml 0.3.0，operationId 与契约一致）。
 *
 * <p>授权规则：login/refresh 由安全配置 permitAll；me/logout/password 仅需认证；
 * sessions 需 {@code PERM_m0:auth:read}（越权返回 403 并由异常处理器写审计）。
 * 审计切面标注在 AuthService 实现方法上（能拿到 AuthPrincipal，登录/刷新/登出/
 * 改密的 actor 与 resource 都可落库，不含口令/令牌明文）。
 */
@RestController
@RequestMapping("/v1/auth")
@Tag(name = "认证与令牌", description = "登录、刷新、登出与当前用户权限查询")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "账号登录")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, AuthRequestContext context) {
        return authService.login(request, context);
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refreshToken", summary = "刷新访问令牌")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request, AuthRequestContext context) {
        return authService.refresh(request, context);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "logout", summary = "登出当前会话")
    public void logout(@AuthenticationPrincipal Jwt jwt, AuthRequestContext context) {
        authService.logout(AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "changePassword", summary = "修改当前用户口令")
    public void changePassword(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request,
            AuthRequestContext context) {
        authService.changePassword(AuthPrincipals.from(jwt), request, context);
    }

    @GetMapping("/me")
    @Operation(operationId = "getCurrentUser", summary = "查询当前用户与权限")
    public CurrentUser me(@AuthenticationPrincipal Jwt jwt) {
        return authService.currentUser(AuthPrincipals.from(jwt).userId());
    }

    @GetMapping("/sessions")
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_READ + "')")
    @Operation(operationId = "listSessions", summary = "查询当前用户会话")
    public SessionPage sessions(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return authService.sessions(AuthPrincipals.from(jwt).userId(), page, size);
    }
}
