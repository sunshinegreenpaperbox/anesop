package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.patient.model.Patient;
import com.anesop.contracts.patient.model.PatientPage;
import com.anesop.contracts.patient.model.PatientSearchRequest;
import com.anesop.m1.api.patient.PatientQuery;
import com.anesop.m1.api.patient.PatientQueryService;
import com.anesop.m1.api.security.M1Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者摘要查询接口（契约 patient.yaml 0.3.0，M2-M1-01）。
 *
 * <p>授权：需要 {@code m1:patient:read}（Q-3 最小权限：SYS_ADMIN/SYS_SURGEON/SYS_OR_MANAGER）；
 * 查询结果同时受数据范围约束（集合越权 403，单对象越界/不存在 404）。响应只含姓名、性别、
 * 出生日期/年龄、脱敏住院号、科室/病区/床号，**不返回证件号与手机号真实值**；兼容字段
 * {@code idCardMasked}/{@code phoneMasked}/{@code inpatientNo} 固定返回空字符串。
 *
 * <p>B-3：医学检索使用 {@code POST /v1/patients/search}，检索条件放 JSON 请求体，避免 PHI
 * 进入 URL 查询串与 Nginx/代理访问日志；{@code GET /v1/patients} 标记 deprecated 仅作兼容。
 */
@RestController
@RequestMapping("/v1/patients")
@Tag(name = "患者与手术申请", description = "患者主索引查询、患者详情与手术申请接收/查询")
public class PatientController {

    private final PatientQueryService patientQueryService;

    public PatientController(PatientQueryService patientQueryService) {
        this.patientQueryService = patientQueryService;
    }

    /** 兼容旧调用方；查询参数可能含 PHI，生产禁用。 */
    @GetMapping
    @Deprecated(since = "0.3.0")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_PATIENT_READ + "')")
    @Operation(operationId = "listPatients", summary = "分页查询患者摘要（已弃用，改用 POST /search）",
            deprecated = true)
    public PatientPage list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "patientNo", required = false) String patientNo,
            @RequestParam(name = "hisPatientNo", required = false) String hisPatientNo,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "inpatientNo", required = false) String inpatientNo,
            @RequestParam(name = "departmentId", required = false) String departmentId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return patientQueryService.list(
                new PatientQuery(page, size, hisPatientNo, patientNo, name, inpatientNo, departmentId),
                AuthPrincipals.from(jwt));
    }

    /** 医学检索：条件在请求体，不进入 URL/访问日志。 */
    @PostMapping("/search")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_PATIENT_READ + "')")
    @Operation(operationId = "searchPatients", summary = "检索患者（POST，条件在请求体）")
    public PatientPage search(@AuthenticationPrincipal Jwt jwt,
            @RequestBody PatientSearchRequest request) {
        int page = request.getPage() == null ? 1 : request.getPage();
        int size = request.getSize() == null ? 20 : request.getSize();
        return patientQueryService.list(new PatientQuery(page, size, request.getHisPatientNo(),
                request.getPatientNo(), request.getName(), request.getInpatientNo(),
                request.getDepartmentId()), AuthPrincipals.from(jwt));
    }

    @GetMapping("/{patientId}")
    @PreAuthorize("hasAuthority('" + M1Permissions.AUTHORITY_PATIENT_READ + "')")
    @Operation(operationId = "getPatient", summary = "查询患者摘要详情")
    public Patient get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("patientId") String patientId) {
        return patientQueryService.get(patientId, AuthPrincipals.from(jwt));
    }
}
