package com.anesop.m2.impl.alert;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.m2.impl.config.AlertProperties;
import com.anesop.m2.impl.persistence.entity.AlertRuleEntity;
import com.anesop.m2.impl.persistence.entity.VitalRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 规则引擎边界单测（M3-M2-02）：等于阈值不触发、持续时长、采样断档切窗、质量码、MAP 推导、
 * 去重键与规则版本快照。纯判定层，无数据库。
 */
class AlertRuleEngineTest {

    private static final UUID RECORD = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final Instant BASE = Instant.parse("2026-12-01T01:00:00Z");
    private final AlertRuleEngine engine = new AlertRuleEngine(
            new AlertProperties(10, 10, 300, 300, 300, 20000));

    @Test
    void ltAndGtDoNotTriggerOnThresholdEquality() {
        AlertRuleEntity spo2 = rule("SPO2_LOW", "SPO2", "LT", "90", null, 0, "CRITICAL", 1);
        AlertRuleEntity hrHigh = rule("HR_HIGH", "HR", "GT", null, "120", 0, "WARN", 1);

        assertThat(engine.evaluate(RECORD, List.of(spo2, hrHigh), List.of(
                sample(BASE, "90", "120")))).isEmpty();

        assertThat(engine.evaluate(RECORD, List.of(spo2, hrHigh), List.of(
                sample(BASE, "89.9", "120.1"))))
                .extracting(AlertDecision::ruleCode)
                .containsExactly("HR_HIGH", "SPO2_LOW");
    }

    @Test
    void outOfRangeTreatsBoundsAsClosedInterval() {
        AlertRuleEntity etco2 = rule("ETCO2_RANGE", "ETCO2", "OUT_OF_RANGE", "30", "50", 0, "WARN", 1);

        assertThat(engine.evaluate(RECORD, List.of(etco2), List.of(etco2Sample("30")))).isEmpty();
        assertThat(engine.evaluate(RECORD, List.of(etco2), List.of(etco2Sample("50")))).isEmpty();
        assertThat(engine.evaluate(RECORD, List.of(etco2), List.of(etco2Sample("29.9"))))
                .hasSize(1);
        assertThat(engine.evaluate(RECORD, List.of(etco2), List.of(etco2Sample("50.1"))))
                .hasSize(1);
    }

    @Test
    void durationRequiresSustainedBreachAndTriggersAtQualifyingSample() {
        AlertRuleEntity hrLow = rule("HR_LOW", "HR", "LT", "50", null, 30, "WARN", 1);

        // 连续波形按 10s 间隔采样（HR gap 默认 10s）；仅 20 秒未达到 30s，不触发。
        assertThat(engine.evaluate(RECORD, List.of(hrLow), List.of(
                hrSample(BASE, "45"), hrSample(BASE.plusSeconds(10), "44"),
                hrSample(BASE.plusSeconds(20), "43")))).isEmpty();

        // 达到 30 秒：在满足时长的采样上触发，值取该采样值。
        List<AlertDecision> decisions = engine.evaluate(RECORD, List.of(hrLow), List.of(
                hrSample(BASE, "45"), hrSample(BASE.plusSeconds(10), "44"),
                hrSample(BASE.plusSeconds(20), "43"), hrSample(BASE.plusSeconds(30), "42")));
        assertThat(decisions).hasSize(1);
        AlertDecision decision = decisions.get(0);
        assertThat(decision.triggeredAt()).isEqualTo(BASE.plusSeconds(30));
        assertThat(decision.value()).isEqualByComparingTo("42");
        assertThat(decision.dedupeKey())
                .isEqualTo(RECORD + ":HR_LOW:" + BASE.getEpochSecond());
    }

