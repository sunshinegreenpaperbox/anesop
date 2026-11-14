package com.anesop.m2.impl.rescue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.CreateResuscitationRequest;
import com.anesop.contracts.anesthesia.model.ResuscitationDrug;
import com.anesop.contracts.anesthesia.model.ResuscitationEventSummary;
import com.anesop.contracts.anesthesia.model.ResuscitationParticipant;
import com.anesop.contracts.anesthesia.model.ResuscitationRecord;
import com.anesop.contracts.anesthesia.model.ResuscitationRecordList;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.config.RecordProperties;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.ResuscitationRecordEntity;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.ResuscitationRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.security.access.AccessDeniedException;

/**
 * 抢救记录服务单测（M3-M2-02）：时间窗校验、用药明细校验、负责人/参与人员必填（B-4）、
 * append-only 写入与审计（人员姓名不入审计）。
 */
@ExtendWith(MockitoExtension.class)
class ResuscitationServiceImplTest {

    private static final UUID RECORD_ID = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID SCHEDULE_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("e0000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-12-01T02:00:00Z");
    private static final Instant RECORD_START = NOW.minusSeconds(3600);
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_anes", "jti", NOW.plusSeconds(900), "sid");

    @Mock
    private AnesthesiaRecordMapper recordMapper;
    @Mock
    private ResuscitationRecordMapper resuscitationMapper;
    @Mock
    private SchedulingService schedulingService;
    @Mock
    private AuditWriter auditWriter;

    private ResuscitationServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        service = new ResuscitationServiceImpl(recordMapper, resuscitationMapper,
                schedulingService, auditWriter, objectMapper, new RecordProperties(600),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createPersistsStructuredRecordAndAudits() {
        stubVisibleRecord();
        CreateResuscitationRequest request = request(NOW.minusSeconds(1200),
                NOW.minusSeconds(900), "演示：胸外按压（合成数据）", "SUCCESS");

        ResuscitationRecord created = service.create(RECORD_ID.toString(), request, CALLER, null);

        assertThat(created.getResuscitationId()).isNotBlank();
        assertThat(created.getEventSummary().getSummary()).isEqualTo("演示：胸外按压（合成数据）");
        assertThat(created.getOutcome()).isEqualTo(ResuscitationRecord.OutcomeEnum.SUCCESS);
        ArgumentCaptor<ResuscitationRecordEntity> inserted =
                ArgumentCaptor.forClass(ResuscitationRecordEntity.class);
        verify(resuscitationMapper).insertRecord(inserted.capture());
        assertThat(inserted.getValue().getEventSummary()).contains("胸外按压");
        assertThat(inserted.getValue().getDrugs()).contains("肾上腺素");
        assertThat(inserted.getValue().getLeader()).contains("演示负责人");
        assertThat(inserted.getValue().getRescuers()).contains("演示护士");
        assertThat(created.getLeader().getName()).isEqualTo("演示负责人（合成）");
        assertThat(created.getRescuers()).hasSize(2);
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("RESCUE_RECORD"), eq("RESUSCITATION_RECORD"), any(),
                eq(true), detail.capture(), eq(ACTOR), eq("synth_anes"), eq(null));
        assertThat(detail.getValue()).doesNotContainKeys("eventSummary", "drugs", "summary");
        assertThat(detail.getValue()).containsEntry("rescuerCount", 2);
        assertThat(detail.getValue().toString()).doesNotContain("演示负责人", "演示护士");
    }

    @Test
    void endedAtBeforeStartedAtIsRejected() {
        stubVisibleRecord();

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(), request(NOW.minusSeconds(600),
                NOW.minusSeconds(900), "演示", "SUCCESS"), CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("结束时间");
        verify(resuscitationMapper, never()).insertRecord(any());
    }

