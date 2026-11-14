package com.anesop.m2.impl.pacu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.CreatePacuRecordRequest;
import com.anesop.contracts.anesthesia.model.PacuAssessment;
import com.anesop.contracts.anesthesia.model.PacuAssessmentList;
import com.anesop.contracts.anesthesia.model.PacuDischarge;
import com.anesop.contracts.anesthesia.model.PacuDischargeRequest;
import com.anesop.contracts.anesthesia.model.PacuHandover;
import com.anesop.contracts.anesthesia.model.PacuRecord;
import com.anesop.contracts.anesthesia.model.PacuRecordList;
import com.anesop.contracts.anesthesia.model.PacuVitalSigns;
import com.anesop.contracts.anesthesia.model.RecordPacuAssessmentRequest;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.error.PacuDischargePreconditionException;
import com.anesop.m2.impl.config.PacuProperties;
import com.anesop.m2.impl.config.RecordProperties;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.PacuAssessmentEntity;
import com.anesop.m2.impl.persistence.entity.PacuDischargeEntity;
import com.anesop.m2.impl.persistence.entity.PacuRecordEntity;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.PacuAssessmentMapper;
import com.anesop.m2.impl.persistence.mapper.PacuDischargeMapper;
import com.anesop.m2.impl.persistence.mapper.PacuRecordMapper;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * PACU 复苏/评估/转出服务单测（M3-M10-01）：入室时间窗与重复入室、Aldrete 服务端计算与
 * 越界 400、append-only 语义、转出前置 409（未评估/未达阈值）、转出后 409、数据范围与审计。
 * Mock 掉 Mapper / m1-api / m0-api；数据库触发器（append-only、转出后禁评估）与权限由集成
 * 测试验证。
 */
@ExtendWith(MockitoExtension.class)
class PacuServiceImplTest {

    private static final UUID PACU_ID = UUID.fromString("19000000-0000-4000-8000-000000000001");
    private static final UUID RECORD_ID = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID SCHEDULE_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID PATIENT_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-12-01T03:00:00Z");
    private static final int THRESHOLD = 9;

    @Mock
    private PacuRecordMapper pacuRecordMapper;
    @Mock
    private PacuAssessmentMapper assessmentMapper;
    @Mock
    private PacuDischargeMapper dischargeMapper;
    @Mock
    private AnesthesiaRecordMapper recordMapper;
    @Mock
    private SchedulingService schedulingService;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private ClinicalFailureAuditor failureAuditor;

    private PacuServiceImpl service;
    private AuthPrincipal caller;
    private AuthRequestContext context;

