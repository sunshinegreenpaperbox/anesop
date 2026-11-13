package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.anesthesia.model.AdministerDrugRequest;
import com.anesop.contracts.anesthesia.model.AnesthesiaRecord;
import com.anesop.contracts.anesthesia.model.AnesthesiaRecordList;
import com.anesop.contracts.anesthesia.model.CreateAnesthesiaRecordRequest;
import com.anesop.contracts.anesthesia.model.CreateRecordEventRequest;
import com.anesop.contracts.anesthesia.model.DrugAdministration;
import com.anesop.contracts.anesthesia.model.DrugAdministrationList;
import com.anesop.contracts.anesthesia.model.FluidRecord;
import com.anesop.contracts.anesthesia.model.FluidRecordList;
import com.anesop.contracts.anesthesia.model.IntakeOutputSummary;
import com.anesop.contracts.anesthesia.model.RecordEvent;
import com.anesop.contracts.anesthesia.model.RecordEventList;
import com.anesop.contracts.anesthesia.model.RecordFluidRequest;
import com.anesop.contracts.anesthesia.model.UpdateAnesthesiaRecordRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.record.AnesthesiaRecordService;
import com.anesop.m2.api.security.M2Permissions;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * 麻醉记录单与时间轴接口（契约 anesthesia.yaml 0.5.0，M3-M2-01）。
 *
 * <p>授权：查询需要 {@code m2:record:read}，写入需要 {@code m2:record:write}（V13 授予
 * SYS_ADMIN/SYS_ANESTHESIOLOGIST/SYS_NURSE_ANESTHETIST）。数据范围复用台次/申请可见性：
 * 按台次列表越权 403（集合语义）、单记录越界 404、创建时台次不可见 422；一个台次唯一记录单
 * （重复创建 409）。时间轴/用药/出入量均为 append-only，麻精类用药必须双人核对。
 */
@RestController
@RequestMapping("/v1/anesthesia")
@Tag(name = "麻醉记录", description = "术前访视、麻醉记录单、锁单与修订、术后随访")
public class AnesthesiaRecordController {

    private final AnesthesiaRecordService recordService;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例服务由容器管理，控制器不修改其内部状态")
    public AnesthesiaRecordController(AnesthesiaRecordService recordService) {
        this.recordService = recordService;
    }

    @GetMapping("/records")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_READ + "')")
    @Operation(operationId = "listAnesthesiaRecords", summary = "按台次查询麻醉记录单")
    public AnesthesiaRecordList list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "scheduleId") String scheduleId) {
        return recordService.listBySchedule(scheduleId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/records")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_WRITE + "')")
    @Operation(operationId = "createAnesthesiaRecord", summary = "创建麻醉记录单（按台次）")
    public AnesthesiaRecord create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateAnesthesiaRecordRequest request,
            AuthRequestContext context) {
        return recordService.create(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/records/{recordId}")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_READ + "')")
    @Operation(operationId = "getAnesthesiaRecord", summary = "查询麻醉记录单")
    public AnesthesiaRecord get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId) {
        return recordService.get(recordId, AuthPrincipals.from(jwt));
    }

    @PutMapping("/records/{recordId}")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_WRITE + "')")
    @Operation(operationId = "updateAnesthesiaRecord", summary = "保存麻醉记录单")
    public AnesthesiaRecord update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId,
            @Valid @RequestBody UpdateAnesthesiaRecordRequest request,
            AuthRequestContext context) {
        return recordService.update(recordId, request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/records/{recordId}/events")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_READ + "')")
    @Operation(operationId = "listRecordEvents", summary = "查询记录单时间轴事件")
    public RecordEventList listEvents(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId) {
        return recordService.listEvents(recordId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/records/{recordId}/events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_WRITE + "')")
    @Operation(operationId = "appendRecordEvent", summary = "追加时间轴事件（append-only）")
    public RecordEvent appendEvent(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId,
            @Valid @RequestBody CreateRecordEventRequest request,
            AuthRequestContext context) {
        return recordService.appendEvent(recordId, request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/records/{recordId}/drugs")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_READ + "')")
    @Operation(operationId = "listDrugAdministrations", summary = "查询用药记录")
    public DrugAdministrationList listDrugs(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId) {
        return recordService.listDrugs(recordId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/records/{recordId}/drugs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_WRITE + "')")
    @Operation(operationId = "recordDrugAdministration", summary = "记录用药（麻精类双人核对）")
    public DrugAdministration recordDrug(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId,
            @Valid @RequestBody AdministerDrugRequest request,
            AuthRequestContext context) {
        return recordService.recordDrug(recordId, request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/records/{recordId}/fluids")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_READ + "')")
    @Operation(operationId = "listFluidRecords", summary = "查询输液/出入量明细")
    public FluidRecordList listFluids(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId) {
        return recordService.listFluids(recordId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/records/{recordId}/fluids")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_WRITE + "')")
    @Operation(operationId = "recordFluidRecord", summary = "记录输液/出入量")
    public FluidRecord recordFluid(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId,
            @Valid @RequestBody RecordFluidRequest request,
            AuthRequestContext context) {
        return recordService.recordFluid(recordId, request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/records/{recordId}/io-summary")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_READ + "')")
    @Operation(operationId = "getIntakeOutputSummary", summary = "查询出入量汇总")
    public IntakeOutputSummary ioSummary(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId) {
        return recordService.ioSummary(recordId, AuthPrincipals.from(jwt));
    }
}
