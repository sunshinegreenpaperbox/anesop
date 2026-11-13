package com.anesop.m2.impl.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.ConsentRecord;
import com.anesop.contracts.anesthesia.model.ConsentRecordList;
import com.anesop.contracts.anesthesia.model.ConsentTemplateList;
import com.anesop.contracts.anesthesia.model.CreateConsentRequest;
import com.anesop.contracts.anesthesia.model.DeclineConsentRequest;
import com.anesop.contracts.anesthesia.model.RevokeConsentRequest;
import com.anesop.contracts.anesthesia.model.SignConsentRequest;
import com.anesop.contracts.scheduling.model.PatientSnapshot;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.application.SurgeryApplicationService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.persistence.entity.ConsentRecordEntity;
import com.anesop.m2.impl.persistence.entity.ConsentTemplateEntity;
import com.anesop.m2.impl.persistence.mapper.ConsentRecordMapper;
import com.anesop.m2.impl.persistence.mapper.ConsentTemplateMapper;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

/**
 * 知情同意服务单测（M2-M5-01）：模板版本冻结、签署状态机、CA 占位签名、数据范围语义与审计。
 *
 * <p>Mock 掉 mapper 与 M1 申请服务，断言可观察行为（返回状态/异常/落库字段/审计动作），
 * 不复制实现内部的哈希输入拼接细节（集成测试校验摘要持久化与不可改写）。
 */
@ExtendWith(MockitoExtension.class)
class ConsentServiceImplTest {

    private static final UUID APPLICATION_UUID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final String APPLICATION_ID = APPLICATION_UUID.toString();
    private static final UUID PATIENT_UUID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID TEMPLATE_UUID = UUID.fromString("b1000000-0000-4000-8000-000000000002");
    private static final UUID CONSENT_UUID = UUID.fromString("17000000-0000-4000-8000-000000000001");
    private static final String CONSENT_ID = CONSENT_UUID.toString();
    private static final Instant NOW = Instant.parse("2026-12-01T00:20:00Z");

    private static final AuthPrincipal CALLER =
            new AuthPrincipal(UUID.fromString("e0000000-0000-4000-8000-000000000002"),
                    "synth_anesthesiologist", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-0001");

    @Mock
    private ConsentRecordMapper recordMapper;
    @Mock
    private ConsentTemplateMapper templateMapper;
    @Mock
    private SurgeryApplicationService applicationService;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private ClinicalFailureAuditor failureAuditor;

    private ConsentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConsentServiceImpl(recordMapper, templateMapper, applicationService,
                auditWriter, failureAuditor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // -------------------------------------------------------------- templates --

    @Test
    void listTemplatesMapsVersions() {
        when(templateMapper.selectList(any())).thenReturn(List.of(template(2),
                templateEntity(1, NOW.minusSeconds(86_400))));

        ConsentTemplateList result = service.listTemplates("ANES_GENERAL", null, null, CALLER);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getVersion()).isEqualTo(2);
        assertThat(result.getItems().get(0).getTemplateCode()).isEqualTo("ANES_GENERAL");
        assertThat(result.getItems().get(1).getVersion()).isEqualTo(1);
    }

    // ----------------------------------------------------------------- create --

    @Test
    void createStartsWaitingSignAndAuditsWithoutSignerName() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(template(2));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.insertRecord(any())).thenReturn(1);

        ConsentRecord result = service.create(createRequest(), CALLER, CONTEXT);

        assertThat(result.getStatus()).isEqualTo(ConsentRecord.StatusEnum.WAITING_SIGN);
        assertThat(result.getCaStatus()).isEqualTo(ConsentRecord.CaStatusEnum.PENDING);
        assertThat(result.getTemplateVersion()).isEqualTo(2);
        assertThat(result.getTemplateCode()).isEqualTo("ANES_GENERAL");
        assertThat(result.getSignerName()).isEqualTo("演示患者");
        assertThat(result.getSignatureRef()).isNull();
        assertThat(result.getVersion()).isEqualTo(1);

