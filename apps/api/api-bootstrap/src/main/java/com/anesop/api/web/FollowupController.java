package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.anesthesia.model.CreatePostopFollowupRequest;
import com.anesop.contracts.anesthesia.model.PainManagement;
import com.anesop.contracts.anesthesia.model.PainManagementList;
import com.anesop.contracts.anesthesia.model.PostopFollowup;
import com.anesop.contracts.anesthesia.model.PostopFollowupList;
import com.anesop.contracts.anesthesia.model.RecordPainManagementRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.followup.FollowupService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 术后随访与镇痛接口（契约 anesthesia.yaml 0.10.0，M3-M11-01）。
 *
 * <p>授权：查询需要 {@code m2:followup:read}，录入随访/镇痛需要 {@code m2:followup:write}
 * （V19 授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST/SYS_NURSE_ANESTHETIST）。数据范围复用
 * 记录单/台次可见性：按记录单列表越权 403（集合语义）、单对象越界 404、录入时记录单不可见
 * 422。随访要求记录单已结束（未结束 409）；随访与镇痛均 append-only。
 */
@RestController
@RequestMapping("/v1/anesthesia")
@Tag(name = "麻醉记录", description = "术前访视、麻醉记录单、锁单与修订、术后随访")
public class FollowupController {

    private final FollowupService followupService;

    public FollowupController(FollowupService followupService) {
        this.followupService = followupService;
    }

    @GetMapping("/followups")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_FOLLOWUP_READ + "')")
    @Operation(operationId = "listPostopFollowups", summary = "按麻醉记录单查询术后随访")
    public PostopFollowupList list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "recordId") String recordId) {
        return followupService.listByRecord(recordId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/followups")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_FOLLOWUP_WRITE + "')")
    @Operation(operationId = "createPostopFollowup", summary = "录入术后随访（append-only）")
    public PostopFollowup create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePostopFollowupRequest request,
            AuthRequestContext context) {
        return followupService.create(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/followups/{followupId}")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_FOLLOWUP_READ + "')")
    @Operation(operationId = "getPostopFollowup", summary = "查询术后随访详情")
    public PostopFollowup get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("followupId") String followupId) {
        return followupService.get(followupId, AuthPrincipals.from(jwt));
    }

    @GetMapping("/followups/{followupId}/pain-management")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_FOLLOWUP_READ + "')")
    @Operation(operationId = "listPainManagement", summary = "查询随访的镇痛记录")
    public PainManagementList listPainManagement(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("followupId") String followupId) {
        return followupService.listPainManagement(followupId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/followups/{followupId}/pain-management")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_FOLLOWUP_WRITE + "')")
    @Operation(operationId = "recordPainManagement", summary = "记录镇痛方案与效果（append-only）")
    public PainManagement recordPainManagement(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("followupId") String followupId,
            @Valid @RequestBody RecordPainManagementRequest request,
            AuthRequestContext context) {
        return followupService.recordPainManagement(followupId, request, AuthPrincipals.from(jwt),
                context);
    }
}
