package com.anesop.api.web;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.contracts.anesthesia.model.ConsentRecord;
import com.anesop.contracts.anesthesia.model.ConsentRecordList;
import com.anesop.contracts.anesthesia.model.ConsentTemplate;
import com.anesop.contracts.anesthesia.model.ConsentTemplateList;
import com.anesop.contracts.anesthesia.model.CreateConsentRequest;
import com.anesop.contracts.anesthesia.model.DeclineConsentRequest;
import com.anesop.contracts.anesthesia.model.RevokeConsentRequest;
import com.anesop.contracts.anesthesia.model.SignConsentRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.consent.ConsentService;
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
 * 知情同意接口（契约 anesthesia.yaml 0.4.0，M2-M5-01）。
 *
 * <p>授权：查询需要 {@code m2:consent:read}，创建/签署/拒绝/撤销需要 {@code m2:consent:write}
 * （V11 授予 SYS_ADMIN/SYS_ANESTHESIOLOGIST/SYS_NURSE_ANESTHETIST）。数据范围复用 M1
 * 申请可见性：模板列表不涉及患者数据；按申请列表越权 403（集合语义）；单记录越界 404；
 * 创建时申请不可见 422。CA 不可用（EXT-05）：签署返回 caStatus=SIMULATED 的占位签名。
 */
@RestController
@RequestMapping("/v1/anesthesia")
@Tag(name = "麻醉记录", description = "术前访视、麻醉记录单、锁单与修订、术后随访")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    @GetMapping("/consent-templates")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_CONSENT_READ + "')")
    @Operation(operationId = "listConsentTemplates", summary = "查询知情同意模板与版本")
    public ConsentTemplateList listTemplates(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "templateCode", required = false) String templateCode,
            @RequestParam(name = "effectiveOnly", required = false) Boolean effectiveOnly,
            @RequestParam(name = "includeRetired", required = false) Boolean includeRetired) {
        return consentService.listTemplates(templateCode, effectiveOnly, includeRetired,
                AuthPrincipals.from(jwt));
    }

    @PostMapping("/consent-templates/{templateId}/retire")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_CONSENT_WRITE + "')")
    @Operation(operationId = "retireConsentTemplate", summary = "停用知情同意模板版本")
    public ConsentTemplate retireTemplate(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("templateId") String templateId,
            AuthRequestContext context) {
        return consentService.retireTemplate(templateId, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/consents")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_CONSENT_READ + "')")
    @Operation(operationId = "listConsents", summary = "按手术申请查询知情同意记录")
    public ConsentRecordList list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "applicationId") String applicationId) {
        return consentService.listByApplication(applicationId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/consents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_CONSENT_WRITE + "')")
    @Operation(operationId = "createConsent", summary = "创建知情同意记录（待签署）")
    public ConsentRecord create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateConsentRequest request,
            AuthRequestContext context) {
        return consentService.create(request, AuthPrincipals.from(jwt), context);
    }

    @GetMapping("/consents/{consentId}")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_CONSENT_READ + "')")
    @Operation(operationId = "getConsent", summary = "查询知情同意记录详情")
    public ConsentRecord get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("consentId") String consentId) {
        return consentService.get(consentId, AuthPrincipals.from(jwt));
    }

    @PostMapping("/consents/{consentId}/sign")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_CONSENT_WRITE + "')")
    @Operation(operationId = "signConsent", summary = "签署知情同意（CA 占位）")
    public ConsentRecord sign(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("consentId") String consentId,
            @Valid @RequestBody SignConsentRequest request,
            AuthRequestContext context) {
        return consentService.sign(consentId, request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/consents/{consentId}/decline")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_CONSENT_WRITE + "')")
    @Operation(operationId = "declineConsent", summary = "拒绝知情同意")
    public ConsentRecord decline(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("consentId") String consentId,
            @Valid @RequestBody DeclineConsentRequest request,
            AuthRequestContext context) {
        return consentService.decline(consentId, request, AuthPrincipals.from(jwt), context);
    }

    @PostMapping("/consents/{consentId}/revoke")
    @PreAuthorize("hasAuthority('" + M2Permissions.AUTHORITY_CONSENT_WRITE + "')")
    @Operation(operationId = "revokeConsent", summary = "撤销已签署的知情同意")
    public ConsentRecord revoke(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("consentId") String consentId,
            @Valid @RequestBody RevokeConsentRequest request,
            AuthRequestContext context) {
        return consentService.revoke(consentId, request, AuthPrincipals.from(jwt), context);
    }
}
