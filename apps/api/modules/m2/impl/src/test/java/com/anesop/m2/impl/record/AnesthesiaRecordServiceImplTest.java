package com.anesop.m2.impl.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.AdministerDrugRequest;
import com.anesop.contracts.anesthesia.model.AnesthesiaEvent;
import com.anesop.contracts.anesthesia.model.AnesthesiaRecord;
import com.anesop.contracts.anesthesia.model.AnesthesiaRecordList;
import com.anesop.contracts.anesthesia.model.CreateAnesthesiaRecordRequest;
import com.anesop.contracts.anesthesia.model.CreateRecordEventRequest;
import com.anesop.contracts.anesthesia.model.DrugAdministration;
import com.anesop.contracts.anesthesia.model.DrugAdministrationList;
import com.anesop.contracts.anesthesia.model.FluidRecord;
import com.anesop.contracts.anesthesia.model.FluidRecordList;
import com.anesop.contracts.anesthesia.model.IntakeOutputSummary;
import com.anesop.contracts.anesthesia.model.RecordEvent;
import com.anesop.contracts.anesthesia.model.RecordEventList;
import com.anesop.contracts.anesthesia.model.RecordFluidRequest;
import com.anesop.contracts.anesthesia.model.UpdateAnesthesiaRecordRequest;
import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.UserStatus;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.AuthService;
import com.anesop.m0.api.security.UserAdminService;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.threeway.ThreeWayCheckService;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.DrugAdministrationEntity;
import com.anesop.m2.impl.persistence.entity.FluidRecordEntity;
import com.anesop.m2.impl.persistence.entity.RecordEventEntity;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.DrugAdministrationMapper;
import com.anesop.m2.impl.persistence.mapper.FluidRecordMapper;
import com.anesop.m2.impl.config.RecordProperties;
import com.anesop.m2.impl.persistence.mapper.RecordEventMapper;
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
import org.springframework.security.authentication.BadCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