    @BeforeEach
    void setUp() {
        service = new PacuServiceImpl(pacuRecordMapper, assessmentMapper, dischargeMapper,
                recordMapper, schedulingService, auditWriter, failureAuditor, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC), new RecordProperties(600),
                new PacuProperties(THRESHOLD));
        caller = new AuthPrincipal(ACTOR_ID, "synth_pacu", "jti", NOW.plusSeconds(900), "sid");
        context = new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE");
    }

    // --------------------------------------------------------------- 入室 --

    @Test
    void admitPersistsAdmissionAndAuditsWithoutPhi() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectActiveByRecord(RECORD_ID)).thenReturn(null);
        when(pacuRecordMapper.insertRecord(any(PacuRecordEntity.class))).thenReturn(1);

        PacuRecord result = service.admit(admitRequest(), caller, context);

        ArgumentCaptor<PacuRecordEntity> entityCaptor =
                ArgumentCaptor.forClass(PacuRecordEntity.class);
        verify(pacuRecordMapper).insertRecord(entityCaptor.capture());
        PacuRecordEntity saved = entityCaptor.getValue();
        assertThat(saved.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(saved.getStatus()).isEqualTo("ADMITTED");
        assertThat(saved.getConsciousness()).isEqualTo("ALERT");
        assertThat(saved.getAirway()).isEqualTo("NATURAL");
        assertThat(saved.getAdmittedVitalSigns()).contains("systolicBp", "spo2");
        assertThat(result.getStatus()).isEqualTo(PacuRecord.StatusEnum.ADMITTED);
        assertThat(result.getAldreteThreshold()).isEqualTo(THRESHOLD);
        assertThat(result.getLatestAldreteTotal()).isNull();

        ArgumentCaptor<Map<String, Object>> detailCaptor = auditDetail("PACU_ADMIT");
        assertThat(detailCaptor.getValue()).containsEntry("recordId", RECORD_ID.toString())
                .containsEntry("status", "ADMITTED")
                .doesNotContainKeys("patientName", "situation", "notes");
    }

    @Test
    void admitRejectsInvalidRecordId() {
        assertThatThrownBy(() -> service.admit(admitRequestWithRecordId("not-a-uuid"), caller,
                context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("格式");
    }

    @Test
    void admitMapsInvisibleRecordTo422() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.admit(admitRequest(), caller, context))
                .isInstanceOf(M2UnprocessableException.class);
    }

    @Test
    void admitRejectsRecordWhoseScheduleIsOutOfScope() {
        AnesthesiaRecordEntity record = startedRecord();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller))
                .thenThrow(new NoSuchElementException("not visible"));
        assertThatThrownBy(() -> service.admit(admitRequest(), caller, context))
                .isInstanceOf(M2UnprocessableException.class);
    }

    @Test
    void admitRejectsNotStartedRecord() {
        AnesthesiaRecordEntity record = startedRecord();
        record.setStatus("DRAFT");
        record.setStartedAt(null);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        assertThatThrownBy(() -> service.admit(admitRequest(), caller, context))
                .isInstanceOf(M2ConflictException.class);
        verify(pacuRecordMapper, never()).insertRecord(any());
    }

    @Test
    void admitRejectsAdmissionBeforeRecordStartAndFutureAdmission() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        CreatePacuRecordRequest beforeStart = admitRequest();
        beforeStart.setAdmittedAt(NOW.minusSeconds(3 * 3600).atOffset(ZoneOffset.ofHours(8)));
        assertThatThrownBy(() -> service.admit(beforeStart, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("早于");

        CreatePacuRecordRequest future = admitRequest();
        future.setAdmittedAt(NOW.plusSeconds(3600).atOffset(ZoneOffset.ofHours(8)));
        assertThatThrownBy(() -> service.admit(future, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("晚于");
    }

    @Test
    void admitValidatesVitalSignsPainScoreAndAirway() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        CreatePacuRecordRequest noVitals = admitRequest();
        noVitals.setVitalSigns(null);
        assertThatThrownBy(() -> service.admit(noVitals, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("生命体征");

        CreatePacuRecordRequest badVitals = admitRequest();
        badVitals.getVitalSigns().setSpo2(120);
        assertThatThrownBy(() -> service.admit(badVitals, caller, context))
                .isInstanceOf(M2ValidationException.class);

        CreatePacuRecordRequest badPain = admitRequest();
        badPain.setPainScore(11);
        assertThatThrownBy(() -> service.admit(badPain, caller, context))
                .isInstanceOf(M2ValidationException.class);

        CreatePacuRecordRequest noAirway = admitRequest();
        noAirway.setAirway(null);
        assertThatThrownBy(() -> service.admit(noAirway, caller, context))
                .isInstanceOf(M2ValidationException.class);

        CreatePacuRecordRequest badTemperature = admitRequest();
        badTemperature.getVitalSigns().setTemperature(new BigDecimal("50"));
        assertThatThrownBy(() -> service.admit(badTemperature, caller, context))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void admitRejectsDuplicateActiveRecordAndAuditsFailure() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectActiveByRecord(RECORD_ID)).thenReturn(pacuRecord("MONITORING"));

        assertThatThrownBy(() -> service.admit(admitRequest(), caller, context))
                .isInstanceOf(M2ConflictException.class);
        verify(failureAuditor).recordFailure(eq("PACU_ADMIT"), eq("PACU_RECORD"),
                eq(RECORD_ID.toString()), any(), eq(caller), eq(context));
        verify(pacuRecordMapper, never()).insertRecord(any());
    }

    @Test
    void admitMapsConcurrentUniqueViolationTo409() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectActiveByRecord(RECORD_ID)).thenReturn(null);
        when(pacuRecordMapper.insertRecord(any(PacuRecordEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_pacu_record_active"));
        assertThatThrownBy(() -> service.admit(admitRequest(), caller, context))
                .isInstanceOf(M2ConflictException.class);
        verify(failureAuditor).recordFailure(eq("PACU_ADMIT"), eq("PACU_RECORD"),
                eq(RECORD_ID.toString()), any(), eq(caller), eq(context));
    }

    // --------------------------------------------------------------- 评估 --

    @Test
    void assessmentComputesTotalServerSideAndMovesToMonitoring() {
        PacuRecordEntity pacu = pacuRecord("ADMITTED");
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacu);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacu);
        when(assessmentMapper.insertAssessment(any(PacuAssessmentEntity.class))).thenReturn(1);
        when(pacuRecordMapper.markAssessed(eq(PACU_ID), eq(1L), eq("MONITORING"), any(),
                eq(ACTOR_ID))).thenReturn(1);

        PacuAssessment result = service.recordAssessment(PACU_ID.toString(),
                assessmentRequest(NOW.minusSeconds(600), 1, 2, 2, 2, 1), caller, context);

        assertThat(result.getAldreteTotal()).isEqualTo(8);
        assertThat(result.getActivity()).isEqualTo(1);
        assertThat(result.getAssessmentId()).isNotBlank();
        ArgumentCaptor<PacuAssessmentEntity> captor =
                ArgumentCaptor.forClass(PacuAssessmentEntity.class);
        verify(assessmentMapper).insertAssessment(captor.capture());
        assertThat(captor.getValue().getAldreteTotal()).isEqualTo(8);
        ArgumentCaptor<Map<String, Object>> detailCaptor = auditDetail("PACU_ASSESS");
        assertThat(detailCaptor.getValue()).containsEntry("aldreteTotal", 8)
                .containsEntry("status", "MONITORING")
                .containsEntry("aldreteThreshold", THRESHOLD);
    }

    @Test
    void assessmentReachingThresholdMovesToReady() {
        PacuRecordEntity pacu = pacuRecord("MONITORING");
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacu);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacu);
        when(assessmentMapper.insertAssessment(any(PacuAssessmentEntity.class))).thenReturn(1);
        when(pacuRecordMapper.markAssessed(eq(PACU_ID), eq(1L), eq("READY"), any(),
                eq(ACTOR_ID))).thenReturn(1);

        PacuAssessment result = service.recordAssessment(PACU_ID.toString(),
                assessmentRequest(NOW.minusSeconds(60), 2, 2, 2, 2, 1), caller, context);
        assertThat(result.getAldreteTotal()).isEqualTo(9);
        verify(pacuRecordMapper).markAssessed(eq(PACU_ID), eq(1L), eq("READY"), any(),
                eq(ACTOR_ID));
    }

    @Test
    void assessmentRejectsMissingOrOutOfRangeItems() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("MONITORING"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacuRecord("MONITORING"));

        RecordPacuAssessmentRequest missing = assessmentRequest(NOW.minusSeconds(60), 2, 2, 2, 2, 2);
        missing.setRespiration(null);
        assertThatThrownBy(() -> service.recordAssessment(PACU_ID.toString(), missing, caller,
                context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("不能为空");

        RecordPacuAssessmentRequest outOfRange =
                assessmentRequest(NOW.minusSeconds(60), 3, 2, 2, 2, 2);
        assertThatThrownBy(() -> service.recordAssessment(PACU_ID.toString(), outOfRange, caller,
                context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("0–2");
        verify(assessmentMapper, never()).insertAssessment(any());
    }

    @Test
    void assessmentRejectsTimeBeforeAdmissionAndFutureTime() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("MONITORING"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacuRecord("MONITORING"));

        assertThatThrownBy(() -> service.recordAssessment(PACU_ID.toString(),
                assessmentRequest(NOW.minusSeconds(2 * 3600), 2, 2, 2, 2, 2), caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("早于入室");

        assertThatThrownBy(() -> service.recordAssessment(PACU_ID.toString(),
                assessmentRequest(NOW.plusSeconds(3600), 2, 2, 2, 2, 2), caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("晚于");
    }

    @Test
    void assessmentRejectsTimeBeforeLatestAssessment() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("MONITORING"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacuRecord("MONITORING"));
        when(assessmentMapper.selectLatestByPacu(PACU_ID))
                .thenReturn(assessment(NOW.minusSeconds(120), 2, 2, 2, 2, 2));

        assertThatThrownBy(() -> service.recordAssessment(PACU_ID.toString(),
                assessmentRequest(NOW.minusSeconds(300), 2, 2, 2, 2, 2), caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("上一条");
        verify(assessmentMapper, never()).insertAssessment(any());
    }

    @Test
    void assessmentStatusFollowsLatestAssessmentNotSubmittedItems() {
        PacuRecordEntity pacu = pacuRecord("READY");
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacu);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacu);
        PacuAssessmentEntity previous = assessment(NOW.minusSeconds(600), 2, 2, 2, 2, 2);
        PacuAssessmentEntity canonicalLatest = assessment(NOW.minusSeconds(60), 1, 2, 2, 2, 1);
        when(assessmentMapper.selectLatestByPacu(PACU_ID))
                .thenReturn(previous, canonicalLatest);
        when(assessmentMapper.insertAssessment(any(PacuAssessmentEntity.class))).thenReturn(1);
        when(pacuRecordMapper.markAssessed(eq(PACU_ID), eq(1L), eq("MONITORING"), any(),
                eq(ACTOR_ID))).thenReturn(1);

        PacuAssessment result = service.recordAssessment(PACU_ID.toString(),
                assessmentRequest(NOW.minusSeconds(60), 2, 2, 2, 2, 2), caller, context);

        assertThat(result.getAldreteTotal()).isEqualTo(10);
        verify(pacuRecordMapper).markAssessed(eq(PACU_ID), eq(1L), eq("MONITORING"), any(),
                eq(ACTOR_ID));
    }

    @Test
    void assessmentRejectsDischargedRecordAndAuditsFailure() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("DISCHARGED"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacuRecord("DISCHARGED"));

        assertThatThrownBy(() -> service.recordAssessment(PACU_ID.toString(),
                assessmentRequest(NOW.minusSeconds(60), 2, 2, 2, 2, 2), caller, context))
                .isInstanceOf(M2ConflictException.class);
        verify(failureAuditor).recordFailure(eq("PACU_ASSESS"), eq("PACU_RECORD"),
                eq(PACU_ID.toString()), any(), eq(caller), eq(context));
        verify(assessmentMapper, never()).insertAssessment(any());
    }

    @Test
    void assessmentRejectsOverlongNotesAndStaleVersion() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("MONITORING"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacuRecord("MONITORING"));

        RecordPacuAssessmentRequest longNotes =
                assessmentRequest(NOW.minusSeconds(60), 2, 2, 2, 2, 2);
        longNotes.setNotes("x".repeat(501));
        assertThatThrownBy(() -> service.recordAssessment(PACU_ID.toString(), longNotes, caller,
                context))
                .isInstanceOf(M2ValidationException.class);

        when(assessmentMapper.insertAssessment(any(PacuAssessmentEntity.class))).thenReturn(1);
        when(pacuRecordMapper.markAssessed(eq(PACU_ID), eq(1L), eq("READY"), any(),
                eq(ACTOR_ID))).thenReturn(0);
        assertThatThrownBy(() -> service.recordAssessment(PACU_ID.toString(),
                assessmentRequest(NOW.minusSeconds(60), 2, 2, 2, 2, 2), caller, context))
                .isInstanceOf(M2ConflictException.class);
        verify(failureAuditor).recordFailure(eq("PACU_ASSESS"), eq("PACU_RECORD"),
                eq(PACU_ID.toString()), any(), eq(caller), eq(context));
    }

    // --------------------------------------------------------------- 转出 --

    @Test
    void dischargeFreezesCriteriaAndWritesAppendOnlyRecord() {
        PacuRecordEntity pacu = pacuRecord("READY");
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacu);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacu);
        PacuAssessmentEntity latest = assessment(NOW.minusSeconds(300), 2, 2, 2, 2, 2);
        when(assessmentMapper.selectLatestByPacu(PACU_ID)).thenReturn(latest);
        when(dischargeMapper.insertDischarge(any(PacuDischargeEntity.class))).thenReturn(1);
        when(pacuRecordMapper.markDischarged(eq(PACU_ID), eq(1L), any(), eq(ACTOR_ID)))
                .thenReturn(1);

        PacuDischarge result = service.discharge(PACU_ID.toString(), dischargeRequest(), caller,
                context);

        assertThat(result.getDestination()).isEqualTo(PacuDischarge.DestinationEnum.WARD);
        assertThat(result.getCriteria().getAldreteTotal()).isEqualTo(10);
        assertThat(result.getCriteria().getAldreteThreshold()).isEqualTo(THRESHOLD);
        assertThat(result.getCriteria().getMeetsThreshold()).isTrue();
        assertThat(result.getCriteria().getLatestAssessmentId())
                .isEqualTo(latest.getId().toString());
        assertThat(result.getHandover().getSituation()).contains("演示");
        ArgumentCaptor<PacuDischargeEntity> captor =
                ArgumentCaptor.forClass(PacuDischargeEntity.class);
        verify(dischargeMapper).insertDischarge(captor.capture());
        assertThat(captor.getValue().getCriteriaSnapshot())
                .contains("aldreteThreshold", "latestAssessmentId");
        ArgumentCaptor<Map<String, Object>> detailCaptor = auditDetail("PACU_DISCHARGE");
        assertThat(detailCaptor.getValue()).containsEntry("destination", "WARD")
                .containsEntry("aldreteTotal", 10)
                .doesNotContainKeys("situation", "background", "recommendation");
    }

    @Test
    void dischargeRejectsMissingAssessmentWith409() {
        PacuRecordEntity pacu = pacuRecord("ADMITTED");
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacu);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacu);
        when(assessmentMapper.selectLatestByPacu(PACU_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.discharge(PACU_ID.toString(), dischargeRequest(), caller,
                context))
                .isInstanceOf(PacuDischargePreconditionException.class)
                .satisfies(failure -> assertThat(
                        ((PacuDischargePreconditionException) failure).unmetConditions())
                        .extracting(PacuDischargePreconditionException.UnmetCondition::code)
                        .containsExactly("ALDRETE_ASSESSED"));
        verify(dischargeMapper, never()).insertDischarge(any());
    }

    @Test
    void dischargeRejectsBelowThresholdWith409() {
        PacuRecordEntity pacu = pacuRecord("MONITORING");
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacu);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacu);
        when(assessmentMapper.selectLatestByPacu(PACU_ID))
                .thenReturn(assessment(NOW.minusSeconds(300), 1, 2, 2, 2, 1));

        assertThatThrownBy(() -> service.discharge(PACU_ID.toString(), dischargeRequest(), caller,
                context))
                .isInstanceOf(PacuDischargePreconditionException.class)
                .satisfies(failure -> assertThat(
                        ((PacuDischargePreconditionException) failure).unmetConditions())
                        .extracting(PacuDischargePreconditionException.UnmetCondition::code)
                        .containsExactly("ALDRETE_THRESHOLD_MET"));
        verify(failureAuditor).recordFailure(eq("PACU_DISCHARGE"), eq("PACU_RECORD"),
                eq(PACU_ID.toString()), any(), eq(caller), eq(context));
        verify(dischargeMapper, never()).insertDischarge(any());
        verify(pacuRecordMapper, never()).markDischarged(any(), any(), any(), any());
    }

    @Test
    void dischargeValidatesConfirmationDestinationAndSbar() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("READY"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacuRecord("READY"));

        PacuDischargeRequest unconfirmed = dischargeRequest();
        unconfirmed.setCriteriaConfirmed(false);
        assertThatThrownBy(() -> service.discharge(PACU_ID.toString(), unconfirmed, caller,
                context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("确认");

        PacuDischargeRequest noDestination = dischargeRequest();
        noDestination.setDestination(null);
        assertThatThrownBy(() -> service.discharge(PACU_ID.toString(), noDestination, caller,
                context))
                .isInstanceOf(M2ValidationException.class);

        PacuDischargeRequest blankSbar = dischargeRequest();
        blankSbar.getHandover().setRecommendation("  ");
        assertThatThrownBy(() -> service.discharge(PACU_ID.toString(), blankSbar, caller, context))
                .isInstanceOfSatisfying(M2ValidationException.class,
                        failure -> assertThat(failure.field())
                                .isEqualTo("handover.recommendation"));

        PacuDischargeRequest noHandover = dischargeRequest();
        noHandover.setHandover(null);
        assertThatThrownBy(() -> service.discharge(PACU_ID.toString(), noHandover, caller, context))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void dischargeRejectsAlreadyDischargedRecord() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("DISCHARGED"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacuRecord("DISCHARGED"));

        assertThatThrownBy(() -> service.discharge(PACU_ID.toString(), dischargeRequest(), caller,
                context))
                .isInstanceOf(M2ConflictException.class);
        verify(failureAuditor).recordFailure(eq("PACU_DISCHARGE"), eq("PACU_RECORD"),
                eq(PACU_ID.toString()), any(), eq(caller), eq(context));
    }

    @Test
    void dischargeMapsConcurrentUniqueViolationAndStaleVersionTo409() {
        PacuRecordEntity pacu = pacuRecord("READY");
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacu);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectByIdForUpdate(PACU_ID)).thenReturn(pacu);
        when(assessmentMapper.selectLatestByPacu(PACU_ID))
                .thenReturn(assessment(NOW.minusSeconds(300), 2, 2, 2, 2, 2));
        when(dischargeMapper.insertDischarge(any(PacuDischargeEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_pacu_discharge_pacu"));
        assertThatThrownBy(() -> service.discharge(PACU_ID.toString(), dischargeRequest(), caller,
                context))
                .isInstanceOf(M2ConflictException.class);

        when(dischargeMapper.insertDischarge(any(PacuDischargeEntity.class))).thenReturn(1);
        when(pacuRecordMapper.markDischarged(eq(PACU_ID), eq(1L), any(), eq(ACTOR_ID)))
                .thenReturn(0);
        assertThatThrownBy(() -> service.discharge(PACU_ID.toString(), dischargeRequest(), caller,
                context))
                .isInstanceOf(M2ConflictException.class);
        verify(failureAuditor, org.mockito.Mockito.times(2)).recordFailure(
                eq("PACU_DISCHARGE"), eq("PACU_RECORD"), eq(PACU_ID.toString()), any(),
                eq(caller), eq(context));
    }

    // ------------------------------------------------------------- 查询 --

    @Test
    void getReturnsLatestAssessmentProjection() {
        PacuRecordEntity pacu = pacuRecord("READY");
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacu);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        PacuAssessmentEntity latest = assessment(NOW.minusSeconds(120), 2, 2, 2, 2, 2);
        when(assessmentMapper.selectLatestByPacu(PACU_ID)).thenReturn(latest);

        PacuRecord result = service.get(PACU_ID.toString(), caller);
        assertThat(result.getLatestAssessmentId()).isEqualTo(latest.getId().toString());
        assertThat(result.getLatestAldreteTotal()).isEqualTo(10);
        assertThat(result.getVitalSigns().getHeartRate()).isEqualTo(82);
    }

    @Test
    void getHidesUnknownOrOutOfScopePacu() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.get(PACU_ID.toString(), caller))
                .isInstanceOf(NoSuchElementException.class);

        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("ADMITTED"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller))
                .thenThrow(new NoSuchElementException("not visible"));
        assertThatThrownBy(() -> service.get(PACU_ID.toString(), caller))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listByRecordReturnsAllAndHidesInvisibleRecord() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(pacuRecordMapper.selectList(any())).thenReturn(List.of(pacuRecord("DISCHARGED")));
        when(assessmentMapper.selectLatestByPacu(PACU_ID)).thenReturn(null);
        PacuRecordList list = service.listByRecord(RECORD_ID.toString(), caller);
        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().get(0).getStatus())
                .isEqualTo(PacuRecord.StatusEnum.DISCHARGED);

        when(recordMapper.selectById(RECORD_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.listByRecord(RECORD_ID.toString(), caller))
                .isInstanceOf(AccessDeniedException.class);

        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller))
                .thenThrow(new NoSuchElementException("not visible"));
        assertThatThrownBy(() -> service.listByRecord(RECORD_ID.toString(), caller))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listAssessmentsReturnsAscendingTimeline() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("MONITORING"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        PacuAssessmentEntity first = assessment(NOW.minusSeconds(900), 1, 2, 2, 2, 1);
        PacuAssessmentEntity second = assessment(NOW.minusSeconds(60), 2, 2, 2, 2, 2);
        when(assessmentMapper.selectByPacu(PACU_ID)).thenReturn(List.of(first, second));
        PacuAssessmentList list = service.listAssessments(PACU_ID.toString(), caller);
        assertThat(list.getItems()).hasSize(2);
        assertThat(list.getItems()).extracting(PacuAssessment::getAldreteTotal)
                .containsExactly(8, 10);
    }

    @Test
    void getDischargeReturns404WhenNotDischarged() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("READY"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(dischargeMapper.selectByPacu(PACU_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.getDischarge(PACU_ID.toString(), caller))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getDischargeReturnsStoredHandoverAndCriteria() {
        when(pacuRecordMapper.selectById(PACU_ID)).thenReturn(pacuRecord("DISCHARGED"));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(startedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        PacuDischargeEntity entity = new PacuDischargeEntity();
        entity.setId(UUID.randomUUID());
        entity.setPacuId(PACU_ID);
        entity.setDischargedAt(NOW);
        entity.setDestination("ICU");
        entity.setHandover("{\"situation\":\"演示 S\",\"background\":\"演示 B\","
                + "\"assessment\":\"演示 A\",\"recommendation\":\"演示 R\"}");
        entity.setCriteriaSnapshot("{\"aldreteThreshold\":9,\"aldreteTotal\":10,"
                + "\"meetsThreshold\":true,\"itemsComplete\":true,"
                + "\"latestAssessmentId\":\"1a000000-0000-4000-8000-000000000002\","
                + "\"latestAssessmentAt\":\"2026-12-01T10:30:00+08:00\"}");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR_ID);
        when(dischargeMapper.selectByPacu(PACU_ID)).thenReturn(entity);

        PacuDischarge discharge = service.getDischarge(PACU_ID.toString(), caller);
        assertThat(discharge.getDestination()).isEqualTo(PacuDischarge.DestinationEnum.ICU);
        assertThat(discharge.getCriteria().getItemsComplete()).isTrue();
        assertThat(discharge.getHandover().getSituation()).isEqualTo("演示 S");
    }

    // ------------------------------------------------------------- fixtures --

    private ArgumentCaptor<Map<String, Object>> auditDetail(String action) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(auditWriter).record(eq(action), eq("PACU_RECORD"), anyString(), eq(true),
                captor.capture(), eq(ACTOR_ID), eq("synth_pacu"), eq(context));
        return captor;
    }

    private AnesthesiaRecordEntity startedRecord() {
        AnesthesiaRecordEntity record = new AnesthesiaRecordEntity();
        record.setId(RECORD_ID);
        record.setScheduleId(SCHEDULE_ID);
        record.setPatientId(PATIENT_ID);
        record.setStatus("ONGOING");
        record.setStartedAt(NOW.minusSeconds(2 * 3600));
        return record;
    }

    private PacuRecordEntity pacuRecord(String status) {
        PacuRecordEntity entity = new PacuRecordEntity();
        entity.setId(PACU_ID);
        entity.setAnesthesiaRecordId(RECORD_ID);
        entity.setPatientId(PATIENT_ID);
        entity.setAdmittedAt(NOW.minusSeconds(3600));
        entity.setStatus(status);
        if ("DISCHARGED".equals(status)) {
            entity.setDischargedAt(NOW.minusSeconds(60));
        }
        entity.setAdmittedVitalSigns("{\"systolicBp\":118,\"heartRate\":82,\"spo2\":97}");
        entity.setConsciousness("ALERT");
        entity.setPainScore(3);
        entity.setNausea(false);
        entity.setAirway("NATURAL");
        entity.setCreatedAt(NOW.minusSeconds(3600));
        entity.setCreatedBy(ACTOR_ID);
        entity.setUpdatedAt(NOW.minusSeconds(60));
        entity.setUpdatedBy(ACTOR_ID);
        entity.setVersion(1L);
        entity.setDeleted(false);
        return entity;
    }

    private PacuAssessmentEntity assessment(Instant assessedAt, int activity, int respiration,
            int circulation, int consciousness, int oxygenation) {
        PacuAssessmentEntity entity = new PacuAssessmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setPacuId(PACU_ID);
        entity.setAssessedAt(assessedAt);
        entity.setAldreteActivity(activity);
        entity.setAldreteRespiration(respiration);
        entity.setAldreteCirculation(circulation);
        entity.setAldreteConsciousness(consciousness);
        entity.setAldreteOxygenation(oxygenation);
        entity.setAldreteTotal(activity + respiration + circulation + consciousness + oxygenation);
        entity.setCreatedAt(assessedAt);
        entity.setCreatedBy(ACTOR_ID);
        return entity;
    }

    private CreatePacuRecordRequest admitRequest() {
        CreatePacuRecordRequest request = new CreatePacuRecordRequest();
        request.setRecordId(RECORD_ID.toString());
        request.setAdmittedAt(NOW.minusSeconds(3600).atOffset(ZoneOffset.ofHours(8)));
        PacuVitalSigns vitals = new PacuVitalSigns();
        vitals.setSystolicBp(118);
        vitals.setDiastolicBp(72);
        vitals.setHeartRate(82);
        vitals.setSpo2(97);
        vitals.setRespiratoryRate(14);
        vitals.setTemperature(new BigDecimal("36.5"));
        request.setVitalSigns(vitals);
        request.setConsciousness(CreatePacuRecordRequest.ConsciousnessEnum.ALERT);
        request.setPainScore(3);
        request.setNausea(false);
        request.setAirway(CreatePacuRecordRequest.AirwayEnum.NATURAL);
        return request;
    }

    private CreatePacuRecordRequest admitRequestWithRecordId(String recordId) {
        CreatePacuRecordRequest request = admitRequest();
        request.setRecordId(recordId);
        return request;
    }

    private RecordPacuAssessmentRequest assessmentRequest(Instant assessedAt, int activity,
            int respiration, int circulation, int consciousness, int oxygenation) {
        RecordPacuAssessmentRequest request = new RecordPacuAssessmentRequest();
        request.setAssessedAt(assessedAt.atOffset(ZoneOffset.ofHours(8)));
        request.setActivity(activity);
        request.setRespiration(respiration);
        request.setCirculation(circulation);
        request.setConsciousness(consciousness);
        request.setOxygenation(oxygenation);
        return request;
    }

    private PacuDischargeRequest dischargeRequest() {
        PacuDischargeRequest request = new PacuDischargeRequest();
        request.setDestination(PacuDischargeRequest.DestinationEnum.WARD);
        request.setCriteriaConfirmed(true);
        PacuHandover handover = new PacuHandover();
        handover.setSituation("演示：术后 PACU 观察 65 分钟（合成）");
        handover.setBackground("演示：ASA II，术中平稳（合成）");
        handover.setAssessment("演示：Aldrete 10 分（合成）");
        handover.setRecommendation("演示：返病房监测（合成）");
        request.setHandover(handover);
        return request;
    }
}
