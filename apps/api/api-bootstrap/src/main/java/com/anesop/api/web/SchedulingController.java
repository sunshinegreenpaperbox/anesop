package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.scheduling.model.CancelScheduleRequest;
import com.anesop.contracts.scheduling.model.ConflictCheckRequest;
import com.anesop.contracts.scheduling.model.ConflictCheckResult;
import com.anesop.contracts.scheduling.model.CreateScheduleRequest;
import com.anesop.contracts.scheduling.model.IdResponse;
import com.anesop.contracts.scheduling.model.OperatingRoomList;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.contracts.scheduling.model.SchedulePage;
import com.anesop.contracts.scheduling.model.UpdateScheduleRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.schedule.SchedulingService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 排程工作台接口（契约 scheduling.yaml 0.4.0，M2-M2-01）。
 *
 * <p>授权：术间/日排程/详情/冲突预检需要 {@code m1:schedule:read}；创建/调整/取消需要
 * {@code m1:schedule:write}（V8 仅授 SYS_ADMIN/SYS_OR_MANAGER）。数据范围由服务层按
 * {@code DataScopeAssignments} 过滤（DEPT 本科室 / ROOM 绑定术间），无范围 403；
 * 单对象越界 404。冲突在事务内复检，返回 409 + {@code conflicts[]}，不静默覆盖。
 */
@RestController
@RequestMapping("/v1/scheduling")
@Tag(name = "手术排程", description = "术间资源、日排程、冲突检测、急诊加台与停台操作")
public class SchedulingController {

    private final SchedulingService schedulingService;

    public SchedulingController(SchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    @GetMapping("/rooms")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_SCHEDULE_READ + "')")
    @Operation(operationId = "listOperatingRooms", summary = "查询术间列表")
    public OperatingRoomList listRooms(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "siteId", required = false) String siteId,
            @RequestParam(name = "status", required = false) String status) {
        return schedulingService.listRooms(AuthPrincipals.from(jwt), siteId, status);
    }

    @GetMapping("/schedules")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_SCHEDULE_READ + "')")
    @Operation(operationId = "listSchedules", summary = "按日期/术间查询排程")
    public SchedulePage listSchedules(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "date") String date,
            @RequestParam(name = "roomId", required = false) String roomId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return schedulingService.listSchedules(date, roomId, status, page, size,
                AuthPrincipals.from(jwt));
    }

    @GetMapping("/schedules/{scheduleId}")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_SCHEDULE_READ + "')")
    @Operation(operationId = "getSchedule", summary = "查询排程详情")
    public Schedule get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("scheduleId") String scheduleId) {
        return schedulingService.get(scheduleId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_SCHEDULE_WRITE + "')")
    @Operation(operationId = "createSchedule", summary = "创建排程")
    public Schedule create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateScheduleRequest request,
            AuthRequestContext context) {
        return schedulingService.create(request, AuthPrincipals.from(jwt), context);
    }

    @PutMapping("/schedules/{scheduleId}")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_SCHEDULE_WRITE + "')")
    @Operation(operationId = "rescheduleSchedule", summary = "调整排程（改时间/改术间）")
    public Schedule reschedule(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("scheduleId") String scheduleId,
            @Valid @RequestBody UpdateScheduleRequest request,
            AuthRequestContext context) {
        return schedulingService.reschedule(scheduleId, request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/schedules/{scheduleId}/cancel")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_SCHEDULE_WRITE + "')")
    @Operation(operationId = "cancelSchedule", summary = "停台/取消排程")
    public IdResponse cancel(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("scheduleId") String scheduleId,
            @Valid @RequestBody CancelScheduleRequest request,
            AuthRequestContext context) {
        return schedulingService.cancel(scheduleId, request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/schedules/conflicts")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_SCHEDULE_READ + "')")
    @Operation(operationId = "detectScheduleConflicts", summary = "排程冲突即时检测（工作台预检）")
    public ConflictCheckResult checkConflicts(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConflictCheckRequest request) {
        return schedulingService.checkConflicts(request, AuthPrincipals.from(jwt));
    }

    /**
     * 0.3.0 兼容端点（契约 {@code POST /v1/scheduling/conflicts/check}，deprecated，计划 1.0.0
     * 删除，ADR-0011 弃用窗口）：映射到同一 {@link SchedulingService#checkConflicts}，响应结构
     * 与新端点完全一致，不在此复制规则逻辑。
     */
    @Deprecated
    @PostMapping("/conflicts/check")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_SCHEDULE_READ + "')")
    @Operation(operationId = "checkScheduleConflict", deprecated = true,
            summary = "检测排程冲突（已弃用，改用 POST /v1/scheduling/schedules/conflicts）")
    public ConflictCheckResult checkConflictsDeprecated(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ConflictCheckRequest request) {
        return schedulingService.checkConflicts(request, AuthPrincipals.from(jwt));
    }
}
