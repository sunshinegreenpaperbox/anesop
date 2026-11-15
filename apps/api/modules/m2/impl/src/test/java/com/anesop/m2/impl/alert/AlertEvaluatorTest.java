package com.anesop.m2.impl.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m1.api.notification.NotificationEvent;
import com.anesop.m1.api.notification.NotificationPort;
import com.anesop.m2.impl.config.AlertProperties;
import com.anesop.m2.impl.persistence.entity.AlertEntity;
import com.anesop.m2.impl.persistence.entity.AlertRuleEntity;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.DeviceRegistryRow;
import com.anesop.m2.impl.persistence.entity.VitalRow;
import com.anesop.m2.impl.persistence.mapper.AlertMapper;
import com.anesop.m2.impl.persistence.mapper.AlertRuleMapper;
import com.anesop.m2.impl.persistence.mapper.AlertWatermarkMapper;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaVitalMapper;
import com.anesop.m2.impl.persistence.mapper.DeviceRegistryMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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

/**
 * 增量告警评估器单测（M3-M2-02 评审 B-1/B-3）：DRAFT 不评估、水位增量、
 * 审计关联 alert 主键（S-1）、手工/自动 actor 区分。
 */
@ExtendWith(MockitoExtension.class)
class AlertEvaluatorTest {

    private static final UUID RECORD_ID = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_ID = UUID.fromString("f2000000-0000-4000-8000-0000000000c1");
    private static final UUID RULE_ID = UUID.fromString("1b000000-0000-4000-8000-000000000002");
    private static final UUID ACTOR = UUID.fromString("e0000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-12-01T02:00:00Z");
    private static final Instant T0 = NOW.minusSeconds(600);

    @Mock
    private AlertMapper alertMapper;
    @Mock
    private AlertRuleMapper ruleMapper;
    @Mock
    private AlertWatermarkMapper watermarkMapper;
    @Mock
    private AnesthesiaVitalMapper vitalMapper;
    @Mock
    private DeviceRegistryMapper registryMapper;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private NotificationPort notificationPort;

