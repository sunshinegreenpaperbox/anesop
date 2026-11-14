package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.staff.model.AuthorizationCheckRequest;
import com.anesop.contracts.staff.model.AuthorizationCheckResult;
import com.anesop.contracts.staff.model.StaffAuthorizationHistory;
import com.anesop.contracts.staff.model.UpdateStaffAuthorizationRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.security.M4Permissions;
import com.anesop.m4.api.staff.StaffAuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分级授权接口（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>授权历史 append-only（每次 GRANT/REVOKE 写新版本）；授权校验是 M1 排程与 M5 术中的
 * 集成点：返回 allowed + 全部原因，规则判定不替代临床决策（AGENTS H-06）。
 */
@RestController
@RequestMapping("/v1/staff")
@Tag(name = "分级授权", description = "R1–R5 分级授权（术式/年龄段/ASA/有效期）、append-only 历史与授权校验")
public class StaffAuthorizationController {

    private final StaffAuthorizationService authorizationService;

    public StaffAuthorizationController(StaffAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @GetMapping("/{staffId}/authorizations")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_READ + "')")
    @Operation(operationId = "getStaffAuthorizations", summary = "查询人员分级授权（当前 + 历史）")
    public StaffAuthorizationHistory getHistory(@PathVariable("staffId") String staffId,
            @AuthenticationPrincipal Jwt jwt) {
        return authorizationService.getHistory(staffId, AuthPrincipals.from(jwt));
    }

    @PutMapping("/{staffId}/authorizations")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_WRITE + "')")
    @Operation(operationId = "updateStaffAuthorizations", summary = "授予/撤销分级授权（append-only 新版本）")
    public StaffAuthorizationHistory update(@PathVariable("staffId") String staffId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateStaffAuthorizationRequest request,
            AuthRequestContext context) {
        return authorizationService.update(staffId, request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/authorization-checks")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_READ + "')")
    @Operation(operationId = "checkStaffAuthorization", summary = "授权校验（排程/术中集成点）")
    public AuthorizationCheckResult check(@Valid @RequestBody AuthorizationCheckRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return authorizationService.check(request, AuthPrincipals.from(jwt));
    }
}
