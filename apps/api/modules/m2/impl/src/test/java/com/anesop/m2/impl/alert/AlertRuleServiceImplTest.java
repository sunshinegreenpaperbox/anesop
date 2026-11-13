package com.anesop.m2.impl.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.AlertRule;
import com.anesop.contracts.anesthesia.model.AlertRuleList;
import com.anesop.contracts.anesthesia.model.UpdateAlertRuleRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.persistence.entity.AlertRuleEntity;
import com.anesop.m2.impl.persistence.mapper.AlertRuleMapper;
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
import org.springframework.dao.DuplicateKeyException;

/**
 * 规则服务单测（M3-M2-02）：版本化保存、阈值自洽与按指标范围校验、名称更新与
 * pendingConfirmation、历史版本查询、乐观锁/非当前版本 409、审计无原文。
 */
@ExtendWith(MockitoExtension.class)
class AlertRuleServiceImplTest {

    private static final UUID RULE_ID = UUID.fromString("1b000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-12-01T02:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_admin", "jti", NOW.plusSeconds(900), "sid");

    @Mock
    private AlertRuleMapper ruleMapper;
    @Mock
    private AuditWriter auditWriter;

    private AlertRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AlertRuleServiceImpl(ruleMapper, auditWriter,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listRulesMapsCurrentVersions() {
        when(ruleMapper.selectCurrentRules()).thenReturn(List.of(rule(1, true)));

        AlertRuleList list = service.listRules();

        assertThat(list.getItems()).hasSize(1);
        AlertRule rule = list.getItems().get(0);
        assertThat(rule.getCode()).isEqualTo("SPO2_LOW");
        assertThat(rule.getOperator()).isEqualTo(AlertRule.OperatorEnum.LT);
        assertThat(rule.getSeverity()).isEqualTo(AlertRule.SeverityEnum.CRITICAL);
        assertThat(rule.getVersion()).isEqualTo(1);
        assertThat(rule.getCreatedBy()).isEqualTo(ACTOR.toString());
    }

    @Test
    void updateRuleCreatesNextVersionAndDisablesPreviousOne() {
        AlertRuleEntity current = rule(1, true);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(current);

        AlertRule updated = service.updateRule(RULE_ID.toString(),
                request("88", null, 15, "CRITICAL", true, 1), CALLER, null);

        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getThresholdLow()).isEqualByComparingTo("88");
        assertThat(updated.getDurationSeconds()).isEqualTo(15);
        assertThat(updated.getPendingConfirmation()).isTrue();
        verify(ruleMapper).disableVersion(eq(RULE_ID), any(), eq(ACTOR));
        ArgumentCaptor<AlertRuleEntity> inserted = ArgumentCaptor.forClass(AlertRuleEntity.class);
        verify(ruleMapper).insertVersion(inserted.capture());
        assertThat(inserted.getValue().getVersion()).isEqualTo(2);
        assertThat(inserted.getValue().getThresholdHigh()).isNull();
        verify(auditWriter).record(eq("ALERT_RULE_UPDATE"), eq("ALERT_RULE"),
                eq(inserted.getValue().getId().toString()), eq(true), any(), eq(ACTOR),
                eq("synth_admin"), eq(null));
    }

    @Test
    void staleVersionIsRejectedWithConflict() {
        AlertRuleEntity current = rule(1, true);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(current);

        assertThatThrownBy(() -> service.updateRule(RULE_ID.toString(),
                request("88", null, 0, "WARN", true, 2), CALLER, null))
                .isInstanceOf(M2ConflictException.class);
        verify(ruleMapper, never()).insertVersion(any());
    }

    @Test
    void nonCurrentVersionIsRejectedWithConflict() {
        AlertRuleEntity current = rule(1, true);
        AlertRuleEntity newer = rule(2, true);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(newer);

        assertThatThrownBy(() -> service.updateRule(RULE_ID.toString(),
                request("88", null, 0, "WARN", true, 1), CALLER, null))
                .isInstanceOf(M2ConflictException.class);
        verify(ruleMapper, never()).disableVersion(any(), any(), any());
    }

