package com.anesop.m2.impl.preop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.FinalizePreopAssessmentRequest;
import com.anesop.contracts.anesthesia.model.PreopAirwayAssessment;
import com.anesop.contracts.anesthesia.model.PreopAssessment;
import com.anesop.contracts.anesthesia.model.PreopAssessmentCreateRequest;
import com.anesop.contracts.anesthesia.model.PreopAssessmentList;
import com.anesop.contracts.anesthesia.model.PreopAssessmentUpdateRequest;
import com.anesop.contracts.anesthesia.model.PreopPatientSnapshot;
import com.anesop.contracts.anesthesia.model.PreopPlan;
import com.anesop.contracts.anesthesia.model.PreopRcriInput;
import com.anesop.contracts.anesthesia.model.PreopApfelInput;
import com.anesop.contracts.anesthesia.model.PreopScoreInput;
import com.anesop.contracts.anesthesia.model.PreopScores;
import com.anesop.contracts.anesthesia.model.PreopStopBangInput;
import com.anesop.contracts.scheduling.model.PatientSnapshot;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.application.SurgeryApplicationService;
import com.anesop.m2.api.preop.PreopConflictException;
import com.anesop.m2.api.preop.PreopUnprocessableException;
import com.anesop.m2.api.preop.PreopValidationException;
import com.anesop.m2.impl.persistence.entity.PreopAssessmentEntity;
import com.anesop.m2.impl.persistence.mapper.PreopAssessmentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
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
 * 术前访视服务单测（M2-M4-01）：评分由服务端计算、状态机、数据范围语义与审计。
 * Mock 掉 mapper 与 M1 申请服务，验证可观察行为（返回值/异常/落库字段/审计动作）。
 */
@ExtendWith(MockitoExtension.class)
class PreopAssessmentServiceImplTest {

    private static final UUID APPLICATION_UUID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final String APPLICATION_ID = APPLICATION_UUID.toString();
    private static final String PATIENT_ID = "50000000-0000-4000-8000-000000000001";
    private static final String HIS_PATIENT_NO = "SYNTH-HIS-P-0001";
    private static final UUID ASSESSMENT_UUID = UUID.fromString("16000000-0000-4000-8000-000000000001");
    private static final String ASSESSMENT_ID = ASSESSMENT_UUID.toString();
    private static final Instant NOW = Instant.parse("2026-11-28T01:40:00Z");

    private static final AuthPrincipal CALLER =
            new AuthPrincipal(UUID.fromString("e0000000-0000-4000-8000-000000000002"),
                    "synth_anesthesiologist", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-0001");

    @Mock
    private PreopAssessmentMapper assessmentMapper;
    @Mock
    private SurgeryApplicationService applicationService;
    @Mock
    private AuditWriter auditWriter;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final PreopScoreCalculator calculator =
            new PreopScoreCalculator(Clock.fixed(NOW, ZoneOffset.UTC), new PreopScoreProperties());

    private PreopAssessmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PreopAssessmentServiceImpl(assessmentMapper, applicationService, calculator,
                auditWriter, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // --------------------------------------------------------------- create --

    @Test
    void createComputesScoresFromInputsAndWritesAudit() throws Exception {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.selectCount(any())).thenReturn(0L);
        when(assessmentMapper.insertDraft(any())).thenReturn(1);

        PreopAssessment result = service.create(createRequest(), CALLER, CONTEXT);

        assertThat(result.getStatus()).isEqualTo(PreopAssessment.StatusEnum.DRAFT);
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getScores().getRcriScore()).isEqualTo(1);
        assertThat(result.getScores().getRcriRisk().getValue()).isEqualTo("MODERATE");
        assertThat(result.getScores().getApfelScore()).isEqualTo(4);
        assertThat(result.getScores().getStopBangScore()).isEqualTo(4);
        assertThat(result.getScores().getDasiScore()).isEqualByComparingTo(new BigDecimal("18.95"));
        assertThat(result.getScores().getRiskLevel().getValue()).isEqualTo("HIGH");
        assertThat(result.getPatientSnapshot().getHisPatientNo()).isEqualTo(HIS_PATIENT_NO);

        ArgumentCaptor<PreopAssessmentEntity> inserted =
                ArgumentCaptor.forClass(PreopAssessmentEntity.class);
        verify(assessmentMapper).insertDraft(inserted.capture());
        assertThat(inserted.getValue().getRiskLevel()).isEqualTo("HIGH");
        assertThat(inserted.getValue().getCalculatorVersion()).isEqualTo("1.0.0");
        assertThat(inserted.getValue().getScoresJson()).contains("\"rcriScore\":1");

        ArgumentCaptor<Map<String, Object>> detail = auditDetail("PREOP_CREATE");
        assertThat(detail.getValue())
                .containsEntry("applicationId", APPLICATION_ID)
                .doesNotContainKeys("patientName", "patientNo", "hisPatientNo", "historySummary");
    }

