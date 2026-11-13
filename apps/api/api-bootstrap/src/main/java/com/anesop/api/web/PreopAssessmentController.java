package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.anesthesia.model.FinalizePreopAssessmentRequest;
import com.anesop.contracts.anesthesia.model.PreopAssessment;
import com.anesop.contracts.anesthesia.model.PreopAssessmentCreateRequest;
import com.anesop.contracts.anesthesia.model.PreopAssessmentList;
import com.anesop.contracts.anesthesia.model.PreopAssessmentUpdateRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.preop.PreopAssessmentService;
import com.anesop.m2.api.security.M2Permissions;
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
 * 术前访视与评估接口（契约 anesthesia.yaml 0.3.0，M2-M4-01）。
 *
 * <p>授权：查询需要 {@code m2:preop:read}，创建/更新/最终化需要 {@code m2:preop:write}
 * （V10 授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST/SYS_NURSE_ANESTHETIST）。数据范围复用 M1
 * 申请可见性：列表按申请过滤（越权 403）、单对象越界 404、创建时申请不可见 422。
 * 评分由服务端计算，请求不接收分数；最终化后修改返回 409。
 */
@RestController
@RequestMapping("/v1/anesthesia/preop-assessments")
@Tag(name = "麻醉记录", description = "术前访视、麻醉记录单、锁单与修订、术后随访")
public class PreopAssessmentController {

    private final PreopAssessmentService preopAssessmentService;

    public PreopAssessmentController(PreopAssessmentService preopAssessmentService) {
        this.preopAssessmentService = preopAssessmentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PREOP_READ + "')")
    @Operation(operationId = "listPreopAssessments", summary = "按手术申请查询术前访视与评估")
    public PreopAssessmentList list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "applicationId") String applicationId) {
        return preopAssessmentService.listByApplication(applicationId, AuthPrincipals.from(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PREOP_WRITE + "')")
    @Operation(operationId = "createPreopAssessment", summary = "创建术前访视与评估（草稿）")
    public PreopAssessment create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PreopAssessmentCreateRequest request,
            AuthRequestContext context) {
        return preopAssessmentService.create(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/{assessmentId}")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PREOP_READ + "')")
    @Operation(operationId = "getPreopAssessment", summary = "查询术前访视与评估详情")
    public PreopAssessment get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("assessmentId") String assessmentId) {
        return preopAssessmentService.get(assessmentId, AuthPrincipals.from(jwt));
    }

    @PutMapping("/{assessmentId}")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PREOP_WRITE + "')")
    @Operation(operationId = "updatePreopAssessment", summary = "保存术前访视与评估（草稿）")
    public PreopAssessment update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("assessmentId") String assessmentId,
            @Valid @RequestBody PreopAssessmentUpdateRequest request,
            AuthRequestContext context) {
        return preopAssessmentService.update(assessmentId, request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/{assessmentId}/finalize")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PREOP_WRITE + "')")
    @Operation(operationId = "finalizePreopAssessment", summary = "最终化术前访视与评估")
    public PreopAssessment finalizeAssessment(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("assessmentId") String assessmentId,
            @Valid @RequestBody FinalizePreopAssessmentRequest request,
            AuthRequestContext context) {
        return preopAssessmentService.finalizeAssessment(assessmentId, request,
                AuthPrincipals.from(jwt), context);
    }
}
