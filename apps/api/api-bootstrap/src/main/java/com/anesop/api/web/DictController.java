package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.platform.model.CreateDictItemRequest;
import com.anesop.contracts.platform.model.DictItem;
import com.anesop.contracts.platform.model.DictItemList;
import com.anesop.contracts.platform.model.DictTypeList;
import com.anesop.contracts.platform.model.UpdateDictItemRequest;
import com.anesop.m0.api.platform.DictService;
import com.anesop.m0.api.security.AuthPermissions;
import com.anesop.m0.api.security.AuthRequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 字典接口（契约 platform.yaml 0.2.0，M8-M0-04）。
 *
 * <p>授权：查询需 {@code m0:dict:read}（V27 授予全部 6 个基线角色，字典全院可读）；
 * 维护需 {@code m0:dict:write}（V27 仅授予 {@code SYS_ADMIN}，服务层再校验角色做纵深防御）。
 * {@code includeDisabled=true} 只对持写权限者生效，普通用户 403。
 * 错误映射：类型/字典项不存在 404、编码重复 409、字段非法 400、越权 403。
 */
@RestController
@RequestMapping("/v1/dicts")
@Tag(name = "字典", description = "字典类型/字典项查询（全院读）与字典项维护（仅管理员、逻辑停用）")
public class DictController {

    private final DictService dictService;

    public DictController(DictService dictService) {
        this.dictService = dictService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_DICT_READ + "')")
    @Operation(operationId = "listDictTypes", summary = "查询字典类型列表")
    public DictTypeList listTypes() {
        return dictService.listTypes();
    }

    @GetMapping("/{type}")
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_DICT_READ + "')")
    @Operation(operationId = "listDictItems", summary = "查询字典项（默认只返回启用项）")
    public DictItemList listItems(@PathVariable("type") String type,
            @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled,
            @AuthenticationPrincipal Jwt jwt) {
        boolean canManage = AuthPrincipals.permissions(jwt)
                .contains(AuthPermissions.M0_DICT_WRITE);
        return dictService.listItems(type, includeDisabled, canManage);
    }

    @PostMapping("/{type}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_DICT_WRITE + "')")
    @Operation(operationId = "createDictItem", summary = "新增字典项（仅管理员）")
    public DictItem create(@PathVariable("type") String type,
            @Valid @RequestBody CreateDictItemRequest request,
            @AuthenticationPrincipal Jwt jwt, AuthRequestContext context) {
        return dictService.createItem(type, request, AuthPrincipals.from(jwt),
                AuthPrincipals.roles(jwt), context);
    }

    @PutMapping("/{type}/items/{itemId}")
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_DICT_WRITE + "')")
    @Operation(operationId = "updateDictItem", summary = "更新字典项（仅管理员，编码不可改）")
    public DictItem update(@PathVariable("type") String type,
            @PathVariable("itemId") UUID itemId,
            @Valid @RequestBody UpdateDictItemRequest request,
            @AuthenticationPrincipal Jwt jwt, AuthRequestContext context) {
        return dictService.updateItem(type, itemId, request, AuthPrincipals.from(jwt),
                AuthPrincipals.roles(jwt), context);
    }

    @DeleteMapping("/{type}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('" + AuthPermissions.AUTHORITY_M0_DICT_WRITE + "')")
    @Operation(operationId = "disableDictItem", summary = "停用字典项（逻辑停用，仅管理员）")
    public void disable(@PathVariable("type") String type,
            @PathVariable("itemId") UUID itemId,
            @AuthenticationPrincipal Jwt jwt, AuthRequestContext context) {
        dictService.disableItem(type, itemId, AuthPrincipals.from(jwt),
                AuthPrincipals.roles(jwt), context);
    }
}
