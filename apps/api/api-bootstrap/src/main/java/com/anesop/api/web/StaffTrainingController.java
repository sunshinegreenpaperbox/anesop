package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.staff.model.CreateStaffTrainingRequest;
import com.anesop.contracts.staff.model.ExpiringTrainingList;
import com.anesop.contracts.staff.model.StaffTraining;
import com.anesop.contracts.staff.model.StaffTrainingList;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.security.M4Permissions;
import com.anesop.m4.api.staff.StaffTrainingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * 培训与继续教育接口（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>查询需要 {@code m4:staff:read}，登记需要 {@code m4:training:manage}（V21 授予
 * SYS_ADMIN/SYS_ANESTHESIOLOGIST）；到期提醒默认 30 天（方案书“证书到期前 30 天提醒”）。
 */
@RestController
@RequestMapping("/v1/staff/trainings")
@Tag(name = "培训", description = "培训与继续教育记录（课程/完成时间/到期时间/证书号）与到期提醒")
public class StaffTrainingController {

    private final StaffTrainingService trainingService;

    public StaffTrainingController(StaffTrainingService trainingService) {
        this.trainingService = trainingService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_READ + "')")
    @Operation(operationId = "listStaffTrainings", summary = "查询培训记录")
    public StaffTrainingList list(@RequestParam(name = "staffId", required = false) String staffId,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return trainingService.list(staffId, limit, AuthPrincipals.from(jwt));
    }

    @GetMapping("/expiring")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_READ + "')")
    @Operation(operationId = "listExpiringStaffTrainings", summary = "查询即将到期/已到期的培训")
    public ExpiringTrainingList expiring(
            @RequestParam(name = "days", required = false) Integer days,
            @RequestParam(name = "departmentId", required = false) String departmentId,
            @AuthenticationPrincipal Jwt jwt) {
        return trainingService.expiring(days, departmentId, AuthPrincipals.from(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_TRAINING_MANAGE + "')")
    @Operation(operationId = "createStaffTraining", summary = "登记培训记录")
    public StaffTraining create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateStaffTrainingRequest request, AuthRequestContext context) {
        return trainingService.create(request, AuthPrincipals.from(jwt), context);
    }
}
