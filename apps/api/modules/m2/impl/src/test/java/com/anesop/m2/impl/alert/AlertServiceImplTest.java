package com.anesop.m2.impl.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.AcknowledgeAlertRequest;
import com.anesop.contracts.anesthesia.model.Alert;
import com.anesop.contracts.anesthesia.model.AlertEvaluationResult;
import com.anesop.contracts.anesthesia.model.AlertList;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.persistence.entity.AlertEntity;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.mapper.AlertMapper;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
 * 告警服务单测（M3-M2-02）：只读列表、手动评估委托（actor/水位）、状态机 409、
 * 数据范围 404、审计无 PHI。增量引擎与水位行为由 {@link AlertEvaluatorTest} 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class AlertServiceImplTest {

    private static final UUID RECORD_ID = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID SCHEDULE_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_ID = UUID.fromString("f2000000-0000-4000-8000-0000000000c1");
    private static final UUID RULE_ID = UUID.fromString("1b000000-0000-4000-8000-000000000001");
    private static final UUID ALERT_ID = UUID.fromString("1c000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("e0000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-12-01T02:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_anes", "jti", NOW.plusSeconds(900), "sid");

    @Mock
    private AlertMapper alertMapper;
    @Mock
    private AnesthesiaRecordMapper recordMapper;
    @Mock
    private SchedulingService schedulingService;
    @Mock
    private AlertEvaluator evaluator;
    @Mock
    private AuditWriter auditWriter;

    private AlertServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AlertServiceImpl(alertMapper, recordMapper, schedulingService, evaluator,
                auditWriter, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listAlertsReturnsRowsWithoutEvaluating() {
        stubVisibleRecord();
        when(alertMapper.selectByRecord(RECORD_ID)).thenReturn(List.of(alertEntity("TRIGGERED", 1)));

        AlertList list = service.listAlerts(RECORD_ID.toString(), CALLER);

        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().get(0).getRuleCode()).isEqualTo("SPO2_LOW");
        assertThat(list.getItems().get(0).getStatus()).isEqualTo(Alert.StatusEnum.TRIGGERED);
        verifyNoInteractions(evaluator);
    }

    @Test
    void evaluateDelegatesToIncrementalEvaluatorWithManualActor() {
        stubVisibleRecord();
        Instant watermark = NOW.minusSeconds(60);
        when(evaluator.evaluate(any(), any())).thenReturn(
                new AlertEvaluator.EvaluationSummary(1, 2, 1, watermark));

        AlertEvaluationResult result = service.evaluate(RECORD_ID.toString(), CALLER, null);

        assertThat(result.getRecordId()).isEqualTo(RECORD_ID.toString());
        assertThat(result.getEvaluatedRules()).isEqualTo(1);
        assertThat(result.getSampledFrames()).isEqualTo(2);
        assertThat(result.getCreatedAlerts()).isEqualTo(1);
        assertThat(result.getEvaluatedThrough()).isNotNull();
        ArgumentCaptor<AlertEvaluator.EvaluationTrigger> trigger =
                ArgumentCaptor.forClass(AlertEvaluator.EvaluationTrigger.class);
        verify(evaluator).evaluate(any(), trigger.capture());
        assertThat(trigger.getValue().source()).isEqualTo("MANUAL");
        assertThat(trigger.getValue().actorId()).isEqualTo(ACTOR);
        assertThat(trigger.getValue().actorName()).isEqualTo("synth_anes");
    }

    @Test
    void unknownRecordIsNotFound() {
        assertThatThrownBy(() -> service.evaluate(RECORD_ID.toString(), CALLER, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void outOfScopeRecordIsNotFound() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record());
        when(schedulingService.get(SCHEDULE_ID.toString(), CALLER))
                .thenThrow(new AccessDeniedException("denied"));

        assertThatThrownBy(() -> service.listAlerts(RECORD_ID.toString(), CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void acknowledgeTransitionsOnceAndAuditsWithoutReason() {
        stubVisibleRecord();
        when(alertMapper.selectById(ALERT_ID))
                .thenReturn(alertEntity("TRIGGERED", 1), alertEntity("ACKNOWLEDGED", 2));
        when(alertMapper.acknowledge(eq(ALERT_ID), eq(1L), eq("ACKNOWLEDGED"), eq(ACTOR), any(),
                eq("已处置"))).thenReturn(1);

        Alert alert = service.acknowledge(ALERT_ID.toString(), acknowledge("CONFIRMED", "已处置", 1),
                CALLER, null);

        assertThat(alert.getStatus()).isEqualTo(Alert.StatusEnum.ACKNOWLEDGED);
        assertThat(alert.getVersion()).isEqualTo(2);
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("ALERT_ACK"), eq("ALERT"), eq(ALERT_ID.toString()), eq(true),
                detail.capture(), eq(ACTOR), eq("synth_anes"), isNull());
        assertThat(detail.getValue()).doesNotContainKey("reason");
        assertThat(detail.getValue()).containsEntry("decision", "CONFIRMED");
    }

    @Test
    void ignoredDecisionMapsToIgnoredStatus() {
        stubVisibleRecord();
        when(alertMapper.selectById(ALERT_ID))
                .thenReturn(alertEntity("TRIGGERED", 1), alertEntity("IGNORED", 2));
        when(alertMapper.acknowledge(any(), any(), eq("IGNORED"), any(), any(), any())).thenReturn(1);

        Alert alert = service.acknowledge(ALERT_ID.toString(), acknowledge("IGNORED", "无临床意义", 1),
                CALLER, null);

        assertThat(alert.getStatus()).isEqualTo(Alert.StatusEnum.IGNORED);
    }

    @Test
    void alreadyProcessedAlertIsConflict() {
        stubVisibleRecord();
        when(alertMapper.selectById(ALERT_ID)).thenReturn(alertEntity("ACKNOWLEDGED", 2));

        assertThatThrownBy(() -> service.acknowledge(ALERT_ID.toString(),
                acknowledge("CONFIRMED", "重复", 2), CALLER, null))
                .isInstanceOf(M2ConflictException.class);
        verify(alertMapper, never()).acknowledge(any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleAlertVersionIsConflict() {
        stubVisibleRecord();
        when(alertMapper.selectById(ALERT_ID)).thenReturn(alertEntity("TRIGGERED", 1));

        assertThatThrownBy(() -> service.acknowledge(ALERT_ID.toString(),
                acknowledge("CONFIRMED", "过期", 9), CALLER, null))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void losingRaceOnConditionalUpdateIsConflict() {
        stubVisibleRecord();
        when(alertMapper.selectById(ALERT_ID)).thenReturn(alertEntity("TRIGGERED", 1));
        when(alertMapper.acknowledge(any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.acknowledge(ALERT_ID.toString(),
                acknowledge("CONFIRMED", "并发", 1), CALLER, null))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void emptyReasonIsRejected() {
        stubVisibleRecord();
        when(alertMapper.selectById(ALERT_ID)).thenReturn(alertEntity("TRIGGERED", 1));

        assertThatThrownBy(() -> service.acknowledge(ALERT_ID.toString(),
                acknowledge("CONFIRMED", "  ", 1), CALLER, null))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void unknownAlertIsNotFound() {
        assertThatThrownBy(() -> service.acknowledge(ALERT_ID.toString(),
                acknowledge("CONFIRMED", "x", 1), CALLER, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ------------------------------------------------------------- fixtures --

    private void stubVisibleRecord() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record());
        Schedule schedule = new Schedule();
        schedule.setScheduleId(SCHEDULE_ID.toString());
        schedule.setRoomId(ROOM_ID.toString());
        when(schedulingService.get(SCHEDULE_ID.toString(), CALLER)).thenReturn(schedule);
    }

    private static AnesthesiaRecordEntity record() {
        AnesthesiaRecordEntity record = new AnesthesiaRecordEntity();
        record.setId(RECORD_ID);
        record.setScheduleId(SCHEDULE_ID);
        record.setStatus("ONGOING");
        record.setStartedAt(NOW.minusSeconds(3600));
        record.setCreatedAt(NOW.minusSeconds(7200));
        record.setVersion(3L);
        return record;
    }




    private static AlertEntity alertEntity(String status, long version) {
        AlertEntity entity = new AlertEntity();
        entity.setId(ALERT_ID);
        entity.setRecordId(RECORD_ID);
        entity.setRuleId(RULE_ID);
        entity.setRuleCode("SPO2_LOW");
        entity.setRuleVersion(1);
        entity.setMetric("SPO2");
        entity.setValue(new BigDecimal("85"));
        entity.setUnit("%");
        entity.setSeverity("CRITICAL");
        entity.setTriggeredAt(NOW.minusSeconds(1800));
        entity.setDedupeKey(RECORD_ID + ":SPO2_LOW:" + NOW.minusSeconds(1800).getEpochSecond());
        entity.setStatus(status);
        entity.setCreatedAt(NOW.minusSeconds(1799));
        entity.setUpdatedAt(NOW.minusSeconds(1799));
        entity.setVersion(version);
        if (!"TRIGGERED".equals(status)) {
            entity.setAcknowledgedBy(ACTOR);
            entity.setAcknowledgedAt(NOW);
            entity.setAcknowledgeReason("已处置");
        }
        return entity;
    }

    private static AcknowledgeAlertRequest acknowledge(String decision, String reason, int version) {
        AcknowledgeAlertRequest request = new AcknowledgeAlertRequest();
        request.setDecision(AcknowledgeAlertRequest.DecisionEnum.fromValue(decision));
        request.setReason(reason);
        request.setVersion(version);
        return request;
    }
}
