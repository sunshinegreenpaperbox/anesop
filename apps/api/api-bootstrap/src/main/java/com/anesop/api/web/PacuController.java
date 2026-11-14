package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.anesthesia.model.CreatePacuRecordRequest;
import com.anesop.contracts.anesthesia.model.PacuAssessment;
import com.anesop.contracts.anesthesia.model.PacuAssessmentList;
import com.anesop.contracts.anesthesia.model.PacuDischarge;
import com.anesop.contracts.anesthesia.model.PacuDischargeRequest;
import com.anesop.contracts.anesthesia.model.PacuRecord;
import com.anesop.contracts.anesthesia.model.PacuRecordList;
import com.anesop.contracts.anesthesia.model.RecordPacuAssessmentRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.pacu.PacuService;
import com.anesop.m2.api.security.M2Permissions;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * PACU 复苏记录与转出接口（契约 anesthesia.yaml 0.9.0，M3-M10-01）。
 *
 * <p>授权：查询需要 {@code m2:pacu:read}，入室/评估/转出需要 {@code m2:pacu:write}
 * （V18 授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST/SYS_NURSE_ANESTHETIST）。数据范围复用
 * 记录单/台次可见性：按记录单列表越权 403（集合语义）、单对象越界 404、入室时记录单不可见
 * 422。Aldrete 总分由服务端计算；转出前置由服务端统一评估（未达标 409 并逐项列出条件码）。
 */
@RestController
@RequestMapping("/v1/anesthesia")
@Tag(name = "麻醉记录", description = "术前访视、麻醉记录单、锁单与修订、术后随访")
public class PacuController {

    private final PacuService pacuService;

    public PacuController(PacuService pacuService) {
        this.pacuService = pacuService;
    }

    @GetMapping("/pacu-records")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PACU_READ + "')")
    @Operation(operationId = "listPacuRecords", summary = "按麻醉记录单查询复苏记录")
    public PacuRecordList list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "recordId") String recordId) {
        return pacuService.listByRecord(recordId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/pacu-records")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PACU_WRITE + "')")
    @Operation(operationId = "admitPacuRecord", summary = "入室（创建复苏记录）")
    public PacuRecord admit(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePacuRecordRequest request,
            AuthRequestContext context) {
        return pacuService.admit(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/pacu-records/{pacuId}")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PACU_READ + "')")
    @Operation(operationId = "getPacuRecord", summary = "查询复苏记录详情")
    public PacuRecord get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("pacuId") String pacuId) {
        return pacuService.get(pacuId, AuthPrincipals.from(jwt));
    }

    @GetMapping("/pacu-records/{pacuId}/assessments")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PACU_READ + "')")
    @Operation(operationId = "listPacuAssessments", summary = "查询 Aldrete 评估时间序列")
    public PacuAssessmentList listAssessments(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("pacuId") String pacuId) {
        return pacuService.listAssessments(pacuId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/pacu-records/{pacuId}/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PACU_WRITE + "')")
    @Operation(operationId = "recordPacuAssessment", summary = "记录 Aldrete 评估（append-only）")
    public PacuAssessment recordAssessment(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("pacuId") String pacuId,
            @Valid @RequestBody RecordPacuAssessmentRequest request,
            AuthRequestContext context) {
        return pacuService.recordAssessment(pacuId, request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/pacu-records/{pacuId}/discharge")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PACU_READ + "')")
    @Operation(operationId = "getPacuDischarge", summary = "查询转出记录")
    public PacuDischarge getDischarge(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("pacuId") String pacuId) {
        return pacuService.getDischarge(pacuId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/pacu-records/{pacuId}/discharge")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_PACU_WRITE + "')")
    @Operation(operationId = "dischargePacuRecord", summary = "转出（校验 Aldrete 阈值与交接）")
    public PacuDischarge discharge(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("pacuId") String pacuId,
            @Valid @RequestBody PacuDischargeRequest request,
            AuthRequestContext context) {
        return pacuService.discharge(pacuId, request, AuthPrincipals.from(jwt), context);
    }
}
