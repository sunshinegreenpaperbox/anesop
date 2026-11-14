package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.report.model.MultiReport;
import com.anesop.contracts.report.model.WorkloadReport;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.report.MultiReportQuery;
import com.anesop.m4.api.report.ReportExportQuery;
import com.anesop.m4.api.report.ReportQuery;
import com.anesop.m4.api.report.ReportService;
import com.anesop.m4.api.security.M4Permissions;
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
 * 工作量统计、多维报表与导出接口（契约 report.yaml 0.1.0，M4-M16-01）。
 *
 * <p>只读聚合：查询需 {@code m4:report:read}、导出需 {@code m4:report:export}
 * （V24 data-only 授权 SYS_ADMIN/SYS_OR_MANAGER/SYS_QC_MANAGER）；数据范围由服务层按
 * ALL/SITE/DEPT/ROOM/SELF 裁剪，未绑定或越界 403。查询与导出均写审计
 * （{@code REPORT_QUERY}/{@code REPORT_EXPORT}）。
 *
 * <p>CSV 导出直接写 {@link HttpServletResponse} 输出流（UTF-8 BOM、流式）；服务层在写任何
 * 字节前完成参数校验与数据范围解析，非法请求仍由全局异常处理器返回 400/403 JSON。
 * 导出只含聚合值与维度标签（科室/术间/术式/院内在册人员工号姓名），不含患者标识。
 */
@RestController
@RequestMapping("/v1/reports")
@Tag(name = "报表", description = "工作量统计、多维报表与脱敏 CSV 导出")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/workload")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_REPORT_READ + "')")
    @Operation(operationId = "getWorkloadReport", summary = "工作量统计（台次/麻醉时长/权重/术式分布）")
    public WorkloadReport workload(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "dimension") String dimension,
            @RequestParam(name = "deptId", required = false) String deptId,
            AuthRequestContext context) {
        return reportService.workload(new ReportQuery(from, to, dimension, deptId),
                AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/multi")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_REPORT_READ + "')")
    @Operation(operationId = "getMultiDimensionalReport", summary = "多维报表（组合维度 + 分页 + 合计）")
    public MultiReport multi(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "dimensions") String dimensions,
            @RequestParam(name = "metrics") String metrics,
            @RequestParam(name = "deptId", required = false) String deptId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            AuthRequestContext context) {
        return reportService.multi(
                new MultiReportQuery(from, to, dimensions, metrics, deptId, page, size),
                AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_REPORT_EXPORT + "')")
    @Operation(operationId = "exportReport", summary = "导出工作量/多维报表 CSV（脱敏）")
    public void export(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "report") String report,
            @RequestParam(name = "format") String format,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "dimension", required = false) String dimension,
            @RequestParam(name = "dimensions", required = false) String dimensions,
            @RequestParam(name = "metrics", required = false) String metrics,
            @RequestParam(name = "deptId", required = false) String deptId,
            AuthRequestContext context,
            HttpServletResponse response) throws IOException {
        // 先完成全部校验与聚合（可能抛 400/403），再设置 CSV 响应头并写出；
        // 避免在已提交的 text/csv 响应中抛错导致全局异常处理器无法返回 JSON。
        var plan = reportService.prepareExport(
                new ReportExportQuery(report, format, from, to, dimension, dimensions, metrics,
                        deptId),
                AuthPrincipals.from(jwt), context);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + plan.fileName() + "\"");
        plan.writer().accept(response.getOutputStream());
    }
}
