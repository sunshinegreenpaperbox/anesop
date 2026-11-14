package com.anesop.m4.impl.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.staff.model.CreateStaffRequest;
import com.anesop.contracts.staff.model.Staff;
import com.anesop.contracts.staff.model.StaffList;
import com.anesop.contracts.staff.model.StaffQualification;
import com.anesop.contracts.staff.model.StaffQualificationInput;
import com.anesop.contracts.auth.model.UserStatus;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.contracts.staff.model.UpdateStaffRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m0.api.security.UserAdminService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
import com.anesop.m4.impl.persistence.entity.StaffQualificationEntity;
import com.anesop.m4.impl.persistence.mapper.StaffProfileMapper;
import com.anesop.m4.impl.persistence.mapper.StaffQualificationMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.anesop.m4.impl.support.StaffAccessGuard;
import com.anesop.m4.impl.support.StaffJson;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * 人员档案服务单测（M4-M13-01）：工号唯一、资质有效期、乐观锁、SELF 只读与审计脱敏。
 *
 * <p>Mock Mapper/数据范围守卫；真实 jsonb 编解码（StaffJson + Jackson）。数据库唯一索引与
 * 触发器由集成测试验证。断言可观察行为（异常字段/DTO/审计 detail 不含姓名与证书号）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaffProfileServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STAFF_ID = UUID.fromString("3a000000-0000-4000-8000-000000000001");
    private static final String DEPT = "f1000000-0000-4000-8000-000000000002";
    private static final UUID DEPT_ID = UUID.fromString(DEPT);
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_anes", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-2001");

    @Mock
    private StaffProfileMapper profileMapper;
    @Mock
    private StaffQualificationMapper qualificationMapper;
    @Mock
    private StaffAccessGuard accessGuard;
    @Mock
    private OrganizationQueryService organizationQueryService;
    @Mock
    private UserAdminService userAdminService;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private M4FailureAuditor failureAuditor;

    private StaffProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StaffProfileServiceImpl(profileMapper, qualificationMapper, accessGuard,
                organizationQueryService, userAdminService, auditWriter, failureAuditor,
                new StaffJson(new ObjectMapper()), Clock.fixed(NOW, ZoneOffset.UTC));
        when(userAdminService.get(any())).thenReturn(new UserSummary()
                .userId("10000000-0000-4000-8000-000000000012").username("synth_user")
                .displayName("合成用户").status(UserStatus.ACTIVE).roles(List.of())
                .mustChangePassword(false).createdAt(NOW.atOffset(ZoneOffset.UTC)));
        when(accessGuard.visibility(any())).thenReturn(
                new StaffAccessGuard.StaffVisibility(null, null));
        doNothing().when(accessGuard).requireWritable(any());
    }

    @Test
    void createPersistsProfileAndQualificationsWithoutPersonalDataInAudit() {
        when(accessGuard.requireDepartment(any(), eq(DEPT), eq("departmentId"))).thenReturn(DEPT);
        when(profileMapper.insertProfile(any())).thenReturn(1);
        when(qualificationMapper.selectByStaff(any())).thenReturn(List.of(qualification()));

        Staff created = service.create(request(), CALLER, CONTEXT);

        assertThat(created.getStaffId()).isNotBlank();
        assertThat(created.getStaffNo()).isEqualTo("STAFF-ANES-001");
        assertThat(created.getDepartmentId()).isEqualTo(DEPT);
        assertThat(created.getEmploymentStatus()).isEqualTo(Staff.EmploymentStatusEnum.ACTIVE);
        assertThat(created.getSpecialties()).containsExactly("心血管麻醉");
        assertThat(created.getQualifications()).hasSize(1);
        assertThat(created.getQualifications().get(0).getExpired()).isFalse();
        verify(qualificationMapper).insertQualification(any());
        ArgumentCaptor<Map<String, Object>> detail = detailCaptor();
        verify(auditWriter).record(eq("STAFF_CREATE"), eq("STAFF"), anyString(), eq(true),
                detail.capture(), eq(ACTOR), eq("synth_anes"), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("staffNo", "STAFF-ANES-001");
        assertThat(detail.getValue()).containsKey("qualificationCount");
        assertThat(detail.getValue()).doesNotContainKeys("name", "certificateNo");
    }

    @Test
    void createRejectsDuplicateStaffNoAndRecordsFailureAudit() {
        when(accessGuard.requireDepartment(any(), eq(DEPT), eq("departmentId"))).thenReturn(DEPT);
        when(profileMapper.selectByStaffNo("STAFF-ANES-001")).thenReturn(new StaffProfileEntity());

        assertThatThrownBy(() -> service.create(request(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("工号");
        verify(failureAuditor).recordFailure(eq("STAFF_CREATE"), eq("STAFF"), eq("NEW"),
                any(), eq(CALLER), eq(CONTEXT));
    }

    @Test
    void createRejectsInvalidStaffNoPattern() {
        CreateStaffRequest request = request();
        request.setStaffNo("工号 张三");

        assertThatThrownBy(() -> service.create(request, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("工号");
    }

    @Test
    void createRejectsQualificationWithInvalidValidity() {
        when(accessGuard.requireDepartment(any(), eq(DEPT), eq("departmentId"))).thenReturn(DEPT);
        CreateStaffRequest request = request();
        request.getQualifications().get(0).setValidTo(LocalDate.parse("2019-01-01"));

        assertThatThrownBy(() -> service.create(request, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("有效期止");
    }

    @Test
    void createDeniedForSelfScope() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("本人数据范围仅允许查看"))
                .when(accessGuard).requireWritable(any());

        assertThatThrownBy(() -> service.create(request(), CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void updateRejectsStaleVersion() {
        when(profileMapper.selectById(STAFF_ID)).thenReturn(profile(3));
        UpdateStaffRequest request = updateRequest(2);

        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), request, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void updateReplacesQualificationsAndBumpsVersion() {
        when(profileMapper.selectById(STAFF_ID)).thenReturn(profile(1));
        when(accessGuard.requireDepartment(any(), eq(DEPT), eq("departmentId"))).thenReturn(DEPT);
        when(profileMapper.updateProfile(any())).thenReturn(1);
        when(qualificationMapper.selectByStaff(any())).thenReturn(List.of(qualification()));

        Staff updated = service.update(STAFF_ID.toString(), updateRequest(1), CALLER, CONTEXT);

        verify(qualificationMapper).deleteByStaff(STAFF_ID);
        verify(profileMapper).updateProfile(any());
        assertThat(updated.getVersion()).isEqualTo(1);
        assertThat(updated.getQualifications()).hasSize(1);
    }

    @Test
    void getRejectsUnknownStaff() {
        when(profileMapper.selectById(STAFF_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.get(STAFF_ID.toString(), CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listAppliesSelfScopeFilterAndPaging() {
        when(accessGuard.visibility(any())).thenReturn(
                new StaffAccessGuard.StaffVisibility(null, ACTOR.toString()));
        when(profileMapper.selectPage(any(), any(), any(), any(), eq(ACTOR.toString()), anyInt(),
                anyInt())).thenReturn(List.of(profile(1)));
        when(profileMapper.countPage(any(), any(), any(), any(), eq(ACTOR.toString())))
                .thenReturn(1L);
        when(profileMapper.selectById(any())).thenReturn(profile(1));

        StaffList list = service.list("", null, "ACTIVE", 1, 20, CALLER);

        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getTotal()).isEqualTo(1);
        verify(profileMapper).selectPage(any(), eq("ACTIVE"), any(), any(),
                eq(ACTOR.toString()), eq(20), eq(0));
    }

    @Test
    void listRejectsInvalidEmploymentStatusAndPaging() {
        assertThatThrownBy(() -> service.list(null, null, "RETIRED", 1, 20, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("ACTIVE");
        assertThatThrownBy(() -> service.list(null, null, null, 0, 20, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("页码");
        assertThatThrownBy(() -> service.list(null, null, null, 1, 500, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("每页");
    }

    // ------------------------------------------------------------------ 夹具 --

    private static ArgumentCaptor<Map<String, Object>> detailCaptor() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        return captor;
    }

    private static CreateStaffRequest request() {
        CreateStaffRequest request = new CreateStaffRequest();
        request.setUserId("10000000-0000-4000-8000-000000000012");
        request.setStaffNo("STAFF-ANES-001");
        request.setName("演示麻醉医师甲（合成）");
        request.setDepartmentId(DEPT);
        request.setTitle("主任医师");
        request.setJobGrade("正高");
        request.setSpecialties(List.of("心血管麻醉", "心血管麻醉"));
        StaffQualificationInput qualification = new StaffQualificationInput();
        qualification.setQualificationType(
                StaffQualificationInput.QualificationTypeEnum.PRACTICE_LICENSE);
        qualification.setCertificateNo("SYNTH-CERT-0001");
        qualification.setIssuingAuthority("省卫生健康委员会（合成）");
        qualification.setValidFrom(LocalDate.parse("2020-01-01"));
        qualification.setValidTo(LocalDate.parse("2030-12-31"));
        request.setQualifications(List.of(qualification));
        return request;
    }

    private static UpdateStaffRequest updateRequest(int version) {
        UpdateStaffRequest request = new UpdateStaffRequest();
        request.setName("演示麻醉医师甲（合成）");
        request.setDepartmentId(DEPT);
        request.setTitle("副主任医师");
        request.setEmploymentStatus(UpdateStaffRequest.EmploymentStatusEnum.ACTIVE);
        request.setVersion(version);
        return request;
    }

    private static StaffProfileEntity profile(int version) {
        StaffProfileEntity entity = new StaffProfileEntity();
        entity.setId(STAFF_ID);
        entity.setUserId(UUID.fromString("10000000-0000-4000-8000-000000000012"));
        entity.setStaffNo("STAFF-ANES-001");
        entity.setName("演示麻醉医师甲（合成）");
        entity.setDepartmentId(DEPT_ID);
        entity.setTitle("主任医师");
        entity.setEmploymentStatus("ACTIVE");
        entity.setSpecialties("[]");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        entity.setUpdatedAt(NOW);
        entity.setUpdatedBy(ACTOR);
        entity.setVersion(version);
        return entity;
    }

    private static StaffQualificationEntity qualification() {
        StaffQualificationEntity entity = new StaffQualificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setStaffId(STAFF_ID);
        entity.setQualificationType("PRACTICE_LICENSE");
        entity.setCertificateNo("SYNTH-CERT-0001");
        entity.setIssuingAuthority("省卫生健康委员会（合成）");
        entity.setValidFrom(LocalDate.parse("2020-01-01"));
        entity.setValidTo(LocalDate.parse("2030-12-31"));
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        return entity;
    }
}
