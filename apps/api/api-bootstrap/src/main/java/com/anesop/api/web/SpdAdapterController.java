package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.material.model.SpdAdapterStatus;
import com.anesop.contracts.material.model.SpdSyncResult;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.material.SpdAdapterService;
import com.anesop.m4.api.security.M4Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPD 适配器接口（契约 material.yaml 0.1.0，M4-M14-01，EXT-03 SIMULATED）。
 *
 * <p>授权：状态查询 {@code m4:material:read}，触发同步 {@code m4:material:write}；
 * 同步失败返回 200 + success=false/degraded=true（本地库存继续可用），并写 SPD_SYNC 审计。
 * 不返回外部凭据、供应商明细与患者信息。
 */
@RestController
@RequestMapping("/v1/materials/spd-adapter")
@Tag(name = "SPD 适配器", description = "SPD 供应链适配器（EXT-03，SIMULATED）状态与模拟同步")
public class SpdAdapterController {

    private final SpdAdapterService adapterService;

    public SpdAdapterController(SpdAdapterService adapterService) {
        this.adapterService = adapterService;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_READ + "')")
    @Operation(operationId = "getSpdAdapterStatus", summary = "查询 SPD 适配器状态（EXT-03）")
    public SpdAdapterStatus status(@AuthenticationPrincipal Jwt jwt, AuthRequestContext context) {
        return adapterService.status(AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_WRITE + "')")
    @Operation(operationId = "syncSpdAdapter", summary = "触发 SPD 模拟同步（EXT-03）")
    public SpdSyncResult sync(@AuthenticationPrincipal Jwt jwt, AuthRequestContext context) {
        return adapterService.sync(AuthPrincipals.from(jwt), context);
    }
}
