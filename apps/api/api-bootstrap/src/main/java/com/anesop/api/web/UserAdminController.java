package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.auth.model.PasswordResetResponse;
import com.anesop.contracts.auth.model.UserCreateRequest;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.contracts.auth.model.UserSummaryPage;
import com.anesop.contracts.auth.model.UserUpdateRequest;
import com.anesop.m0.api.security.AuthPermissions;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（契约 auth.yaml 0.5.0，M2-M0-02）。
 *
 * <p>授权规则：
 * <ul>
 *   <li>{@code GET /v1/auth/users}、{@code GET /v1/auth/users/{userId}}：
 *       需要 {@code PERM_m0:user:read}（V6 起仅 SYS_ADMIN）；</li>
 *   <li>{@code POST /v1/auth/users}、{@code PUT /v1/auth/users/{userId}}、
 *       {@code POST /v1/auth/users/{userId}/password-reset}：需要 {@code PERM_m0:user:write}；</li>
 *   <li>缺权限返回 403，由异常处理器写 {@code ACCESS_DENIED} 审计；</li>
 *   <li>响应只含 {@code phoneMasked}，永不返回手机号明文、{@code phone_enc}、
 *       {@code phone_hash} 或口令哈希；关键操作审计在 m0-impl 的服务方法上（@Audited）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/auth/users")
@Tag(name = "认证与令牌", description = "登录、刷新、登出与当前用户权限查询")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_USER_READ + "')")
    @Operation(operationId = "listUsers", summary = "查询用户列表（管理员）")
    public UserSummaryPage list(@RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "keyword", required = false) String keyword) {
        return userAdminService.list(keyword, page, size);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_USER_READ + "')")
    @Operation(operationId = "getUser", summary = "查询用户详情（管理员）")
    public UserSummary get(@PathVariable("userId") UUID userId) {
        return userAdminService.get(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_USER_WRITE + "')")
    @Operation(operationId = "createUser", summary = "创建用户（管理员）")
    public UserSummary create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserCreateRequest request,
            AuthRequestContext context) {
        return userAdminService.create(AuthPrincipals.from(jwt), request, context);
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_USER_WRITE + "')")
    @Operation(operationId = "updateUser", summary = "更新用户（管理员）")
    public UserSummary update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody UserUpdateRequest request,
            AuthRequestContext context) {
        return userAdminService.update(AuthPrincipals.from(jwt), userId, request, context);
    }

    @PostMapping("/{userId}/password-reset")
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_USER_WRITE + "')")
    @Operation(operationId = "resetUserPassword", summary = "重置用户口令（管理员）")
    public PasswordResetResponse resetPassword(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("userId") UUID userId,
            AuthRequestContext context) {
        return userAdminService.resetPassword(AuthPrincipals.from(jwt), userId, context);
    }
}
