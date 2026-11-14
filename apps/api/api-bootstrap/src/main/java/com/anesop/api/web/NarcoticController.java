package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.drug.model.CabinetHandover;
import com.anesop.contracts.drug.model.CabinetHandoverList;
import com.anesop.contracts.drug.model.CreateCabinetHandoverRequest;
import com.anesop.contracts.drug.model.CreateDailyCloseRequest;
import com.anesop.contracts.drug.model.CreateNarcoticRegisterRequest;
import com.anesop.contracts.drug.model.DailyClose;
import com.anesop.contracts.drug.model.DepartmentList;
import com.anesop.contracts.drug.model.DrugBaselineList;
import com.anesop.contracts.drug.model.NarcoticRegister;
import com.anesop.contracts.drug.model.NarcoticRegisterList;
import com.anesop.contracts.drug.model.UpdateDrugBaselinesRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.narcotic.NarcoticService;
import com.anesop.m4.api.security.M4Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
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
 * 麻精五专接口（契约 drug.yaml 0.1.0，M4-M15-01）。
 *
 * <p>授权：全部端点需要 {@code m4:narcotic:manage}（V20 授予 SYS_ADMIN/SYS_PHARMACIST/
 * SYS_ANESTHESIOLOGIST），并按调用者科室数据范围限制（ALL 全院；SITE 院区子树；DEPT 授权
 * 科室；SELF 按本人所属科室推导；ROOM/无范围 403）。前端必须显式选择科室，服务端校验
 * 科室存在、启用且 {@code org_type=DEPT}。专册/交接/日结 append-only；双人签字必须不同人（400）；
 * 日结不平 409 且 details 给出期望值与实盘值；重复日结 409；药品未收录 422。
 */
@RestController
@RequestMapping("/v1/narcotic")
@Tag(name = "麻精五专", description = "专册登记、专柜交接、基数维护、日结/班结（双人签字、平账、append-only）")
public class NarcoticController {

    private final NarcoticService narcoticService;

    public NarcoticController(NarcoticService narcoticService) {
        this.narcoticService = narcoticService;
    }

    @GetMapping("/departments")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_NARCOTIC_MANAGE + "')")
    @Operation(operationId = "listNarcoticDepartments", summary = "查询五专可选科室（显式选择）")
    public DepartmentList listDepartments(@AuthenticationPrincipal Jwt jwt) {
        return narcoticService.listDepartments(AuthPrincipals.from(jwt));
    }

    @GetMapping("/registers")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_NARCOTIC_MANAGE + "')")
    @Operation(operationId = "listNarcoticRegisters", summary = "查询麻精专册登记")
    public NarcoticRegisterList listRegisters(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "departmentId", required = false) String departmentId,
            @RequestParam(name = "drugId", required = false) String drugId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return narcoticService.listRegisters(departmentId, drugId, from, to, limit,
                AuthPrincipals.from(jwt));
    }

    @PostMapping("/registers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_NARCOTIC_MANAGE + "')")
    @Operation(operationId = "createNarcoticRegister", summary = "登记麻精专册（append-only）")
    public NarcoticRegister createRegister(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateNarcoticRegisterRequest request, AuthRequestContext context) {
        return narcoticService.createRegister(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/cabinet-handovers")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_NARCOTIC_MANAGE + "')")
    @Operation(operationId = "listCabinetHandovers", summary = "查询专柜交接记录")
    public CabinetHandoverList listHandovers(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "cabinetCode", required = false) String cabinetCode,
            @RequestParam(name = "departmentId", required = false) String departmentId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return narcoticService.listHandovers(cabinetCode, departmentId, limit,
                AuthPrincipals.from(jwt));
    }

    @PostMapping("/cabinet-handovers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_NARCOTIC_MANAGE + "')")
    @Operation(operationId = "createCabinetHandover", summary = "登记专柜交接（append-only）")
    public CabinetHandover createHandover(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCabinetHandoverRequest request, AuthRequestContext context) {
        return narcoticService.createHandover(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/baselines")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_NARCOTIC_MANAGE + "')")
    @Operation(operationId = "listDrugBaselines", summary = "查询麻精基数")
    public DrugBaselineList listBaselines(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "cabinetCode", required = false) String cabinetCode,
            @RequestParam(name = "departmentId", required = false) String departmentId) {
        return narcoticService.listBaselines(cabinetCode, departmentId, AuthPrincipals.from(jwt));
    }

    @PutMapping("/baselines")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_NARCOTIC_MANAGE + "')")
    @Operation(operationId = "updateDrugBaselines", summary = "维护麻精基数（品种 + 柜 upsert）")
    public DrugBaselineList updateBaselines(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateDrugBaselinesRequest request, AuthRequestContext context) {
        return narcoticService.updateBaselines(request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/daily-closes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_NARCOTIC_MANAGE + "')")
    @Operation(operationId = "createDailyClose", summary = "日结/班结（平账校验，append-only）")
    public DailyClose createDailyClose(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateDailyCloseRequest request, AuthRequestContext context) {
        return narcoticService.createDailyClose(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/daily-closes/{closeId}")
    @PreAuthorize("hasAuthority('" + M4Permissions.AUTHORITY_NARCOTIC_MANAGE + "')")
    @Operation(operationId = "getDailyClose", summary = "查询日结/班结详情")
    public DailyClose getDailyClose(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("closeId") String closeId) {
        return narcoticService.getDailyClose(closeId, AuthPrincipals.from(jwt));
    }
}
