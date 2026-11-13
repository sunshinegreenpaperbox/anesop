package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.scheduling.model.CancellationReport;
import com.anesop.contracts.scheduling.model.OnTimeReport;
import com.anesop.contracts.scheduling.model.UtilizationReport;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.analytics.AnalyticsExportQuery;
import com.anesop.m1.api.analytics.AnalyticsQuery;
import com.anesop.m1.api.analytics.SchedulingAnalyticsService;
import com.anesop.m1.api.security.M1Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 排程分析接口（契约 scheduling.yaml 0.6.0，M2-M6-01）。
 *
 * <p>只读聚合；权限 {@code m1:analytics:read}（V12 授 SYS_ADMIN/SYS_OR_MANAGER/SYS_QC_MANAGER），
 * 数据范围由服务层按 DEPT/ROOM 过滤（未绑定 403）。查询与导出均写审计。
 *
 * <p>CSV 导出直接写 {@link HttpServletResponse} 输出流（UTF-8 BOM、流式）；服务层在写任何字节前
 * 完成参数校验与数据范围解析，非法请求仍由全局异常处理器返回 400/403 JSON。导出仅含聚合值，
 * 不含患者姓名、住院号、证件号、诊断等 PHI。
 */
@RestController
@RequestMapping("/v1/scheduling/analytics")
@Tag(name = "排程分析", description = "术间利用率、首台准点率、取消率与脱敏 CSV 导出")
@PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_ANALYTICS_READ + "')")
public class SchedulingAnalyticsController {

    private final SchedulingAnalyticsService analyticsService;

    public SchedulingAnalyticsController(SchedulingAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/utilization")
    @Operation(operationId = "getSchedulingUtilization", summary = "术间利用率（按术间与汇总）")
    public UtilizationReport utilization(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "deptId", required = false) String deptId,
            AuthRequestContext context) {
        return analyticsService.utilization(new AnalyticsQuery(from, to, deptId),
                AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/on-time")
    @Operation(operationId = "getSchedulingOnTimeRate", summary = "首台准点开台率（按术间与汇总）")
    public OnTimeReport onTime(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "deptId", required = false) String deptId,
            AuthRequestContext context) {
        return analyticsService.onTime(new AnalyticsQuery(from, to, deptId),
                AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/cancellations")
    @Operation(operationId = "getSchedulingCancellationRate", summary = "取消率（按原因码与术间分组）")
    public CancellationReport cancellations(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "deptId", required = false) String deptId,
            AuthRequestContext context) {
        return analyticsService.cancellations(new AnalyticsQuery(from, to, deptId),
                AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/export")
    @Operation(operationId = "exportSchedulingAnalytics", summary = "导出排程分析 CSV（脱敏）")
    public void export(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "metric") String metric,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "format") String format,
            @RequestParam(name = "deptId", required = false) String deptId,
            AuthRequestContext context,
            HttpServletResponse response) throws IOException {
        // 先完成全部校验与聚合（可能抛 400/403），再设置 CSV 响应头并写出；
        // 避免在已提交的 text/csv 响应中抛错导致全局异常处理器无法返回 JSON。
        var plan = analyticsService.prepareExport(
                new AnalyticsExportQuery(metric, from, to, format, deptId),
                AuthPrincipals.from(jwt), context);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + plan.fileName() + "\"");
        plan.writer().accept(response.getOutputStream());
    }
}
