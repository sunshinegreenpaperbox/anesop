package com.anesop.m1.impl.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.scheduling.model.CancelScheduleRequest;
import com.anesop.contracts.scheduling.model.ConflictCheckRequest;
import com.anesop.contracts.scheduling.model.ConflictCheckResult;
import com.anesop.contracts.scheduling.model.CreateScheduleRequest;
import com.anesop.contracts.scheduling.model.EmergencyAddRequest;
import com.anesop.contracts.scheduling.model.EmergencyAddResult;
import com.anesop.contracts.scheduling.model.IdResponse;
import com.anesop.contracts.scheduling.model.OperatingRoomList;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.contracts.scheduling.model.ScheduleChangeList;
import com.anesop.contracts.scheduling.model.SchedulePage;
import com.anesop.contracts.scheduling.model.StopScheduleRequest;
import com.anesop.contracts.scheduling.model.UpdateScheduleRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m1.api.notification.NotificationEvent;
import com.anesop.m1.api.notification.NotificationPort;
import com.anesop.m1.api.schedule.ScheduleUnprocessableException;
import com.anesop.m1.api.schedule.SchedulingConflictException;
import com.anesop.m1.api.schedule.SchedulingValidationException;
import com.anesop.m1.impl.persistence.entity.OperatingRoomEntity;
import com.anesop.m1.impl.persistence.entity.PatientSnapshotEntity;
import com.anesop.m1.impl.persistence.entity.ScheduleChangeRequestEntity;
import com.anesop.m1.impl.persistence.entity.ScheduleEntity;
import com.anesop.m1.impl.persistence.entity.ScheduleOverlapRow;
import com.anesop.m1.impl.persistence.entity.SurgeryApplicationEntity;
import com.anesop.m1.impl.persistence.mapper.OperatingRoomMapper;
import com.anesop.m1.impl.persistence.mapper.PatientSnapshotMapper;
import com.anesop.m1.impl.persistence.mapper.ScheduleChangeRequestMapper;
import com.anesop.m1.impl.persistence.mapper.ScheduleMapper;
import com.anesop.m1.impl.persistence.mapper.SurgeryApplicationMapper;
import com.anesop.m1.impl.schedule.rule.PatientConflictRule;
import com.anesop.m1.impl.schedule.rule.RoomOverlapRule;
import com.anesop.m1.impl.schedule.rule.RoomTypeMatchRule;
import com.anesop.m1.impl.schedule.rule.SchedulingRuleRegistry;
import com.anesop.m1.impl.schedule.rule.SurgeonConflictRule;
import com.anesop.m1.impl.schedule.rule.TimeWindowRule;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * 排程服务单测（M2-M2-01）：覆盖数据范围、创建/调整/取消、冲突 409、状态与版本校验、
 * 术间类型规则与审计动作。协作组件用 Mockito 替身；规则使用真实实现（非 mock 被测对象）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulingServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_ID = UUID.fromString("f2000000-0000-4000-8000-0000000000c1");
    private static final UUID APP_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID PATIENT_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID SCHEDULE_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID DEPT_ID = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_DEPT_ID = UUID.fromString("f1000000-0000-4000-8000-000000000003");
    private static final UUID SITE_ID = UUID.fromString("f1000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-13T02:00:00Z");
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-12-01T08:30:00+08:00");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-12-01T11:00:00+08:00");

    @Mock
    private OperatingRoomMapper roomMapper;
    @Mock
    private ScheduleMapper scheduleMapper;
    @Mock
    private ScheduleChangeRequestMapper changeRequestMapper;
    @Mock
    private SurgeryApplicationMapper applicationMapper;
    @Mock
    private PatientSnapshotMapper patientSnapshotMapper;
    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private NotificationPort notificationPort;

    private SchedulingServiceImpl service;
    private AuthPrincipal caller;

    @BeforeEach
    void setUp() {
        SchedulingRuleRegistry registry = new SchedulingRuleRegistry(List.of(
                new RoomOverlapRule(), new SurgeonConflictRule(), new PatientConflictRule(),
                new RoomTypeMatchRule(), new TimeWindowRule()));
        service = new SchedulingServiceImpl(roomMapper, scheduleMapper, changeRequestMapper,
                applicationMapper, patientSnapshotMapper, registry, dataScopeResolver, auditWriter,
                notificationPort, Clock.fixed(NOW, ZoneOffset.UTC),
                "急诊加台抢占择期台次（系统自动）");
        caller = new AuthPrincipal(USER_ID, "demo_admin", "jti", NOW.plusSeconds(900), "sid");
        when(dataScopeResolver.resolve(USER_ID))
                .thenReturn(DataScopeAssignments.of(List.of(new DataScopeAssignment(DataScope.ALL, List.of()))));
    }

    private OperatingRoomEntity room(UUID id, UUID deptId, String type) {
        OperatingRoomEntity room = new OperatingRoomEntity();
        room.setId(id);
        room.setRoomNo(id.equals(ROOM_ID) ? "OR-01" : "OR-02");
        room.setName("演示手术间（合成）");
        room.setRoomType(type);
        room.setStatus("IDLE");
        room.setDeptName("麻醉科（合成）");
        room.setDeptId(deptId);
        room.setSiteId(SITE_ID);
        room.setCreatedAt(NOW);
        room.setUpdatedAt(NOW);
        room.setVersion(1L);
        room.setDeleted(false);
        return room;
    }

    private SurgeryApplicationEntity application(String status, String requiredRoomType) {
        SurgeryApplicationEntity application = new SurgeryApplicationEntity();
        application.setId(APP_ID);
        application.setPatientId(PATIENT_ID);
        application.setSurgeryName("演示术式");
        application.setUrgency("ELECTIVE");
        application.setPlannedDurationMin(150);
        application.setDeptName("麻醉科（合成）");
        application.setDeptId(DEPT_ID);
        application.setSiteId(SITE_ID);
        application.setSurgeonName("演示术者");
        application.setStatus(status);
        application.setRequiredRoomType(requiredRoomType);
        return application;
    }

    private PatientSnapshotEntity patient() {
        PatientSnapshotEntity patient = new PatientSnapshotEntity();
        patient.setId(PATIENT_ID);
        patient.setHisPatientNo("SYNTH-HIS-P-0001");
        patient.setName("演示患者");
        return patient;
    }

    private ScheduleEntity schedule(String status, long version) {
        ScheduleEntity schedule = new ScheduleEntity();
        schedule.setId(SCHEDULE_ID);
        schedule.setApplicationId(APP_ID);
        schedule.setRoomId(ROOM_ID);
        schedule.setStartAt(START.toInstant());
        schedule.setEndAt(END.toInstant());
        schedule.setSequence(1);
        schedule.setStatus(status);
        schedule.setCreatedAt(NOW);
        schedule.setUpdatedAt(NOW);
        schedule.setVersion(version);
        schedule.setDeleted(false);
        return schedule;
    }

    private ScheduleOverlapRow overlap(UUID roomId) {
        ScheduleOverlapRow row = new ScheduleOverlapRow();
        row.setId(UUID.fromString("80000000-0000-4000-8000-000000000002"));
        row.setApplicationId(APP_ID);
        row.setRoomId(roomId);
        row.setRoomNo("OR-01");
        row.setRoomType("GENERAL");
        row.setStartAt(START.plusMinutes(30).toInstant());
        row.setEndAt(END.toInstant());
        row.setSequence(1);
        row.setPriority(0);
        row.setStatus("PLANNED");
        row.setPatientId(UUID.fromString("50000000-0000-4000-8000-000000000002"));
        row.setSurgeonName("其他术者");
        return row;
    }

    private CreateScheduleRequest createRequest() {
        CreateScheduleRequest request = new CreateScheduleRequest();
        request.setApplicationId(APP_ID.toString());
        request.setRoomId(ROOM_ID.toString());
        request.setPlannedStart(START);
        request.setPlannedEnd(END);
        request.setSequenceNo(1);
        request.setPriority(CreateScheduleRequest.PriorityEnum.ELECTIVE);
        return request;
    }

    @Test
    void 术间列表返回类型与状态() {
        when(roomMapper.selectList(any())).thenReturn(List.of(room(ROOM_ID, DEPT_ID, "HYBRID")));

        OperatingRoomList result = service.listRooms(caller);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getRoomNo()).isEqualTo("OR-01");
        assertThat(result.getItems().get(0).getRoomType().getValue()).isEqualTo("HYBRID");
        assertThat(result.getItems().get(0).getStatus().getValue()).isEqualTo("IDLE");
    }

    @Test
    void 无数据范围时拒绝() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.denied());

        assertThatThrownBy(() -> service.listRooms(caller))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 科室范围只返回本科室术间() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.DEPT, List.of(DEPT_ID.toString())))));
        when(roomMapper.selectList(any()))
                .thenReturn(List.of(room(ROOM_ID, DEPT_ID, "GENERAL")))
                .thenReturn(List.of(room(ROOM_ID, DEPT_ID, "GENERAL")));

        OperatingRoomList result = service.listRooms(caller);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getRoomId()).isEqualTo(ROOM_ID.toString());
    }

    @Test
    void 日排程按日期分页并返回脱敏患者摘要() {
        when(scheduleMapper.selectCount(any())).thenReturn(1L);
        when(scheduleMapper.selectList(any())).thenReturn(List.of(schedule("PLANNED", 1)));
        when(applicationMapper.selectBatchIds(any())).thenReturn(List.of(application("ACCEPTED", null)));
        when(patientSnapshotMapper.selectBatchIds(any())).thenReturn(List.of(patient()));
        when(roomMapper.selectBatchIds(any())).thenReturn(List.of(room(ROOM_ID, DEPT_ID, "GENERAL")));

        SchedulePage page = service.listSchedules("2026-12-01", null, "PLANNED", 1, 50, caller);

        assertThat(page.getMeta().getTotal()).isEqualTo(1);
        assertThat(page.getItems()).hasSize(1);
        Schedule item = page.getItems().get(0);
        assertThat(item.getScheduleId()).isEqualTo(SCHEDULE_ID.toString());
        assertThat(item.getPatientNo()).isEqualTo("SYNTH-HIS-P-0001");
        assertThat(item.getPatientName()).isEqualTo("演示患者");
        assertThat(item.getPriority().getValue()).isEqualTo("ELECTIVE");
    }

    @Test
    void 非法日期与状态返回字段校验错误() {
        assertThatThrownBy(() -> service.listSchedules("2026/12/01", null, null, 1, 50, caller))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> service.listSchedules("2026-12-01", null, "UNKNOWN", 1, 50, caller))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("状态");
    }

    @Test
    void 创建成功写入台次并审计() {
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SUBMITTED", null));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectByIdForUpdate(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null))).thenReturn(List.of());
        when(scheduleMapper.insert(any(ScheduleEntity.class))).thenReturn(1);
        when(patientSnapshotMapper.selectById(PATIENT_ID)).thenReturn(patient());

        Schedule created = service.create(createRequest(), caller, AuthRequestContext.empty());

        assertThat(created.getScheduleId()).isNotNull();
        assertThat(created.getStatus().getValue()).isEqualTo("PLANNED");
        assertThat(created.getVersion()).isEqualTo(1);
        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleMapper).insert(captor.capture());
        assertThat(captor.getValue().getStartAt()).isEqualTo(START.toInstant());
        assertThat(captor.getValue().getEndAt()).isEqualTo(END.toInstant());
        verify(auditWriter).record(eq("SCHEDULE_CREATE"), eq("SCHEDULE"), any(), eq(true),
                any(), eq(USER_ID), eq("demo_admin"), any());
    }

    @Test
    void 同室时间重叠返回冲突且不落库() {
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SUBMITTED", null));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectByIdForUpdate(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null)))
                .thenReturn(List.of(overlap(ROOM_ID)));
        when(patientSnapshotMapper.selectById(PATIENT_ID)).thenReturn(patient());

        assertThatThrownBy(() -> service.create(createRequest(), caller, AuthRequestContext.empty()))
                .isInstanceOfSatisfying(SchedulingConflictException.class,
                        exception -> assertThat(exception.conflicts())
                                .extracting(conflict -> conflict.getConflictType())
                                .containsExactly("ROOM_OVERLAP"));
        verify(scheduleMapper, never()).insert(any(ScheduleEntity.class));
    }

    @Test
    void 申请不存在或状态不允许时拒绝创建() {
        when(applicationMapper.selectById(APP_ID)).thenReturn(null);
        // 不存在与数据范围外统一 404，不泄露存在性（B-1）。
        assertThatThrownBy(() -> service.create(createRequest(), caller, AuthRequestContext.empty()))
                .isInstanceOf(NoSuchElementException.class);

        when(applicationMapper.selectById(APP_ID)).thenReturn(application("CANCELLED", null));
        assertThatThrownBy(() -> service.create(createRequest(), caller, AuthRequestContext.empty()))
                .isInstanceOf(ScheduleUnprocessableException.class);
    }

    @Test
    void 已排程申请再次创建返回冲突() {
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SCHEDULED", null));

        assertThatThrownBy(() -> service.create(createRequest(), caller, AuthRequestContext.empty()))
                .isInstanceOf(SchedulingConflictException.class);
    }

    @Test
    void 术间类型不匹配时拒绝创建() {
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SUBMITTED", "HYBRID"));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectByIdForUpdate(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null))).thenReturn(List.of());
        when(patientSnapshotMapper.selectById(PATIENT_ID)).thenReturn(patient());

        assertThatThrownBy(() -> service.create(createRequest(), caller, AuthRequestContext.empty()))
                .isInstanceOfSatisfying(SchedulingConflictException.class,
                        exception -> assertThat(exception.conflicts().get(0).getConflictType())
                                .isEqualTo("ROOM_TYPE_MISMATCH"));
    }

    @Test
    void 科室范围外术间返回不可见() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.DEPT, List.of(DEPT_ID.toString())))));
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SUBMITTED", null));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, OTHER_DEPT_ID, "GENERAL"));

        assertThatThrownBy(() -> service.create(createRequest(), caller, AuthRequestContext.empty()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 调整成功更新时段并审计() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(roomMapper.selectById(ROOM_ID))
                .thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"))
                .thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectByIdForUpdate(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("ACCEPTED", null));
        when(scheduleMapper.selectOverlaps(any(), any(), eq(SCHEDULE_ID))).thenReturn(List.of());
        when(patientSnapshotMapper.selectById(PATIENT_ID)).thenReturn(patient());
        when(scheduleMapper.updateById(any(ScheduleEntity.class))).thenReturn(1);
        UpdateScheduleRequest request = new UpdateScheduleRequest();
        request.setRoomId(ROOM_ID.toString());
        request.setPlannedStart(START.plusHours(2));
        request.setPlannedEnd(END.plusHours(2));
        request.setSequenceNo(2);
        request.setVersion(1);
        request.setReasonCode("SCHEDULE_ADJUST");
        request.setReason("演示调整");

        Schedule updated = service.reschedule(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty());

        assertThat(updated.getPlannedStart().getHour()).isEqualTo(10);
        assertThat(updated.getSequenceNo()).isEqualTo(2);
        verify(scheduleMapper).updateById(any(ScheduleEntity.class));
        verify(auditWriter).record(eq("SCHEDULE_UPDATE"), eq("SCHEDULE"), any(), eq(true),
                any(), eq(USER_ID), eq("demo_admin"), any());
    }

    @Test
    void 调整版本过期返回冲突() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 3));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        UpdateScheduleRequest request = new UpdateScheduleRequest();
        request.setPlannedStart(START);
        request.setPlannedEnd(END);
        request.setSequenceNo(1);
        request.setVersion(1);

        assertThatThrownBy(() -> service.reschedule(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty()))
                .isInstanceOf(SchedulingConflictException.class);
        verify(scheduleMapper, never()).updateById(any(ScheduleEntity.class));
    }

    @Test
    void 取消台次写入状态原因与审计() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(scheduleMapper.updateById(any(ScheduleEntity.class))).thenReturn(1);
        when(changeRequestMapper.insert(any(ScheduleChangeRequestEntity.class))).thenReturn(1);
        when(notificationPort.publish(any(NotificationEvent.class))).thenReturn(UUID.randomUUID());
        CancelScheduleRequest request = new CancelScheduleRequest();
        request.setReasonCode("PATIENT_CONDITION");
        request.setReason("演示停台原因（合成数据）");
        request.setVersion(1);

        IdResponse response = service.cancel(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty());

        assertThat(response.getId()).isEqualTo(SCHEDULE_ID.toString());
        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("CANCELLED");
        assertThat(captor.getValue().getReason()).isEqualTo("演示停台原因（合成数据）");
        verify(auditWriter).record(eq("SCHEDULE_CANCEL"), eq("SCHEDULE"), any(), eq(true),
                any(), eq(USER_ID), eq("demo_admin"), any());
        // S-1：0.4.0 兼容取消端点同样写变更记录与 outbox，不绕过变更历史。
        ArgumentCaptor<ScheduleChangeRequestEntity> changeCaptor =
                ArgumentCaptor.forClass(ScheduleChangeRequestEntity.class);
        verify(changeRequestMapper).insert(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getChangeType()).isEqualTo("STOP");
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationPort).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo("SCHEDULE_STOPPED");
    }

    @Test
    void 已开始台次不允许取消() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("IN_PROGRESS", 1));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("IN_PROGRESS", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        CancelScheduleRequest request = new CancelScheduleRequest();
        request.setReasonCode("PATIENT_CONDITION");
        request.setReason("演示停台原因");
        request.setVersion(1);

        assertThatThrownBy(() -> service.cancel(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty()))
                .isInstanceOf(SchedulingConflictException.class);
    }

    @Test
    void 冲突预检返回结构化冲突且不落库() {
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SUBMITTED", "HYBRID"));
        when(patientSnapshotMapper.selectById(PATIENT_ID)).thenReturn(patient());
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null)))
                .thenReturn(List.of(overlap(ROOM_ID)));
        ConflictCheckRequest request = new ConflictCheckRequest();
        request.setRoomId(ROOM_ID.toString());
        request.setApplicationId(APP_ID.toString());
        request.setPlannedStart(START);
        request.setPlannedEnd(END);

        ConflictCheckResult result = service.checkConflicts(request, caller);

        assertThat(result.getHasConflict()).isTrue();
        assertThat(result.getConflicts()).extracting(conflict -> conflict.getConflictType())
                .containsExactly("ROOM_OVERLAP", "ROOM_TYPE_MISMATCH");
        verify(scheduleMapper, never()).insert(any(ScheduleEntity.class));
        verify(scheduleMapper, never()).updateById(any(ScheduleEntity.class));
    }

    @Test
    void 排程详情不存在返回404语义() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.get(SCHEDULE_ID.toString(), caller))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 术间范围用户只可见绑定术间() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.ROOM, List.of(ROOM_ID.toString())))));
        when(roomMapper.selectList(any())).thenReturn(List.of(room(ROOM_ID, DEPT_ID, "GENERAL")));

        OperatingRoomList result = service.listRooms(caller);

        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getRoomId()).isEqualTo(ROOM_ID.toString());
    }

    @Test
    void 无可见术间时集合查询拒绝() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.ROOM, List.of()))));

        assertThatThrownBy(() -> service.listRooms(caller))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 台次版本为空时创建失败() {
        assertThatThrownBy(() -> service.reschedule(null, new UpdateScheduleRequest(), caller,
                AuthRequestContext.empty()))
                .isInstanceOf(NoSuchElementException.class);
        verify(scheduleMapper, never()).selectCount(any());
    }

    @Test
    void 日排程按术间过滤且空结果返回零页() {
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(scheduleMapper.selectCount(any())).thenReturn(0L);

        SchedulePage page = service.listSchedules("2026-12-01", ROOM_ID.toString(), null, 1, 50, caller);

        assertThat(page.getItems()).isEmpty();
        assertThat(page.getMeta().getTotalPages()).isZero();
    }

    @Test
    void 日排程指定范围外术间返回403语义() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.DEPT, List.of(DEPT_ID.toString())))));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, OTHER_DEPT_ID, "GENERAL"));

        assertThatThrownBy(() -> service.listSchedules("2026-12-01", ROOM_ID.toString(), null, 1, 50,
                caller))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 无可见术间的日排程与冲突预检拒绝() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.ROOM, List.of()))));

        assertThatThrownBy(() -> service.listSchedules("2026-12-01", null, null, 1, 50, caller))
                .isInstanceOf(AccessDeniedException.class);
        ConflictCheckRequest request = new ConflictCheckRequest();
        request.setRoomId(ROOM_ID.toString());
        request.setPlannedStart(START);
        request.setPlannedEnd(END);
        assertThatThrownBy(() -> service.checkConflicts(request, caller))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 排程详情返回患者与术间信息() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectBatchIds(any())).thenReturn(List.of(room(ROOM_ID, DEPT_ID, "GENERAL")));
        when(applicationMapper.selectBatchIds(any())).thenReturn(List.of(application("ACCEPTED", null)));
        when(patientSnapshotMapper.selectBatchIds(any())).thenReturn(List.of(patient()));

        Schedule detail = service.get(SCHEDULE_ID.toString(), caller);

        assertThat(detail.getRoomNo()).isEqualTo("OR-01");
        assertThat(detail.getPatientName()).isEqualTo("演示患者");
    }

    @Test
    void 创建参数非法时返回字段校验错误() {
        CreateScheduleRequest badApplication = createRequest();
        badApplication.setApplicationId("bad-uuid");
        assertThatThrownBy(() -> service.create(badApplication, caller, AuthRequestContext.empty()))
                .isInstanceOf(SchedulingValidationException.class);

        CreateScheduleRequest badRoom = createRequest();
        badRoom.setRoomId("bad-uuid");
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SUBMITTED", null));
        assertThatThrownBy(() -> service.create(badRoom, caller, AuthRequestContext.empty()))
                .isInstanceOf(SchedulingValidationException.class);

        CreateScheduleRequest badSequence = createRequest();
        badSequence.setSequenceNo(0);
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        assertThatThrownBy(() -> service.create(badSequence, caller, AuthRequestContext.empty()))
                .isInstanceOf(SchedulingValidationException.class);
    }

    @Test
    void 术间不存在或锁定时已删除返回404语义() {
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SUBMITTED", null));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.create(createRequest(), caller, AuthRequestContext.empty()))
                .isInstanceOf(NoSuchElementException.class);

        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectByIdForUpdate(ROOM_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.create(createRequest(), caller, AuthRequestContext.empty()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 并发撞唯一索引时返回冲突() {
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SUBMITTED", null));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectByIdForUpdate(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null))).thenReturn(List.of());
        when(patientSnapshotMapper.selectById(PATIENT_ID)).thenReturn(patient());
        when(scheduleMapper.insert(any(ScheduleEntity.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.create(createRequest(), caller, AuthRequestContext.empty()))
                .isInstanceOf(SchedulingConflictException.class);
    }

    @Test
    void 调整非法状态或序号时拒绝() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("IN_PROGRESS", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        UpdateScheduleRequest request = new UpdateScheduleRequest();
        request.setPlannedStart(START);
        request.setPlannedEnd(END);
        request.setSequenceNo(1);
        request.setVersion(1);
        assertThatThrownBy(() -> service.reschedule(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty()))
                .isInstanceOf(SchedulingConflictException.class);

        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        request.setSequenceNo(0);
        assertThatThrownBy(() -> service.reschedule(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty()))
                .isInstanceOf(SchedulingValidationException.class);
    }

    @Test
    void 调整时并发版本变化返回冲突() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(roomMapper.selectById(ROOM_ID))
                .thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"))
                .thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectByIdForUpdate(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("ACCEPTED", null));
        when(scheduleMapper.selectOverlaps(any(), any(), eq(SCHEDULE_ID))).thenReturn(List.of());
        when(patientSnapshotMapper.selectById(PATIENT_ID)).thenReturn(patient());
        when(scheduleMapper.updateById(any(ScheduleEntity.class))).thenReturn(0);
        UpdateScheduleRequest request = new UpdateScheduleRequest();
        request.setPlannedStart(START);
        request.setPlannedEnd(END);
        request.setSequenceNo(1);
        request.setVersion(1);
        request.setReasonCode("SCHEDULE_ADJUST");

        assertThatThrownBy(() -> service.reschedule(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty()))
                .isInstanceOf(SchedulingConflictException.class);
    }

    @Test
    void 取消参数或版本非法时拒绝() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 2));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 2));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        CancelScheduleRequest request = new CancelScheduleRequest();
        request.setReasonCode("PATIENT_CONDITION");
        request.setReason("演示停台原因");
        request.setVersion(1);
        assertThatThrownBy(() -> service.cancel(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty()))
                .isInstanceOf(SchedulingConflictException.class);

        request.setVersion(2);
        request.setReason(" ");
        assertThatThrownBy(() -> service.cancel(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty()))
                .isInstanceOf(SchedulingValidationException.class);
    }

    @Test
    void 冲突预检参数与范围校验() {
        ConflictCheckRequest request = new ConflictCheckRequest();
        request.setRoomId("bad-uuid");
        assertThatThrownBy(() -> service.checkConflicts(request, caller))
                .isInstanceOf(SchedulingValidationException.class);

        request.setRoomId(ROOM_ID.toString());
        request.setPlannedStart(START);
        request.setPlannedEnd(END);
        when(roomMapper.selectById(ROOM_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.checkConflicts(request, caller))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 冲突预检未传申请时只做术间规则() {
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null))).thenReturn(List.of());
        ConflictCheckRequest request = new ConflictCheckRequest();
        request.setRoomId(ROOM_ID.toString());
        request.setPlannedStart(START);
        request.setPlannedEnd(END);

        ConflictCheckResult result = service.checkConflicts(request, caller);

        assertThat(result.getHasConflict()).isFalse();
    }

    @Test
    void 科室范围用户没有可见术间时拒绝() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.DEPT, List.of("not-a-uuid")))));

        assertThatThrownBy(() -> service.listRooms(caller))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 科室用户持其他科室申请返回404且不泄露患者标识() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.DEPT, List.of(DEPT_ID.toString())))));
        SurgeryApplicationEntity otherDept = application("SUBMITTED", null);
        otherDept.setDeptId(OTHER_DEPT_ID);
        when(applicationMapper.selectById(APP_ID)).thenReturn(otherDept);

        assertThatThrownBy(() -> service.create(createRequest(), caller, AuthRequestContext.empty()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("不可见");
        verify(roomMapper, never()).selectByIdForUpdate(any());
        verify(patientSnapshotMapper, never()).selectById(any());
    }

    @Test
    void 术间范围用户的申请可见性按绑定术间科室推导() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.ROOM, List.of(ROOM_ID.toString())))));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectBatchIds(any())).thenReturn(List.of(room(ROOM_ID, DEPT_ID, "GENERAL")));
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SUBMITTED", null));
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null))).thenReturn(List.of());
        ConflictCheckRequest request = new ConflictCheckRequest();
        request.setRoomId(ROOM_ID.toString());
        request.setApplicationId(APP_ID.toString());
        request.setPlannedStart(START);
        request.setPlannedEnd(END);

        ConflictCheckResult result = service.checkConflicts(request, caller);

        assertThat(result.getHasConflict()).isFalse();
        // 必须真正执行申请可见性校验：删除该校验时本用例会失败。
        verify(applicationMapper).selectById(APP_ID);
    }

    @Test
    void 预检冲突时其他科室申请返回404且不泄露患者标识() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.DEPT, List.of(DEPT_ID.toString())))));
        SurgeryApplicationEntity otherDept = application("SUBMITTED", null);
        otherDept.setDeptId(OTHER_DEPT_ID);
        when(applicationMapper.selectById(APP_ID)).thenReturn(otherDept);
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));

        ConflictCheckRequest request = new ConflictCheckRequest();
        request.setRoomId(ROOM_ID.toString());
        request.setApplicationId(APP_ID.toString());
        request.setPlannedStart(START);
        request.setPlannedEnd(END);

        assertThatThrownBy(() -> service.checkConflicts(request, caller))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("不可见");
        verify(scheduleMapper, never()).selectOverlaps(any(), any(), any());
    }

    @Test
    void 停台原因码非法返回字段校验错误() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        CancelScheduleRequest request = new CancelScheduleRequest();
        request.setReasonCode("patient condition");
        request.setReason("演示停台原因");
        request.setVersion(1);

        assertThatThrownBy(() -> service.cancel(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty()))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("原因码");
        verify(scheduleMapper, never()).updateById(any(ScheduleEntity.class));
    }

    @Test
    void 版本与状态冲突使用专用冲突类型() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 3));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        UpdateScheduleRequest stale = new UpdateScheduleRequest();
        stale.setPlannedStart(START);
        stale.setPlannedEnd(END);
        stale.setSequenceNo(1);
        stale.setVersion(1);
        assertThatThrownBy(() -> service.reschedule(SCHEDULE_ID.toString(), stale, caller,
                AuthRequestContext.empty()))
                .isInstanceOfSatisfying(SchedulingConflictException.class,
                        exception -> assertThat(exception.conflicts().get(0).getConflictType())
                                .isEqualTo("VERSION_CONFLICT"));

        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("IN_PROGRESS", 1));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("IN_PROGRESS", 1));
        CancelScheduleRequest cancel = new CancelScheduleRequest();
        cancel.setReasonCode("SCHEDULE_CHANGE");
        cancel.setReason("演示停台原因");
        cancel.setVersion(1);
        assertThatThrownBy(() -> service.cancel(SCHEDULE_ID.toString(), cancel, caller,
                AuthRequestContext.empty()))
                .isInstanceOfSatisfying(SchedulingConflictException.class,
                        exception -> assertThat(exception.conflicts().get(0).getConflictType())
                                .isEqualTo("STATE_CONFLICT"));
    }

    @Test
    void 时间窗非法统一返回400语义() {
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("SUBMITTED", null));
        CreateScheduleRequest reversed = createRequest();
        reversed.setPlannedEnd(START.minusMinutes(1));
        assertThatThrownBy(() -> service.create(reversed, caller, AuthRequestContext.empty()))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("晚于开始时间");

        CreateScheduleRequest tooShort = createRequest();
        tooShort.setPlannedEnd(START.plusSeconds(30));
        assertThatThrownBy(() -> service.create(tooShort, caller, AuthRequestContext.empty()))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("时长");
        verify(roomMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    void 术间列表过滤参数非法返回400语义() {
        assertThatThrownBy(() -> service.listRooms(caller, "bad-uuid", null))
                .isInstanceOf(SchedulingValidationException.class);
        assertThatThrownBy(() -> service.listRooms(caller, null, "UNKNOWN"))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("状态");
    }

    @Test
    void 自定义数据范围用户没有术间范围时拒绝() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.SELF, List.of()))));

        assertThatThrownBy(() -> service.listRooms(caller))
                .isInstanceOf(AccessDeniedException.class);
    }

    private StopScheduleRequest stopRequest(String reasonCode, Integer version) {
        StopScheduleRequest request = new StopScheduleRequest();
        request.setReasonCode(reasonCode);
        request.setReason("演示停台原因（合成数据）");
        request.setVersion(version);
        return request;
    }

    private EmergencyAddRequest emergencyRequest(String reasonCode) {
        EmergencyAddRequest request = new EmergencyAddRequest();
        request.setApplicationId(APP_ID.toString());
        request.setRoomId(ROOM_ID.toString());
        request.setReasonCode(reasonCode);
        request.setReason("演示急诊加台（合成数据）");
        request.setExpectedStart(START);
        request.setExpectedEnd(END);
        request.setSequenceNo(1);
        return request;
    }

    private ScheduleOverlapRow preemptOverlap() {
        ScheduleOverlapRow row = overlap(ROOM_ID);
        row.setPriority(0);
        row.setStatus("PLANNED");
        row.setPatientId(UUID.fromString("50000000-0000-4000-8000-000000000003"));
        row.setSurgeonName("择期术者");
        return row;
    }

    private ScheduleEntity preemptScheduleEntity() {
        ScheduleEntity entity = schedule("PLANNED", 1);
        entity.setId(UUID.fromString("80000000-0000-4000-8000-000000000002"));
        entity.setPriority(0);
        return entity;
    }

    private void stubStopLookups() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("ACCEPTED", null));
        when(patientSnapshotMapper.selectById(PATIENT_ID)).thenReturn(patient());
        when(scheduleMapper.updateById(any(ScheduleEntity.class))).thenReturn(1);
        when(changeRequestMapper.insert(any(ScheduleChangeRequestEntity.class))).thenReturn(1);
        when(notificationPort.publish(any(NotificationEvent.class))).thenReturn(UUID.randomUUID());
    }

    private void stubEmergencyLookups() {
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("ACCEPTED", null));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectByIdForUpdate(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(scheduleMapper.insert(any(ScheduleEntity.class))).thenReturn(1);
        when(changeRequestMapper.insert(any(ScheduleChangeRequestEntity.class))).thenReturn(1);
        when(notificationPort.publish(any(NotificationEvent.class))).thenReturn(UUID.randomUUID());
    }

    @Test
    void 计划台次停台直接生效并写变更审计与通知() {
        stubStopLookups();

        Schedule stopped = service.stop(SCHEDULE_ID.toString(), stopRequest("PATIENT_CONDITION", 1),
                caller, AuthRequestContext.empty(), false);

        assertThat(stopped.getStatus().getValue()).isEqualTo("CANCELLED");
        assertThat(stopped.getChangeReason()).isEqualTo("演示停台原因（合成数据）");
        ArgumentCaptor<ScheduleChangeRequestEntity> changeCaptor =
                ArgumentCaptor.forClass(ScheduleChangeRequestEntity.class);
        verify(changeRequestMapper).insert(changeCaptor.capture());
        assertThat(changeCaptor.getValue().getChangeType()).isEqualTo("STOP");
        assertThat(changeCaptor.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(changeCaptor.getValue().getApprovedBy()).isEqualTo(USER_ID);
        assertThat(changeCaptor.getValue().getReasonCode()).isEqualTo("PATIENT_CONDITION");
        verify(auditWriter).record(eq("SCHEDULE_STOP"), eq("SCHEDULE"), any(), eq(true),
                any(), eq(USER_ID), eq("demo_admin"), any());
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationPort).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo("SCHEDULE_STOPPED");
        assertThat(eventCaptor.getValue().payload()).containsOnlyKeys(
                "scheduleId", "applicationId", "roomId", "changeId", "changeType", "reasonCode", "status");
    }

    @Test
    void 停台原因码不在白名单返回400且不改状态() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));

        assertThatThrownBy(() -> service.stop(SCHEDULE_ID.toString(),
                stopRequest("patient_condition", 1), caller, AuthRequestContext.empty(), true))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("白名单");
        verify(scheduleMapper, never()).updateById(any(ScheduleEntity.class));
        verify(notificationPort, never()).publish(any(NotificationEvent.class));
    }

    @Test
    void 术中停台无审批权限返回403() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("IN_PROGRESS", 1));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("IN_PROGRESS", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));

        assertThatThrownBy(() -> service.stop(SCHEDULE_ID.toString(),
                stopRequest("ANESTHESIA_RISK", 1), caller, AuthRequestContext.empty(), false))
                .isInstanceOf(AccessDeniedException.class);
        verify(scheduleMapper, never()).updateById(any(ScheduleEntity.class));
    }

    @Test
    void 术中停台有审批权限时生效() {
        stubStopLookups();
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("IN_PROGRESS", 1));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("IN_PROGRESS", 1));

        Schedule stopped = service.stop(SCHEDULE_ID.toString(), stopRequest("ANESTHESIA_RISK", 1),
                caller, AuthRequestContext.empty(), true);

        assertThat(stopped.getStatus().getValue()).isEqualTo("CANCELLED");
        verify(auditWriter).record(eq("SCHEDULE_STOP"), eq("SCHEDULE"), any(), eq(true),
                any(), eq(USER_ID), eq("demo_admin"), any());
    }

    @Test
    void 终态台次停台返回409且版本过期返回409() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("COMPLETED", 1));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("COMPLETED", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        assertThatThrownBy(() -> service.stop(SCHEDULE_ID.toString(),
                stopRequest("PATIENT_CONDITION", 1), caller, AuthRequestContext.empty(), true))
                .isInstanceOfSatisfying(SchedulingConflictException.class,
                        exception -> assertThat(exception.conflicts().get(0).getConflictType())
                                .isEqualTo("STATE_CONFLICT"));

        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 2));
        when(scheduleMapper.selectByIdForUpdate(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 2));
        assertThatThrownBy(() -> service.stop(SCHEDULE_ID.toString(),
                stopRequest("PATIENT_CONDITION", 1), caller, AuthRequestContext.empty(), true))
                .isInstanceOfSatisfying(SchedulingConflictException.class,
                        exception -> assertThat(exception.conflicts().get(0).getConflictType())
                                .isEqualTo("VERSION_CONFLICT"));
    }

    @Test
    void 改台写MODIFY变更并校验原因码白名单() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectByIdForUpdate(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("ACCEPTED", null));
        when(scheduleMapper.selectOverlaps(any(), any(), eq(SCHEDULE_ID))).thenReturn(List.of());
        when(patientSnapshotMapper.selectById(PATIENT_ID)).thenReturn(patient());
        when(scheduleMapper.updateById(any(ScheduleEntity.class))).thenReturn(1);
        when(changeRequestMapper.insert(any(ScheduleChangeRequestEntity.class))).thenReturn(1);
        when(notificationPort.publish(any(NotificationEvent.class))).thenReturn(UUID.randomUUID());
        UpdateScheduleRequest request = new UpdateScheduleRequest();
        request.setPlannedStart(START);
        request.setPlannedEnd(END);
        request.setSequenceNo(1);
        request.setVersion(1);
        request.setReasonCode("SURGEON_UNAVAILABLE");

        service.reschedule(SCHEDULE_ID.toString(), request, caller, AuthRequestContext.empty());

        ArgumentCaptor<ScheduleChangeRequestEntity> captor =
                ArgumentCaptor.forClass(ScheduleChangeRequestEntity.class);
        verify(changeRequestMapper).insert(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo("MODIFY");
        assertThat(captor.getValue().getReasonCode()).isEqualTo("SURGEON_UNAVAILABLE");
        verify(notificationPort).publish(any(NotificationEvent.class));

        UpdateScheduleRequest invalid = new UpdateScheduleRequest();
        invalid.setPlannedStart(START);
        invalid.setPlannedEnd(END);
        invalid.setSequenceNo(1);
        invalid.setVersion(1);
        invalid.setReasonCode("NOT_ALLOWED");
        assertThatThrownBy(() -> service.reschedule(SCHEDULE_ID.toString(), invalid, caller,
                AuthRequestContext.empty()))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void 急诊加台无审批权限返回403() {
        assertThatThrownBy(() -> service.addEmergency(emergencyRequest("EMERGENCY_GREEN_CHANNEL"),
                caller, AuthRequestContext.empty(), false))
                .isInstanceOf(AccessDeniedException.class);
        verify(scheduleMapper, never()).insert(any(ScheduleEntity.class));
    }

    @Test
    void 急诊加台成功写入EMERGENCY优先级与审批记录() {
        stubEmergencyLookups();
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null))).thenReturn(List.of());

        EmergencyAddResult result = service.addEmergency(
                emergencyRequest("EMERGENCY_GREEN_CHANNEL"), caller, AuthRequestContext.empty(), true);

        assertThat(result.getAccepted()).isTrue();
        assertThat(result.getApprovalRequired()).isTrue();
        assertThat(result.getApproval().getStatus().getValue()).isEqualTo("APPROVED");
        assertThat(result.getApproval().getChangeType().getValue()).isEqualTo("EMERGENCY");
        ArgumentCaptor<ScheduleEntity> scheduleCaptor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleMapper).insert(scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().getPriority()).isEqualTo(2);
        assertThat(scheduleCaptor.getValue().getStatus()).isEqualTo("PLANNED");
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationPort).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo("EMERGENCY_SCHEDULED");
        verify(auditWriter).record(eq("SCHEDULE_EMERGENCY_CREATE"), eq("SCHEDULE"), any(),
                eq(true), any(), eq(USER_ID), eq("demo_admin"), any());
    }

    @Test
    void 急诊加台未指定抢占遇冲突返回409且不落库() {
        stubEmergencyLookups();
        ScheduleOverlapRow conflictRow = overlap(ROOM_ID);
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null)))
                .thenReturn(List.of(conflictRow));

        assertThatThrownBy(() -> service.addEmergency(
                emergencyRequest("EMERGENCY_GREEN_CHANNEL"), caller, AuthRequestContext.empty(), true))
                .isInstanceOfSatisfying(SchedulingConflictException.class,
                        exception -> assertThat(exception.conflicts())
                                .extracting(conflict -> conflict.getConflictingScheduleId())
                                .containsExactly(conflictRow.getId().toString()));
        verify(scheduleMapper, never()).insert(any(ScheduleEntity.class));
    }

    @Test
    void 急诊抢占择期台次成功并留痕通知() {
        stubEmergencyLookups();
        ScheduleOverlapRow preempted = preemptOverlap();
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null))).thenReturn(List.of(preempted));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(scheduleMapper.selectByIdForUpdate(preempted.getId()))
                .thenReturn(preemptScheduleEntity());
        when(scheduleMapper.updateById(any(ScheduleEntity.class))).thenReturn(1);
        EmergencyAddRequest request = emergencyRequest("EMERGENCY_PREEMPT");
        request.setPreemptScheduleId(preempted.getId().toString());

        EmergencyAddResult result = service.addEmergency(request, caller,
                AuthRequestContext.empty(), true);

        assertThat(result.getApproval().getPreemptScheduleId()).isEqualTo(preempted.getId().toString());
        ArgumentCaptor<ScheduleEntity> updates = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(scheduleMapper).updateById(updates.capture());
        assertThat(updates.getValue().getStatus()).isEqualTo("CANCELLED");
        assertThat(updates.getValue().getReason()).isEqualTo("EMERGENCY_PREEMPT");

        ArgumentCaptor<ScheduleChangeRequestEntity> changes =
                ArgumentCaptor.forClass(ScheduleChangeRequestEntity.class);
        verify(changeRequestMapper, org.mockito.Mockito.times(2)).insert(changes.capture());
        assertThat(changes.getAllValues()).extracting(ScheduleChangeRequestEntity::getChangeType)
                .containsExactly("EMERGENCY", "STOP");
        assertThat(changes.getAllValues().get(1).getReasonCode()).isEqualTo("EMERGENCY_PREEMPT");
        assertThat(changes.getAllValues().get(1).getPreemptScheduleId())
                .isEqualTo(result.getScheduleId() == null ? null
                        : UUID.fromString(result.getScheduleId()));

        ArgumentCaptor<NotificationEvent> events = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationPort, org.mockito.Mockito.times(2)).publish(events.capture());
        assertThat(events.getAllValues()).extracting(NotificationEvent::eventType)
                .containsExactly("EMERGENCY_SCHEDULED", "SCHEDULE_PREEMPTED");
        verify(auditWriter).record(eq("SCHEDULE_PREEMPT"), eq("SCHEDULE"), eq(preempted.getId().toString()),
                eq(true), any(), eq(USER_ID), eq("demo_admin"), any());
    }

    @Test
    void 抢占非本次冲突台次返回409() {
        stubEmergencyLookups();
        ScheduleOverlapRow conflictRow = overlap(ROOM_ID);
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null)))
                .thenReturn(List.of(conflictRow));
        EmergencyAddRequest request = emergencyRequest("EMERGENCY_PREEMPT");
        request.setPreemptScheduleId("80000000-0000-4000-8000-0000000000ff");

        assertThatThrownBy(() -> service.addEmergency(request, caller,
                AuthRequestContext.empty(), true))
                .isInstanceOf(SchedulingConflictException.class);
        verify(scheduleMapper, never()).updateById(any(ScheduleEntity.class));
    }

    @Test
    void 无冲突时指定抢占返回400且急诊原因码白名单校验() {
        stubEmergencyLookups();
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null))).thenReturn(List.of());
        EmergencyAddRequest request = emergencyRequest("EMERGENCY_GREEN_CHANNEL");
        request.setPreemptScheduleId("80000000-0000-4000-8000-000000000002");
        assertThatThrownBy(() -> service.addEmergency(request, caller,
                AuthRequestContext.empty(), true))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("无需抢占");

        EmergencyAddRequest invalid = emergencyRequest("NOT_ALLOWED");
        assertThatThrownBy(() -> service.addEmergency(invalid, caller,
                AuthRequestContext.empty(), true))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("白名单");
    }

    @Test
    void 急诊加台缺目标术间返回400() {
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("ACCEPTED", null));
        EmergencyAddRequest request = emergencyRequest("EMERGENCY_GREEN_CHANNEL");
        request.setRoomId(null);
        assertThatThrownBy(() -> service.addEmergency(request, caller,
                AuthRequestContext.empty(), true))
                .isInstanceOf(SchedulingValidationException.class)
                .hasMessageContaining("目标术间");
    }

    @Test
    void 变更历史按台次返回并按数据范围校验() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("CANCELLED", 2));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        ScheduleChangeRequestEntity stop = new ScheduleChangeRequestEntity();
        stop.setId(UUID.fromString("90000000-0000-4000-8000-000000000001"));
        stop.setScheduleId(SCHEDULE_ID);
        stop.setApplicationId(APP_ID);
        stop.setChangeType("STOP");
        stop.setReasonCode("PATIENT_CONDITION");
        stop.setReason("演示停台原因");
        stop.setStatus("APPROVED");
        stop.setRequestedBy(USER_ID);
        stop.setRequestedAt(NOW);
        stop.setApprovedBy(USER_ID);
        stop.setApprovedAt(NOW);
        stop.setVersion(1L);
        when(changeRequestMapper.selectCount(any())).thenReturn(1L);
        when(changeRequestMapper.selectList(any())).thenReturn(List.of(stop));

        ScheduleChangeList changes = service.listChanges(SCHEDULE_ID.toString(), caller, 1, 50);

        assertThat(changes.getItems()).hasSize(1);
        assertThat(changes.getItems().get(0).getChangeType().getValue()).isEqualTo("STOP");
        assertThat(changes.getItems().get(0).getStatus().getValue()).isEqualTo("APPROVED");
        assertThat(changes.getItems().get(0).getPreemptScheduleId()).isNull();

        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> service.listChanges(SCHEDULE_ID.toString(), caller, 1, 50))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 改台缺少原因码返回400且不落库() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(schedule("PLANNED", 1));
        when(roomMapper.selectById(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(roomMapper.selectByIdForUpdate(ROOM_ID)).thenReturn(room(ROOM_ID, DEPT_ID, "GENERAL"));
        when(applicationMapper.selectById(APP_ID)).thenReturn(application("ACCEPTED", null));
        when(scheduleMapper.selectOverlaps(any(), any(), eq(SCHEDULE_ID))).thenReturn(List.of());
        UpdateScheduleRequest request = new UpdateScheduleRequest();
        request.setPlannedStart(START);
        request.setPlannedEnd(END);
        request.setSequenceNo(1);
        request.setVersion(1);

        assertThatThrownBy(() -> service.reschedule(SCHEDULE_ID.toString(), request, caller,
                AuthRequestContext.empty()))
                .isInstanceOfSatisfying(SchedulingValidationException.class,
                        exception -> assertThat(exception.getMessage()).contains("不能为空"));
        verify(scheduleMapper, never()).updateById(any(ScheduleEntity.class));
        verify(changeRequestMapper, never()).insert(any(ScheduleChangeRequestEntity.class));
    }

    @Test
    void 抢占目标锁定后复检已变化返回PREEMPT_CONFLICT且不误停() {
        stubEmergencyLookups();
        ScheduleOverlapRow preempted = preemptOverlap();
        when(scheduleMapper.selectOverlaps(any(), any(), eq(null))).thenReturn(List.of(preempted));
        ScheduleEntity changed = preemptScheduleEntity();
        changed.setStatus("CANCELLED");
        when(scheduleMapper.selectByIdForUpdate(preempted.getId())).thenReturn(changed);
        EmergencyAddRequest request = emergencyRequest("EMERGENCY_PREEMPT");
        request.setPreemptScheduleId(preempted.getId().toString());

        assertThatThrownBy(() -> service.addEmergency(request, caller,
                AuthRequestContext.empty(), true))
                .isInstanceOfSatisfying(SchedulingConflictException.class,
                        exception -> assertThat(exception.conflicts().get(0).getConflictType())
                                .isEqualTo("PREEMPT_CONFLICT"));
        verify(scheduleMapper, never()).updateById(any(ScheduleEntity.class));
        verify(scheduleMapper, never()).insert(any(ScheduleEntity.class));
    }

    @Test
    void 停台排程不存在返回404() {
        when(scheduleMapper.selectById(SCHEDULE_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.stop(SCHEDULE_ID.toString(),
                stopRequest("PATIENT_CONDITION", 1), caller, AuthRequestContext.empty(), true))
                .isInstanceOf(NoSuchElementException.class);
    }
}