    @Test
    void createRejectsInvisibleApplicationAsUnprocessable() {
        when(applicationService.get(APPLICATION_ID, CALLER))
                .thenThrow(new NoSuchElementException("不可见"));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(PreopUnprocessableException.class);
        verify(assessmentMapper, never()).insertDraft(any());
    }

    @Test
    void createRejectsInvalidApplicationId() {
        PreopAssessmentCreateRequest request = createRequest();
        request.setApplicationId("not-a-uuid");

        assertThatThrownBy(() -> service.create(request, CALLER, CONTEXT))
                .isInstanceOf(PreopValidationException.class);
        verify(applicationService, never()).get(anyString(), any());
    }

    @Test
    void createRejectsTerminalApplication() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("CANCELLED"));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(PreopConflictException.class);
        verify(assessmentMapper, never()).insertDraft(any());
    }

    @Test
    void createRejectsPatientMismatch() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        PreopAssessmentCreateRequest request = createRequest();
        request.getPatientSnapshot().setHisPatientNo("SYNTH-OTHER-9999");

        assertThatThrownBy(() -> service.create(request, CALLER, CONTEXT))
                .isInstanceOf(PreopValidationException.class)
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("patientSnapshot.hisPatientNo");
    }

    @Test
    void createBackfillsPatientSnapshotFromApplicationAndIgnoresClientValues() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.selectCount(any())).thenReturn(0L);
        when(assessmentMapper.insertDraft(any())).thenReturn(1);
        PreopAssessmentCreateRequest request = createRequest();
        request.getPatientSnapshot().setPatientName("篡改姓名");
        request.getPatientSnapshot().setAge(99);
        request.getPatientSnapshot().setGender(PreopPatientSnapshot.GenderEnum.MALE);
        request.getPatientSnapshot().setWard("篡改病区");
        request.getPatientSnapshot().setBedNo("99");

        PreopAssessment result = service.create(request, CALLER, CONTEXT);

        // 姓名/年龄/性别/病区床号一律来自 M1 申请快照，客户端值被忽略。
        assertThat(result.getPatientSnapshot().getPatientName()).isEqualTo("演示患者");
        assertThat(result.getPatientSnapshot().getAge()).isEqualTo(54);
        assertThat(result.getPatientSnapshot().getGender().getValue()).isEqualTo("FEMALE");
        assertThat(result.getPatientSnapshot().getWard()).isNull();
        assertThat(result.getPatientSnapshot().getInpatientNoMasked()).isEqualTo("SYNT****0001");

        ArgumentCaptor<PreopAssessmentEntity> inserted =
                ArgumentCaptor.forClass(PreopAssessmentEntity.class);
        verify(assessmentMapper).insertDraft(inserted.capture());
        assertThat(inserted.getValue().getPatientSnapshotJson())
                .contains("演示患者")
                .doesNotContain("篡改姓名", "篡改病区");
    }

    @Test
    void createBackfillsWhenClientOmitsPatientSnapshot() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.selectCount(any())).thenReturn(0L);
        when(assessmentMapper.insertDraft(any())).thenReturn(1);
        PreopAssessmentCreateRequest request = createRequest();
        request.setPatientSnapshot(null);

        PreopAssessment result = service.create(request, CALLER, CONTEXT);

        assertThat(result.getPatientSnapshot().getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(result.getPatientSnapshot().getHisPatientNo()).isEqualTo(HIS_PATIENT_NO);
    }

    @Test
    void createRejectsClientPatientIdMismatch() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        PreopAssessmentCreateRequest request = createRequest();
        request.getPatientSnapshot().setPatientId("50000000-0000-4000-8000-000000000099");

        assertThatThrownBy(() -> service.create(request, CALLER, CONTEXT))
                .isInstanceOf(PreopValidationException.class)
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("patientSnapshot.patientId");
        verify(assessmentMapper, never()).insertDraft(any());
    }

    @Test
    void createRejectsExistingDraft() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(PreopConflictException.class);
        verify(assessmentMapper, never()).insertDraft(any());
    }

    @Test
    void createMapsConcurrentDuplicateDraftToConflict() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.selectCount(any())).thenReturn(0L);
        when(assessmentMapper.insertDraft(any())).thenThrow(new DuplicateKeyException("uk_draft"));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(PreopConflictException.class);
    }

    @Test
    void createRejectsInvalidAsaClass() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        PreopAssessmentCreateRequest request = createRequest();
        request.setAsaClass(null);

        assertThatThrownBy(() -> service.create(request, CALLER, CONTEXT))
                .isInstanceOf(PreopValidationException.class)
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("asaClass");
    }

    // ----------------------------------------------------------------- list --

    @Test
    void listReturnsApplicationContextAndAssessments() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.selectList(any())).thenReturn(List.of(draftEntity(1), finalizedEntity(2)));

        PreopAssessmentList list = service.listByApplication(APPLICATION_ID, CALLER);

        assertThat(list.getApplication().getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(list.getApplication().getStatus().getValue()).isEqualTo("SCHEDULED");
        assertThat(list.getApplication().getPatientSnapshot().getPatientName()).isEqualTo("演示患者");
        assertThat(list.getItems()).hasSize(2);
        assertThat(list.getItems().get(1).getStatus()).isEqualTo(PreopAssessment.StatusEnum.FINALIZED);
    }

    @Test
    void listRejectsInvalidApplicationId() {
        assertThatThrownBy(() -> service.listByApplication("bad-id", CALLER))
                .isInstanceOf(PreopValidationException.class);
    }

    @Test
    void listMapsInvisibleApplicationToForbidden() {
        when(applicationService.get(APPLICATION_ID, CALLER))
                .thenThrow(new NoSuchElementException("不可见"));

        assertThatThrownBy(() -> service.listByApplication(APPLICATION_ID, CALLER))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ get --

    @Test
    void getReturnsScoresAndAuditFields() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(draftEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        PreopAssessment result = service.get(ASSESSMENT_ID, CALLER);

        assertThat(result.getAssessmentId()).isEqualTo(ASSESSMENT_ID);
        assertThat(result.getScores().getCalculatorVersion()).isEqualTo("1.0.0");
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getCreatedAt().toInstant()).isEqualTo(NOW);
    }

    @Test
    void getRejectsUnknownAssessmentAsNotFound() {
        when(assessmentMapper.selectById(any())).thenReturn(null);

        assertThatThrownBy(() -> service.get(ASSESSMENT_ID, CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getRejectsInvalidAssessmentIdAsNotFound() {
        assertThatThrownBy(() -> service.get("not-a-uuid", CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getPropagatesApplicationNotVisibleAsNotFound() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(draftEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER))
                .thenThrow(new NoSuchElementException("不可见"));

        assertThatThrownBy(() -> service.get(ASSESSMENT_ID, CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --------------------------------------------------------------- update --

    @Test
    void updateRecomputesScoresAndWritesAudit() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(draftEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.updateDraft(any())).thenReturn(1);

        PreopAssessment result = service.update(ASSESSMENT_ID, updateRequest(1), CALLER, CONTEXT);

        assertThat(result.getVersion()).isEqualTo(2);
        assertThat(result.getScores().getRcriScore()).isEqualTo(3);
        assertThat(result.getScores().getStopBangScore()).isEqualTo(5);
        auditDetail("PREOP_UPDATE");
    }

    @Test
    void updateRejectsFinalizedAssessment() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(finalizedEntity(2));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(() -> service.update(ASSESSMENT_ID, updateRequest(2), CALLER, CONTEXT))
                .isInstanceOf(PreopConflictException.class);
        verify(assessmentMapper, never()).updateDraft(any());
    }

    @Test
    void updateRejectsStaleVersion() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(draftEntity(3));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(() -> service.update(ASSESSMENT_ID, updateRequest(2), CALLER, CONTEXT))
                .isInstanceOf(PreopConflictException.class);
        verify(assessmentMapper, never()).updateDraft(any());
    }

    @Test
    void updateRejectsRowChangedConcurrently() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(draftEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.updateDraft(any())).thenReturn(0);

        assertThatThrownBy(() -> service.update(ASSESSMENT_ID, updateRequest(1), CALLER, CONTEXT))
                .isInstanceOf(PreopConflictException.class);
    }

    @Test
    void updateRejectsInactiveApplication() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(draftEntity(1));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("CANCELLED"));

        assertThatThrownBy(() -> service.update(ASSESSMENT_ID, updateRequest(1), CALLER, CONTEXT))
                .isInstanceOf(PreopConflictException.class);
    }

    // ------------------------------------------------------------- finalize --

    @Test
    void finalizeLocksAssessmentAndWritesAudit() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(draftEntity(2));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.finalizeDraft(ASSESSMENT_UUID, 2L, NOW, CALLER.userId(),
                "访视完成（合成示例）")).thenReturn(1);

        PreopAssessment result =
                service.finalizeAssessment(ASSESSMENT_ID, finalizeRequest(2), CALLER, CONTEXT);

        assertThat(result.getStatus()).isEqualTo(PreopAssessment.StatusEnum.FINALIZED);
        assertThat(result.getFinalizedAt().toInstant()).isEqualTo(NOW);
        assertThat(result.getFinalizedBy()).isEqualTo(CALLER.userId().toString());
        assertThat(result.getFinalizeNote()).isEqualTo("访视完成（合成示例）");
        assertThat(result.getVersion()).isEqualTo(3);
        auditDetail("PREOP_FINALIZE");
    }

    @Test
    void finalizePersistsNoteAndPassesItToMapper() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(draftEntity(2));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.finalizeDraft(ASSESSMENT_UUID, 2L, NOW, CALLER.userId(), "最终化说明"))
                .thenReturn(1);
        FinalizePreopAssessmentRequest request = finalizeRequest(2);
        request.setNote("最终化说明");

        PreopAssessment result =
                service.finalizeAssessment(ASSESSMENT_ID, request, CALLER, CONTEXT);

        assertThat(result.getFinalizeNote()).isEqualTo("最终化说明");
        verify(assessmentMapper).finalizeDraft(ASSESSMENT_UUID, 2L, NOW, CALLER.userId(),
                "最终化说明");
        // 最终化说明属自由文本，不得进入审计 detail。
        assertThat(auditDetail("PREOP_FINALIZE").getValue())
                .doesNotContainKeys("note", "finalizeNote");
    }

    @Test
    void finalizeRejectsAlreadyFinalized() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(finalizedEntity(3));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(
                () -> service.finalizeAssessment(ASSESSMENT_ID, finalizeRequest(3), CALLER, CONTEXT))
                .isInstanceOf(PreopConflictException.class);
        verify(assessmentMapper, never()).finalizeDraft(any(), any(), any(), any(), any());
    }

    @Test
    void finalizeRejectsStaleVersion() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(draftEntity(3));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(
                () -> service.finalizeAssessment(ASSESSMENT_ID, finalizeRequest(2), CALLER, CONTEXT))
                .isInstanceOf(PreopConflictException.class);
    }

    @Test
    void finalizeRejectsRowChangedConcurrently() {
        when(assessmentMapper.selectById(ASSESSMENT_UUID)).thenReturn(draftEntity(2));
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(assessmentMapper.finalizeDraft(ASSESSMENT_UUID, 2L, NOW, CALLER.userId(),
                "访视完成（合成示例）")).thenReturn(0);

        assertThatThrownBy(
                () -> service.finalizeAssessment(ASSESSMENT_ID, finalizeRequest(2), CALLER, CONTEXT))
                .isInstanceOf(PreopConflictException.class);
    }

    // ------------------------------------------------------------- helpers --

    private ArgumentCaptor<Map<String, Object>> auditDetail(String action) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(auditWriter).record(eq(action), eq("PREOP_ASSESSMENT"), anyString(), eq(true),
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
        patient.setPatientId(PATIENT_ID);
        patient.setPatientNo(HIS_PATIENT_NO);
        patient.setHisPatientNo(HIS_PATIENT_NO);
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

    private PreopAssessmentCreateRequest createRequest() {
        PreopAssessmentCreateRequest request = new PreopAssessmentCreateRequest();
        request.setApplicationId(APPLICATION_ID);
        request.setPatientSnapshot(snapshot());
        request.setHistorySummary("演示病史（高血压，规律服药；合成数据）");
        request.setAsaClass(PreopAssessmentCreateRequest.AsaClassEnum.II);
        request.setAirwayAssessment(airway());
        request.setScoreInput(scoreInput());
        request.setPlan(plan());
        return request;
    }

    private PreopAssessmentUpdateRequest updateRequest(int version) {
        PreopAssessmentUpdateRequest request = new PreopAssessmentUpdateRequest();
        request.setVersion(version);
        request.setHistorySummary("演示病史（高血压、糖尿病，规律服药；合成数据）");
        request.setAsaClass(PreopAssessmentUpdateRequest.AsaClassEnum.III);
        PreopAirwayAssessment airway = airway();
        airway.setMallampati(PreopAirwayAssessment.MallampatiEnum.III);
        airway.setMouthOpeningCm(new BigDecimal("3.8"));
        airway.setCervicalSpineMobility(PreopAirwayAssessment.CervicalSpineMobilityEnum.LIMITED);
        request.setAirwayAssessment(airway);
        request.setScoreInput(severeScoreInput());
        request.setPlan(plan());
        return request;
    }

    private FinalizePreopAssessmentRequest finalizeRequest(int version) {
        FinalizePreopAssessmentRequest request = new FinalizePreopAssessmentRequest();
        request.setVersion(version);
        request.setNote("访视完成（合成示例）");
        return request;
    }

    private PreopPatientSnapshot snapshot() {
        PreopPatientSnapshot snapshot = new PreopPatientSnapshot();
        snapshot.setPatientId(PATIENT_ID);
        snapshot.setHisPatientNo(HIS_PATIENT_NO);
        snapshot.setPatientName("演示患者");
        snapshot.setGender(PreopPatientSnapshot.GenderEnum.FEMALE);
        snapshot.setBirthDate(LocalDate.of(1972, 5, 18));
        snapshot.setAge(54);
        snapshot.setDepartmentName("演示手术科室（合成）");
        snapshot.setWard("演示病区（合成）");
        snapshot.setBedNo("01");
        return snapshot;
    }

    private PreopAirwayAssessment airway() {
        PreopAirwayAssessment airway = new PreopAirwayAssessment();
        airway.setMallampati(PreopAirwayAssessment.MallampatiEnum.II);
        airway.setMouthOpeningCm(new BigDecimal("4.5"));
        airway.setCervicalSpineMobility(PreopAirwayAssessment.CervicalSpineMobilityEnum.NORMAL);
        airway.setNotes("演示气道评估（合成数据）");
        return airway;
    }

    private PreopPlan plan() {
        PreopPlan plan = new PreopPlan();
        plan.setPlanType(PreopPlan.PlanTypeEnum.GENERAL);
        plan.setAirwayPlan("气管插管（合成示例）");
        plan.setNotes("演示麻醉计划（合成数据）");
        return plan;
    }

    private PreopScoreInput scoreInput() {
        PreopScoreInput input = new PreopScoreInput();
        PreopRcriInput rcri = new PreopRcriInput();
        rcri.setHighRiskSurgery(true);
        rcri.setIschemicHeartDisease(false);
        rcri.setCongestiveHeartFailure(false);
        rcri.setCerebrovascularDisease(false);
        rcri.setInsulinDependentDiabetes(false);
        rcri.setRenalImpairment(false);
        input.setRcri(rcri);

        PreopApfelInput apfel = new PreopApfelInput();
        apfel.setFemale(true);
        apfel.setPonvOrMotionSicknessHistory(true);
        apfel.setNonSmoker(true);
        apfel.setPostoperativeOpioid(true);
        input.setApfel(apfel);

        PreopStopBangInput stopBang = new PreopStopBangInput();
        stopBang.setSnoring(true);
        stopBang.setTiredness(true);
        stopBang.setObservedApnea(false);
        stopBang.setHypertension(true);
        stopBang.setBmiOver35(false);
        stopBang.setAgeOver50(true);
        stopBang.setNeckCircumferenceOver40(false);
        stopBang.setMale(false);
        input.setStopBang(stopBang);

        input.setDasiActivities(List.of(PreopScoreInput.DasiActivitiesEnum.SELF_CARE,
                PreopScoreInput.DasiActivitiesEnum.WALK_INDOORS,
                PreopScoreInput.DasiActivitiesEnum.WALK_BLOCK,
                PreopScoreInput.DasiActivitiesEnum.CLIMB_STAIRS,
                PreopScoreInput.DasiActivitiesEnum.LIGHT_HOUSEHOLD_WORK,
                PreopScoreInput.DasiActivitiesEnum.MODERATE_HOUSEHOLD_WORK));
        return input;
    }

    private PreopScoreInput severeScoreInput() {
        PreopScoreInput input = new PreopScoreInput();
        PreopRcriInput rcri = new PreopRcriInput();
        rcri.setHighRiskSurgery(true);
        rcri.setIschemicHeartDisease(true);
        rcri.setCongestiveHeartFailure(false);
        rcri.setCerebrovascularDisease(false);
        rcri.setInsulinDependentDiabetes(true);
        rcri.setRenalImpairment(false);
        input.setRcri(rcri);

        PreopApfelInput apfel = new PreopApfelInput();
        apfel.setFemale(true);
        apfel.setPonvOrMotionSicknessHistory(true);
        apfel.setNonSmoker(false);
        apfel.setPostoperativeOpioid(true);
        input.setApfel(apfel);

        PreopStopBangInput stopBang = new PreopStopBangInput();
        stopBang.setSnoring(true);
        stopBang.setTiredness(true);
        stopBang.setObservedApnea(true);
        stopBang.setHypertension(true);
        stopBang.setBmiOver35(false);
        stopBang.setAgeOver50(true);
        stopBang.setNeckCircumferenceOver40(false);
        stopBang.setMale(false);
        input.setStopBang(stopBang);

        input.setDasiActivities(List.of(PreopScoreInput.DasiActivitiesEnum.SELF_CARE,
                PreopScoreInput.DasiActivitiesEnum.WALK_INDOORS,
                PreopScoreInput.DasiActivitiesEnum.LIGHT_HOUSEHOLD_WORK));
        return input;
    }

    private PreopAssessmentEntity draftEntity(int version) {
        return entity("DRAFT", version);
    }

    private PreopAssessmentEntity finalizedEntity(int version) {
        return entity("FINALIZED", version);
    }

    private PreopAssessmentEntity entity(String status, int version) {
        try {
            PreopScores scores = calculator.calculate(scoreInput());
            PreopAssessmentEntity entity = new PreopAssessmentEntity();
            entity.setId(ASSESSMENT_UUID);
            entity.setApplicationId(APPLICATION_UUID);
            entity.setPatientId(UUID.fromString(PATIENT_ID));
            entity.setPatientSnapshotJson(objectMapper.writeValueAsString(snapshot()));
            entity.setHistorySummary("演示病史（高血压，规律服药；合成数据）");
            entity.setAsaClass("II");
            entity.setMallampati("II");
            entity.setAirwayDetailsJson(objectMapper.writeValueAsString(airway()));
            entity.setScoreInputJson(objectMapper.writeValueAsString(scoreInput()));
            entity.setScoresJson(objectMapper.writeValueAsString(scores));
            entity.setRcriScore(scores.getRcriScore());
            entity.setApfelScore(scores.getApfelScore());
            entity.setStopbangScore(scores.getStopBangScore());
            entity.setDasiScore(scores.getDasiScore().doubleValue());
            entity.setRcriRisk(scores.getRcriRisk().getValue());
            entity.setApfelRisk(scores.getApfelRisk().getValue());
            entity.setStopbangRisk(scores.getStopBangRisk().getValue());
            entity.setFunctionalCapacityLow(scores.getFunctionalCapacityLow());
            entity.setRiskLevel(scores.getRiskLevel().getValue());
            entity.setCalculatorVersion(scores.getCalculatorVersion());
            entity.setPlanType("GENERAL");
            entity.setAirwayPlan("气管插管（合成示例）");
            entity.setPlanNotes("演示麻醉计划（合成数据）");
            entity.setStatus(status);
            entity.setFinalizedAt("FINALIZED".equals(status) ? NOW : null);
            entity.setFinalizedBy("FINALIZED".equals(status) ? CALLER.userId() : null);
            entity.setCreatedAt(NOW);
            entity.setCreatedBy(CALLER.userId());
            entity.setUpdatedAt(NOW);
            entity.setUpdatedBy(CALLER.userId());
            entity.setVersion((long) version);
            return entity;
        } catch (com.fasterxml.jackson.core.JsonProcessingException serializationFailure) {
            throw new IllegalStateException(serializationFailure);
        }
    }
}
