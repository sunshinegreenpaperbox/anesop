package com.anesop.m2.impl.threeway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.ApplicationThreeWayCheck;
import com.anesop.contracts.anesthesia.model.ApplicationThreeWayCheckList;
import com.anesop.contracts.anesthesia.model.RecordApplicationThreeWayCheckRequest;
import com.anesop.contracts.scheduling.model.PatientSnapshot;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.application.SurgeryApplicationService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.persistence.entity.ThreeWayCheckEntity;
import com.anesop.m2.impl.persistence.mapper.ThreeWayCheckMapper;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * 三方核查服务单测（M2-M5-01）：append-only 版本、完成判定、时间/问题项校验与审计。
 *
 * <p>Mock 掉 mapper 与 M1 申请服务；通过捕获插入实体与审计 detail 断言可观察行为，
 * 数据库层 append-only 由迁移集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class ThreeWayCheckServiceImplTest {

    private static final UUID APPLICATION_UUID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final String APPLICATION_ID = APPLICATION_UUID.toString();
    private static final UUID PATIENT_UUID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID CHECK_UUID = UUID.fromString("18000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-12-01T01:00:00Z");
    private static final OffsetDateTime TIME_OUT = OffsetDateTime.ofInstant(NOW.minusSeconds(3600),
            ZoneOffset.ofHours(8));
    private static final OffsetDateTime SIGN_OUT = OffsetDateTime.ofInstant(NOW.minusSeconds(600),
            ZoneOffset.ofHours(8));

    private static final AuthPrincipal CALLER =
            new AuthPrincipal(UUID.fromString("e0000000-0000-4000-8000-000000000002"),
                    "synth_anesthesiologist", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-0001");

    @Mock
    private ThreeWayCheckMapper checkMapper;
    @Mock
    private SurgeryApplicationService applicationService;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private ClinicalFailureAuditor failureAuditor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ThreeWayCheckServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ThreeWayCheckServiceImpl(checkMapper, applicationService, auditWriter,
                failureAuditor, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------- list --

    @Test
    void listReturnsEmptyWhenNoChecks() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(checkMapper.selectList(any())).thenReturn(List.of());

        ApplicationThreeWayCheckList result = service.listByApplication(APPLICATION_ID, CALLER);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.getCompleted()).isFalse();
        assertThat(result.getApplication().getApplicationId()).isEqualTo(APPLICATION_ID);
        assertThat(result.getApplication().getPatientSnapshot().getPatientName()).isEqualTo("演示患者");
    }

    @Test
    void listMapsVersionsAndCompletionFromLatest() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(checkMapper.selectList(any())).thenReturn(List.of(check(2, true), check(1, false)));

        ApplicationThreeWayCheckList result = service.listByApplication(APPLICATION_ID, CALLER);

        assertThat(result.getCompleted()).isTrue();
        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getItems().get(0).getVersion()).isEqualTo(2);
        assertThat(result.getItems().get(0).getSignOutAt()).isNotNull();
        assertThat(result.getItems().get(1).getVersion()).isEqualTo(1);
        assertThat(result.getItems().get(1).getSignOutAt()).isNull();
    }

    @Test
    void listRejectsInvalidApplicationIdentifierAsNotFound() {
        assertThatThrownBy(() -> service.listByApplication("invalid", CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listPropagatesNotFoundForOutOfScopeApplication() {
        when(applicationService.get(APPLICATION_ID, CALLER))
                .thenThrow(new NoSuchElementException("不可见"));

        assertThatThrownBy(() -> service.listByApplication(APPLICATION_ID, CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ----------------------------------------------------------------- record --

    @Test
    void recordFirstVersionDefaultsActorAndAudits() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(checkMapper.maxCheckVersion(APPLICATION_UUID)).thenReturn(0);
        when(checkMapper.insertCheck(any())).thenReturn(1);

        ApplicationThreeWayCheck result = service.record(APPLICATION_ID,
                recordRequest(TIME_OUT, null, List.of()), CALLER, CONTEXT);

        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getTimeOutBy()).isEqualTo(CALLER.userId().toString());
        assertThat(result.getSignOutAt()).isNull();
        assertThat(result.getIssues()).isEmpty();

        ArgumentCaptor<ThreeWayCheckEntity> inserted =
                ArgumentCaptor.forClass(ThreeWayCheckEntity.class);
        verify(checkMapper).insertCheck(inserted.capture());
        assertThat(inserted.getValue().getCheckVersion()).isEqualTo(1);
        assertThat(inserted.getValue().getTimeOutBy()).isEqualTo(CALLER.userId());
        assertThat(inserted.getValue().getIssuesJson()).isEqualTo("[]");

        ArgumentCaptor<Map<String, Object>> detail = auditDetail("THREEWAY_RECORD");
        assertThat(detail.getValue())
                .containsEntry("checkVersion", 1)
                .containsEntry("issuesCount", 0)
                .containsEntry("signOutRecorded", false)
                .doesNotContainKeys("issues", "note");
    }

    @Test
    void recordSecondVersionWithSignOutCompletesCheck() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(checkMapper.maxCheckVersion(APPLICATION_UUID)).thenReturn(1);
        when(checkMapper.insertCheck(any())).thenReturn(1);

        ApplicationThreeWayCheck result = service.record(APPLICATION_ID,
                recordRequest(TIME_OUT, SIGN_OUT, List.of("演示问题项（合成）")), CALLER, CONTEXT);

        assertThat(result.getVersion()).isEqualTo(2);
        assertThat(result.getSignOutAt()).isNotNull();
        assertThat(result.getSignOutBy()).isEqualTo(CALLER.userId().toString());
        assertThat(result.getIssues()).containsExactly("演示问题项（合成）");

        ArgumentCaptor<Map<String, Object>> detail = auditDetail("THREEWAY_RECORD");
        assertThat(detail.getValue()).containsEntry("signOutRecorded", true)
                .containsEntry("issuesCount", 1);
    }

    @Test
    void recordRejectsFutureTimeBeyondClockTolerance() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        OffsetDateTime future = OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.ofHours(8));
        assertThatThrownBy(() -> service.record(APPLICATION_ID,
                recordRequest(future, null, List.of()), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
        verify(checkMapper, never()).insertCheck(any());
    }

    @Test
    void recordRejectsSignOutBeforeTimeOut() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        assertThatThrownBy(() -> service.record(APPLICATION_ID,
                recordRequest(TIME_OUT, TIME_OUT.minusMinutes(10), List.of()), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void recordRejectsSignOutActorWithoutSignOutTime() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        RecordApplicationThreeWayCheckRequest request = recordRequest(TIME_OUT, null, List.of());
        request.setSignOutBy(CALLER.userId().toString());
        assertThatThrownBy(() -> service.record(APPLICATION_ID, request, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void recordRejectsMalformedActorIdentifier() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));

        RecordApplicationThreeWayCheckRequest request = recordRequest(TIME_OUT, null, List.of());
        request.setTimeOutBy("not-a-uuid");
        assertThatThrownBy(() -> service.record(APPLICATION_ID, request, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void recordRejectsTooManyOrTooLongIssues() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        List<String> tooMany = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
                "12", "13", "14", "15", "16", "17", "18", "19", "20", "21");
        assertThatThrownBy(() -> service.record(APPLICATION_ID,
                recordRequest(TIME_OUT, null, tooMany), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);

        String tooLong = "x".repeat(201);
        assertThatThrownBy(() -> service.record(APPLICATION_ID,
                recordRequest(TIME_OUT, null, List.of(tooLong)), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void recordRejectsInvisibleApplication() {
        when(applicationService.get(APPLICATION_ID, CALLER))
                .thenThrow(new NoSuchElementException("不可见"));

        assertThatThrownBy(() -> service.record(APPLICATION_ID,
                recordRequest(TIME_OUT, null, List.of()), CALLER, CONTEXT))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void recordRejectsInactiveApplication() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("CANCELLED"));

        assertThatThrownBy(() -> service.record(APPLICATION_ID,
                recordRequest(TIME_OUT, null, List.of()), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void recordMapsConcurrentVersionConflict() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(checkMapper.maxCheckVersion(APPLICATION_UUID)).thenReturn(1);
        when(checkMapper.insertCheck(any())).thenThrow(new DuplicateKeyException(
                "uk_three_way_check_application_version"));

        assertThatThrownBy(() -> service.record(APPLICATION_ID,
                recordRequest(TIME_OUT, null, List.of()), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    // ------------------------------------------------ cross-version time-out --
    @Test
    void recordInheritsPreviousTimeOutWhenOmitted() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(checkMapper.selectLatest(APPLICATION_UUID)).thenReturn(check(1, false));
        when(checkMapper.maxCheckVersion(APPLICATION_UUID)).thenReturn(1);
        when(checkMapper.insertCheck(any())).thenReturn(1);

        ApplicationThreeWayCheck result = service.record(APPLICATION_ID,
                recordRequest(null, SIGN_OUT, List.of()), CALLER, CONTEXT);

        assertThat(result.getTimeOutAt().toInstant()).isEqualTo(TIME_OUT.toInstant());
        ArgumentCaptor<ThreeWayCheckEntity> inserted =
                ArgumentCaptor.forClass(ThreeWayCheckEntity.class);
        verify(checkMapper).insertCheck(inserted.capture());
        assertThat(inserted.getValue().getTimeOutAt()).isEqualTo(TIME_OUT.toInstant());
        ArgumentCaptor<Map<String, Object>> detail = auditDetail("THREEWAY_RECORD");
        assertThat(detail.getValue()).containsEntry("timeOutInherited", true);
    }

    @Test
    void recordRequiresTimeOutOnFirstVersion() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(checkMapper.selectLatest(APPLICATION_UUID)).thenReturn(null);

        assertThatThrownBy(() -> service.record(APPLICATION_ID,
                recordRequest(null, null, List.of()), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("首次核查");
        verify(checkMapper, never()).insertCheck(any());
    }

    @Test
    void recordRejectsChangedTimeOutWithoutExplicitConfirmation() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(checkMapper.selectLatest(APPLICATION_UUID)).thenReturn(check(1, false));

        OffsetDateTime changed = TIME_OUT.plusMinutes(5);
        assertThatThrownBy(() -> service.record(APPLICATION_ID,
                recordRequest(changed, null, List.of()), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("显式确认");
        verify(checkMapper, never()).insertCheck(any());
        ArgumentCaptor<Map<String, Object>> failure = failureDetail();
        assertThat(failure.getValue()).containsEntry("error", "STATE_CONFLICT")
                .doesNotContainKeys("issues", "timeOutAt", "patientId");
    }

    @Test
    void recordAllowsChangedTimeOutWithExplicitConfirmation() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("SCHEDULED"));
        when(checkMapper.selectLatest(APPLICATION_UUID)).thenReturn(check(1, false));
        when(checkMapper.maxCheckVersion(APPLICATION_UUID)).thenReturn(1);
        when(checkMapper.insertCheck(any())).thenReturn(1);

        RecordApplicationThreeWayCheckRequest request =
                recordRequest(TIME_OUT.plusMinutes(5), null, List.of());
        request.setConfirmTimeOutChange(true);

        ApplicationThreeWayCheck result = service.record(APPLICATION_ID, request, CALLER, CONTEXT);

        assertThat(result.getTimeOutAt().toInstant())
                .isEqualTo(TIME_OUT.plusMinutes(5).toInstant());
    }

    @Test
    void recordInactiveApplicationFailureIsAudited() {
        when(applicationService.get(APPLICATION_ID, CALLER)).thenReturn(application("CANCELLED"));

        assertThatThrownBy(() -> service.record(APPLICATION_ID,
                recordRequest(TIME_OUT, null, List.of()), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
        ArgumentCaptor<Map<String, Object>> failure = failureDetail();
        assertThat(failure.getValue())
                .containsEntry("applicationId", APPLICATION_ID)
                .containsEntry("error", "STATE_CONFLICT")
                .doesNotContainKeys("issues", "patientId", "specimenChecked");
    }

    // ------------------------------------------------------------- completion --

    @Test
    void isThreeWayCheckCompletedReflectsLatestVersion() {
        when(checkMapper.selectLatest(APPLICATION_UUID)).thenReturn(check(2, true));
        assertThat(service.isThreeWayCheckCompleted(APPLICATION_ID)).isTrue();

        when(checkMapper.selectLatest(APPLICATION_UUID)).thenReturn(check(1, false));
        assertThat(service.isThreeWayCheckCompleted(APPLICATION_ID)).isFalse();

        when(checkMapper.selectLatest(APPLICATION_UUID)).thenReturn(null);
        assertThat(service.isThreeWayCheckCompleted(APPLICATION_ID)).isFalse();

        assertThat(service.isThreeWayCheckCompleted("invalid")).isFalse();
    }

    // --------------------------------------------------------------- helpers --

    private ArgumentCaptor<Map<String, Object>> failureDetail() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(failureAuditor).recordFailure(eq("THREEWAY_RECORD"), eq("THREE_WAY_CHECK"),
                eq(APPLICATION_ID), captor.capture(), eq(CALLER), eq(CONTEXT));
        return captor;
    }

    private ArgumentCaptor<Map<String, Object>> auditDetail(String action) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(auditWriter).record(eq(action), eq("THREE_WAY_CHECK"), any(), eq(true),
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

    private ThreeWayCheckEntity check(int version, boolean signedOut) {
        ThreeWayCheckEntity entity = new ThreeWayCheckEntity();
        entity.setId(UUID.nameUUIDFromBytes(("check-" + version).getBytes()));
        entity.setApplicationId(APPLICATION_UUID);
        entity.setCheckVersion(version);
        entity.setTimeOutAt(TIME_OUT.toInstant());
        entity.setTimeOutBy(CALLER.userId());
        if (signedOut) {
            entity.setSignOutAt(SIGN_OUT.toInstant());
            entity.setSignOutBy(CALLER.userId());
        }
        entity.setSpecimenChecked(true);
        entity.setImplantChecked(true);
        entity.setIssuesJson("[]");
        entity.setCreatedAt(NOW.minusSeconds(600));
        entity.setCreatedBy(CALLER.userId());
        entity.setUpdatedAt(NOW.minusSeconds(600));
        entity.setUpdatedBy(CALLER.userId());
        return entity;
    }

    private RecordApplicationThreeWayCheckRequest recordRequest(OffsetDateTime timeOutAt,
            OffsetDateTime signOutAt, List<String> issues) {
        RecordApplicationThreeWayCheckRequest request = new RecordApplicationThreeWayCheckRequest();
        request.setTimeOutAt(timeOutAt);
        request.setSignOutAt(signOutAt);
        request.setSpecimenChecked(true);
        request.setImplantChecked(true);
        request.setIssues(issues);
        return request;
    }
}
