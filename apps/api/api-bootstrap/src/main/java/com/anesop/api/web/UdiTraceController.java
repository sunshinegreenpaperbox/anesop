package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.material.model.RegisterUdiInstanceRequest;
import com.anesop.contracts.material.model.UdiInstance;
import com.anesop.contracts.material.model.UdiTrace;
import com.anesop.contracts.material.model.UseUdiInstanceRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.material.UdiTraceService;
import com.anesop.m4.api.security.M4Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * UDI 一物一码接口（契约 material.yaml 0.1.0，M4-M14-01）。
 *
 * <p>授权：登记/使用需要 {@code m4:udi:manage}，追溯查询需要 {@code m4:material:read}；
 * UDI 实例按科室数据范围（ALL/SITE/DEPT）隔离，越界统一 404。重复登记/重复使用 409，
 * 绑定记录不存在/不可见 422。路径参数 `udi` 为 URL 编码后的 `UDI-DI^UDI-PI`。
 */
@RestController
@RequestMapping("/v1/materials/udi-instances")
@Tag(name = "UDI 追溯", description = "UDI 实例登记、使用绑定记录/台次、material→UDI→record 追溯")
public class UdiTraceController {

    private final UdiTraceService udiTraceService;

    public UdiTraceController(UdiTraceService udiTraceService) {
        this.udiTraceService = udiTraceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_UDI_MANAGE + "')")
    @Operation(operationId = "registerUdiInstance", summary = "登记 UDI 实例（高值耗材逐件入库）")
    public UdiInstance register(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegisterUdiInstanceRequest request, AuthRequestContext context) {
        return udiTraceService.register(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/{udi}")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_MATERIAL_READ + "')")
    @Operation(operationId = "getUdiTrace", summary = "查询 UDI 追溯链（material→UDI→record）")
    public UdiTrace trace(@PathVariable("udi") String udi,
            @AuthenticationPrincipal Jwt jwt) {
        return udiTraceService.trace(udi, AuthPrincipals.from(jwt));
    }

    @PostMapping("/{udi}/use")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_UDI_MANAGE + "')")
    @Operation(operationId = "useUdiInstance", summary = "使用 UDI 实例（绑定记录/台次）")
    public UdiInstance use(@AuthenticationPrincipal Jwt jwt, @PathVariable("udi") String udi,
            @Valid @RequestBody UseUdiInstanceRequest request, AuthRequestContext context) {
        return udiTraceService.use(udi, request, AuthPrincipals.from(jwt), context);
    }
}
