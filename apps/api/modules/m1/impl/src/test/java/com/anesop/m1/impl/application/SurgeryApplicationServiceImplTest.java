package com.anesop.m1.impl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.scheduling.model.CancelSurgeryApplicationRequest;
import com.anesop.contracts.scheduling.model.IdResponse;
import com.anesop.contracts.scheduling.model.ImportSurgeryApplicationsRequest;
import com.anesop.contracts.scheduling.model.ImportSurgeryApplicationsResult;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.contracts.scheduling.model.SurgeryApplicationCreateRequest;
import com.anesop.contracts.scheduling.model.SurgeryApplicationPage;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m1.api.application.PatientNotInIndexException;
import com.anesop.m1.api.application.SurgeryApplicationConflictException;
import com.anesop.m1.api.application.SurgeryApplicationQuery;
import com.anesop.m1.api.application.SurgeryApplicationValidationException;
import com.anesop.m1.api.his.HisSurgeryApplicationBatch;
import com.anesop.m1.api.his.HisSurgeryApplicationPort;
import com.anesop.m1.api.his.HisSurgeryApplicationRecord;
import com.anesop.m1.impl.persistence.entity.ApplicationStatusHistoryEntity;
import com.anesop.m1.impl.persistence.entity.PatientSnapshotEntity;
import com.anesop.m1.impl.persistence.entity.SurgeryApplicationEntity;
import com.anesop.m1.impl.persistence.mapper.ApplicationStatusHistoryMapper;
import com.anesop.m1.impl.persistence.mapper.PatientSnapshotMapper;
import com.anesop.m1.impl.persistence.mapper.SurgeryApplicationMapper;
import com.anesop.m1.impl.support.InpatientNoHasher;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SurgeryApplicationServiceImplTest {

    private static final UUID DEPT_ANES = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final UUID DEPT_OR = UUID.fromString("f1000000-0000-4000-8000-000000000003");
    private static final UUID ACTOR_ID = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR_ID, "demo_admin", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT = new AuthRequestContext("10.0.0.1", "test", "trace-1");

    @Mock
    private PatientSnapshotMapper patientSnapshotMapper;
    @Mock
    private SurgeryApplicationMapper applicationMapper;
    @Mock
    private ApplicationStatusHistoryMapper historyMapper;
    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private HisSurgeryApplicationPort hisPort;

    private SurgeryApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SurgeryApplicationServiceImpl(patientSnapshotMapper, applicationMapper, historyMapper,
                dataScopeResolver, auditWriter, hisPort, new InpatientNoHasher("c3ludGhldGljLXBlcHBlci0wMTIzNDU2Nzg5LWFuZXNvcC1tMS10ZXN0", false),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(dataScopeResolver.resolve(any())).thenReturn(allScope());
        when(patientSnapshotMapper.upsertReturningId(any(PatientSnapshotEntity.class)))
                .thenAnswer(invocation -> ((PatientSnapshotEntity) invocation.getArgument(0)).getId());
    }

    @Test
    void submitCreatesSubmittedApplicationWithHistoryAndAudit() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        when(patientSnapshotMapper.selectById(patient.getId())).thenReturn(patient);
        when(applicationMapper.selectCount(any())).thenReturn(0L);
        when(applicationMapper.insert(any(SurgeryApplicationEntity.class))).thenReturn(1);
        when(historyMapper.insert(any(ApplicationStatusHistoryEntity.class))).thenReturn(1);

        SurgeryApplication result = service.submit(createRequest(patient.getId()), CALLER, CONTEXT);

        assertThat(result.getStatus()).isEqualTo(SurgeryApplication.StatusEnum.SUBMITTED);
        assertThat(result.getSource()).isEqualTo(SurgeryApplication.SourceEnum.MANUAL);
        assertThat(result.getVersion()).isEqualTo(1);
        assertThat(result.getPatient().getInpatientNoMasked()).isEqualTo("SYNT****0001");
        assertThat(result.getPatient().getDepartmentId()).isEqualTo(DEPT_ANES.toString());
        assertThat(result.getStatusHistory()).hasSize(1);
        assertThat(result.getStatusHistory().get(0).getFromStatus()).isNull();
        verify(historyMapper).insert(any(ApplicationStatusHistoryEntity.class));
        verify(auditWriter).record(eq("SURGERY_APPLICATION_CREATE"), eq("SURGERY_APPLICATION"),
                anyString(), eq(true), anyMap(), eq(ACTOR_ID), eq("demo_admin"), eq(CONTEXT));
    }

    @Test
    void submitRejectsDuplicateApplicationNumber() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        when(patientSnapshotMapper.selectById(patient.getId())).thenReturn(patient);
        when(applicationMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.submit(createRequest(patient.getId()), CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationConflictException.class);
        verify(applicationMapper, never()).insert(any(SurgeryApplicationEntity.class));
    }

    @Test
    void submitRejectsUnknownPatient() {
        UUID patientId = UUID.randomUUID();
        when(patientSnapshotMapper.selectById(patientId)).thenReturn(null);

        assertThatThrownBy(() -> service.submit(createRequest(patientId), CALLER, CONTEXT))
                .isInstanceOf(PatientNotInIndexException.class);
    }

    @Test
    void submitRejectsInvalidDurationAndUrgency() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        when(patientSnapshotMapper.selectById(patient.getId())).thenReturn(patient);

        SurgeryApplicationCreateRequest zeroDuration = createRequest(patient.getId());
        zeroDuration.setPlannedDurationMin(0);
        assertThatThrownBy(() -> service.submit(zeroDuration, CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationValidationException.class)
                .hasMessageContaining("时长");

        SurgeryApplicationCreateRequest noUrgency = createRequest(patient.getId());
        noUrgency.setUrgency(null);
        assertThatThrownBy(() -> service.submit(noUrgency, CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationValidationException.class)
                .hasMessageContaining("紧急度");
    }

    @Test
    void submitRejectsBlankAndOverlongFields() {
        SurgeryApplicationCreateRequest blank = createRequest(UUID.randomUUID());
        blank.setHisApplicationNo(" ");
        assertThatThrownBy(() -> service.submit(blank, CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationValidationException.class);

        SurgeryApplicationCreateRequest overlong = createRequest(UUID.randomUUID());
        overlong.setSurgeryName("演示术式".repeat(60));
        assertThatThrownBy(() -> service.submit(overlong, CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationValidationException.class);
    }

    @Test
    void deniedScopeRejectsSubmitAndList() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.denied());

        assertThatThrownBy(() -> service.submit(createRequest(UUID.randomUUID()), CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.list(
                new SurgeryApplicationQuery(1, 20, null, null, null), CALLER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deptScopeRejectsPatientOutsideDepartmentOnSubmit() {
        when(dataScopeResolver.resolve(any())).thenReturn(deptScope(DEPT_ANES));
        PatientSnapshotEntity other = patient(DEPT_OR);
        when(patientSnapshotMapper.selectById(other.getId())).thenReturn(other);

        assertThatThrownBy(() -> service.submit(createRequest(other.getId()), CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listAppliesFiltersAndMapsPage() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        SurgeryApplicationEntity application = application(patient.getId(), "SUBMITTED", 1L);
        when(applicationMapper.selectCount(any())).thenReturn(1L);
        when(applicationMapper.selectList(any())).thenReturn(List.of(application));
        when(patientSnapshotMapper.selectBatchIds(any())).thenReturn(List.of(patient));

        SurgeryApplicationPage page = service.list(
                new SurgeryApplicationQuery(1, 10, "submitted", "urgent", "演示外科"), CALLER);

        assertThat(page.getMeta().getPage()).isEqualTo(1);
        assertThat(page.getMeta().getTotal()).isEqualTo(1);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getStatusHistory()).isEmpty();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(applicationMapper).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("status", "urgency", "dept_name");
    }

    @Test
    void listClampsPaginationAndRejectsUnknownStatus() {
        when(applicationMapper.selectCount(any())).thenReturn(0L);
        SurgeryApplicationPage page = service.list(
                new SurgeryApplicationQuery(0, 1000, null, null, null), CALLER);
        assertThat(page.getMeta().getPage()).isEqualTo(1);
        assertThat(page.getMeta().getSize()).isEqualTo(200);

        assertThatThrownBy(() -> service.list(
                new SurgeryApplicationQuery(1, 20, "UNKNOWN", null, null), CALLER))
                .isInstanceOf(SurgeryApplicationValidationException.class);
        
        assertThatThrownBy(() -> service.list(
                new SurgeryApplicationQuery(1, 20, null, "UNKNOWN", null), CALLER))
                .isInstanceOf(SurgeryApplicationValidationException.class);
    }

    @Test
    void selfScopeFiltersListByCreator() {
        when(dataScopeResolver.resolve(any())).thenReturn(selfScope());
        when(applicationMapper.selectCount(any())).thenReturn(0L);

        service.list(new SurgeryApplicationQuery(1, 20, null, null, null), CALLER);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(applicationMapper).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("created_by");
    }

    @Test
    void detailReturnsHistoryAndHidesOtherDepartment() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        SurgeryApplicationEntity application = application(patient.getId(), "SUBMITTED", 1L);
        when(applicationMapper.selectById(application.getId())).thenReturn(application);
        when(patientSnapshotMapper.selectById(patient.getId())).thenReturn(patient);
        when(historyMapper.selectList(any())).thenReturn(List.of(history(application.getId(), null, "SUBMITTED")));

        SurgeryApplication detail = service.get(application.getId().toString(), CALLER);
        assertThat(detail.getStatusHistory()).hasSize(1);
        assertThat(detail.getHisApplicationNo()).isEqualTo("SYNTH-HIS-APP-0001");

        when(dataScopeResolver.resolve(any())).thenReturn(deptScope(DEPT_OR));
        assertThatThrownBy(() -> service.get(application.getId().toString(), CALLER))
                .isInstanceOf(java.util.NoSuchElementException.class);

        assertThatThrownBy(() -> service.get("not-a-uuid", CALLER))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void selfScopeCannotReadOthersApplication() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        SurgeryApplicationEntity application = application(patient.getId(), "SUBMITTED", 1L);
        application.setCreatedBy(UUID.randomUUID());
        when(dataScopeResolver.resolve(any())).thenReturn(selfScope());
        when(applicationMapper.selectById(application.getId())).thenReturn(application);

        assertThatThrownBy(() -> service.get(application.getId().toString(), CALLER))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void cancelWritesHistoryAndAudit() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        SurgeryApplicationEntity application = application(patient.getId(), "SUBMITTED", 1L);
        when(applicationMapper.selectById(application.getId())).thenReturn(application);
        when(applicationMapper.updateById(any(SurgeryApplicationEntity.class))).thenReturn(1);
        when(historyMapper.insert(any(ApplicationStatusHistoryEntity.class))).thenReturn(1);

        IdResponse response = service.cancel(application.getId().toString(), cancelRequest(1), CALLER, CONTEXT);

        assertThat(response.getId()).isEqualTo(application.getId().toString());
        assertThat(application.getStatus()).isEqualTo("CANCELLED");
        verify(historyMapper).insert(any(ApplicationStatusHistoryEntity.class));
        verify(auditWriter).record(eq("SURGERY_APPLICATION_CANCEL"), eq("SURGERY_APPLICATION"),
                eq(application.getId().toString()), eq(true), anyMap(), eq(ACTOR_ID),
                eq("demo_admin"), eq(CONTEXT));
    }

    @Test
    void cancelRejectsTerminalStateAndVersionConflict() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        SurgeryApplicationEntity cancelled = application(patient.getId(), "CANCELLED", 2L);
        when(applicationMapper.selectById(cancelled.getId())).thenReturn(cancelled);
        assertThatThrownBy(() -> service.cancel(cancelled.getId().toString(), cancelRequest(2), CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationConflictException.class);

        SurgeryApplicationEntity submitted = application(patient.getId(), "SUBMITTED", 3L);
        when(applicationMapper.selectById(submitted.getId())).thenReturn(submitted);
        assertThatThrownBy(() -> service.cancel(submitted.getId().toString(), cancelRequest(2), CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationConflictException.class);
    }

    @Test
    void cancelRejectsOptimisticLockRace() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        SurgeryApplicationEntity application = application(patient.getId(), "SUBMITTED", 1L);
        when(applicationMapper.selectById(application.getId())).thenReturn(application);
        when(applicationMapper.updateById(any(SurgeryApplicationEntity.class))).thenReturn(0);

        assertThatThrownBy(() -> service.cancel(application.getId().toString(), cancelRequest(1), CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationConflictException.class);
    }

    @Test
    void importIsIdempotentAndMarksSimulated() {
        HisSurgeryApplicationRecord first = hisRecord("SYNTH-HIS-APP-0001", "SYNTH-P-0001");
        HisSurgeryApplicationRecord second = hisRecord("SYNTH-HIS-APP-0002", "SYNTH-P-0002");
        when(hisPort.pull(3, 42L))
                .thenReturn(new HisSurgeryApplicationBatch(true, "MOCK_HIS", List.of(first, second)));
        when(applicationMapper.insertIfAbsent(any())).thenReturn(1, 0);
        when(historyMapper.insert(any(ApplicationStatusHistoryEntity.class))).thenReturn(1);

        ImportSurgeryApplicationsResult result =
                service.importFromHis(importRequest(), CALLER, CONTEXT);

        assertThat(result.getSimulated()).isTrue();
        assertThat(result.getAdapter()).isEqualTo(ImportSurgeryApplicationsResult.AdapterEnum.MOCK_HIS);
        assertThat(result.getRequestedCount()).isEqualTo(2);
        assertThat(result.getImportedCount()).isEqualTo(1);
        assertThat(result.getSkippedCount()).isEqualTo(1);
        assertThat(result.getApplications()).hasSize(1);
        verify(auditWriter).record(eq("SURGERY_APPLICATION_IMPORT"), eq("SURGERY_APPLICATION"),
                isNull(), eq(true), anyMap(), eq(ACTOR_ID), eq("demo_admin"), eq(CONTEXT));
    }

    @Test
    void importSkipsRecordsOutsideDepartmentScope() {
        HisSurgeryApplicationRecord record = hisRecord("SYNTH-HIS-APP-0009", "SYNTH-P-0009");
        when(dataScopeResolver.resolve(any())).thenReturn(deptScope(DEPT_OR));
        when(hisPort.pull(3, null))
                .thenReturn(new HisSurgeryApplicationBatch(true, "MOCK_HIS", List.of(record)));

        ImportSurgeryApplicationsResult result =
                service.importFromHis(importRequestWithoutSeed(), CALLER, CONTEXT);

        assertThat(result.getImportedCount()).isZero();
        assertThat(result.getSkippedCount()).isEqualTo(1);
        verify(patientSnapshotMapper, never()).upsertReturningId(any(PatientSnapshotEntity.class));
    }

    @Test
    void listBeyondTotalAndMissingPatientSnapshotAreTolerated() {
        when(applicationMapper.selectCount(any())).thenReturn(1L);
        SurgeryApplicationPage empty = service.list(
                new SurgeryApplicationQuery(2, 10, null, null, null), CALLER);
        assertThat(empty.getItems()).isEmpty();
        assertThat(empty.getMeta().getTotalPages()).isEqualTo(1);

        SurgeryApplicationEntity application = application(UUID.randomUUID(), "SUBMITTED", 1L);
        when(applicationMapper.selectList(any())).thenReturn(List.of(application));
        when(patientSnapshotMapper.selectBatchIds(any())).thenReturn(List.of());
        SurgeryApplicationPage page = service.list(
                new SurgeryApplicationQuery(1, 10, null, null, null), CALLER);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getPatient().getPatientId()).isNull();
    }

    @Test
    void deptScopeWithoutOrgBindingFailsClosed() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of()))));
        assertThatThrownBy(() -> service.list(
                new SurgeryApplicationQuery(1, 20, null, null, null), CALLER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void importRejectsOutOfRangeCount() {
        ImportSurgeryApplicationsRequest zero = new ImportSurgeryApplicationsRequest();
        zero.setCount(0);
        assertThatThrownBy(() -> service.importFromHis(zero, CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationValidationException.class)
                .hasMessageContaining("1–5");

        ImportSurgeryApplicationsRequest tooMany = new ImportSurgeryApplicationsRequest();
        tooMany.setCount(6);
        assertThatThrownBy(() -> service.importFromHis(tooMany, CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationValidationException.class)
                .hasMessageContaining("1–5");
    }

    @Test
    void importRejectsMissingBirthDateWithoutFabricatingAge() {
        HisSurgeryApplicationRecord missingBirthDate = new HisSurgeryApplicationRecord(
                "SYNTH-HIS-APP-0012", "SYNTH-P-0012", "演示患者甲", "FEMALE",
                null, null, "SYNTH-ZY-0012", "演示外科", DEPT_ANES, null,
                "演示病区", "12", "演示术式（全膝关节置换术）", "SYNTH-OP-0001", "ELECTIVE",
                150, "演示术者", "演示诊断（膝关节退行性病变）", "模拟 HIS 申请");
        when(hisPort.pull(1, null))
                .thenReturn(new HisSurgeryApplicationBatch(true, "MOCK_HIS", List.of(missingBirthDate)));

        ImportSurgeryApplicationsRequest request = new ImportSurgeryApplicationsRequest();
        request.setCount(1);
        assertThatThrownBy(() -> service.importFromHis(request, CALLER, CONTEXT))
                .isInstanceOf(SurgeryApplicationValidationException.class)
                .hasMessageContaining("出生日期");
        verify(patientSnapshotMapper, never()).upsertReturningId(any(PatientSnapshotEntity.class));
    }

    @Test
    void cancelStoresReasonCodeAndReasonSeparately() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        SurgeryApplicationEntity application = application(patient.getId(), "SUBMITTED", 1L);
        when(applicationMapper.selectById(application.getId())).thenReturn(application);
        when(applicationMapper.updateById(any(SurgeryApplicationEntity.class))).thenReturn(1);
        when(historyMapper.insert(any(ApplicationStatusHistoryEntity.class))).thenReturn(1);

        service.cancel(application.getId().toString(), cancelRequest(1), CALLER, CONTEXT);

        ArgumentCaptor<ApplicationStatusHistoryEntity> captor =
                ArgumentCaptor.forClass(ApplicationStatusHistoryEntity.class);
        verify(historyMapper).insert(captor.capture());
        assertThat(captor.getValue().getReasonCode()).isEqualTo("PATIENT_CONDITION");
        assertThat(captor.getValue().getReason()).isEqualTo("演示取消原因（合成数据）");
    }

    private static DataScopeAssignments allScope() {
        return DataScopeAssignments.of(List.of(new DataScopeAssignment(DataScope.ALL, List.of())));
    }

    private static DataScopeAssignments selfScope() {
        return DataScopeAssignments.of(List.of(new DataScopeAssignment(DataScope.SELF, List.of())));
    }

    private static DataScopeAssignments deptScope(UUID deptId) {
        return DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of(deptId.toString()))));
    }

    private static SurgeryApplicationCreateRequest createRequest(UUID patientId) {
        SurgeryApplicationCreateRequest request = new SurgeryApplicationCreateRequest();
        request.setHisApplicationNo("SYNTH-MANUAL-APP-0001");
        request.setPatientId(patientId.toString());
        request.setSurgeryName("演示术式（腹腔镜胆囊切除术）");
        request.setSurgeryCode("SYNTH-OP-0002");
        request.setUrgency(SurgeryApplicationCreateRequest.UrgencyEnum.URGENT);
        request.setPlannedDurationMin(90);
        request.setSurgeonName("演示术者");
        request.setPreopDiagnosis("演示诊断（胆囊结石）");
        return request;
    }

    private static ImportSurgeryApplicationsRequest importRequest() {
        ImportSurgeryApplicationsRequest request = new ImportSurgeryApplicationsRequest();
        request.setCount(3);
        request.setSeed(42);
        return request;
    }

    private static ImportSurgeryApplicationsRequest importRequestWithoutSeed() {
        ImportSurgeryApplicationsRequest request = new ImportSurgeryApplicationsRequest();
        request.setCount(3);
        return request;
    }

    private static CancelSurgeryApplicationRequest cancelRequest(int version) {
        CancelSurgeryApplicationRequest request = new CancelSurgeryApplicationRequest();
        request.setReasonCode("PATIENT_CONDITION");
        request.setReason("演示取消原因（合成数据）");
        request.setVersion(version);
        return request;
    }

    private static PatientSnapshotEntity patient(UUID deptId) {
        PatientSnapshotEntity patient = new PatientSnapshotEntity();
        patient.setId(UUID.randomUUID());
        patient.setHisPatientNo("SYNTH-HIS-P-0001");
        patient.setName("演示患者");
        patient.setGender("FEMALE");
        patient.setBirthDate(LocalDate.of(1978, 3, 15));
        patient.setAge(47);
        patient.setInpatientNoMasked("SYNT****0001");
        patient.setInpatientNoHash("hash-" + patient.getId());
        patient.setDeptName("演示外科");
        patient.setDeptId(deptId);
        patient.setWard("演示病区");
        patient.setBedNo("12");
        patient.setCreatedAt(NOW);
        patient.setCreatedBy(ACTOR_ID);
        patient.setUpdatedAt(NOW);
        patient.setUpdatedBy(ACTOR_ID);
        patient.setVersion(1L);
        patient.setDeleted(false);
        return patient;
    }

    private static SurgeryApplicationEntity application(UUID patientId, String status, long version) {
        SurgeryApplicationEntity application = new SurgeryApplicationEntity();
        application.setId(UUID.randomUUID());
        application.setHisApplicationNo("SYNTH-HIS-APP-0001");
        application.setPatientId(patientId);
        application.setSurgeryName("演示术式（全膝关节置换术）");
        application.setSurgeryCode("SYNTH-OP-0001");
        application.setUrgency("ELECTIVE");
        application.setPlannedDurationMin(150);
        application.setDeptName("演示外科");
        application.setDeptId(DEPT_ANES);
        application.setSurgeonName("演示术者");
        application.setPreopDiagnosis("演示诊断（膝关节退行性病变）");
        application.setStatus(status);
        application.setSubmittedAt(NOW);
        application.setSource("HIS");
        application.setCreatedAt(NOW);
        application.setCreatedBy(ACTOR_ID);
        application.setUpdatedAt(NOW);
        application.setUpdatedBy(ACTOR_ID);
        application.setVersion(version);
        application.setDeleted(false);
        return application;
    }

    private static ApplicationStatusHistoryEntity history(UUID applicationId, String fromStatus, String toStatus) {
        ApplicationStatusHistoryEntity history = new ApplicationStatusHistoryEntity();
        history.setId(UUID.randomUUID());
        history.setApplicationId(applicationId);
        history.setFromStatus(fromStatus);
        history.setToStatus(toStatus);
        history.setOccurredAt(NOW);
        history.setOperatorId(ACTOR_ID);
        return history;
    }

    private static HisSurgeryApplicationRecord hisRecord(String applicationNo, String patientNo) {
        return new HisSurgeryApplicationRecord(applicationNo, patientNo, "演示患者甲", "FEMALE",
                LocalDate.of(1978, 3, 15), 47, "SYNTH-ZY-0001", "演示外科", DEPT_ANES, null,
                "演示病区", "12", "演示术式（全膝关节置换术）", "SYNTH-OP-0001", "ELECTIVE",
                150, "演示术者", "演示诊断（膝关节退行性病变）", "模拟 HIS 申请");
    }
}
