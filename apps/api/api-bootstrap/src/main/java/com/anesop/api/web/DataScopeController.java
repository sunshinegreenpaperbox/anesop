package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.m0.api.security.DataScopeQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据范围查询示例接口（契约 auth.yaml 0.4.0，M2-M0-01）。
 *
 * <p>只做“按数据范围过滤”的演示：查询服务先解析调用者范围（fail-closed，空范围即拒绝），
 * 再按 orgId/roomId 过滤；越权抛 {@code AccessDeniedException}，由异常处理器返回 403 并写
 * {@code ACCESS_DENIED} 审计（方案书 §3.9.1）。返回用户标识，不含姓名/工号/手机号。
 */
@RestController
@RequestMapping("/v1/auth/data-scope")
@Tag(name = "认证与令牌", description = "登录、刷新、登出与当前用户权限查询")
public class DataScopeController {

    private final DataScopeQueryService dataScopeQueryService;

    public DataScopeController(DataScopeQueryService dataScopeQueryService) {
        this.dataScopeQueryService = dataScopeQueryService;
    }

    @GetMapping("/users")
    @Operation(operationId = "listUsersInDataScope", summary = "按数据范围查询可见用户（示例接口）")
    public List<String> users(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "orgId", required = false) UUID orgId,
            @RequestParam(name = "roomId", required = false) UUID roomId) {
        return dataScopeQueryService.visibleUserIds(AuthPrincipals.from(jwt).userId(), orgId, roomId);
    }
}
