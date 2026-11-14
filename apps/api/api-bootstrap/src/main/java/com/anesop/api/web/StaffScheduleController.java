package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.staff.model.CancelStaffScheduleRequest;
import com.anesop.contracts.staff.model.CreateStaffScheduleRequest;
import com.anesop.contracts.staff.model.StaffSchedule;
import com.anesop.contracts.staff.model.StaffScheduleList;
import com.anesop.contracts.staff.model.UpdateStaffScheduleRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.security.M4Permissions;
import com.anesop.m4.api.staff.StaffScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 排班接口（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>查询需要 {@code m4:staff:read}，维护需要 {@code m4:schedule:manage}（V21 授予
 * SYS_ADMIN/SYS_OR_MANAGER）。冲突返回 409 + details（同人员同日同班次 / 同术间同日同班次）。
 */
@RestController
@RequestMapping("/v1/staff/schedules")
@Tag(name = "排班", description = "班次（白班/夜班/备班）排班、术间与科室分配、冲突检测与取消")
public class StaffScheduleController {

    private final StaffScheduleService scheduleService;

    public StaffScheduleController(StaffScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_READ + "')")
    @Operation(operationId = "listStaffSchedules", summary = "查询排班")
    public StaffScheduleList list(
            @RequestParam(name = "staffId", required = false) String staffId,
            @RequestParam(name = "departmentId", required = false) String departmentId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "shift", required = false) String shift,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        return scheduleService.list(staffId, departmentId, from, to, shift, status, limit,
                AuthPrincipals.from(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_SCHEDULE_MANAGE + "')")
    @Operation(operationId = "createStaffSchedule", summary = "新建排班（冲突检测）")
    public StaffSchedule create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateStaffScheduleRequest request, AuthRequestContext context) {
        return scheduleService.create(request, AuthPrincipals.from(jwt), context);
    }

    @PutMapping("/{scheduleId}")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_SCHEDULE_MANAGE + "')")
    @Operation(operationId = "updateStaffSchedule", summary = "更新排班（乐观锁 + 冲突检测）")
    public StaffSchedule update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("scheduleId") String scheduleId,
            @Valid @RequestBody UpdateStaffScheduleRequest request, AuthRequestContext context) {
        return scheduleService.update(scheduleId, request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/{scheduleId}/cancel")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_SCHEDULE_MANAGE + "')")
    @Operation(operationId = "cancelStaffSchedule", summary = "取消排班（留痕）")
    public StaffSchedule cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("scheduleId") String scheduleId,
            @Valid @RequestBody CancelStaffScheduleRequest request, AuthRequestContext context) {
        return scheduleService.cancel(scheduleId, request, AuthPrincipals.from(jwt), context);
    }
}
