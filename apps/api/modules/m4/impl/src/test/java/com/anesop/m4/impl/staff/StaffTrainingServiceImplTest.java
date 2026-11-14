package com.anesop.m4.impl.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.staff.model.CreateStaffTrainingRequest;
import com.anesop.contracts.staff.model.ExpiringTrainingList;
import com.anesop.contracts.staff.model.StaffTraining;
import com.anesop.contracts.staff.model.StaffTrainingList;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
import com.anesop.m4.impl.persistence.entity.StaffTrainingEntity;
import com.anesop.m4.impl.persistence.mapper.StaffProfileMapper;
import com.anesop.m4.impl.persistence.mapper.StaffTrainingMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.anesop.m4.impl.support.StaffAccessGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 培训服务单测（M4-M13-01）：证书到期状态（VALID/EXPIRING/EXPIRED/NO_EXPIRY）、到期窗口
 * 校验、课程唯一与审计脱敏（不含证书号原文）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaffTrainingServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STAFF_ID = UUID.fromString("3a000000-0000-4000-8000-000000000001");
    private static final UUID TRAINING_ID =
            UUID.fromString("7a000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-14");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_anes", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-5001");

    @Mock
    private StaffTrainingMapper trainingMapper;
    @Mock
    private StaffProfileMapper profileMapper;
    @Mock
    private StaffAccessGuard accessGuard;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private M4FailureAuditor failureAuditor;

    private StaffTrainingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StaffTrainingServiceImpl(trainingMapper, profileMapper, accessGuard,
                auditWriter, failureAuditor, Clock.fixed(NOW, ZoneOffset.UTC));
        when(accessGuard.visibility(any())).thenReturn(
                new StaffAccessGuard.StaffVisibility(null, null));
        doNothing().when(accessGuard).requireWritable(any());
        when(profileMapper.selectById(STAFF_ID)).thenReturn(staff("ACTIVE"));
    }

    @Test
    void createPersistsTrainingAndAuditsWithoutCertificate() {
        when(trainingMapper.insertTraining(any())).thenReturn(1);

        StaffTraining created = service.create(createRequest(), CALLER, CONTEXT);

        assertThat(created.getTrainingId()).isNotBlank();
        assertThat(created.getStatus()).isEqualTo(StaffTraining.StatusEnum.VALID);
        assertThat(created.getDaysUntilExpiry()).isEqualTo(106);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("TRAINING_RECORD"), eq("STAFF_TRAINING"), anyString(),
                eq(true), detail.capture(), eq(ACTOR), eq("synth_anes"), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("courseCode", "TRN-AIRWAY-001");
        assertThat(detail.getValue()).doesNotContainKeys("certificateNo");
    }

    @Test
    void createRejectsDuplicateCourseAndFutureCompletion() {
        when(trainingMapper.countCourseConflict(eq(STAFF_ID.toString()),
                eq("TRN-AIRWAY-001"))).thenReturn(1L);
        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("课程");
        verify(trainingMapper, never()).insertTraining(any());

        when(trainingMapper.countCourseConflict(anyString(), anyString())).thenReturn(0L);
        CreateStaffTrainingRequest future = createRequest();
        future.setCompletedOn(TODAY.plusDays(1));
        assertThatThrownBy(() -> service.create(future, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("完成日期");
    }

    @Test
    void createRejectsExpiryBeforeCompletion() {
        CreateStaffTrainingRequest request = createRequest();
        request.setExpiresOn(LocalDate.parse("2025-12-01"));

        assertThatThrownBy(() -> service.create(request, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("到期日期");
    }

    @Test
    void createRejectsInactiveStaff() {
        when(profileMapper.selectById(STAFF_ID)).thenReturn(staff("INACTIVE"));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("离职");
    }

    @Test
    void listComputesExpiryStatuses() {
        StaffTrainingEntity expired = training(LocalDate.parse("2025-01-01"));
        StaffTrainingEntity expiring = training(LocalDate.parse("2026-11-30"));
        StaffTrainingEntity valid = training(LocalDate.parse("2027-02-28"));
        StaffTrainingEntity noExpiry = training(null);
        noExpiry.setId(UUID.fromString("7a000000-0000-4000-8000-000000000004"));
        when(trainingMapper.selectVisible(any(), any(), any(), anyInt()))
                .thenReturn(List.of(expired, expiring, valid, noExpiry));

        StaffTrainingList list = service.list(null, 100, CALLER);

        assertThat(list.getItems()).extracting(StaffTraining::getStatus)
                .containsExactly(StaffTraining.StatusEnum.EXPIRED, StaffTraining.StatusEnum.EXPIRING,
                        StaffTraining.StatusEnum.VALID, StaffTraining.StatusEnum.NO_EXPIRY);
        assertThat(list.getItems().get(0).getDaysUntilExpiry()).isNegative();
        assertThat(list.getItems().get(3).getDaysUntilExpiry()).isNull();
    }

    @Test
    void expiringUsesDefaultThirtyDayWindowAndIncludesExpired() {
        when(trainingMapper.selectExpiring(any(), any(), any(), any())).thenReturn(
                List.of(training(LocalDate.parse("2026-01-01"))));

        ExpiringTrainingList list = service.expiring(null, null, CALLER);

        assertThat(list.getDays()).isEqualTo(30);
        assertThat(list.getAsOf()).isEqualTo(TODAY);
        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().get(0).getStatus()).isEqualTo(StaffTraining.StatusEnum.EXPIRED);
        verify(trainingMapper).selectExpiring(eq(TODAY.plusDays(30)), eq(null), eq(null), eq(null));
    }

    @Test
    void expiringRejectsInvalidWindow() {
        assertThatThrownBy(() -> service.expiring(0, null, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("提醒窗口");
        assertThatThrownBy(() -> service.expiring(366, null, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("提醒窗口");
    }

    // ------------------------------------------------------------------ 夹具 --

    private static CreateStaffTrainingRequest createRequest() {
        CreateStaffTrainingRequest request = new CreateStaffTrainingRequest();
        request.setStaffId(STAFF_ID.toString());
        request.setCourseCode("TRN-AIRWAY-001");
        request.setCourseName("困难气道处理（SYNTHETIC）");
        request.setCompletedOn(LocalDate.parse("2026-03-01"));
        request.setExpiresOn(LocalDate.parse("2027-02-28"));
        request.setCertificateNo("SYNTH-TRN-0001");
        return request;
    }

    private static StaffProfileEntity staff(String employmentStatus) {
        StaffProfileEntity entity = new StaffProfileEntity();
        entity.setId(STAFF_ID);
        entity.setStaffNo("STAFF-ANES-001");
        entity.setName("演示麻醉医师甲（合成）");
        entity.setDepartmentId(UUID.fromString("f1000000-0000-4000-8000-000000000002"));
        entity.setTitle("主任医师");
        entity.setEmploymentStatus(employmentStatus);
        entity.setSpecialties("[]");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        entity.setUpdatedAt(NOW);
        entity.setUpdatedBy(ACTOR);
        entity.setVersion(1);
        return entity;
    }

    private static StaffTrainingEntity training(LocalDate expiresOn) {
        StaffTrainingEntity entity = new StaffTrainingEntity();
        entity.setId(TRAINING_ID);
        entity.setStaffId(STAFF_ID);
        entity.setStaffNo("STAFF-ANES-001");
        entity.setCourseCode("TRN-AIRWAY-001");
        entity.setCourseName("困难气道处理（SYNTHETIC）");
        entity.setCompletedOn(LocalDate.parse("2026-03-01"));
        entity.setExpiresOn(expiresOn);
        entity.setCertificateNo("SYNTH-TRN-0001");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        return entity;
    }
}
