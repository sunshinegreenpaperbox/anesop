package com.anesop.m1.impl.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.patient.model.Patient;
import com.anesop.contracts.patient.model.PatientPage;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m1.api.patient.PatientQuery;
import com.anesop.m1.impl.persistence.entity.PatientSnapshotEntity;
import com.anesop.m1.impl.persistence.mapper.PatientSnapshotMapper;
import com.anesop.m1.impl.support.InpatientNoHasher;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Instant;
import java.time.LocalDate;
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
class PatientQueryServiceImplTest {

    private static final UUID DEPT_ANES = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final UUID DEPT_OR = UUID.fromString("f1000000-0000-4000-8000-000000000003");
    private static final UUID ACTOR_ID = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR_ID, "demo_admin", "jti-1", NOW.plusSeconds(900), "sid-1");

    @Mock
    private PatientSnapshotMapper patientSnapshotMapper;
    @Mock
    private DataScopeResolver dataScopeResolver;

    private PatientQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PatientQueryServiceImpl(patientSnapshotMapper, dataScopeResolver,
                new InpatientNoHasher("c3ludGhldGljLXBlcHBlci0wMTIzNDU2Nzg5LWFuZXNvcC1tMS10ZXN0", false));
        when(dataScopeResolver.resolve(any())).thenReturn(allScope());
    }

    @Test
    void listAppliesFiltersAndMapsMaskedSummary() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        when(patientSnapshotMapper.selectCount(any())).thenReturn(1L);
        when(patientSnapshotMapper.selectList(any())).thenReturn(List.of(patient));

        PatientPage page = service.list(
                new PatientQuery(1, 20, "SYNTH-HIS-P-0001", null, "演示", "SYNTH-ZY-0001",
                        DEPT_ANES.toString()), CALLER);

        assertThat(page.getMeta().getTotal()).isEqualTo(1);
        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getInpatientNoMasked()).isEqualTo("SYNT****0001");
        assertThat(page.getItems().get(0).getDepartmentName()).isEqualTo("演示外科");
        assertThat(page.getItems().get(0).getWard()).isEqualTo("演示病区");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(patientSnapshotMapper).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment())
                .contains("his_patient_no", "name", "inpatient_no_hash", "dept_id");
    }

    @Test
    void listRejectsDeniedScopeAndInvalidDepartment() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> service.list(new PatientQuery(1, 20, null, null, null, null, null),
                CALLER)).isInstanceOf(AccessDeniedException.class);

        when(dataScopeResolver.resolve(any())).thenReturn(allScope());
        when(patientSnapshotMapper.selectCount(any())).thenReturn(0L);
        service.list(new PatientQuery(1, 20, null, null, null, null, "not-a-uuid"), CALLER);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(patientSnapshotMapper).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("1 = 0");
    }

    @Test
    void listUsesCreatorFilterForSelfScope() {
        when(dataScopeResolver.resolve(any())).thenReturn(selfScope());
        when(patientSnapshotMapper.selectCount(any())).thenReturn(0L);

        service.list(new PatientQuery(1, 20, null, null, null, null, null), CALLER);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(patientSnapshotMapper).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("created_by");
    }

    @Test
    void getReturnsDetailWithoutIdentityOrPhoneFields() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        when(patientSnapshotMapper.selectById(patient.getId())).thenReturn(patient);

        Patient detail = service.get(patient.getId().toString(), CALLER);

        assertThat(detail.getBirthDate()).isEqualTo(LocalDate.of(1978, 3, 15));
        assertThat(detail.getAge()).isEqualTo(47);
        assertThat(detail.getInpatientNoMasked()).isEqualTo("SYNT****0001");
        assertThat(detail.getHisPatientNo()).isEqualTo("SYNTH-HIS-P-0001");
    }

    @Test
    void getReturns404SemanticsForMissingOrOutOfScopePatient() {
        assertThatThrownBy(() -> service.get("not-a-uuid", CALLER))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID().toString(), CALLER))
                .isInstanceOf(java.util.NoSuchElementException.class);

        PatientSnapshotEntity other = patient(DEPT_OR);
        when(patientSnapshotMapper.selectById(other.getId())).thenReturn(other);
        when(dataScopeResolver.resolve(any())).thenReturn(deptScope(DEPT_ANES));
        assertThatThrownBy(() -> service.get(other.getId().toString(), CALLER))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void selfScopeCannotReadOthersPatient() {
        PatientSnapshotEntity patient = patient(DEPT_ANES);
        patient.setCreatedBy(UUID.randomUUID());
        when(patientSnapshotMapper.selectById(patient.getId())).thenReturn(patient);
        when(dataScopeResolver.resolve(any())).thenReturn(selfScope());

        assertThatThrownBy(() -> service.get(patient.getId().toString(), CALLER))
                .isInstanceOf(java.util.NoSuchElementException.class);
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
}