    @Test
    void startedAtBeforeRecordStartIsRejected() {
        stubVisibleRecord();

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(),
                request(RECORD_START.minusSeconds(60), NOW.minusSeconds(900), "演示", "SUCCESS"),
                CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("不得早于麻醉记录开始时间");
    }

    @Test
    void endedAtBeyondRecordWindowIsRejected() {
        stubVisibleRecord();

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(),
                request(NOW.plusSeconds(3600), NOW.plusSeconds(4200), "演示", "SUCCESS"),
                CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("超出麻醉记录时间窗");
    }

    @Test
    void missingEventSummaryIsRejected() {
        stubVisibleRecord();
        CreateResuscitationRequest request = request(NOW.minusSeconds(1200),
                NOW.minusSeconds(900), null, "SUCCESS");

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(), request, CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("事件经过摘要");
    }

    @Test
    void nonPositiveDoseIsRejected() {
        stubVisibleRecord();
        CreateResuscitationRequest request = request(NOW.minusSeconds(1200),
                NOW.minusSeconds(900), "演示", "SUCCESS");
        request.getDrugs().get(0).setDose(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(), request, CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("剂量必须大于 0");
    }

    @Test
    void drugOutsideResuscitationWindowIsRejected() {
        stubVisibleRecord();
        CreateResuscitationRequest request = request(NOW.minusSeconds(1200),
                NOW.minusSeconds(900), "演示", "SUCCESS");
        request.getDrugs().get(0).setAdministeredAt(OffsetDateTime.ofInstant(NOW.minusSeconds(60),
                ZoneOffset.UTC));

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(), request, CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("给药时间必须落在抢救时间窗内");
    }

    @Test
    void missingLeaderIsRejected() {
        stubVisibleRecord();
        CreateResuscitationRequest request = request(NOW.minusSeconds(1200),
                NOW.minusSeconds(900), "演示", "SUCCESS");
        request.setLeader(null);

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(), request, CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("抢救负责人");
    }

    @Test
    void emptyRescuersIsRejected() {
        stubVisibleRecord();
        CreateResuscitationRequest request = request(NOW.minusSeconds(1200),
                NOW.minusSeconds(900), "演示", "SUCCESS");
        request.setRescuers(List.of());

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(), request, CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("至少 1 人");
    }

    @Test
    void rescuerWithoutNameIsRejected() {
        stubVisibleRecord();
        CreateResuscitationRequest request = request(NOW.minusSeconds(1200),
                NOW.minusSeconds(900), "演示", "SUCCESS");
        ResuscitationParticipant nameless = new ResuscitationParticipant();
        nameless.setUserId("10000000-0000-4000-8000-000000000003");
        request.setRescuers(List.of(nameless));

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(), request, CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("姓名不能为空");
    }

    @Test
    void lockedRecordRejectsResuscitationWrite() {
        AnesthesiaRecordEntity locked = record();
        locked.setLockedAt(NOW.minusSeconds(60));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(locked);
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(locked);
        Schedule schedule = new Schedule();
        schedule.setScheduleId(SCHEDULE_ID.toString());
        when(schedulingService.get(SCHEDULE_ID.toString(), CALLER)).thenReturn(schedule);

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(),
                request(NOW.minusSeconds(1200), NOW.minusSeconds(900), "演示", "SUCCESS"), CALLER,
                null))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("已锁单");
        verify(resuscitationMapper, never()).insertRecord(any());
    }

    @Test
    void outOfScopeRecordIsNotFound() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record());
        when(schedulingService.get(SCHEDULE_ID.toString(), CALLER))
                .thenThrow(new AccessDeniedException("denied"));

        assertThatThrownBy(() -> service.create(RECORD_ID.toString(),
                request(NOW.minusSeconds(1200), NOW.minusSeconds(900), "演示", "SUCCESS"), CALLER,
                null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listReturnsParsedRows() {
        stubVisibleRecord();
        ResuscitationRecordEntity entity = new ResuscitationRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setRecordId(RECORD_ID);
        entity.setResusType("CPR");
        entity.setStartedAt(NOW.minusSeconds(1200));
        entity.setEndedAt(NOW.minusSeconds(900));
        entity.setEventSummary("{\"summary\":\"演示\",\"interventions\":[\"胸外按压\"]}");
        entity.setDrugs("[{\"drugName\":\"肾上腺素（演示）\",\"dose\":1,\"unit\":\"mg\"}]");
        entity.setLeader("{\"name\":\"演示负责人（合成）\"}");
        entity.setRescuers("[{\"name\":\"演示负责人（合成）\"},{\"name\":\"演示护士（合成）\"}]");
        entity.setOutcome("SUCCESS");
        entity.setCreatedAt(NOW.minusSeconds(800));
        entity.setCreatedBy(ACTOR);
        when(resuscitationMapper.selectByRecord(RECORD_ID)).thenReturn(List.of(entity));

        ResuscitationRecordList list = service.list(RECORD_ID.toString(), CALLER);

        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().get(0).getDrugs()).hasSize(1);
        assertThat(list.getItems().get(0).getEventSummary().getInterventions())
                .containsExactly("胸外按压");
        assertThat(list.getItems().get(0).getLeader().getName()).isEqualTo("演示负责人（合成）");
        assertThat(list.getItems().get(0).getRescuers()).hasSize(2);
    }

    // ------------------------------------------------------------- fixtures --

    private void stubVisibleRecord() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record());
        // create() 先在记录单行锁内判定冻结；list/其它用例不调用，故用 lenient 避免严格桩报错。
        lenient().when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(record());
        Schedule schedule = new Schedule();
        schedule.setScheduleId(SCHEDULE_ID.toString());
        when(schedulingService.get(SCHEDULE_ID.toString(), CALLER)).thenReturn(schedule);
    }

    private static AnesthesiaRecordEntity record() {
        AnesthesiaRecordEntity record = new AnesthesiaRecordEntity();
        record.setId(RECORD_ID);
        record.setScheduleId(SCHEDULE_ID);
        record.setStatus("ONGOING");
        record.setStartedAt(RECORD_START);
        record.setCreatedAt(RECORD_START.minusSeconds(300));
        record.setVersion(2L);
        return record;
    }

    private static CreateResuscitationRequest request(Instant startedAt, Instant endedAt,
            String summary, String outcome) {
        CreateResuscitationRequest request = new CreateResuscitationRequest();
        request.setResusType(CreateResuscitationRequest.ResusTypeEnum.CPR);
        request.setStartedAt(startedAt.atOffset(ZoneOffset.UTC));
        request.setEndedAt(endedAt.atOffset(ZoneOffset.UTC));
        ResuscitationEventSummary eventSummary = new ResuscitationEventSummary();
        eventSummary.setSummary(summary);
        eventSummary.setInterventions(List.of("胸外按压"));
        request.setEventSummary(eventSummary);
        ResuscitationDrug drug = new ResuscitationDrug();
        drug.setDrugName("肾上腺素（演示）");
        drug.setDose(BigDecimal.ONE);
        drug.setUnit("mg");
        drug.setRoute("IV");
        drug.setAdministeredAt(startedAt.plusSeconds(30).atOffset(ZoneOffset.UTC));
        request.setDrugs(new java.util.ArrayList<>(List.of(drug)));
        ResuscitationParticipant leader = new ResuscitationParticipant();
        leader.setUserId(ACTOR.toString());
        leader.setName("演示负责人（合成）");
        request.setLeader(leader);
        ResuscitationParticipant rescuer = new ResuscitationParticipant();
        rescuer.setName("演示护士（合成）");
        request.setRescuers(new java.util.ArrayList<>(List.of(leader, rescuer)));
        request.setOutcome(CreateResuscitationRequest.OutcomeEnum.fromValue(outcome));
        return request;
    }
}