        ArgumentCaptor<ConsentRecordEntity> inserted = ArgumentCaptor.forClass(ConsentRecordEntity.class);
        verify(recordMapper).insertRecord(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo("WAITING_SIGN");
        assertThat(inserted.getValue().getTemplateVersion()).isEqualTo(2);

        ArgumentCaptor<Map<String, Object>> detail = auditDetail("CONSENT_CREATE");
        assertThat(detail.getValue())
                .containsEntry("applicationId", APPLICATION_ID)
                .containsEntry("status", "WAITING_SIGN")
                .doesNotContainKeys("signerName", "signatureHash", "contentText", "reason");
    }

    @Test
    void createRejectsInvisibleApplicationAsUnprocessable() {
        when(applicationService.get(APPLICATION_ID, CALLER))
                .thenThrow(new NoSuchElementException("不可见"));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M2UnprocessableException.class);
        verify(recordMapper, never()).insertRecord(any());
    }

    @Test
    void createRejectsTemplateVersionMismatch() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(templateEntity(1, NOW.minusSeconds(60)));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M2UnprocessableException.class);
    }

    @Test
    void createRejectsNotYetEffectiveTemplate() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(templateMapper.selectById(TEMPLATE_UUID))
                .thenReturn(templateEntity(2, NOW.plusSeconds(86_400)));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M2UnprocessableException.class);
    }

    @Test
    void createRejectsDuplicateActiveConsent() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(template(2));
        when(recordMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
        verify(recordMapper, never()).insertRecord(any());
    }

    @Test
    void createMapsConcurrentDuplicateToConflict() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(template(2));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.insertRecord(any())).thenThrow(new DuplicateKeyException("uk_consent_record_active"));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void createRejectsInactiveApplication() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("CANCELLED"));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void createRejectsInvalidIdentifiersAndBlankSigner() {
        CreateConsentRequest invalidApplication = createRequest();
        invalidApplication.setApplicationId("not-a-uuid");
        assertThatThrownBy(() -> service.create(invalidApplication, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);

        CreateConsentRequest blankSigner = createRequest();
        blankSigner.setSignerName("   ");
        assertThatThrownBy(() -> service.create(blankSigner, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);

        CreateConsentRequest missingVersion = createRequest();
        missingVersion.setTemplateVersion(0);
        assertThatThrownBy(() -> service.create(missingVersion, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
    }

    // ------------------------------------------------------------------- sign --

    @Test
    void signGeneratesSimulatedSignatureAndAudits() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(waitingSignEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(template(2));
        when(recordMapper.sign(eq(CONSENT_UUID), eq(1L), eq(NOW), eq(CALLER.userId()),
                anyString(), anyString(), eq("SIMULATED"))).thenReturn(1);

        ConsentRecord result = service.sign(CONSENT_ID, signRequest(1), CALLER, CONTEXT);

        assertThat(result.getStatus()).isEqualTo(ConsentRecord.StatusEnum.SIGNED);
        assertThat(result.getCaStatus()).isEqualTo(ConsentRecord.CaStatusEnum.SIMULATED);
        assertThat(result.getSignatureRef()).startsWith("SIM-CA-");
        assertThat(result.getSignatureHash()).hasSize(64).matches("^[0-9a-f]{64}$");
        assertThat(result.getSignedAt()).isNotNull();
        assertThat(result.getVersion()).isEqualTo(2);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(recordMapper).sign(eq(CONSENT_UUID), eq(1L), eq(NOW), eq(CALLER.userId()),
                anyString(), hash.capture(), eq("SIMULATED"));
        assertThat(hash.getValue()).doesNotContain("演示患者").doesNotContain("麻醉风险告知");

        ArgumentCaptor<Map<String, Object>> detail = auditDetail("CONSENT_SIGN");
        assertThat(detail.getValue())
                .containsEntry("status", "SIGNED")
                .containsEntry("caStatus", "SIMULATED")
                .doesNotContainKeys("signerName", "signatureHash");
    }

    @Test
    void signRejectsRepeatedSigning() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(signedEntity(2));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(() -> service.sign(CONSENT_ID, signRequest(2), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("不可重复签署");
    }

    @Test
    void signRejectsDeclinedConsent() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(entity("DECLINED", 2, null));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(() -> service.sign(CONSENT_ID, signRequest(2), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("不可再签署");
    }

    @Test
    void signRejectsStaleVersion() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(waitingSignEntity(3));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(() -> service.sign(CONSENT_ID, signRequest(1), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("版本已过期");
    }

    @Test
    void signMapsConcurrentUpdateToConflict() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(waitingSignEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(template(2));
        when(recordMapper.sign(any(), any(), any(), any(), anyString(), anyString(), anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.sign(CONSENT_ID, signRequest(1), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void signRejectsMissingConsent() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(null);

        assertThatThrownBy(() -> service.sign(CONSENT_ID, signRequest(1), CALLER, CONTEXT))
                .isInstanceOf(NoSuchElementException.class);
    }

    // -------------------------------------------------------- decline/revoke --

    @Test
    void declineRecordsReasonAndAuditsWithoutReasonText() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(waitingSignEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(recordMapper.decline(CONSENT_UUID, 1L, "演示原因：患者要求暂缓手术（合成数据）", NOW,
                CALLER.userId())).thenReturn(1);

        ConsentRecord result = service.decline(CONSENT_ID, declineRequest(1), CALLER, CONTEXT);

        assertThat(result.getStatus()).isEqualTo(ConsentRecord.StatusEnum.DECLINED);
        assertThat(result.getDeclineReason()).isEqualTo("演示原因：患者要求暂缓手术（合成数据）");
        assertThat(result.getDeclinedAt()).isNotNull();
        assertThat(result.getVersion()).isEqualTo(2);

        ArgumentCaptor<Map<String, Object>> detail = auditDetail("CONSENT_DECLINE");
        assertThat(detail.getValue()).doesNotContainKeys("reason", "declineReason");
    }

    @Test
    void declineRejectsSignedConsent() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(signedEntity(2));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(() -> service.decline(CONSENT_ID, declineRequest(2), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("不可再拒绝");
    }

    @Test
    void revokeSignedConsentRecordsActorAndReason() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(signedEntity(2));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(recordMapper.revoke(CONSENT_UUID, 2L, "演示原因：患者术前改变意愿（合成数据）", NOW,
                CALLER.userId())).thenReturn(1);

        ConsentRecord result = service.revoke(CONSENT_ID, revokeRequest(2), CALLER, CONTEXT);

        assertThat(result.getStatus()).isEqualTo(ConsentRecord.StatusEnum.REVOKED);
        assertThat(result.getRevokeReason()).isEqualTo("演示原因：患者术前改变意愿（合成数据）");
        assertThat(result.getRevokedBy()).isEqualTo(CALLER.userId().toString());
        assertThat(result.getSignedAt()).isNotNull();
        assertThat(result.getVersion()).isEqualTo(3);
        verify(auditWriter).record(eq("CONSENT_REVOKE"), eq("CONSENT_RECORD"), eq(CONSENT_ID),
                eq(true), any(), eq(CALLER.userId()), eq(CALLER.username()), eq(CONTEXT));
    }

    @Test
    void revokeRejectsConsentThatIsNotSigned() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(waitingSignEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(() -> service.revoke(CONSENT_ID, revokeRequest(1), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("仅已签署");
    }

    @Test
    void revokeRejectsRevokedConsentAgain() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(entity("REVOKED", 3, "演示原因"));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(() -> service.revoke(CONSENT_ID, revokeRequest(3), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    // ------------------------------------------------------- template retire --
    @Test
    void listTemplatesExcludesRetiredByDefaultAndCanIncludeThem() {
        when(templateMapper.selectList(any())).thenReturn(List.of(template(2)));

        service.listTemplates(null, null, null, CALLER);
        service.listTemplates(null, null, true, CALLER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ConsentTemplateEntity>>
                wrapper = ArgumentCaptor.forClass(
                        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(templateMapper, org.mockito.Mockito.times(2)).selectList(wrapper.capture());
        assertThat(wrapper.getAllValues().get(0).getSqlSegment()).contains("status");
        assertThat(wrapper.getAllValues().get(1).getSqlSegment()).doesNotContain("status");
    }

    @Test
    void retireTemplateMarksRetiredAndAuditsWithoutContent() {
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(template(2));
        when(templateMapper.retire(eq(TEMPLATE_UUID), eq(NOW), eq(CALLER.userId()))).thenReturn(1);

        com.anesop.contracts.anesthesia.model.ConsentTemplate result =
                service.retireTemplate(TEMPLATE_UUID.toString(), CALLER, CONTEXT);

        assertThat(result.getStatus())
                .isEqualTo(com.anesop.contracts.anesthesia.model.ConsentTemplate.StatusEnum.RETIRED);
        assertThat(result.getRetiredAt()).isNotNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(
                (Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(auditWriter).record(eq("CONSENT_TEMPLATE_RETIRE"), eq("CONSENT_TEMPLATE"),
                eq(TEMPLATE_UUID.toString()), eq(true), detail.capture(), eq(CALLER.userId()),
                eq(CALLER.username()), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("status", "RETIRED")
                .containsEntry("templateCode", "ANES_GENERAL")
                .doesNotContainKeys("contentText", "title");
    }

    @Test
    void retireTemplateRejectsAlreadyRetiredOrMissing() {
        ConsentTemplateEntity retired = template(2);
        retired.setStatus("RETIRED");
        retired.setRetiredAt(NOW.minusSeconds(60));
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(retired);
        assertThatThrownBy(() -> service.retireTemplate(TEMPLATE_UUID.toString(), CALLER, CONTEXT))
                .isInstanceOf(M2UnprocessableException.class)
                .hasMessageContaining("已停用");

        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(null);
        assertThatThrownBy(() -> service.retireTemplate(TEMPLATE_UUID.toString(), CALLER, CONTEXT))
                .isInstanceOf(NoSuchElementException.class);

        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(template(2));
        when(templateMapper.retire(eq(TEMPLATE_UUID), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.retireTemplate(TEMPLATE_UUID.toString(), CALLER, CONTEXT))
                .isInstanceOf(M2UnprocessableException.class);
    }

    @Test
    void createRejectsRetiredTemplate() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        ConsentTemplateEntity retired = template(2);
        retired.setStatus("RETIRED");
        retired.setRetiredAt(NOW.minusSeconds(60));
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(retired);

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M2UnprocessableException.class)
                .hasMessageContaining("已停用");
        verify(recordMapper, never()).insertRecord(any());
    }

    // ------------------------------------------------- sign guards & failures --
    @Test
    void signRejectsInactiveApplicationAndAuditsFailure() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(waitingSignEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("CANCELLED"));

        assertThatThrownBy(() -> service.sign(CONSENT_ID, signRequest(1), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("申请状态不允许");
        verify(recordMapper, never()).sign(any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<Map<String, Object>> detail = failureDetail("CONSENT_SIGN");
        assertThat(detail.getValue()).containsEntry("consentId", CONSENT_ID)
                .containsEntry("error", "STATE_CONFLICT")
                .doesNotContainKeys("signerName", "reason", "signatureHash");
    }

    @Test
    void signRejectsExistingUnrevokedSignedConsent() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(waitingSignEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(recordMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.sign(CONSENT_ID, signRequest(1), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("已有未撤销");
        verify(recordMapper, never()).sign(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void signMapsConcurrentSignedUniquenessViolationToConflict() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(waitingSignEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(template(2));
        when(recordMapper.sign(any(), any(), any(), any(), anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("uk_consent_record_signed_application"));

        assertThatThrownBy(() -> service.sign(CONSENT_ID, signRequest(1), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("已有未撤销");
    }

    @Test
    void declineAndRevokeFailuresAreAudited() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(signedEntity(2));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        assertThatThrownBy(() -> service.decline(CONSENT_ID, declineRequest(2), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
        assertThat(failureDetail("CONSENT_DECLINE").getValue())
                .containsEntry("error", "STATE_CONFLICT")
                .doesNotContainKeys("reason", "signerName");

        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(waitingSignEntity(1));
        assertThatThrownBy(() -> service.revoke(CONSENT_ID, revokeRequest(1), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
        assertThat(failureDetail("CONSENT_REVOKE").getValue())
                .containsEntry("error", "STATE_CONFLICT")
                .doesNotContainKeys("reason", "signerName");
    }

    // ------------------------------------------------------------ data scope --

    @Test
    void listByApplicationUsesForbiddenForOutOfScopeApplication() {
        when(applicationService.get(APPLICATION_ID, CALLER))
                .thenThrow(new NoSuchElementException("不可见"));

        assertThatThrownBy(() -> service.listByApplication(APPLICATION_ID, CALLER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listByApplicationRejectsInvalidIdentifier() {
        assertThatThrownBy(() -> service.listByApplication("invalid", CALLER))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void getPropagatesNotFoundForOutOfScopeConsent() {
        when(recordMapper.selectById(CONSENT_UUID)).thenReturn(waitingSignEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER))
                .thenThrow(new NoSuchElementException("不可见"));

        assertThatThrownBy(() -> service.get(CONSENT_ID, CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listByApplicationReturnsApplicationSummaryAndItems() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(recordMapper.selectList(any())).thenReturn(List.of(waitingSignEntity(1)));
        when(templateMapper.selectById(TEMPLATE_UUID)).thenReturn(template(2));

        ConsentRecordList result = service.listByApplication(APPLICATION_ID, CALLER);

        assertThat(result.getApplication().getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(result.getApplication().getPatientSnapshot().getPatientName()).isEqualTo("演示患者");
        assertThat(result.getItems()).hasSize(1);
    }

    // --------------------------------------------------------------- helpers --

    private ArgumentCaptor<Map<String, Object>> failureDetail(String action) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(failureAuditor).recordFailure(eq(action), eq("CONSENT_RECORD"), eq(CONSENT_ID),
                captor.capture(), eq(CALLER), eq(CONTEXT));
        return captor;
    }

    private ArgumentCaptor<Map<String, Object>> auditDetail(String action) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(auditWriter).record(eq(action), eq("CONSENT_RECORD"), anyString(), eq(true),
                captor.capture(), eq(CALLER.userId()), eq(CALLER.username()), eq(CONTEXT));
        return captor;
    }

    private SurgeryApplication application(String status) {
        SurgeryApplication application = new SurgeryApplication();
        application.setApplicationId(APPLICATION_ID);
        application.setSurgeryName("演示腹腔镜胆囊切除术（合成）");
        application.setDepartmentName("演示手术科室（合成）");
        application.setStatus(SurgeryApplication.StatusEnum.fromValue(status));

        PatientSnapshot patient = new PatientSnapshot();
        patient.setPatientId(PATIENT_UUID.toString());
        patient.setPatientNo("SYNTH-HIS-P-0001");
        patient.setHisPatientNo("SYNTH-HIS-P-0001");
        patient.setName("演示患者");
        patient.setGender(PatientSnapshot.GenderEnum.FEMALE);
        patient.setBirthDate(LocalDate.of(1972, 5, 18));
        patient.setAge(54);
        patient.setInpatientNoMasked("SYNT****0001");
        patient.setDepartmentId("f1000000-0000-4000-8000-000000000003");
        patient.setDepartmentName("演示手术科室（合成）");
        patient.setSiteId("f1000000-0000-4000-8000-000000000001");
        application.setPatient(patient);
        return application;
    }

    private ConsentTemplateEntity template(int version) {
        return templateEntity(version, NOW.minusSeconds(86_400));
    }

    private ConsentTemplateEntity templateEntity(int version, Instant effectiveAt) {
        ConsentTemplateEntity entity = new ConsentTemplateEntity();
        entity.setId(TEMPLATE_UUID);
        entity.setTemplateCode("ANES_GENERAL");
        entity.setVersion(version);
        entity.setTitle("麻醉知情同意书（演示模板 v" + version + "）");
        entity.setContentText("【SYNTHETIC 演示模板】患者已了解麻醉风险并同意实施。");
        entity.setEffectiveAt(effectiveAt);
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(NOW.minusSeconds(172_800));
        entity.setUpdatedAt(NOW.minusSeconds(172_800));
        return entity;
    }

    private ConsentRecordEntity waitingSignEntity(long version) {
        return entity("WAITING_SIGN", version, null);
    }

    private ConsentRecordEntity signedEntity(long version) {
        ConsentRecordEntity entity = entity("SIGNED", version, null);
        entity.setSignedAt(NOW.minusSeconds(60));
        entity.setSignedBy(CALLER.userId());
        entity.setSignatureRef("SIM-CA-" + CONSENT_UUID);
        entity.setSignatureHash("a".repeat(64));
        entity.setCaStatus("SIMULATED");
        return entity;
    }

    private ConsentRecordEntity entity(String status, long version, String revokeReason) {
        ConsentRecordEntity entity = new ConsentRecordEntity();
        entity.setId(CONSENT_UUID);
        entity.setApplicationId(APPLICATION_UUID);
        entity.setPatientId(PATIENT_UUID);
        entity.setTemplateId(TEMPLATE_UUID);
        entity.setTemplateVersion(2);
        entity.setStatus(status);
        entity.setSignerName("演示患者");
        entity.setSignerRelation("SELF");
        entity.setSignMethod("ELECTRONIC");
        entity.setCaStatus("PENDING");
        entity.setRevokeReason(revokeReason);
        entity.setCreatedAt(NOW.minusSeconds(600));
        entity.setCreatedBy(CALLER.userId());
        entity.setUpdatedAt(NOW.minusSeconds(600));
        entity.setUpdatedBy(CALLER.userId());
        entity.setVersion(version);
        return entity;
    }

    private CreateConsentRequest createRequest() {
        CreateConsentRequest request = new CreateConsentRequest();
        request.setApplicationId(APPLICATION_ID);
        request.setTemplateId(TEMPLATE_UUID.toString());
        request.setTemplateVersion(2);
        request.setSignerName("演示患者");
        request.setSignerRelation(CreateConsentRequest.SignerRelationEnum.SELF);
        request.setSignMethod(CreateConsentRequest.SignMethodEnum.ELECTRONIC);
        return request;
    }

    private SignConsentRequest signRequest(int version) {
        SignConsentRequest request = new SignConsentRequest();
        request.setVersion(version);
        return request;
    }

    private DeclineConsentRequest declineRequest(int version) {
        DeclineConsentRequest request = new DeclineConsentRequest();
        request.setVersion(version);
        request.setReason("演示原因：患者要求暂缓手术（合成数据）");
        return request;
    }

    private RevokeConsentRequest revokeRequest(int version) {
        RevokeConsentRequest request = new RevokeConsentRequest();
        request.setVersion(version);
        request.setReason("演示原因：患者术前改变意愿（合成数据）");
        return request;
    }
}