    @Test
    void unknownRuleIsNotFound() {
        when(ruleMapper.selectById(RULE_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.updateRule(RULE_ID.toString(),
                request("88", null, 0, "WARN", true, 1), CALLER, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void ltWithoutLowerThresholdIsRejected() {
        AlertRuleEntity current = rule(1, true);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(current);

        assertThatThrownBy(() -> service.updateRule(RULE_ID.toString(),
                request(null, null, 0, "WARN", true, 1), CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("下限阈值");
    }

    @Test
    void outOfRangeRequiresOrderedThresholds() {
        AlertRuleEntity current = rule(1, true);
        current.setOperator("OUT_OF_RANGE");
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(current);

        assertThatThrownBy(() -> service.updateRule(RULE_ID.toString(),
                request("50", "40", 0, "WARN", true, 1), CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("上限阈值必须大于下限阈值");
    }

    @Test
    void durationOutOfRangeIsRejected() {
        AlertRuleEntity current = rule(1, true);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(current);

        assertThatThrownBy(() -> service.updateRule(RULE_ID.toString(),
                request("88", null, 3601, "WARN", true, 1), CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("0–3600");
    }

    @Test
    void concurrentVersionRaceMapsToConflict() {
        AlertRuleEntity current = rule(1, true);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(current);
        when(ruleMapper.insertVersion(any())).thenThrow(new DuplicateKeyException("uk"));

        assertThatThrownBy(() -> service.updateRule(RULE_ID.toString(),
                request("88", null, 0, "WARN", true, 1), CALLER, null))
                .isInstanceOf(M2ConflictException.class);
    }

    @Test
    void auditDetailContainsNoFreeText() {
        AlertRuleEntity current = rule(1, true);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(current);

        service.updateRule(RULE_ID.toString(), request("88", null, 0, "WARN", true, 1), CALLER,
                null);

        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("ALERT_RULE_UPDATE"), eq("ALERT_RULE"), any(), eq(true),
                detail.capture(), any(), any(), any());
        assertThat(detail.getValue()).containsOnlyKeys("operation", "ruleId", "code", "fromVersion",
                "toVersion", "name", "metric", "operator", "thresholdLow", "thresholdHigh",
                "durationSeconds", "severity", "enabled", "pendingConfirmation");
    }

    @Test
    void thresholdIsValidatedAgainstMetricRange() {
        AlertRuleEntity current = rule(1, true);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(current);

        // SpO2 允许 0–100：101 越界 400（不臆造临床阈值，只做物理范围校验）。
        assertThatThrownBy(() -> service.updateRule(RULE_ID.toString(),
                request("101", null, 0, "WARN", true, 1), CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("允许范围");

        current.setMetric("TEMP");
        current.setOperator("LT");
        assertThatThrownBy(() -> service.updateRule(RULE_ID.toString(),
                request("20", null, 0, "WARN", true, 1), CALLER, null))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("允许范围");
    }

    @Test
    void nameIsUpdatableAndThresholdChangeMarksPendingConfirmation() {
        AlertRuleEntity current = rule(1, true);
        current.setPendingConfirmation(false);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(current);

        AlertRule updated = service.updateRule(RULE_ID.toString(),
                request("88", null, 0, "WARN", true, 1, "血氧过低（院内确认版）"), CALLER, null);

        assertThat(updated.getName()).isEqualTo("血氧过低（院内确认版）");
        assertThat(updated.getPendingConfirmation()).isTrue();
    }

    @Test
    void nameOnlyChangeKeepsPreviousPendingFlag() {
        AlertRuleEntity current = rule(1, true);
        current.setPendingConfirmation(false);
        when(ruleMapper.selectById(RULE_ID)).thenReturn(current);
        when(ruleMapper.selectLatestByCodeForUpdate("SPO2_LOW")).thenReturn(current);

        AlertRule updated = service.updateRule(RULE_ID.toString(),
                request("90", null, 0, "CRITICAL", true, 1, "仅改名"), CALLER, null);

        assertThat(updated.getPendingConfirmation()).isFalse();
    }

    @Test
    void listVersionsReturnsHistoryForTraceability() {
        AlertRuleEntity current = rule(2, true);
        when(ruleMapper.selectById(current.getId())).thenReturn(current);
        when(ruleMapper.selectVersionsByCode("SPO2_LOW")).thenReturn(List.of(current, rule(1, false)));

        AlertRuleList versions = service.listVersions(current.getId().toString());

        assertThat(versions.getItems()).hasSize(2);
        assertThat(versions.getItems().get(0).getVersion()).isEqualTo(2);
        assertThat(versions.getItems().get(1).getVersion()).isEqualTo(1);
        assertThat(versions.getItems().get(1).getEnabled()).isFalse();
    }

    // ------------------------------------------------------------- fixtures --

    private static AlertRuleEntity rule(int version, boolean enabled) {
        AlertRuleEntity entity = new AlertRuleEntity();
        entity.setId(version == 1 ? RULE_ID
                : UUID.fromString("1b000000-0000-4000-8000-000000000011"));
        entity.setCode("SPO2_LOW");
        entity.setName("血氧饱和度过低（演示【待确认】）");
        entity.setMetric("SPO2");
        entity.setOperator("LT");
        entity.setThresholdLow(new BigDecimal("90.00"));
        entity.setThresholdHigh(null);
        entity.setDurationSeconds(0);
        entity.setSeverity("CRITICAL");
        entity.setVersion(version);
        entity.setEnabled(enabled);
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        entity.setUpdatedAt(NOW);
        entity.setUpdatedBy(ACTOR);
        return entity;
    }

    private static UpdateAlertRuleRequest request(String low, String high, int duration,
            String severity, boolean enabled, int version) {
        return request(low, high, duration, severity, enabled, version, null);
    }

    private static UpdateAlertRuleRequest request(String low, String high, int duration,
            String severity, boolean enabled, int version, String name) {
        UpdateAlertRuleRequest request = new UpdateAlertRuleRequest();
        request.setName(name);
        request.setThresholdLow(low == null ? null : new BigDecimal(low));
        request.setThresholdHigh(high == null ? null : new BigDecimal(high));
        request.setDurationSeconds(duration);
        request.setSeverity(UpdateAlertRuleRequest.SeverityEnum.fromValue(severity));
        request.setEnabled(enabled);
        request.setVersion(version);
        return request;
    }
}
