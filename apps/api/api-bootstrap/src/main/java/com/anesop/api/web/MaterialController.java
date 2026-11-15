package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.material.model.CreateMaterialRequest;
import com.anesop.contracts.material.model.Material;
import com.anesop.contracts.material.model.MaterialList;
import com.anesop.contracts.material.model.LowStockThresholdRequest;
import com.anesop.contracts.material.model.MaterialStatusRequest;
import com.anesop.contracts.material.model.UpdateMaterialRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.material.MaterialAlertService;
import com.anesop.m4.api.material.MaterialCatalogService;
import com.anesop.m4.api.security.M4Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 耗材目录接口（契约 material.yaml 0.1.0，M4-M14-01）。
 *
 * <p>授权：查询需要 {@code m4:material:read}（目录全院可读），维护需要
 * {@code m4:material:write}（V23 授予 SYS_ADMIN/SYS_OR_MANAGER）。编码/名称/UDI-DI 冲突 409，
 * 版本过期 409，耗材不存在 404。返回体只含耗材主数据，不含患者信息。
 *
 * <p>0.2.0（M8-M4-02）新增库存预警阈值维护端点（独立于主数据更新，审计
 * {@code MATERIAL_THRESHOLD_UPDATE}），需科室数据范围 ALL/SITE/DEPT（SELF/ROOM 403）。
 */
@RestController
@RequestMapping("/v1/materials")
@Tag(name = "耗材目录", description = "耗材主数据维护与检索（编码/名称/UDI-DI 唯一、高值/效期标志）")
public class MaterialController {

    private final MaterialCatalogService catalogService;
    private final MaterialAlertService alertService;

    public MaterialController(MaterialCatalogService catalogService,
            MaterialAlertService alertService) {
        this.catalogService = catalogService;
        this.alertService = alertService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_READ + "')")
    @Operation(operationId = "listMaterials", summary = "查询耗材目录")
    public MaterialList list(@RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "highValue", required = false) Boolean highValue,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return catalogService.list(keyword, status, highValue, page, size);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_READ + "')")
    @Operation(operationId = "searchMaterials", summary = "按名称/编码/UDI-DI 检索耗材（扫码联想）")
    public MaterialList search(@RequestParam(name = "q") String q,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return catalogService.search(q, limit);
    }

    @GetMapping("/{materialId}")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_READ + "')")
    @Operation(operationId = "getMaterial", summary = "查询耗材详情")
    public Material get(@PathVariable("materialId") String materialId) {
        return catalogService.get(materialId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_WRITE + "')")
    @Operation(operationId = "createMaterial", summary = "创建耗材")
    public Material create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateMaterialRequest request, AuthRequestContext context) {
        return catalogService.create(request, AuthPrincipals.from(jwt), context);
    }

    @PutMapping("/{materialId}")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_WRITE + "')")
    @Operation(operationId = "updateMaterial", summary = "更新耗材（乐观锁）")
    public Material update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("materialId") String materialId,
            @Valid @RequestBody UpdateMaterialRequest request, AuthRequestContext context) {
        return catalogService.update(materialId, request, AuthPrincipals.from(jwt), context);
    }

    @PutMapping("/{materialId}/low-stock-threshold")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_WRITE + "')")
    @Operation(operationId = "updateMaterialLowStockThreshold",
            summary = "维护库存预警阈值（乐观锁，独立审计 MATERIAL_THRESHOLD_UPDATE）")
    public Material updateLowStockThreshold(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("materialId") String materialId,
            @Valid @RequestBody LowStockThresholdRequest request, AuthRequestContext context) {
        return alertService.updateThreshold(materialId, request, AuthPrincipals.from(jwt), context);
    }

    @PatchMapping("/{materialId}/status")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_WRITE + "')")
    @Operation(operationId = "updateMaterialStatus", summary = "启用/停用耗材（PATCH 状态）")
    public Material changeStatus(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("materialId") String materialId,
            @Valid @RequestBody MaterialStatusRequest request, AuthRequestContext context) {
        return catalogService.changeStatus(materialId, request, AuthPrincipals.from(jwt), context);
    }
}