    @Test
    void zeroDurationTriggersOnFirstBreachAndOncePerWindow() {
        AlertRuleEntity spo2 = rule("SPO2_LOW", "SPO2", "LT", "90", null, 0, "CRITICAL", 1);

        List<AlertDecision> decisions = engine.evaluate(RECORD, List.of(spo2), List.of(
                sample(BASE, "85", "80"), sample(BASE.plusSeconds(5), "84", "80")));
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).triggeredAt()).isEqualTo(BASE);
    }

    @Test
    void longSampleGapStartsNewWindowAndProducesSeparateDedupeKeys() {
        AlertRuleEntity spo2 = rule("SPO2_LOW", "SPO2", "LT", "90", null, 0, "CRITICAL", 1);

        List<AlertDecision> decisions = engine.evaluate(RECORD, List.of(spo2), List.of(
                sample(BASE, "85", "80"), sample(BASE.plusSeconds(400), "84", "80")));
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).dedupeKey()).isNotEqualTo(decisions.get(1).dedupeKey());
    }

    @Test
    void qualityFramesAreSkippedWithoutResettingTheWindow() {
        AlertRuleEntity hrLow = rule("HR_LOW", "HR", "LT", "50", null, 20, "WARN", 1);
        AlertSample missing = new AlertSample(BASE.plusSeconds(5), BigDecimal.valueOf(40), null,
                null, null, null, 2);

        // 有效样本 0/10/20s（间隔 10s 未超容忍）；质量码 2 的帧被跳过、不重置窗口。
        List<AlertDecision> decisions = engine.evaluate(RECORD, List.of(hrLow), List.of(
                hrSample(BASE, "45"), missing, hrSample(BASE.plusSeconds(10), "44"),
                hrSample(BASE.plusSeconds(20), "43")));
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).triggeredAt()).isEqualTo(BASE.plusSeconds(20));
    }

    @Test
    void nibpEmptyFramesDoNotResetWindowAndGapBoundaryIsInclusive() {
        AlertRuleEntity mapLow = rule("MAP_LOW", "MAP", "LT", "65", null, 60, "WARN", 1);
        // NIBP 300s 间隔容忍：空帧（无 nibp 值）不重置窗口，300s 仍属同一窗口 → 触发。
        List<AlertSample> samples = List.of(
                bpSample(BASE, 70, 40),
                spo2Only(BASE.plusSeconds(100), "98"),
                spo2Only(BASE.plusSeconds(200), "98"),
                bpSample(BASE.plusSeconds(300), 72, 40));
        List<AlertDecision> decisions = engine.evaluate(RECORD, List.of(mapLow), samples);
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).dedupeKey())
                .isEqualTo(RECORD + ":MAP_LOW:" + BASE.getEpochSecond());
        assertThat(decisions.get(0).triggeredAt()).isEqualTo(BASE.plusSeconds(300));
    }

    @Test
    void nibpGapBeyondToleranceStartsNewWindow() {
        AlertRuleEntity mapLow = rule("MAP_LOW", "MAP", "LT", "65", null, 60, "WARN", 1);
        // 301s > 300s：新窗口（起点 301s），持续时长未达到 → 不触发。
        assertThat(engine.evaluate(RECORD, List.of(mapLow), List.of(
                bpSample(BASE, 70, 40), bpSample(BASE.plusSeconds(301), 72, 40)))).isEmpty();
    }

    @Test
    void repeatedNibpValuesWithinToleranceDoNotAccumulateDuration() {
        AlertRuleEntity mapLow = rule("MAP_LOW", "MAP", "LT", "65", null, 60, "WARN", 1);
        List<AlertSample> repeated = List.of(
                bpSample(BASE, 70, 40),
                bpSample(BASE.plusSeconds(10), 70, 40),
                bpSample(BASE.plusSeconds(20), 70, 40),
                bpSample(BASE.plusSeconds(290), 70, 40));
        // 同一测得值重复上报（间隔 < 300s）视为同一次测量：不累加持续时长。
        assertThat(engine.evaluate(RECORD, List.of(mapLow), repeated)).isEmpty();

        // 300s 处的重复值达到间隔边界，按新一次测量推进窗口 → 达到 60s 持续 → 触发。
        List<AlertSample> boundary = List.of(
                bpSample(BASE, 70, 40),
                bpSample(BASE.plusSeconds(10), 70, 40),
                bpSample(BASE.plusSeconds(300), 70, 40));
        List<AlertDecision> decisions = engine.evaluate(RECORD, List.of(mapLow), boundary);
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).triggeredAt()).isEqualTo(BASE.plusSeconds(300));
    }

    @Test
    void waveformGapToleranceIsTenSeconds() {
        AlertRuleEntity hrLow = rule("HR_LOW", "HR", "LT", "50", null, 15, "WARN", 1);
        // 10s 间隔仍在同一窗口 → 15s 持续达成。
        assertThat(engine.evaluate(RECORD, List.of(hrLow), List.of(
                hrSample(BASE, "45"), hrSample(BASE.plusSeconds(10), "44"),
                hrSample(BASE.plusSeconds(15), "43")))).hasSize(1);
        // 11s 间隔切窗：起点变为 11s，15s 处只有 4s → 不触发。
        assertThat(engine.evaluate(RECORD, List.of(hrLow), List.of(
                hrSample(BASE, "45"), hrSample(BASE.plusSeconds(11), "44"),
                hrSample(BASE.plusSeconds(15), "43")))).isEmpty();
    }

    @Test
    void emitAfterFiltersHistoricalDecisionsButKeepsWindowContinuity() {
        AlertRuleEntity hrLow = rule("HR_LOW", "HR", "LT", "50", null, 30, "WARN", 1);
        List<AlertSample> samples = List.of(
                hrSample(BASE, "45"), hrSample(BASE.plusSeconds(10), "44"),
                hrSample(BASE.plusSeconds(20), "43"), hrSample(BASE.plusSeconds(30), "42"));

        // 水位 20s：30s 处判定属于新采样，窗口起点仍是 0s（跨水位连续）。
        List<AlertDecision> decisions = engine.evaluate(RECORD, List.of(hrLow), samples,
                BASE.plusSeconds(20));
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).dedupeKey())
                .isEqualTo(RECORD + ":HR_LOW:" + BASE.getEpochSecond());
        // 水位 30s：该判定已评估过，不再返回（不回评历史窗口）。
        assertThat(engine.evaluate(RECORD, List.of(hrLow), samples, BASE.plusSeconds(30)))
                .isEmpty();
    }

    @Test
    void mapIsDerivedFromSystolicAndDiastolic() {
        AlertRuleEntity mapLow = rule("MAP_LOW", "MAP", "LT", "65", null, 0, "WARN", 1);

        assertThat(engine.evaluate(RECORD, List.of(mapLow), List.of(bpSample(BASE, 120, 80))))
                .isEmpty();
        List<AlertDecision> decisions = engine.evaluate(RECORD, List.of(mapLow),
                List.of(bpSample(BASE, 80, 40)));
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).value()).isEqualByComparingTo("53.3");
        assertThat(decisions.get(0).metric()).isEqualTo("MAP");
    }

    @Test
    void decisionCarriesRuleVersionAndUnitForAuditSnapshot() {
        AlertRuleEntity spo2 = rule("SPO2_LOW", "SPO2", "LT", "90", null, 0, "CRITICAL", 7);

        AlertDecision decision = engine.evaluate(RECORD, List.of(spo2),
                List.of(sample(BASE, "88", "80"))).get(0);

        assertThat(decision.ruleVersion()).isEqualTo(7);
        assertThat(decision.ruleId()).isEqualTo(spo2.getId());
        assertThat(decision.unit()).isEqualTo("%");
        assertThat(decision.severity()).isEqualTo("CRITICAL");
    }

    @Test
    void repeatedEvaluationOfSameSamplesYieldsSameDecisions() {
        AlertRuleEntity spo2 = rule("SPO2_LOW", "SPO2", "LT", "90", null, 0, "CRITICAL", 1);
        List<AlertSample> samples = List.of(sample(BASE, "85", "80"));

        assertThat(engine.evaluate(RECORD, List.of(spo2), samples))
                .isEqualTo(engine.evaluate(RECORD, List.of(spo2), samples));
    }

    @Test
    void emptyInputsProduceNoDecisions() {
        assertThat(engine.evaluate(null, List.of(), List.of())).isEmpty();
        assertThat(engine.evaluate(RECORD, List.of(), List.of(sample(BASE, "80", "80")))).isEmpty();
        assertThat(engine.evaluate(RECORD, List.of(rule("HR_LOW", "HR", "LT", "50", null, 0,
                "WARN", 1)), List.of())).isEmpty();
    }

    // ------------------------------------------------------------- fixtures --

    private static AlertRuleEntity rule(String code, String metric, String operator, String low,
            String high, int duration, String severity, int version) {
        AlertRuleEntity rule = new AlertRuleEntity();
        rule.setId(UUID.randomUUID());
        rule.setCode(code);
        rule.setName(code);
        rule.setMetric(metric);
        rule.setOperator(operator);
        rule.setThresholdLow(low == null ? null : new BigDecimal(low));
        rule.setThresholdHigh(high == null ? null : new BigDecimal(high));
        rule.setDurationSeconds(duration);
        rule.setSeverity(severity);
        rule.setVersion(version);
        rule.setEnabled(true);
        return rule;
    }

    private static AlertSample sample(Instant at, String spo2, String hr) {
        return new AlertSample(at, new BigDecimal(hr), new BigDecimal(spo2), null, null, null, 0);
    }

    private static AlertSample hrSample(Instant at, String hr) {
        return new AlertSample(at, new BigDecimal(hr), null, null, null, null, 0);
    }

    private static AlertSample spo2Only(Instant at, String spo2) {
        return new AlertSample(at, null, new BigDecimal(spo2), null, null, null, 0);
    }

    private static AlertSample etco2Sample(String etco2) {
        return new AlertSample(BASE, null, null, null, new BigDecimal(etco2), null, 0);
    }

    private static AlertSample bpSample(Instant at, int systolic, int diastolic) {
        VitalRow row = new VitalRow();
        row.setMeasuredAt(at);
        row.setNibpSys(systolic);
        row.setNibpDia(diastolic);
        row.setQuality(0);
        return AlertSample.from(row);
    }
}