/**
 * 麻醉记录单服务单测（M3-M2-01）：唯一记录单、事件顺序与 append-only 语义、麻精双人核对、
 * 乐观锁、出入量汇总与审计。
 *
 * <p>Mock 掉 Mapper 与 m1-api 排程服务，断言可观察行为（返回 DTO/异常/落库字段/审计动作）；
 * 数据库触发器的 append-only 由集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class AnesthesiaRecordServiceImplTest {

    private static final UUID RECORD_UUID = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final String RECORD_ID = RECORD_UUID.toString();
    private static final UUID SCHEDULE_UUID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final String SCHEDULE_ID = SCHEDULE_UUID.toString();
    private static final UUID APPLICATION_UUID =
            UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID PATIENT_UUID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-12-01T00:50:00Z");
    private static final UUID ACTOR = UUID.fromString("e0000000-0000-4000-8000-000000000002");

    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_anesthesiologist", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-0001");

    @Mock
    private AnesthesiaRecordMapper recordMapper;
    @Mock
    private RecordEventMapper eventMapper;
    @Mock
    private DrugAdministrationMapper drugMapper;
    @Mock
    private FluidRecordMapper fluidMapper;
    @Mock
    private SchedulingService schedulingService;
    @Mock
    private ThreeWayCheckService threeWayCheckService;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private AuthService authService;
    @Mock
    private UserAdminService userAdminService;
    @Mock
    private ClinicalFailureAuditor failureAuditor;

    private static final UUID CHECKER =
            UUID.fromString("e0000000-0000-4000-8000-000000000003");

    private AnesthesiaRecordServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AnesthesiaRecordServiceImpl(recordMapper, eventMapper, drugMapper, fluidMapper,
                schedulingService, threeWayCheckService, auditWriter, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), authService, userAdminService, failureAuditor,
                new RecordProperties(600));
    }

    // --------------------------------------------------------------- create --

    @Test
    void createPersistsDraftRecordAndAuditsWithoutPatientIdentifiers() {
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.insertRecord(any())).thenReturn(1);

        AnesthesiaRecord created = service.create(createRequest("GENERAL"), CALLER, CONTEXT);

        assertThat(created.getStatus()).isEqualTo(AnesthesiaRecord.StatusEnum.DRAFT);
        assertThat(created.getRecordId()).isNotBlank();
        assertThat(created.getScheduleId()).isEqualTo(SCHEDULE_ID);
        assertThat(created.getPatientNo()).isEqualTo("SYNTH-P-0001");
        assertThat(created.getEvents()).isEmpty();
        assertThat(created.getVitalSigns()).isEmpty();
        assertThat(created.getVersion()).isEqualTo(1);

        ArgumentCaptor<AnesthesiaRecordEntity> entity =
                ArgumentCaptor.forClass(AnesthesiaRecordEntity.class);
        verify(recordMapper).insertRecord(entity.capture());
        assertThat(entity.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(entity.getValue().getStartedAt()).isNull();
        assertThat(entity.getValue().getCreatedBy()).isEqualTo(ACTOR);

        Map<String, Object> detail = auditDetail("RECORD_CREATE");
        assertThat(detail).containsKeys("recordId", "scheduleId", "applicationId", "status");
        assertThat(String.valueOf(detail)).doesNotContain("演示患者", "SYNTH-P-0001");
    }

    @Test
    void createRejectsDuplicateRecordForSameSchedule() {
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));
        when(recordMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(createRequest("GENERAL"), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
        verify(recordMapper, never()).insertRecord(any());
    }

    @Test
    void createMapsConcurrentDuplicateToConflict() {
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(recordMapper.insertRecord(any())).thenThrow(new DuplicateKeyException("uk"));

        assertThatThrownBy(() -> service.create(createRequest("GENERAL"), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void createRejectsInvisibleScheduleAndCancelledSchedule() {
        when(schedulingService.get(SCHEDULE_ID, CALLER))
                .thenThrow(new NoSuchElementException("not visible"))
                .thenReturn(schedule("CANCELLED"));
        assertThatThrownBy(() -> service.create(createRequest("GENERAL"), CALLER, CONTEXT))
                .isInstanceOf(M2UnprocessableException.class);

        assertThatThrownBy(() -> service.create(createRequest("GENERAL"), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void createValidatesScheduleIdAndAnesthesiaType() {
        assertThatThrownBy(() -> service.create(null, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
        CreateAnesthesiaRecordRequest invalid = createRequest("GENERAL");
        invalid.setScheduleId("not-a-uuid");
        assertThatThrownBy(() -> service.create(invalid, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
        CreateAnesthesiaRecordRequest noType = createRequest("GENERAL");
        noType.setAnesthesiaType(null);
        assertThatThrownBy(() -> service.create(noType, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
    }

    // ------------------------------------------------------------------ read --

    @Test
    void listByScheduleRejectsOutOfScopeAsCollectionSemantics() {
        when(schedulingService.get(SCHEDULE_ID, CALLER))
                .thenThrow(new NoSuchElementException("not visible"));

        assertThatThrownBy(() -> service.listBySchedule(SCHEDULE_ID, CALLER))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listBySchedule("invalid", CALLER))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void listByScheduleMapsLegacyEventsAndSkipsStartEndEvents() {
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));
        when(recordMapper.selectList(any())).thenReturn(List.of(ongoingRecord()));
        when(eventMapper.selectByRecord(RECORD_UUID)).thenReturn(List.of(
                event(1, "ANESTHESIA_START"), event(2, "INTUBATION"), event(3, "CUSTOM"),
                event(4, "ANESTHESIA_END")));
        when(threeWayCheckService.isThreeWayCheckCompleted(APPLICATION_UUID.toString()))
                .thenReturn(true);

        AnesthesiaRecordList list = service.listBySchedule(SCHEDULE_ID, CALLER);

        assertThat(list.getItems()).hasSize(1);
        AnesthesiaRecord record = list.getItems().get(0);
        assertThat(record.getStatus()).isEqualTo(AnesthesiaRecord.StatusEnum.IN_PROGRESS);
        assertThat(record.getEvents()).extracting(event -> event.getEventType().getValue())
                .containsExactly("INTUBATION", "OTHER");
        assertThat(record.getThreeWayCheckCompleted()).isTrue();
    }

    @Test
    void getReturnsNotFoundForUnknownOrOutOfScopeRecord() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(null);
        assertThatThrownBy(() -> service.get(RECORD_ID, CALLER))
                .isInstanceOf(NoSuchElementException.class);

        when(recordMapper.selectById(RECORD_UUID)).thenReturn(ongoingRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER))
                .thenThrow(new NoSuchElementException("not visible"));
        assertThatThrownBy(() -> service.get(RECORD_ID, CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ---------------------------------------------------------------- update --

    @Test
    void updateBumpsVersionAndAudits() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));
        when(recordMapper.updateRecord(eq(RECORD_UUID), eq(1L), eq("REGIONAL"), eq("DRAFT"),
                any(), any(), any(), eq(ACTOR))).thenReturn(1);

        UpdateAnesthesiaRecordRequest request = new UpdateAnesthesiaRecordRequest();
        request.setVersion(1);
        request.setAnesthesiaType(UpdateAnesthesiaRecordRequest.AnesthesiaTypeEnum.REGIONAL);
        AnesthesiaRecord updated = service.update(RECORD_ID, request, CALLER, CONTEXT);

        assertThat(updated.getAnesthesiaType())
                .isEqualTo(AnesthesiaRecord.AnesthesiaTypeEnum.REGIONAL);
        assertThat(updated.getVersion()).isEqualTo(2);
        Map<String, Object> detail = auditDetail("RECORD_UPDATE");
        assertThat(detail).containsKeys("before", "after");
        Map<String, Object> before = asMap(detail.get("before"));
        Map<String, Object> after = asMap(detail.get("after"));
        assertThat(before).containsEntry("anesthesiaType", "GENERAL").containsEntry("version", 1L);
        assertThat(after).containsEntry("anesthesiaType", "REGIONAL").containsEntry("version", 2L);
        assertThat(String.valueOf(detail)).doesNotContain("演示患者", "SYNTH-P-0001");
    }

    @Test
    void updateRejectsStaleVersionAndEventPayload() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));

        UpdateAnesthesiaRecordRequest stale = new UpdateAnesthesiaRecordRequest();
        stale.setVersion(9);
        assertThatThrownBy(() -> service.update(RECORD_ID, stale, CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);

        UpdateAnesthesiaRecordRequest withEvents = new UpdateAnesthesiaRecordRequest();
        withEvents.setVersion(1);
        withEvents.setEvents(List.of(new AnesthesiaEvent()));
        assertThatThrownBy(() -> service.update(RECORD_ID, withEvents, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
        verify(recordMapper, never()).updateRecord(any(), any(), anyString(), anyString(),
                any(), any(), any(), any());
    }

    @Test
    void updateMapsConcurrentModificationToConflict() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));
        when(recordMapper.updateRecord(eq(RECORD_UUID), eq(1L), anyString(), anyString(),
                any(), any(), any(), any())).thenReturn(0);

        UpdateAnesthesiaRecordRequest request = new UpdateAnesthesiaRecordRequest();
        request.setVersion(1);
        assertThatThrownBy(() -> service.update(RECORD_ID, request, CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    // ---------------------------------------------------------------- events --

    @Test
    void appendStartEventAssignsSeqAndStartsRecord() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));
        when(eventMapper.selectNextSeq(RECORD_UUID)).thenReturn(1);
        when(eventMapper.insertEvent(any())).thenReturn(1);
        when(recordMapper.updateRecord(eq(RECORD_UUID), eq(1L), eq("GENERAL"), eq("ONGOING"),
                any(), any(), any(), eq(ACTOR))).thenReturn(1);

        RecordEvent event = service.appendEvent(RECORD_ID,
                eventRequest("ANESTHESIA_START", "2026-12-01T08:52:00+08:00"), CALLER, CONTEXT);

        assertThat(event.getSeq()).isEqualTo(1);
        assertThat(event.getEventType()).isEqualTo(RecordEvent.EventTypeEnum.ANESTHESIA_START);
        assertThat(event.getRecordedAt()).isNotNull();
        Map<String, Object> detail = auditDetail("EVENT_RECORD");
        assertThat(detail).containsEntry("eventType", "ANESTHESIA_START").containsEntry("seq", 1)
                .containsEntry("backfilled", false);
        // 锁行语义：追加必须在 SELECT ... FOR UPDATE 之后取 seq。
        verify(recordMapper).selectByIdForUpdate(RECORD_UUID);
    }

    @Test
    void appendEventRejectsTimeBeforeRecordStartAndDuplicateStart() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));

        // 记录创建于 NOW（2026-12-01T08:50+08），事件早于创建时间 400。
        assertThatThrownBy(() -> service.appendEvent(RECORD_ID,
                eventRequest("INDUCTION", "2026-12-01T08:00:00+08:00"), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class)
                .satisfies(failure -> assertThat(((M2ValidationException) failure).field())
                        .isEqualTo("occurredAt"));
        assertThatThrownBy(() -> service.appendEvent(RECORD_ID, eventRequest("INDUCTION", null),
                CALLER, CONTEXT)).isInstanceOf(M2ValidationException.class);

        AnesthesiaRecordEntity ongoing = ongoingRecord();
        ongoing.setVersion(3L);
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(ongoing);
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(ongoing);
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));
        assertThatThrownBy(() -> service.appendEvent(RECORD_ID,
                eventRequest("ANESTHESIA_START", "2026-12-01T08:52:00+08:00"), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void appendEventRejectsFutureTimeBeyondTolerance() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));

        // 容差 600s：+20 分钟属于"未来事件"，400 且字段为 occurredAt。
        assertThatThrownBy(() -> service.appendEvent(RECORD_ID,
                eventRequest("ANESTHESIA_START", "2026-12-01T09:10:00+08:00"), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class)
                .satisfies(failure -> assertThat(((M2ValidationException) failure).field())
                        .isEqualTo("occurredAt"));
        verify(recordMapper, never()).selectByIdForUpdate(any());
    }

    @Test
    void appendEventRequiresEntryReasonWhenEarlierThanLatestEvent() {
        AnesthesiaRecordEntity ongoing = ongoingRecord();
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(ongoing);
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(ongoing);
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));
        // startedAt=NOW-10min；最新事件 NOW-5min；补录事件 NOW-7min（晚于开始、早于最新事件）。
        when(eventMapper.selectLatestOccurredAt(RECORD_UUID))
                .thenReturn(OffsetDateTime.ofInstant(NOW.minusSeconds(300), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.appendEvent(RECORD_ID,
                eventRequest("INTUBATION", "2026-12-01T08:43:00+08:00"), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class)
                .satisfies(failure -> assertThat(((M2ValidationException) failure).field())
                        .isEqualTo("entryReason"));

        when(eventMapper.selectNextSeq(RECORD_UUID)).thenReturn(2);
        when(eventMapper.insertEvent(any())).thenReturn(1);
        when(recordMapper.updateRecord(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        CreateRecordEventRequest request = eventRequest("INTUBATION",
                "2026-12-01T08:43:00+08:00");
        request.setEntryReason("补录：插管时间由麻醉单补记");
        RecordEvent event = service.appendEvent(RECORD_ID, request, CALLER, CONTEXT);
        assertThat(event.getEntryReason()).isEqualTo("补录：插管时间由麻醉单补记");
        assertThat(auditDetail("EVENT_RECORD")).containsEntry("backfilled", true);
    }

    @Test
    void appendEventRequiresEntryReasonWhenEarlierThanServerTolerance() {
        // 记录创建于 2 小时前；事件发生在 1 小时前（早于 now−600s，晚于记录开始）。
        AnesthesiaRecordEntity draft = draftRecord();
        draft.setCreatedAt(NOW.minusSeconds(7200));
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draft);
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(draft);
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));
        when(eventMapper.selectLatestOccurredAt(RECORD_UUID)).thenReturn(null);

        assertThatThrownBy(() -> service.appendEvent(RECORD_ID,
                eventRequest("ANESTHESIA_START", "2026-12-01T07:50:00+08:00"), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class)
                .satisfies(failure -> assertThat(((M2ValidationException) failure).field())
                        .isEqualTo("entryReason"));

        when(eventMapper.selectNextSeq(RECORD_UUID)).thenReturn(1);
        when(eventMapper.insertEvent(any())).thenReturn(1);
        when(recordMapper.updateRecord(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        CreateRecordEventRequest request = eventRequest("ANESTHESIA_START",
                "2026-12-01T07:50:00+08:00");
        request.setEntryReason("补录：麻醉开始时间由 HIS 回填");
        assertThat(service.appendEvent(RECORD_ID, request, CALLER, CONTEXT).getSeq()).isEqualTo(1);
    }

    @Test
    void appendEventRejectsDraftClinicalEventBeforeAnesthesiaStart() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));

        // DRAFT 无 ANESTHESIA_START：术中事件（插管/诱导等）不得先于开始事件出现。
        assertThatThrownBy(() -> service.appendEvent(RECORD_ID,
                eventRequest("INTUBATION", "2026-12-01T08:52:00+08:00"), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("ANESTHESIA_START");
    }

    @Test
    void appendEventRejectsEventAfterAnesthesiaEnd() {
        AnesthesiaRecordEntity ended = ongoingRecord();
        ended.setEndedAt(NOW.minusSeconds(60));
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(ended);
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(ended);
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));

        assertThatThrownBy(() -> service.appendEvent(RECORD_ID,
                eventRequest("CUSTOM", "2026-12-01T08:51:00+08:00"), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("已结束");
    }

    @Test
    void appendEventMapsSequenceCollisionToConflictWithoutRetry() {
        AnesthesiaRecordEntity ongoing = ongoingRecord();
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(ongoing);
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(ongoing);
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));
        when(eventMapper.selectNextSeq(RECORD_UUID)).thenReturn(1);
        when(eventMapper.insertEvent(any())).thenThrow(new DuplicateKeyException("seq"));

        assertThatThrownBy(() -> service.appendEvent(RECORD_ID,
                eventRequest("INDUCTION", "2026-12-01T08:52:00+08:00"), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
        // 唯一键冲突只作兜底：不重试取 seq（避免 PG 事务 aborted 后的 25P02）。
        verify(eventMapper).selectNextSeq(RECORD_UUID);
    }

    @Test
    void appendEventFailsWhenRecordVersionChangedConcurrently() {
        AnesthesiaRecordEntity ongoing = ongoingRecord();
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(ongoing);
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(ongoing);
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));
        when(eventMapper.selectNextSeq(RECORD_UUID)).thenReturn(1);
        when(eventMapper.insertEvent(any())).thenReturn(1);
        when(recordMapper.updateRecord(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.appendEvent(RECORD_ID,
                eventRequest("INDUCTION", "2026-12-01T08:52:00+08:00"), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void listEventsMapsTimelineWithSeq() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(ongoingRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));
        when(eventMapper.selectByRecord(RECORD_UUID)).thenReturn(List.of(
                event(1, "ANESTHESIA_START"), event(2, "INTUBATION")));

        RecordEventList list = service.listEvents(RECORD_ID, CALLER);

        assertThat(list.getItems()).extracting(RecordEvent::getSeq).containsExactly(1, 2);
        assertThat(list.getItems().get(1).getNote()).isEqualTo("演示备注（合成）");
    }

    // ----------------------------------------------------------------- drugs --

    @Test
    void recordNarcoticRequiresTwoPersonCheck() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));

        AdministerDrugRequest missing = drugRequest(true);
        missing.setSecondCheckerId(null);
        missing.setSecondCheckerName(null);
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, missing, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("第二核对人");

        AdministerDrugRequest samePerson = drugRequest(true);
        samePerson.setSecondCheckerId(ACTOR.toString());
        samePerson.setSecondCheckerName("本人（合成）");
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, samePerson, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("同一人");
        verify(drugMapper, never()).insertDrug(any());
        // 失败路径必须留痕（result=0），detail 不含核对人姓名。
        verify(failureAuditor, times(2)).recordFailure(eq("DRUG_ADMIN"),
                eq("ANESTHESIA_RECORD"), anyString(), any(), eq(CALLER), eq(CONTEXT));
    }

    @Test
    void recordNarcoticPersistsCheckerAndAuditsDrugName() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));
        stubEligibleChecker(CHECKER);
        when(drugMapper.insertDrug(any())).thenReturn(1);

        DrugAdministration drug = service.recordDrug(RECORD_ID, drugRequest(true), CALLER, CONTEXT);

        assertThat(drug.getNarcotic()).isTrue();
        assertThat(drug.getSecondCheckerId()).isNotBlank();
        assertThat(drug.getSecondCheckerName()).isEqualTo("演示核对护士（合成）");
        Map<String, Object> detail = auditDetail("DRUG_ADMIN");
        assertThat(detail).containsEntry("drugName", "舒芬太尼（演示）")
                .containsEntry("narcotic", true);
        assertThat(String.valueOf(detail))
                .doesNotContain("演示核对护士", "演示患者", "SYNTH-P-0001");
    }

    @Test
    void recordNarcoticRejectsCheckerWhoIsMissingDisabledOrUnauthorized() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));

        when(userAdminService.get(CHECKER))
                .thenThrow(new NoSuchElementException("missing"))
                .thenReturn(userSummary(UserStatus.DISABLED))
                .thenReturn(userSummary(UserStatus.ACTIVE));
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, drugRequest(true), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class)
                .satisfies(failure -> assertThat(((M2ValidationException) failure).field())
                        .isEqualTo("secondCheckerId"));

        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, drugRequest(true), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);

        when(authService.currentUser(CHECKER))
                .thenReturn(currentUserWithPermissions(List.of()))
                .thenThrow(new BadCredentialsException("disabled"));
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, drugRequest(true), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("书写权限");
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, drugRequest(true), CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);

        verify(drugMapper, never()).insertDrug(any());
        verify(failureAuditor, times(4)).recordFailure(eq("DRUG_ADMIN"),
                eq("ANESTHESIA_RECORD"), anyString(), any(), any(), any());
    }

    @Test
    void recordNonNarcoticRejectsCheckerFields() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));

        AdministerDrugRequest request = drugRequest(false);
        request.setSecondCheckerName("多余核对人（合成）");
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, request, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void recordDrugValidatesRequiredFields() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));

        AdministerDrugRequest request = drugRequest(false);
        request.setDose(new BigDecimal("0"));
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, request, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
        AdministerDrugRequest noName = drugRequest(false);
        noName.setDrugName(" ");
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, noName, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
        AdministerDrugRequest noTime = drugRequest(false);
        noTime.setAdministeredAt(null);
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, noTime, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
        AdministerDrugRequest noNarcoticFlag = drugRequest(false);
        noNarcoticFlag.setNarcotic(null);
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, noNarcoticFlag, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, null, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void listDrugsMapsEntries() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(ongoingRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));
        when(drugMapper.selectByRecord(RECORD_UUID)).thenReturn(List.of(drugEntity(false)));

        DrugAdministrationList list = service.listDrugs(RECORD_ID, CALLER);

        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().get(0).getDrugName()).isEqualTo("丙泊酚（演示）");
        assertThat(list.getItems().get(0).getDose()).isEqualByComparingTo("120");
    }

    @Test
    void lockedRecordRejectsUpdateEventDrugAndFluidWrites() {
        AnesthesiaRecordEntity locked = ongoingRecord();
        locked.setLockedAt(Instant.parse("2026-12-01T03:30:00Z"));
        locked.setLockedBy(ACTOR);
        locked.setLockSignatureRef("SIM-CA-" + RECORD_ID);
        locked.setLockSignatureHash("a".repeat(64));
        locked.setLockCaStatus("SIMULATED");
        locked.setStatus("LOCKED");
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(locked);
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(locked);
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));

        UpdateAnesthesiaRecordRequest update = new UpdateAnesthesiaRecordRequest();
        update.setVersion(4);
        assertThatThrownBy(() -> service.update(RECORD_ID, update, CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("锁单");
        assertThatThrownBy(() -> service.appendEvent(RECORD_ID,
                eventRequest("CUSTOM", "2026-12-01T08:52:00+08:00"), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
        assertThatThrownBy(() -> service.recordDrug(RECORD_ID, drugRequest(false), CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
        assertThatThrownBy(() -> service.recordFluid(RECORD_ID, fluidRequest("INTAKE", 500),
                CALLER, CONTEXT))
                .isInstanceOf(M2ConflictException.class);
        verify(drugMapper, never()).insertDrug(any());
        verify(fluidMapper, never()).insertFluid(any());
        verify(eventMapper, never()).insertEvent(any());
    }

    // ---------------------------------------------------------------- fluids --

    @Test
    void recordFluidValidatesAmountAndAudits() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(draftRecord());
        when(recordMapper.selectByIdForUpdate(RECORD_UUID)).thenReturn(draftRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("PLANNED"));

        RecordFluidRequest invalid = fluidRequest("INTAKE", 0);
        assertThatThrownBy(() -> service.recordFluid(RECORD_ID, invalid, CALLER, CONTEXT))
                .isInstanceOf(M2ValidationException.class);

        when(fluidMapper.insertFluid(any())).thenReturn(1);
        FluidRecord fluid = service.recordFluid(RECORD_ID, fluidRequest("INTAKE", 500), CALLER,
                CONTEXT);
        assertThat(fluid.getDirection()).isEqualTo(FluidRecord.DirectionEnum.INTAKE);
        assertThat(fluid.getAmountMl()).isEqualTo(500);
        Map<String, Object> detail = auditDetail("FLUID_RECORD");
        assertThat(detail).containsEntry("direction", "INTAKE").containsEntry("amountMl", 500);
    }

    @Test
    void ioSummaryComputesBalanceFromAggregates() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(ongoingRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));
        when(fluidMapper.selectTotals(RECORD_UUID))
                .thenReturn(Map.of("intake", 1500L, "output", 400L, "fluid_count", 3L));

        IntakeOutputSummary summary = service.ioSummary(RECORD_ID, CALLER);

        assertThat(summary.getIntakeMl()).isEqualTo(1500);
        assertThat(summary.getOutputMl()).isEqualTo(400);
        assertThat(summary.getBalanceMl()).isEqualTo(1100);
        assertThat(summary.getFluidCount()).isEqualTo(3);
        assertThat(summary.getComputedAt()).isNotNull();
    }

    @Test
    void listFluidsMapsDirection() {
        when(recordMapper.selectById(RECORD_UUID)).thenReturn(ongoingRecord());
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(schedule("IN_PROGRESS"));
        when(fluidMapper.selectByRecord(RECORD_UUID)).thenReturn(List.of(fluidEntity("OUTPUT")));

        FluidRecordList list = service.listFluids(RECORD_ID, CALLER);

        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().get(0).getDirection())
                .isEqualTo(FluidRecord.DirectionEnum.OUTPUT);
    }

    // --------------------------------------------------------------- helpers --

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private void stubEligibleChecker(UUID checkerId) {
        when(userAdminService.get(checkerId)).thenReturn(userSummary(UserStatus.ACTIVE));
        when(authService.currentUser(checkerId))
                .thenReturn(currentUserWithPermissions(List.of("m2:record:write")));
    }

    private static UserSummary userSummary(UserStatus status) {
        UserSummary summary = new UserSummary();
        summary.setUserId(CHECKER.toString());
        summary.setUsername("demo_self");
        summary.setStatus(status);
        return summary;
    }

    private static CurrentUser currentUserWithPermissions(List<String> permissions) {
        CurrentUser user = new CurrentUser();
        user.setUserId(CHECKER.toString());
        user.setUsername("demo_self");
        user.setPermissions(permissions);
        return user;
    }

    private Map<String, Object> auditDetail(String action) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq(action), eq("ANESTHESIA_RECORD"), anyString(), eq(true),
                detail.capture(), eq(ACTOR), eq("synth_anesthesiologist"), eq(CONTEXT));
        return detail.getValue();
    }

    private static Schedule schedule(String status) {
        Schedule schedule = new Schedule();
        schedule.setScheduleId(SCHEDULE_ID);
        schedule.setApplicationId(APPLICATION_UUID.toString());
        schedule.setPatientId(PATIENT_UUID.toString());
        schedule.setPatientNo("SYNTH-P-0001");
        schedule.setPatientName("演示患者");
        schedule.setStatus(Schedule.StatusEnum.fromValue(status));
        return schedule;
    }

    private static AnesthesiaRecordEntity draftRecord() {
        AnesthesiaRecordEntity entity = new AnesthesiaRecordEntity();
        entity.setId(RECORD_UUID);
        entity.setScheduleId(SCHEDULE_UUID);
        entity.setApplicationId(APPLICATION_UUID);
        entity.setPatientId(PATIENT_UUID);
        entity.setStatus("DRAFT");
        entity.setAnesthesiaType("GENERAL");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        entity.setUpdatedAt(NOW);
        entity.setUpdatedBy(ACTOR);
        entity.setVersion(1L);
        entity.setDeleted(false);
        return entity;
    }

    private static AnesthesiaRecordEntity ongoingRecord() {
        AnesthesiaRecordEntity entity = draftRecord();
        entity.setStatus("ONGOING");
        entity.setStartedAt(NOW.minusSeconds(600));
        entity.setVersion(3L);
        return entity;
    }

    private static RecordEventEntity event(int seq, String type) {
        RecordEventEntity entity = new RecordEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setRecordId(RECORD_UUID);
        entity.setSeq(seq);
        entity.setEventType(type);
        entity.setOccurredAt(NOW.minusSeconds(600 - seq * 60L));
        entity.setSource("MANUAL");
        entity.setPayload("{\"note\":\"演示备注（合成）\"}");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        return entity;
    }

    private static DrugAdministrationEntity drugEntity(boolean narcotic) {
        DrugAdministrationEntity entity = new DrugAdministrationEntity();
        entity.setId(UUID.randomUUID());
        entity.setRecordId(RECORD_UUID);
        entity.setDrugName("丙泊酚（演示）");
        entity.setDose(new BigDecimal("120"));
        entity.setUnit("mg");
        entity.setRoute("IV");
        entity.setAdministeredAt(NOW.minusSeconds(300));
        entity.setNarcotic(narcotic);
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        return entity;
    }

    private static FluidRecordEntity fluidEntity(String direction) {
        FluidRecordEntity entity = new FluidRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setRecordId(RECORD_UUID);
        entity.setDirection(direction);
        entity.setFluidType("尿量（演示）");
        entity.setAmountMl(200);
        entity.setRecordedAt(NOW.minusSeconds(120));
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        return entity;
    }

    private static CreateAnesthesiaRecordRequest createRequest(String anesthesiaType) {
        CreateAnesthesiaRecordRequest request = new CreateAnesthesiaRecordRequest();
        request.setScheduleId(SCHEDULE_ID);
        request.setAnesthesiaType(
                CreateAnesthesiaRecordRequest.AnesthesiaTypeEnum.fromValue(anesthesiaType));
        return request;
    }

    private static CreateRecordEventRequest eventRequest(String type, String occurredAt) {
        CreateRecordEventRequest request = new CreateRecordEventRequest();
        request.setEventType(CreateRecordEventRequest.EventTypeEnum.fromValue(type));
        request.setOccurredAt(occurredAt == null ? null : OffsetDateTime.parse(occurredAt));
        return request;
    }

    private static AdministerDrugRequest drugRequest(boolean narcotic) {
        AdministerDrugRequest request = new AdministerDrugRequest();
        request.setDrugName("舒芬太尼（演示）");
        request.setDose(new BigDecimal("10"));
        request.setUnit("ug");
        request.setRoute("IV");
        request.setAdministeredAt(OffsetDateTime.parse("2026-12-01T09:10:00+08:00"));
        request.setNarcotic(narcotic);
        if (narcotic) {
            request.setSecondCheckerId("e0000000-0000-4000-8000-000000000003");
            request.setSecondCheckerName("演示核对护士（合成）");
        }
        return request;
    }

    private static RecordFluidRequest fluidRequest(String direction, int amountMl) {
        RecordFluidRequest request = new RecordFluidRequest();
        request.setDirection(RecordFluidRequest.DirectionEnum.fromValue(direction));
        request.setFluidType("复方氯化钠（演示）");
        request.setAmountMl(amountMl);
        return request;
    }
}
