package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.drug.model.PharmacyAdapterStatus;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.adapter.PharmacyAdapterService;
import com.anesop.m4.api.security.M4Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 药房适配器状态接口（契约 drug.yaml 0.1.0，M4-M15-01，EXT-02 SIMULATED）。
 *
 * <p>授权 {@code m4:drug:read}；每次调用执行一次无外部 I/O 的模拟同步探测并留痕，失败时
 * 返回 available=false / degraded=true（本地字典继续可用）。不返回外部凭据与处方明细。
 */
@RestController
@RequestMapping("/v1/drugs/pharmacy-adapter")
@Tag(name = "药房适配器", description = "药房系统适配器（EXT-02，SIMULATED）状态与降级标记")
public class PharmacyAdapterController {

    private final PharmacyAdapterService adapterService;

    public PharmacyAdapterController(PharmacyAdapterService adapterService) {
        this.adapterService = adapterService;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_DRUG_READ + "')")
    @Operation(operationId = "getPharmacyAdapterStatus", summary = "查询药房适配器状态（EXT-02）")
    public PharmacyAdapterStatus status(@AuthenticationPrincipal Jwt jwt,
            AuthRequestContext context) {
        return adapterService.status(AuthPrincipals.from(jwt), context);
    }
}
