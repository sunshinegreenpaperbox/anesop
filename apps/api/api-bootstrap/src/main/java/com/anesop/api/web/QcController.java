package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.qc.model.AdverseEvent;
import com.anesop.contracts.qc.model.AdverseEventList;
import com.anesop.contracts.qc.model.AdverseRca;
import com.anesop.contracts.qc.model.ChartReview;
import com.anesop.contracts.qc.model.ChartReviewList;
import com.anesop.contracts.qc.model.CloseAdverseRcaRequest;
import com.anesop.contracts.qc.model.CloseRectificationRequest;
import com.anesop.contracts.qc.model.CreateAdverseEventRequest;
import com.anesop.contracts.qc.model.CreateAdverseRcaRequest;
import com.anesop.contracts.qc.model.CreateChartReviewRequest;
import com.anesop.contracts.qc.model.CreateRectificationRequest;
import com.anesop.contracts.qc.model.QcIndicatorList;
import com.anesop.contracts.qc.model.QcIndicatorResultList;
import com.anesop.contracts.qc.model.Rectification;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.qc.AdverseEventQuery;
import com.anesop.m4.api.qc.ChartReviewQuery;
import com.anesop.m4.api.qc.QcExportQuery;
import com.anesop.m4.api.qc.QcIndicatorResultQuery;
import com.anesop.m4.api.qc.QcService;
import com.anesop.m4.api.security.M4Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
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
 * 质控指标、不良事件 RCA、病历质量抽查与上报导出接口（契约 qc.yaml 0.1.0，M4-M17-01）。
 *
 * <p>权限：读 {@code m4:qc:read}；登记/提交 {@code m4:qc:write}；复核与闭环
 * {@code m4:qc:review}；导出 {@code m4:qc:export}（V25 最小授予 SYS_ADMIN/SYS_QC_MANAGER
 * 四项、SYS_OR_MANAGER 读+写）。数据范围由服务层按科室/全院裁剪（ROOM/SELF 403），
 * 单对象越界 404。所有写操作与导出均写审计（detail 无 PHI，导出另写 {@code QC_EXPORT}）。
 *
 * <p>CSV 导出直接写响应流（UTF-8 BOM、流式）；服务层在写任何字节前完成参数校验与数据范围
 * 解析，非法请求仍由全局异常处理器返回 400/403 JSON。
 */
@RestController
@RequestMapping("/v1/qc")
@Tag(name = "质控", description = "质控指标、不良事件 RCA、病历质量抽查与脱敏导出")
public class QcController {

    private final QcService qcService;

    public QcController(QcService qcService) {
        this.qcService = qcService;
    }

    @GetMapping("/indicators")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_READ + "')")
    @Operation(operationId = "getQcIndicators", summary = "质控指标定义（版本化口径/阈值）")
    public QcIndicatorList indicators(@AuthenticationPrincipal Jwt jwt,
            AuthRequestContext context) {
        return qcService.indicators(AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/indicator-results")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_READ + "')")
    @Operation(operationId = "getQcIndicatorResults", summary = "周期指标结果（分子/分母/达标）")
    public QcIndicatorResultList indicatorResults(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "deptId", required = false) String deptId,
            AuthRequestContext context) {
        return qcService.indicatorResults(new QcIndicatorResultQuery(from, to, deptId),
                AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/adverse-events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_WRITE + "')")
    @Operation(operationId = "createAdverseEvent", summary = "登记不良事件（REPORTED）")
    public AdverseEvent createAdverseEvent(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateAdverseEventRequest request,
            AuthRequestContext context) {
        return qcService.createAdverseEvent(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/adverse-events")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_READ + "')")
    @Operation(operationId = "listAdverseEvents", summary = "不良事件台账（分页）")
    public AdverseEventList listAdverseEvents(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "deptId", required = false) String deptId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            AuthRequestContext context) {
        return qcService.listAdverseEvents(new AdverseEventQuery(status, from, to, deptId, page,
                size), AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/adverse-events/{id}/rca")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_WRITE + "')")
    @Operation(operationId = "createAdverseRca", summary = "提交 RCA（REPORTED→RCA）")
    public AdverseRca createAdverseRca(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") String id,
            @Valid @RequestBody CreateAdverseRcaRequest request,
            AuthRequestContext context) {
        return qcService.createRca(id, request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/rca/{id}/close")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_REVIEW + "')")
    @Operation(operationId = "closeAdverseRca", summary = "RCA 复核 / 整改闭环（需复核人）")
    public AdverseRca closeAdverseRca(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") String id,
            @Valid @RequestBody CloseAdverseRcaRequest request,
            AuthRequestContext context) {
        return qcService.closeRca(id, request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/chart-reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_WRITE + "')")
    @Operation(operationId = "createChartReview", summary = "创建病历质量抽查（评分表/缺陷项）")
    public ChartReview createChartReview(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateChartReviewRequest request,
            AuthRequestContext context) {
        return qcService.createChartReview(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/chart-reviews")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_READ + "')")
    @Operation(operationId = "listChartReviews", summary = "病历质量抽查列表（分页）")
    public ChartReviewList listChartReviews(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "deptId", required = false) String deptId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            AuthRequestContext context) {
        return qcService.listChartReviews(new ChartReviewQuery(status, from, to, deptId, page, size),
                AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/chart-reviews/{id}/rectify")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_WRITE + "')")
    @Operation(operationId = "createChartRectification", summary = "提交整改（OPEN→RECTIFYING）")
    public Rectification createChartRectification(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") String id,
            @Valid @RequestBody CreateRectificationRequest request,
            AuthRequestContext context) {
        return qcService.createRectification(id, request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/rectify/{id}/close")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_REVIEW + "')")
    @Operation(operationId = "closeChartRectification", summary = "整改闭环（需复核人）")
    public Rectification closeChartRectification(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") String id,
            @Valid @RequestBody CloseRectificationRequest request,
            AuthRequestContext context) {
        return qcService.closeRectification(id, request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_QC_EXPORT + "')")
    @Operation(operationId = "exportQcReport", summary = "质控指标/不良事件/病历抽查 CSV 导出（脱敏）")
    public void export(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "type") String type,
            @RequestParam(name = "format") String format,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to,
            @RequestParam(name = "deptId", required = false) String deptId,
            AuthRequestContext context,
            HttpServletResponse response) throws IOException {
        // 先完成全部校验与聚合（可能抛 400/403），再设置 CSV 响应头并写出。
        var plan = qcService.prepareExport(new QcExportQuery(type, format, from, to, deptId),
                AuthPrincipals.from(jwt), context);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + plan.fileName() + "\"");
        plan.writer().accept(response.getOutputStream());
    }
}