    private AlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AlertEvaluator(alertMapper, ruleMapper, watermarkMapper, vitalMapper,
                registryMapper, new AlertRuleEngine(new AlertProperties(10, 10, 300, 300, 300, 20000)),
                new AlertProperties(10, 10, 300, 300, 300, 20000), auditWriter, notificationPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void draftRecordIsNotEvaluated() {
        AnesthesiaRecordEntity draft = record("DRAFT", null);
        when(watermarkMapper.selectEvaluatedThrough(RECORD_ID)).thenReturn(T0);

        AlertEvaluator.EvaluationSummary summary = evaluator.evaluate(draft,
                AlertEvaluator.EvaluationTrigger.system());

        assertThat(summary.created()).isZero();
        assertThat(summary.frames()).isZero();
        verifyNoInteractions(ruleMapper, registryMapper, alertMapper);
        verify(ruleMapper, never()).selectEnabledRules();
    }

    @Test
    void recordWithoutStartedAtIsNotEvaluated() {
        AnesthesiaRecordEntity ongoingWithoutStart = record("ONGOING", null);
        when(watermarkMapper.selectEvaluatedThrough(RECORD_ID)).thenReturn(T0);

        AlertEvaluator.EvaluationSummary summary = evaluator.evaluate(ongoingWithoutStart,
                AlertEvaluator.EvaluationTrigger.system());

        assertThat(summary.created()).isZero();
        verifyNoInteractions(ruleMapper, registryMapper, alertMapper);
    }

    @Test
    void recordWithoutRoomHasNoSamples() {
        AnesthesiaRecordEntity record = record("ONGOING", NOW.minusSeconds(3600));
        record.setRoomId(null);
        when(watermarkMapper.selectEvaluatedThrough(RECORD_ID)).thenReturn(null);
        when(ruleMapper.selectEnabledRules()).thenReturn(List.of(rule()));

        AlertEvaluator.EvaluationSummary summary = evaluator.evaluate(record,
                AlertEvaluator.EvaluationTrigger.system());

        assertThat(summary.frames()).isZero();
        assertThat(summary.created()).isZero();
        verify(registryMapper, never()).selectDevices(any(), any());
    }

    @Test
    void incrementalEvaluationEmitsOnlyNewDecisionAndAdvancesWatermark() {
        AnesthesiaRecordEntity record = record("ONGOING", NOW.minusSeconds(3600));
        when(watermarkMapper.selectEvaluatedThrough(RECORD_ID)).thenReturn(null);
        when(ruleMapper.selectEnabledRules()).thenReturn(List.of(rule()));
        when(registryMapper.selectDevices(isNull(), eq(ROOM_ID))).thenReturn(List.of(device()));
        when(vitalMapper.selectAlertSamples(any(), any(), any(), any(), anyInt())).thenReturn(
                List.of(vital(T0, 45), vital(T0.plusSeconds(10), 44),
                        vital(T0.plusSeconds(20), 43), vital(T0.plusSeconds(30), 42)));
        when(alertMapper.insertIgnoreDuplicate(any())).thenReturn(1);

        AlertEvaluator.EvaluationSummary summary = evaluator.evaluate(record,
                AlertEvaluator.EvaluationTrigger.system());

        assertThat(summary.created()).isEqualTo(1);
        assertThat(summary.frames()).isEqualTo(4);
        assertThat(summary.evaluatedThrough()).isEqualTo(T0.plusSeconds(30));
        ArgumentCaptor<AlertEntity> inserted = ArgumentCaptor.forClass(AlertEntity.class);
        verify(alertMapper).insertIgnoreDuplicate(inserted.capture());
        AlertEntity alert = inserted.getValue();
        assertThat(alert.getRuleCode()).isEqualTo("HR_LOW");
        assertThat(alert.getTriggeredAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(alert.getDedupeKey())
                .isEqualTo(RECORD_ID + ":HR_LOW:" + T0.getEpochSecond());
        verify(watermarkMapper).advance(RECORD_ID, T0.plusSeconds(30), NOW);
        // S-1：ALERT_TRIGGERED 以 alert 主键为 resourceId、resourceType=ALERT。
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("ALERT_TRIGGERED"), eq("ALERT"),
                eq(alert.getId().toString()), eq(true), detail.capture(), isNull(),
                eq("alert-engine"), isNull());
        assertThat(detail.getValue()).containsEntry("alertId", alert.getId().toString())
                .containsEntry("triggerSource", "INGEST_EVENT");
    }

    @Test
    void watermarkPreventsReevaluatingHistoricalWindows() {
        AnesthesiaRecordEntity record = record("ONGOING", NOW.minusSeconds(3600));
        when(watermarkMapper.selectEvaluatedThrough(RECORD_ID))
                .thenReturn(T0.plusSeconds(30));
        when(ruleMapper.selectEnabledRules()).thenReturn(List.of(rule()));
        when(registryMapper.selectDevices(isNull(), eq(ROOM_ID))).thenReturn(List.of(device()));
        when(vitalMapper.selectAlertSamples(any(), any(), any(), any(), anyInt())).thenReturn(
                List.of(vital(T0, 45), vital(T0.plusSeconds(10), 44),
                        vital(T0.plusSeconds(20), 43), vital(T0.plusSeconds(30), 42)));

        AlertEvaluator.EvaluationSummary summary = evaluator.evaluate(record,
                AlertEvaluator.EvaluationTrigger.system());

        assertThat(summary.created()).isZero();
        verify(alertMapper, never()).insertIgnoreDuplicate(any());
    }

    @Test
    void criticalAlertIsDeliveredAsNotificationWithoutPatientIdentifiers() {
        AnesthesiaRecordEntity record = record("ONGOING", NOW.minusSeconds(3600));
        when(watermarkMapper.selectEvaluatedThrough(RECORD_ID)).thenReturn(null);
        when(ruleMapper.selectEnabledRules()).thenReturn(List.of(criticalRule()));
        when(registryMapper.selectDevices(isNull(), eq(ROOM_ID))).thenReturn(List.of(device()));
        when(vitalMapper.selectAlertSamples(any(), any(), any(), any(), anyInt())).thenReturn(
                List.of(vital(T0, 45), vital(T0.plusSeconds(10), 44),
                        vital(T0.plusSeconds(20), 43), vital(T0.plusSeconds(30), 42)));
        when(alertMapper.insertIgnoreDuplicate(any())).thenReturn(1);

        evaluator.evaluate(record, AlertEvaluator.EvaluationTrigger.system());

        ArgumentCaptor<NotificationEvent> event = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationPort).publish(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("ALERT_CRITICAL");
        assertThat(event.getValue().payload())
                .containsKeys("alertId", "recordId", "metric", "severity")
                .doesNotContainKeys("patientName", "patientNo", "inpatientNo", "diagnosis");
        assertThat(event.getValue().payload().get("severity")).isEqualTo("CRITICAL");
    }

    @Test
    void warnAlertDoesNotSendNotification() {
        AnesthesiaRecordEntity record = record("ONGOING", NOW.minusSeconds(3600));
        when(watermarkMapper.selectEvaluatedThrough(RECORD_ID)).thenReturn(null);
        when(ruleMapper.selectEnabledRules()).thenReturn(List.of(rule()));
        when(registryMapper.selectDevices(isNull(), eq(ROOM_ID))).thenReturn(List.of(device()));
        when(vitalMapper.selectAlertSamples(any(), any(), any(), any(), anyInt())).thenReturn(
                List.of(vital(T0, 45), vital(T0.plusSeconds(10), 44),
                        vital(T0.plusSeconds(20), 43), vital(T0.plusSeconds(30), 42)));
        when(alertMapper.insertIgnoreDuplicate(any())).thenReturn(1);

        evaluator.evaluate(record, AlertEvaluator.EvaluationTrigger.system());

        verify(notificationPort, never()).publish(any());
    }

    @Test
    void manualTriggerAuditsCaller() {
        AnesthesiaRecordEntity record = record("ONGOING", NOW.minusSeconds(3600));
        when(watermarkMapper.selectEvaluatedThrough(RECORD_ID)).thenReturn(null);
        when(ruleMapper.selectEnabledRules()).thenReturn(List.of(rule()));
        when(registryMapper.selectDevices(isNull(), eq(ROOM_ID))).thenReturn(List.of(device()));
        when(vitalMapper.selectAlertSamples(any(), any(), any(), any(), anyInt())).thenReturn(
                List.of(vital(T0, 45), vital(T0.plusSeconds(10), 44),
                        vital(T0.plusSeconds(20), 43), vital(T0.plusSeconds(30), 42)));
        when(alertMapper.insertIgnoreDuplicate(any())).thenReturn(1);

        evaluator.evaluate(record, new AlertEvaluator.EvaluationTrigger("MANUAL", ACTOR,
                "synth_anes", null));

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("ALERT_TRIGGERED"), eq("ALERT"), any(), eq(true),
                detail.capture(), eq(ACTOR), eq("synth_anes"), isNull());
        assertThat(detail.getValue()).containsEntry("triggerSource", "MANUAL");
    }

