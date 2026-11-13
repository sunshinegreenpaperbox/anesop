package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.anesthesia.model.AcknowledgeAlertRequest;
import com.anesop.contracts.anesthesia.model.Alert;
import com.anesop.contracts.anesthesia.model.AlertEvaluationResult;
import com.anesop.contracts.anesthesia.model.AlertList;
import com.anesop.contracts.anesthesia.model.AlertRule;
import com.anesop.contracts.anesthesia.model.AlertRuleList;
import com.anesop.contracts.anesthesia.model.CreateResuscitationRequest;
import com.anesop.contracts.anesthesia.model.ResuscitationRecord;
import com.anesop.contracts.anesthesia.model.ResuscitationRecordList;
import com.anesop.contracts.anesthesia.model.UpdateAlertRuleRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.alert.AlertRuleService;
import com.anesop.m2.api.alert.AlertService;
import com.anesop.m2.api.rescue.ResuscitationService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 术中预警与抢救记录接口（契约 anesthesia.yaml 0.7.0，M3-M2-02）。
 *
 * <p>授权与数据范围：
 * <ul>
 *   <li>规则读取/历史版本 {@code m2:alert:read}，规则配置 {@code m2:alert:configure}
 *       （仅 SYS_ADMIN）；</li>
 *   <li>告警列表 {@code m2:alert:read}，手动评估 {@code m2:record:write}，
 *       确认/忽略 {@code m2:alert:ack}；</li>
 *   <li>抢救记录读取复用 {@code m2:record:read}，写入 {@code m2:rescue:write}；</li>
 *   <li>记录单/台次数据范围沿用 M3-M2-01：不存在或越界统一 404；告警状态冲突 409。</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/anesthesia")
@Tag(name = "麻醉记录", description = "术前访视、麻醉记录单、锁单与修订、术后随访")
public class AnesthesiaAlertController {

    private final AlertRuleService alertRuleService;
    private final AlertService alertService;
    private final ResuscitationService resuscitationService;

    public AnesthesiaAlertController(AlertRuleService alertRuleService, AlertService alertService,
            ResuscitationService resuscitationService) {
        this.alertRuleService = alertRuleService;
        this.alertService = alertService;
        this.resuscitationService = resuscitationService;
    }

    @GetMapping("/alert-rules")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_ALERT_READ + "')")
    @Operation(operationId = "listAlertRules", summary = "查询术中预警规则")
    public AlertRuleList listRules() {
        return alertRuleService.listRules();
    }

    @GetMapping("/alert-rules/{ruleId}/versions")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_ALERT_READ + "')")
    @Operation(operationId = "listAlertRuleVersions", summary = "查询预警规则历史版本")
    public AlertRuleList listRuleVersions(@PathVariable("ruleId") String ruleId) {
        return alertRuleService.listVersions(ruleId);
    }

    @PutMapping("/alert-rules/{ruleId}")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_ALERT_CONFIGURE + "')")
    @Operation(operationId = "updateAlertRule", summary = "配置预警规则（生成新版本）")
    public AlertRule updateRule(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("ruleId") String ruleId,
            @Valid @RequestBody UpdateAlertRuleRequest request,
            AuthRequestContext context) {
        return alertRuleService.updateRule(ruleId, request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/records/{recordId}/alerts")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_ALERT_READ + "')")
    @Operation(operationId = "listRecordAlerts", summary = "查询记录单告警（实时评估落库）")
    public AlertList listAlerts(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId) {
        return alertService.listAlerts(recordId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/records/{recordId}/alerts/evaluate")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_WRITE + "')")
    @Operation(operationId = "evaluateRecordAlerts", summary = "手动评估记录单告警（补偿）")
    public AlertEvaluationResult evaluateAlerts(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId, AuthRequestContext context) {
        return alertService.evaluate(recordId, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_ALERT_ACK + "')")
    @Operation(operationId = "acknowledgeAlert", summary = "确认或忽略告警")
    public Alert acknowledgeAlert(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("alertId") String alertId,
            @Valid @RequestBody AcknowledgeAlertRequest request,
            AuthRequestContext context) {
        return alertService.acknowledge(alertId, request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/records/{recordId}/resuscitations")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RECORD_READ + "')")
    @Operation(operationId = "listResuscitations", summary = "查询抢救记录")
    public ResuscitationRecordList listResuscitations(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId) {
        return resuscitationService.list(recordId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/records/{recordId}/resuscitations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_RESCUE_WRITE + "')")
    @Operation(operationId = "createResuscitation", summary = "新增抢救记录（append-only）")
    public ResuscitationRecord createResuscitation(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("recordId") String recordId,
            @Valid @RequestBody CreateResuscitationRequest request,
            AuthRequestContext context) {
        return resuscitationService.create(recordId, request, AuthPrincipals.from(jwt), context);
    }
}
