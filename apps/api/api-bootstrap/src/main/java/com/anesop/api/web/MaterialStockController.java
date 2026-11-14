package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.material.model.CreateStockMovementRequest;
import com.anesop.contracts.material.model.DepartmentList;
import com.anesop.contracts.material.model.MaterialStockLevelList;
import com.anesop.contracts.material.model.StockMovement;
import com.anesop.contracts.material.model.StockMovementList;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.material.MaterialStockService;
import com.anesop.m4.api.security.M4Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
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
 * 耗材批次库存与出入库接口（契约 material.yaml 0.1.0，M4-M14-01）。
 *
 * <p>授权：查询需要 {@code m4:material:read}，登记需要 {@code m4:material:write}；
 * 库存/流水按科室数据范围（ALL/SITE/DEPT）隔离，SELF/ROOM 403。数量不足 409，
 * 效期/高值裸数量规则 422；流水 append-only（只增不改）。
 */
@RestController
@RequestMapping("/v1/materials")
@Tag(name = "出入库", description = "批次库存与出入库流水（IN/OUT/RETURN/ADJUST，数量/效期/批次校验）")
public class MaterialStockController {

    private final MaterialStockService stockService;

    public MaterialStockController(MaterialStockService stockService) {
        this.stockService = stockService;
    }

    @GetMapping("/departments")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_READ + "')")
    @Operation(operationId = "listMaterialDepartments", summary = "查询可访问科室（库存/UDI 归属）")
    public DepartmentList departments(@AuthenticationPrincipal Jwt jwt) {
        return stockService.listDepartments(AuthPrincipals.from(jwt));
    }

    @GetMapping("/stock")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_READ + "')")
    @Operation(operationId = "listMaterialStock", summary = "查询批次库存")
    public MaterialStockLevelList stock(
            @RequestParam(name = "materialId", required = false) String materialId,
            @RequestParam(name = "departmentId", required = false) String departmentId,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return stockService.listStock(materialId, departmentId, limit, AuthPrincipals.from(jwt));
    }

    @GetMapping("/stock-movements")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_READ + "')")
    @Operation(operationId = "listStockMovements", summary = "查询出入库流水")
    public StockMovementList movements(
            @RequestParam(name = "materialId", required = false) String materialId,
            @RequestParam(name = "departmentId", required = false) String departmentId,
            @RequestParam(name = "from", required = false) OffsetDateTime from,
            @RequestParam(name = "to", required = false) OffsetDateTime to,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return stockService.listMovements(materialId, departmentId, from, to, limit,
                AuthPrincipals.from(jwt));
    }

    @PostMapping("/stock-movements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_WRITE + "')")
    @Operation(operationId = "createStockMovement", summary = "登记出入库流水")
    public StockMovement create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateStockMovementRequest request, AuthRequestContext context) {
        return stockService.createMovement(request, AuthPrincipals.from(jwt), context);
    }
}
