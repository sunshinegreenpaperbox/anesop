package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.drug.model.CreateDrugRequest;
import com.anesop.contracts.drug.model.Drug;
import com.anesop.contracts.drug.model.DrugList;
import com.anesop.contracts.drug.model.UpdateDrugRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.drug.DrugCatalogService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 药品目录接口（契约 drug.yaml 0.1.0，M4-M15-01）。
 *
 * <p>授权：查询需要 {@code m4:drug:read}（目录全院可读），维护需要 {@code m4:drug:write}
 * （V20 授予 SYS_ADMIN/SYS_PHARMACIST）。编码/名称重复 409，别名冲突 400，版本过期 409；
 * 药品不存在 404。返回体只含药品主数据，不含患者信息。
 */
@RestController
@RequestMapping("/v1/drugs")
@Tag(name = "药品目录", description = "药品主数据维护与检索（编码/名称/别名唯一、麻精/高危标志）")
public class DrugController {

    private final DrugCatalogService catalogService;

    public DrugController(DrugCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_DRUG_READ + "')")
    @Operation(operationId = "listDrugs", summary = "查询药品目录")
    public DrugList list(@RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return catalogService.list(keyword, status, page, size);
    }

    @GetMapping("/search")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_DRUG_READ + "')")
    @Operation(operationId = "searchDrugs", summary = "按名称/别名检索药品（用药录入联想）")
    public DrugList search(@RequestParam(name = "q") String q,
            @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return catalogService.search(q, limit);
    }

    @GetMapping("/{drugId}")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_DRUG_READ + "')")
    @Operation(operationId = "getDrug", summary = "查询药品详情")
    public Drug get(@PathVariable("drugId") String drugId) {
        return catalogService.get(drugId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_DRUG_WRITE + "')")
    @Operation(operationId = "createDrug", summary = "创建药品")
    public Drug create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateDrugRequest request,
            AuthRequestContext context) {
        return catalogService.create(request, AuthPrincipals.from(jwt), context);
    }

    @PutMapping("/{drugId}")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_DRUG_WRITE + "')")
    @Operation(operationId = "updateDrug", summary = "更新药品（乐观锁）")
    public Drug update(@AuthenticationPrincipal Jwt jwt, @PathVariable("drugId") String drugId,
            @Valid @RequestBody UpdateDrugRequest request, AuthRequestContext context) {
        return catalogService.update(drugId, request, AuthPrincipals.from(jwt), context);
    }
}
