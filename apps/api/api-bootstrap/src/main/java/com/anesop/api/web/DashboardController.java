package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.dashboard.model.DashboardKpi;
import com.anesop.contracts.dashboard.model.RealtimeDashboard;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m5.api.dashboard.DashboardService;
import com.anesop.m5.api.security.M5Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实时态势与 KPI 看板接口（契约 dashboard.yaml 0.1.0，M5-M18-01）。
 *
 * <p>只读聚合：两个端点共用 {@code m5:dashboard:read}（V26 data-only 授予 SYS_ADMIN /
 * SYS_OR_MANAGER / SYS_ANESTHESIOLOGIST / SYS_QC_MANAGER）；数据范围由服务层按
 * ALL/SITE/DEPT/ROOM/SELF 裁剪，无可用范围或越界 403。查询写审计 {@code DASHBOARD_VIEW}
 * （按用户采样，避免 10s 轮询刷屏）。
 *
 * <p>响应只含术间/科室/状态/计数与 KPI 数值，不含任何患者标识（AGENTS 患者数据红线）。
 */
@RestController
@RequestMapping("/v1/dashboard")
@Tag(name = "驾驶舱", description = "实时态势与当日运营 KPI（只读、无 PHI）")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/realtime")
    @PreAuthorize("hasAuthority('" + M5Permissions.AUTHORITY_DASHBOARD_READ + "')")
    @Operation(operationId = "getRealtimeDashboard", summary = "实时态势（术间/台次/告警/设备 + 数据延迟）")
    public RealtimeDashboard realtime(@AuthenticationPrincipal Jwt jwt,
            AuthRequestContext context) {
        return dashboardService.realtime(AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/kpi")
    @PreAuthorize("hasAuthority('" + M5Permissions.AUTHORITY_DASHBOARD_READ + "')")
    @Operation(operationId = "getDashboardKpi", summary = "当日运营 KPI（台次/准点/取消/利用率/告警/时长）")
    public DashboardKpi kpi(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "date", required = false) String date,
            AuthRequestContext context) {
        return dashboardService.kpi(date, AuthPrincipals.from(jwt), context);
    }
}
