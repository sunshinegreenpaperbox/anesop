package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.scheduling.model.CancelSurgeryApplicationRequest;
import com.anesop.contracts.scheduling.model.IdResponse;
import com.anesop.contracts.scheduling.model.ImportSurgeryApplicationsRequest;
import com.anesop.contracts.scheduling.model.ImportSurgeryApplicationsResult;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.contracts.scheduling.model.SurgeryApplicationCreateRequest;
import com.anesop.contracts.scheduling.model.SurgeryApplicationPage;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.application.SurgeryApplicationQuery;
import com.anesop.m1.api.application.SurgeryApplicationService;
import com.anesop.m1.api.security.M1Permissions;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手术申请接口（契约 scheduling.yaml 0.3.0，M2-M1-01）。
 *
 * <p>授权：列表/详情需要 {@code m1:application:read}；创建/导入/取消需要
 * {@code m1:application:write}。数据范围由服务层按 {@code DataScopeAssignments} 过滤，
 * 无范围返回 403 并写 ACCESS_DENIED 审计；单对象越界返回 404。
 *
 * <p>模拟 HIS 导入的响应固定 {@code simulated=true}/{@code adapter=MOCK_HIS}，
 * 不调用院方真实接口（EXT-01）。
 */
@RestController
@RequestMapping("/v1/scheduling/applications")
@Tag(name = "手术排程", description = "术间资源、日排程、冲突检测、急诊加台与停台操作")
public class SurgeryApplicationController {

    private final SurgeryApplicationService applicationService;

    public SurgeryApplicationController(SurgeryApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_APPLICATION_READ + "')")
    @Operation(operationId = "listSurgeryApplications", summary = "分页查询手术申请")
    public SurgeryApplicationPage list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "urgency", required = false) String urgency,
            @RequestParam(name = "departmentName", required = false) String departmentName,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return applicationService.list(
                new SurgeryApplicationQuery(page, size, status, urgency, departmentName),
                AuthPrincipals.from(jwt));
    }

    @GetMapping("/{applicationId}")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_APPLICATION_READ + "')")
    @Operation(operationId = "getSchedulingApplication", summary = "查询手术申请详情")
    public SurgeryApplication get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("applicationId") String applicationId) {
        return applicationService.get(applicationId, AuthPrincipals.from(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_APPLICATION_WRITE + "')")
    @Operation(operationId = "submitSurgeryApplication", summary = "创建手术申请（手工/测试）")
    public SurgeryApplication submit(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SurgeryApplicationCreateRequest request,
            AuthRequestContext context) {
        return applicationService.submit(request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_APPLICATION_WRITE + "')")
    @Operation(operationId = "importSurgeryApplications", summary = "从 HIS 拉取并导入手术申请（模拟）")
    public ImportSurgeryApplicationsResult importFromHis(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ImportSurgeryApplicationsRequest request,
            AuthRequestContext context) {
        return applicationService.importFromHis(request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/{applicationId}/cancel")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_APPLICATION_WRITE + "')")
    @Operation(operationId = "cancelSchedulingApplication", summary = "取消手术申请")
    public IdResponse cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("applicationId") String applicationId,
            @Valid @RequestBody CancelSurgeryApplicationRequest request,
            AuthRequestContext context) {
        return applicationService.cancel(applicationId, request, AuthPrincipals.from(jwt), context);
    }
}
