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

import com.anesop.contracts.staff.model.CancelStaffScheduleRequest;
import com.anesop.contracts.staff.model.CreateStaffScheduleRequest;
import com.anesop.contracts.staff.model.StaffSchedule;
import com.anesop.contracts.staff.model.StaffScheduleList;
import com.anesop.contracts.staff.model.UpdateStaffScheduleRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
import com.anesop.m4.impl.persistence.entity.StaffScheduleEntity;
import com.anesop.m4.impl.persistence.mapper.StaffProfileMapper;
import com.anesop.m4.impl.persistence.mapper.StaffScheduleMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.anesop.m4.impl.support.StaffAccessGuard;
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

/**
 * 排班服务单测（M4-M13-01）：同人员/同术间同日同班次冲突（409 + details）、乐观锁、
 * 取消留痕与已取消不可复活。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaffScheduleServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STAFF_ID = UUID.fromString("3a000000-0000-4000-8000-000000000001");
    private static final UUID SCHEDULE_ID =
            UUID.fromString("6a000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_ID = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final String DEPT = "f1000000-0000-4000-8000-000000000002";
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_or_manager", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-4001");

    @Mock
    private StaffScheduleMapper scheduleMapper;
    @Mock
    private StaffProfileMapper profileMapper;
    @Mock
    private StaffAccessGuard accessGuard;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private M4FailureAuditor failureAuditor;

    private StaffScheduleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StaffScheduleServiceImpl(scheduleMapper, profileMapper, accessGuard,
                auditWriter, failureAuditor, Clock.fixed(NOW, ZoneOffset.UTC));
        when(accessGuard.visibility(any())).thenReturn(
                new StaffAccessGuard.StaffVisibility(null, null));
        when(accessGuard.requireDepartment(any(), eq(DEPT), eq("departmentId"))).thenReturn(DEPT);
        doNothing().when(accessGuard).requireWritable(any());
        when(profileMapper.selectById(STAFF_ID)).thenReturn(staff("ACTIVE"));
    }

    @Test
    void createPersistsScheduleAndAudits() {
        when(scheduleMapper.insertSchedule(any())).thenReturn(1);
        when(scheduleMapper.selectById(any())).thenReturn(schedule("PLANNED", 1));

        StaffSchedule created = service.create(createRequest(), CALLER, CONTEXT);

        assertThat(created.getStaffId()).isEqualTo(STAFF_ID.toString());
        assertThat(created.getStaffNo()).isEqualTo("STAFF-ANES-001");
        assertThat(created.getShift()).isEqualTo(StaffSchedule.ShiftEnum.DAY);
        assertThat(created.getStatus()).isEqualTo(StaffSchedule.StatusEnum.PLANNED);
        verify(auditWriter).record(eq("SCHEDULE_CREATE"), eq("STAFF_SCHEDULE"), anyString(),
                eq(true), any(), eq(ACTOR), eq("synth_or_manager"), eq(CONTEXT));
    }

    @Test
    void createRejectsStaffAndRoomConflictsWithDetails() {
        StaffScheduleEntity staffConflict = schedule("PLANNED", 1);
        staffConflict.setId(UUID.fromString("6a000000-0000-4000-8000-000000000099"));
        StaffScheduleEntity roomConflict = schedule("PLANNED", 1);
        roomConflict.setId(UUID.fromString("6a000000-0000-4000-8000-000000000098"));
        when(scheduleMapper.selectStaffConflict(eq(STAFF_ID.toString()), any(), eq("DAY"), eq(null)))
                .thenReturn(staffConflict);
        when(scheduleMapper.selectRoomConflict(eq(ROOM_ID.toString()), any(), eq("DAY"), eq(null)))
                .thenReturn(roomConflict);

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .satisfies(error -> {
                    M4ConflictException conflict = (M4ConflictException) error;
                    assertThat(conflict.details()).extracting(M4ConflictException.Detail::field)
                            .containsExactly("staffId", "roomId");
                    assertThat(conflict.details().get(0).reason())
                            .contains("2026-11-20", "白班", "6a000000-0000-4000-8000-000000000099");
                });
        verify(scheduleMapper, never()).insertSchedule(any());
    }

    @Test
    void createMapsDuplicateKeyToConflict() {
        when(scheduleMapper.insertSchedule(any())).thenThrow(
                new org.springframework.dao.DuplicateKeyException("uk_staff_schedule"));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("冲突");
        verify(failureAuditor).recordFailure(eq("SCHEDULE_CREATE"), eq("STAFF_SCHEDULE"), eq("NEW"),
                any(), eq(CALLER), eq(CONTEXT));
    }

    @Test
    void createRejectsInactiveStaff() {
        when(profileMapper.selectById(STAFF_ID)).thenReturn(staff("INACTIVE"));

        assertThatThrownBy(() -> service.create(createRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("离职");
    }

    @Test
    void updateDetectsConflictsExcludingItself() {
        StaffScheduleEntity existing = schedule("PLANNED", 1);
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(existing);
        when(scheduleMapper.selectStaffConflict(eq(STAFF_ID.toString()), any(), eq("DAY"),
                eq(SCHEDULE_ID.toString()))).thenReturn(null);
        when(scheduleMapper.selectRoomConflict(eq(ROOM_ID.toString()), any(), eq("DAY"),
                eq(SCHEDULE_ID.toString()))).thenReturn(null);
        when(scheduleMapper.updateSchedule(any())).thenReturn(1);

        UpdateStaffScheduleRequest request = new UpdateStaffScheduleRequest();
        request.setScheduleDate(LocalDate.parse("2026-11-21"));
        request.setShift(UpdateStaffScheduleRequest.ShiftEnum.DAY);
        request.setRoomId(ROOM_ID.toString());
        request.setDepartmentId(DEPT);
        request.setNote("SYNTHETIC 调整后的排班");
        request.setVersion(1);
        StaffSchedule updated = service.update(SCHEDULE_ID.toString(), request, CALLER, CONTEXT);

        verify(scheduleMapper).updateSchedule(any());
        verify(auditWriter).record(eq("SCHEDULE_UPDATE"), eq("STAFF_SCHEDULE"), anyString(),
                eq(true), any(), eq(ACTOR), eq("synth_or_manager"), eq(CONTEXT));
        assertThat(updated).isNotNull();
    }

    @Test
    void updateRejectsCancelledScheduleAndStaleVersion() {
        StaffScheduleEntity cancelled = schedule("CANCELLED", 2);
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(cancelled);
        UpdateStaffScheduleRequest request = new UpdateStaffScheduleRequest();
        request.setScheduleDate(LocalDate.parse("2026-11-21"));
        request.setShift(UpdateStaffScheduleRequest.ShiftEnum.DAY);
        request.setDepartmentId(DEPT);
        request.setVersion(2);

        assertThatThrownBy(() -> service.update(SCHEDULE_ID.toString(), request, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("已取消");

        StaffScheduleEntity planned = schedule("PLANNED", 3);
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(planned);
        request.setVersion(2);
        assertThatThrownBy(() -> service.update(SCHEDULE_ID.toString(), request, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void cancelKeepsRecordAndAudits() {
        StaffScheduleEntity existing = schedule("PLANNED", 1);
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(existing,
                schedule("CANCELLED", 2));
        when(scheduleMapper.cancelSchedule(any())).thenReturn(1);
        CancelStaffScheduleRequest request = new CancelStaffScheduleRequest();
        request.setReason("人员临时调整（SYNTHETIC）");
        request.setVersion(1);

        StaffSchedule cancelled = service.cancel(SCHEDULE_ID.toString(), request, CALLER, CONTEXT);

        ArgumentCaptor<StaffScheduleEntity> captor =
                ArgumentCaptor.forClass(StaffScheduleEntity.class);
        verify(scheduleMapper).cancelSchedule(captor.capture());
        assertThat(captor.getValue().getCancelReason()).contains("人员临时调整");
        assertThat(cancelled.getStatus()).isEqualTo(StaffSchedule.StatusEnum.CANCELLED);
        verify(auditWriter).record(eq("SCHEDULE_CANCEL"), eq("STAFF_SCHEDULE"), anyString(),
                eq(true), any(), eq(ACTOR), eq("synth_or_manager"), eq(CONTEXT));
    }

    @Test
    void cancelRejectsAlreadyCancelledAndStaleVersion() {
        StaffScheduleEntity cancelled = schedule("CANCELLED", 2);
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(cancelled);
        CancelStaffScheduleRequest request = new CancelStaffScheduleRequest();
        request.setVersion(2);

        assertThatThrownBy(() -> service.cancel(SCHEDULE_ID.toString(), request, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("重复取消");

        StaffScheduleEntity planned = schedule("PLANNED", 3);
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(planned);
        request.setVersion(1);
        assertThatThrownBy(() -> service.cancel(SCHEDULE_ID.toString(), request, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void listValidatesFiltersAndReturnsRows() {
        StaffScheduleEntity row = schedule("PLANNED", 1);
        when(scheduleMapper.selectList(any(), any(), any(), any(), any(), any(), any(), any(),
                any(Integer.class))).thenReturn(List.of(row));

        StaffScheduleList list = service.list(null, null, LocalDate.parse("2026-11-01"),
                LocalDate.parse("2026-11-30"), "DAY", "PLANNED", 100, CALLER);

        assertThat(list.getItems()).hasSize(1);
        assertThatThrownBy(() -> service.list(null, null, LocalDate.parse("2026-11-30"),
                LocalDate.parse("2026-11-01"), null, null, 100, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("开始日期");
        assertThatThrownBy(() -> service.list(null, null, null, null, "EVENING", null, 100,
                CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("班次");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, "DONE", 100, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("状态");
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, 0, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("条数");
    }

    // ------------------------------------------------------------------ 夹具 --

    private static CreateStaffScheduleRequest createRequest() {
        CreateStaffScheduleRequest request = new CreateStaffScheduleRequest();
        request.setStaffId(STAFF_ID.toString());
        request.setScheduleDate(LocalDate.parse("2026-11-20"));
        request.setShift(CreateStaffScheduleRequest.ShiftEnum.DAY);
        request.setRoomId(ROOM_ID.toString());
        request.setDepartmentId(DEPT);
        request.setNote("SYNTHETIC 演示排班");
        return request;
    }

    private static StaffProfileEntity staff(String employmentStatus) {
        StaffProfileEntity entity = new StaffProfileEntity();
        entity.setId(STAFF_ID);
        entity.setStaffNo("STAFF-ANES-001");
        entity.setName("演示麻醉医师甲（合成）");
        entity.setDepartmentId(UUID.fromString(DEPT));
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

    private static StaffScheduleEntity schedule(String status, int version) {
        StaffScheduleEntity entity = new StaffScheduleEntity();
        entity.setId(SCHEDULE_ID);
        entity.setStaffId(STAFF_ID);
        entity.setStaffNo("STAFF-ANES-001");
        entity.setScheduleDate(LocalDate.parse("2026-11-20"));
        entity.setShift("DAY");
        entity.setRoomId(ROOM_ID);
        entity.setDepartmentId(UUID.fromString(DEPT));
        entity.setStatus(status);
        entity.setNote("SYNTHETIC 演示排班");
        entity.setCancelledAt("CANCELLED".equals(status) ? NOW : null);
        entity.setCancelledBy("CANCELLED".equals(status) ? ACTOR : null);
        entity.setCancelReason("CANCELLED".equals(status) ? "人员临时调整（SYNTHETIC）" : null);
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        entity.setUpdatedAt(NOW);
        entity.setUpdatedBy(ACTOR);
        entity.setVersion(version);
        return entity;
    }
}
