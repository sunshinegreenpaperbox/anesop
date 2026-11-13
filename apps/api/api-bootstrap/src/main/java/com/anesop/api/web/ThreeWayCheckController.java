package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.anesthesia.model.ApplicationThreeWayCheck;
import com.anesop.contracts.anesthesia.model.ApplicationThreeWayCheckList;
import com.anesop.contracts.anesthesia.model.RecordApplicationThreeWayCheckRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.security.M2Permissions;
import com.anesop.m2.api.threeway.ThreeWayCheckService;
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
 * 三方核查（Time-out / Sign-out）接口，手术申请维度（契约 anesthesia.yaml 0.4.0，M2-M5-01）。
 *
 * <p>授权：查询需要 {@code m2:threeway:read}，录入需要 {@code m2:threeway:write}
 * （V11 授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST/SYS_NURSE_ANESTHETIST）。append-only：
 * 每次录入新增版本；数据范围复用 M1 申请可见性，申请不存在/越界 404。
 *
 * <p>记录单（record）维度的 {@code /v1/anesthesia/records/{recordId}/three-way-checks}
 * 契约定义保持不变，实现留在 M3。
 */
@RestController
@RequestMapping("/v1/anesthesia/applications/{applicationId}/three-way-checks")
@Tag(name = "麻醉记录", description = "术前访视、麻醉记录单、锁单与修订、术后随访")
public class ThreeWayCheckController {

    private final ThreeWayCheckService threeWayCheckService;

    public ThreeWayCheckController(ThreeWayCheckService threeWayCheckService) {
        this.threeWayCheckService = threeWayCheckService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_THREEWAY_READ + "')")
    @Operation(operationId = "listApplicationThreeWayChecks", summary = "按手术申请查询三方核查")
    public ApplicationThreeWayCheckList list(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("applicationId") String applicationId) {
        return threeWayCheckService.listByApplication(applicationId, AuthPrincipals.from(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_THREEWAY_WRITE + "')")
    @Operation(operationId = "recordApplicationThreeWayCheck", summary = "录入三方核查（新增版本）")
    public ApplicationThreeWayCheck record(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("applicationId") String applicationId,
            @Valid @RequestBody RecordApplicationThreeWayCheckRequest request,
            AuthRequestContext context) {
        return threeWayCheckService.record(applicationId, request, AuthPrincipals.from(jwt), context);
    }
}