    // ------------------------------------------------------------- fixtures --

    private static AnesthesiaRecordEntity record(String status, Instant startedAt) {
        AnesthesiaRecordEntity record = new AnesthesiaRecordEntity();
        record.setId(RECORD_ID);
        record.setRoomId(ROOM_ID);
        record.setStatus(status);
        record.setStartedAt(startedAt);
        record.setCreatedAt(startedAt == null ? NOW.minusSeconds(7200)
                : startedAt.minusSeconds(60));
        return record;
    }

    private static AlertRuleEntity criticalRule() {
        AlertRuleEntity rule = rule();
        rule.setCode("HR_CRITICAL");
        rule.setSeverity("CRITICAL");
        return rule;
    }

    private static AlertRuleEntity rule() {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(RULE_ID);
        rule.setCode("HR_LOW");
        rule.setName("心动过缓（演示【待确认】）");
        rule.setMetric("HR");
        rule.setOperator("LT");
        rule.setThresholdLow(new BigDecimal("50"));
        rule.setDurationSeconds(30);
        rule.setSeverity("WARN");
        rule.setVersion(1);
        rule.setEnabled(true);
        return rule;
    }

    private static DeviceRegistryRow device() {
        DeviceRegistryRow row = new DeviceRegistryRow();
        row.setGatewayId("GW-SYNTH-01");
        row.setDeviceId("DEV-SYNTH-01");
        row.setRoomId(ROOM_ID);
        return row;
    }

    private static VitalRow vital(Instant measuredAt, int hr) {
        VitalRow row = new VitalRow();
        row.setMeasuredAt(measuredAt);
        row.setHr(hr);
        row.setQuality(0);
        return row;
    }
}
