package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.staff.model.CreateStaffRequest;
import com.anesop.contracts.staff.model.DepartmentList;
import com.anesop.contracts.staff.model.Staff;
import com.anesop.contracts.staff.model.StaffList;
import com.anesop.contracts.staff.model.UpdateStaffRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.security.M4Permissions;
import com.anesop.m4.api.staff.StaffProfileService;
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
 * 人员档案接口（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>授权：查询需要 {@code m4:staff:read}，维护需要 {@code m4:staff:write}（V21 授予
 * SYS_ADMIN/SYS_ANESTHESIOLOGIST，排班管理由 SYS_OR_MANAGER 承担）。工号/userId 重复 409，
 * 科室不可用 422，数据范围不足 403，单对象不可见 404；返回体只含在职人员档案，不含患者信息。
 */
@RestController
@RequestMapping("/v1/staff")
@Tag(name = "人员档案", description = "麻醉医师/护士档案（工号/职称/职级/执业资质/专长）与 m0 用户关联")
public class StaffController {

    private final StaffProfileService profileService;

    public StaffController(StaffProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_READ + "')")
    @Operation(operationId = "listStaff", summary = "查询人员档案")
    public StaffList list(@RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "departmentId", required = false) String departmentId,
            @RequestParam(name = "employmentStatus", required = false) String employmentStatus,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt) {
        return profileService.list(keyword, departmentId, employmentStatus, page, size,
                AuthPrincipals.from(jwt));
    }

    @GetMapping("/departments")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_READ + "')")
    @Operation(operationId = "listStaffDepartments", summary = "查询可见的启用科室（档案/排班选择）")
    public DepartmentList listDepartments(@AuthenticationPrincipal Jwt jwt) {
        return profileService.listDepartments(AuthPrincipals.from(jwt));
    }

    @GetMapping("/{staffId}")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_READ + "')")
    @Operation(operationId = "getStaff", summary = "查询人员档案详情")
    public Staff get(@PathVariable("staffId") String staffId,
            @AuthenticationPrincipal Jwt jwt) {
        return profileService.get(staffId, AuthPrincipals.from(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_WRITE + "')")
    @Operation(operationId = "createStaff", summary = "新建人员档案")
    public Staff create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateStaffRequest request, AuthRequestContext context) {
        return profileService.create(request, AuthPrincipals.from(jwt), context);
    }

    @PutMapping("/{staffId}")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_STAFF_WRITE + "')")
    @Operation(operationId = "updateStaff", summary = "更新人员档案（乐观锁）")
    public Staff update(@AuthenticationPrincipal Jwt jwt, @PathVariable("staffId") String staffId,
            @Valid @RequestBody UpdateStaffRequest request, AuthRequestContext context) {
        return profileService.update(staffId, request, AuthPrincipals.from(jwt), context);
    }
}
