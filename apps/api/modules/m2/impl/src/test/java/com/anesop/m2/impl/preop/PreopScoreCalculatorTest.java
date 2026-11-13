package com.anesop.m2.impl.preop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.contracts.anesthesia.model.PreopApfelInput;
import com.anesop.contracts.anesthesia.model.PreopRcriInput;
import com.anesop.contracts.anesthesia.model.PreopScoreInput;
import com.anesop.contracts.anesthesia.model.PreopScores;
import com.anesop.contracts.anesthesia.model.PreopStopBangInput;
import com.anesop.m2.api.preop.PreopValidationException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * 评分计算器表驱动用例（M2-M4-01，临床规则高风险：每量表正常/边界/缺项）。
 *
 * <p>固定时钟保证 calculatedAt 可断言；所有输入为合成值，不涉及真实患者。
 */
class PreopScoreCalculatorTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-11-28T01:10:00Z");

    private static final Clock CLOCK = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    private static PreopScoreProperties defaults() {
        return new PreopScoreProperties();
    }

    private final PreopScoreCalculator calculator =
            new PreopScoreCalculator(CLOCK, defaults());

    // ---------------------------------------------------------------- RCRI --

    @ParameterizedTest(name = "RCRI {0}/{1}/{2}/{3}/{4}/{5} → {6} {7}")
    @CsvSource({
        // 6 个预测因子全 false：0 分低危
        "false,false,false,false,false,false,0,LOW",
        // 仅 1 项：中危边界
        "true,false,false,false,false,false,1,MODERATE",
        // 2 项：高危下边界
        "true,true,false,false,false,false,2,HIGH",
        // 全部 6 项：上限
        "true,true,true,true,true,true,6,HIGH",
    })
    void rcriScoresAndRiskBoundaries(boolean highRiskSurgery, boolean ischemic,
            boolean heartFailure, boolean cerebrovascular, boolean insulinDiabetes,
            boolean renalImpairment, int expectedScore, String expectedRisk) {
        PreopScores scores = calculator.calculate(input(
                rcri(highRiskSurgery, ischemic, heartFailure, cerebrovascular, insulinDiabetes,
                        renalImpairment),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false),
                List.of()));

        assertThat(scores.getRcriScore()).isEqualTo(expectedScore);
        assertThat(scores.getRcriRisk().getValue()).isEqualTo(expectedRisk);
        assertThat(scores.getCalculatorVersion()).isEqualTo("1.0.0");
        assertThat(scores.getCalculatedAt().toInstant()).isEqualTo(FIXED_INSTANT);
    }

    // --------------------------------------------------------------- Apfel --

    @ParameterizedTest(name = "Apfel {0}/{1}/{2}/{3} → {4} {5}")
    @CsvSource({
        "false,false,false,false,0,LOW",
        "true,false,false,false,1,LOW",
        "true,true,false,false,2,MODERATE",
        "true,true,true,false,3,HIGH",
        "true,true,true,true,4,HIGH",
    })
    void apfelScoresAndRiskBoundaries(boolean female, boolean ponvHistory, boolean nonSmoker,
            boolean opioid, int expectedScore, String expectedRisk) {
        PreopScores scores = calculator.calculate(input(
                rcri(false, false, false, false, false, false),
                apfel(female, ponvHistory, nonSmoker, opioid),
                stopBang(false, false, false, false, false, false, false, false),
                List.of()));

        assertThat(scores.getApfelScore()).isEqualTo(expectedScore);
        assertThat(scores.getApfelRisk().getValue()).isEqualTo(expectedRisk);
    }

    // ------------------------------------------------------------ STOP-BANG --

    @ParameterizedTest(name = "STOP-BANG {0}/{1}/{2}/{3}/{4}/{5}/{6}/{7} → {8} {9}")
    @CsvSource({
        "false,false,false,false,false,false,false,false,0,LOW",
        "true,true,false,false,false,false,false,false,2,LOW",
        "true,true,true,false,false,false,false,false,3,MODERATE",
        "true,true,true,true,false,false,false,false,4,MODERATE",
        "true,true,true,true,true,false,false,false,5,HIGH",
        "true,true,true,true,true,true,true,true,8,HIGH",
    })
    void stopBangScoresAndRiskBoundaries(boolean snoring, boolean tiredness, boolean apnea,
            boolean hypertension, boolean bmi, boolean age, boolean neck, boolean male,
            int expectedScore, String expectedRisk) {
        PreopScores scores = calculator.calculate(input(
                rcri(false, false, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(snoring, tiredness, apnea, hypertension, bmi, age, neck, male),
                List.of()));

        assertThat(scores.getStopBangScore()).isEqualTo(expectedScore);
        assertThat(scores.getStopBangRisk().getValue()).isEqualTo(expectedRisk);
    }

    // ----------------------------------------------------------------- DASI --

    /** Hlatky 1989 表 2 的 12 项权重，逐项驱动（合计 58.2）。 */
    static Stream<Arguments> dasiSingleItemWeights() {
        return Stream.of(
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.SELF_CARE, "2.75"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.WALK_INDOORS, "1.75"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.WALK_BLOCK, "2.75"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.CLIMB_STAIRS, "5.50"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.RUN_SHORT_DISTANCE, "8.00"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.LIGHT_HOUSEHOLD_WORK, "2.70"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.MODERATE_HOUSEHOLD_WORK, "3.50"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.HEAVY_HOUSEHOLD_WORK, "8.00"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.YARD_WORK, "4.50"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.SEXUAL_RELATIONS, "5.25"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.MODERATE_RECREATION, "6.00"),
                Arguments.of(PreopScoreInput.DasiActivitiesEnum.STRENUOUS_SPORTS, "7.50"));
    }

    @ParameterizedTest(name = "DASI 单项 {0} = {1}")
    @MethodSource("dasiSingleItemWeights")
    void dasiWeightPerActivity(PreopScoreInput.DasiActivitiesEnum activity, String expected) {
        PreopScores scores = calculator.calculate(input(
                rcri(false, false, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false),
                List.of(activity)));

        assertThat(scores.getDasiScore()).isEqualByComparingTo(new BigDecimal(expected));
        // 单项最高 8.00 → 估算 VO2 = 0.43×8+9.6 = 13.04 < 14 → 功能容量偏低
        assertThat(scores.getFunctionalCapacityLow()).isTrue();
    }

    static Stream<Arguments> dasiCases() {
        return Stream.of(
                Arguments.of(List.of(), new BigDecimal("0.0"), true),
                Arguments.of(List.of(PreopScoreInput.DasiActivitiesEnum.SELF_CARE),
                        new BigDecimal("2.75"), true),
                Arguments.of(List.of(PreopScoreInput.DasiActivitiesEnum.CLIMB_STAIRS),
                        new BigDecimal("5.50"), true),
                // 2.75 + 1.75 + 2.70 = 7.20，估算 VO2 < 14 mL/kg/min → 功能容量偏低
                Arguments.of(List.of(PreopScoreInput.DasiActivitiesEnum.SELF_CARE,
                                PreopScoreInput.DasiActivitiesEnum.WALK_INDOORS,
                                PreopScoreInput.DasiActivitiesEnum.LIGHT_HOUSEHOLD_WORK),
                        new BigDecimal("7.20"), true),
                // 12 项全选 = 58.2（量表上限），估算 VO2 = 0.43×58.2+9.6 = 34.626 ≥ 14
                Arguments.of(List.of(PreopScoreInput.DasiActivitiesEnum.values()),
                        new BigDecimal("58.20"), false));
    }

    @ParameterizedTest(name = "DASI {1} 分 → 功能容量偏低 {2}")
    @MethodSource("dasiCases")
    void dasiWeightedSumAndFunctionalCapacity(
            List<PreopScoreInput.DasiActivitiesEnum> activities,
            BigDecimal expectedScore, boolean expectedLow) {
        PreopScores scores = calculator.calculate(input(
                rcri(false, false, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false),
                activities));

        assertThat(scores.getDasiScore()).isEqualByComparingTo(expectedScore);
        assertThat(scores.getFunctionalCapacityLow()).isEqualTo(expectedLow);
    }

    // ------------------------------------------------------------- 综合风险 --

    @Test
    void compositeRiskTakesHighestComponent() {
        // RCRI 0（低）、Apfel 2（中）、STOP-BANG 5（高） → 综合高危
        PreopScores scores = calculator.calculate(input(
                rcri(false, false, false, false, false, false),
                apfel(true, true, false, false),
                stopBang(true, true, true, true, true, false, false, false),
                List.of()));

        assertThat(scores.getRiskLevel().getValue()).isEqualTo("HIGH");
    }

    @Test
    void compositeRiskIsLowOnlyWhenAllComponentsLow() {
        PreopScores scores = calculator.calculate(input(
                rcri(false, false, false, false, false, false),
                apfel(true, false, false, false),
                stopBang(true, true, false, false, false, false, false, false),
                List.of()));

        assertThat(scores.getRiskLevel().getValue()).isEqualTo("LOW");
    }

    // ----------------------------------------------------------------- 缺项 --

    @Test
    void missingRcriItemRejectsInsteadOfCountingAsZero() {
        PreopRcriInput incomplete = rcri(true, false, false, false, false, false);
        incomplete.setHighRiskSurgery(null);

        assertThatThrownBy(() -> calculator.calculate(input(incomplete,
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false),
                List.of())))
                .isInstanceOf(PreopValidationException.class)
                .hasMessageContaining("评分项不能为空")
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("scoreInput.rcri.highRiskSurgery");
    }

    @Test
    void missingScoreInputRejects() {
        assertThatThrownBy(() -> calculator.calculate(null))
                .isInstanceOf(PreopValidationException.class)
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("scoreInput");
    }

    @Test
    void missingDasiActivitiesRejects() {
        PreopScoreInput input = input(rcri(false, false, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false),
                List.of());
        input.setDasiActivities(null);

        assertThatThrownBy(() -> calculator.calculate(input))
                .isInstanceOf(PreopValidationException.class)
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("scoreInput.dasiActivities");
    }

    @Test
    void missingApfelAndStopBangReject() {
        PreopScoreInput input = input(rcri(false, false, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false),
                List.of());
        input.setApfel(null);
        assertThatThrownBy(() -> calculator.calculate(input))
                .isInstanceOf(PreopValidationException.class)
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("scoreInput.apfel");

        PreopScoreInput second = input(rcri(false, false, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false),
                List.of());
        second.setStopBang(null);
        assertThatThrownBy(() -> calculator.calculate(second))
                .isInstanceOf(PreopValidationException.class)
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("scoreInput.stopBang");
    }

    // --------------------------------------------------------- 枚举校验 ----

    @Test
    void clinicalEnumsAcceptPublishedClasses() {
        calculator.validateClinicalEnums("III", "IV");
        calculator.validateClinicalEnums("I", "I");
    }

    @Test
    void clinicalEnumsRejectUnknownOrMissingValues() {
        assertThatThrownBy(() -> calculator.validateClinicalEnums("VII", "II"))
                .isInstanceOf(PreopValidationException.class)
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("asaClass");
        assertThatThrownBy(() -> calculator.validateClinicalEnums(null, "II"))
                .isInstanceOf(PreopValidationException.class);
        assertThatThrownBy(() -> calculator.validateClinicalEnums("II", "V"))
                .isInstanceOf(PreopValidationException.class)
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("airwayAssessment.mallampati");
    }

    // --------------------------------------------------- 可配置切点（S-1） --

    @Test
    void overriddenCutPointsChangeRiskClassificationAndVersion() {
        PreopScoreProperties properties = defaults();
        properties.setRcriModerateMin(2);
        properties.setRcriHighMin(3);
        properties.setCalculatorVersion("1.1.0-test");
        PreopScoreCalculator configured = new PreopScoreCalculator(CLOCK, properties);

        PreopScores one = configured.calculate(input(
                rcri(true, false, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false), List.of()));
        PreopScores two = configured.calculate(input(
                rcri(true, true, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false), List.of()));
        PreopScores three = configured.calculate(input(
                rcri(true, true, true, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false), List.of()));

        // 默认口径 1 分=中危，覆盖后 1 分=低危、2 分=中危、3 分=高危。
        assertThat(one.getRcriRisk().getValue()).isEqualTo("LOW");
        assertThat(two.getRcriRisk().getValue()).isEqualTo("MODERATE");
        assertThat(three.getRcriRisk().getValue()).isEqualTo("HIGH");
        assertThat(one.getCalculatorVersion()).isEqualTo("1.1.0-test");
    }

    @Test
    void overriddenFunctionalCapacityThresholdChangesFlag() {
        PreopScoreProperties properties = defaults();
        properties.setFourMetsVo2Threshold(new BigDecimal("40"));
        PreopScoreCalculator configured = new PreopScoreCalculator(CLOCK, properties);

        PreopScores all = configured.calculate(input(
                rcri(false, false, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false),
                List.of(PreopScoreInput.DasiActivitiesEnum.values())));

        // 12 项全选 VO2≈34.6 < 覆盖后的 40 → 功能容量偏低（默认口径为 false）。
        assertThat(all.getFunctionalCapacityLow()).isTrue();
    }

    @Test
    void bindsOverrideFromConfigurationPropertiesSource() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "anesop.m2.preop.rcri-moderate-min", "2",
                "anesop.m2.preop.rcri-high-min", "3",
                "anesop.m2.preop.calculator-version", "2.0.0"));
        PreopScoreProperties bound = new Binder(source)
                .bind("anesop.m2.preop", Bindable.of(PreopScoreProperties.class))
                .get();
        assertThat(bound.getRcriModerateMin()).isEqualTo(2);
        assertThat(bound.getRcriHighMin()).isEqualTo(3);
        assertThat(bound.getApfelModerateMin()).isEqualTo(2);
        assertThat(bound.getFourMetsVo2Threshold()).isEqualByComparingTo(new BigDecimal("14"));

        PreopScores scores = new PreopScoreCalculator(CLOCK, bound).calculate(input(
                rcri(true, false, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false), List.of()));
        assertThat(scores.getRcriRisk().getValue()).isEqualTo("LOW");
        assertThat(scores.getCalculatorVersion()).isEqualTo("2.0.0");
    }

    @Test
    void rejectsInvalidCutPointConfiguration() {
        PreopScoreProperties inverted = defaults();
        inverted.setRcriModerateMin(4);
        inverted.setRcriHighMin(3);
        assertThatThrownBy(() -> new PreopScoreCalculator(CLOCK, inverted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rcri");

        PreopScoreProperties outOfRange = defaults();
        outOfRange.setStopbangHighMin(99);
        assertThatThrownBy(() -> new PreopScoreCalculator(CLOCK, outOfRange))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopbang");

        PreopScoreProperties unknownStrategy = defaults();
        unknownStrategy.setCompositeRiskStrategy("WEIGHTED");
        assertThatThrownBy(() -> new PreopScoreCalculator(CLOCK, unknownStrategy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("composite-risk-strategy");
    }

    @Test
    void duplicateDasiActivityIsRejectedInsteadOfSilentlyDeduped() {
        PreopScoreInput input = input(
                rcri(false, false, false, false, false, false),
                apfel(false, false, false, false),
                stopBang(false, false, false, false, false, false, false, false),
                List.of(PreopScoreInput.DasiActivitiesEnum.SELF_CARE,
                        PreopScoreInput.DasiActivitiesEnum.SELF_CARE));

        assertThatThrownBy(() -> calculator.calculate(input))
                .isInstanceOf(PreopValidationException.class)
                .hasMessageContaining("不能重复")
                .extracting(error -> ((PreopValidationException) error).field())
                .isEqualTo("scoreInput.dasiActivities");
    }

    // ------------------------------------------------------------- helpers --

    private static PreopScoreInput input(PreopRcriInput rcri, PreopApfelInput apfel,
            PreopStopBangInput stopBang, List<PreopScoreInput.DasiActivitiesEnum> dasi) {
        PreopScoreInput input = new PreopScoreInput();
        input.setRcri(rcri);
        input.setApfel(apfel);
        input.setStopBang(stopBang);
        input.setDasiActivities(dasi);
        return input;
    }

    private static PreopRcriInput rcri(boolean highRiskSurgery, boolean ischemic,
            boolean heartFailure, boolean cerebrovascular, boolean insulinDiabetes,
            boolean renalImpairment) {
        PreopRcriInput rcri = new PreopRcriInput();
        rcri.setHighRiskSurgery(highRiskSurgery);
        rcri.setIschemicHeartDisease(ischemic);
        rcri.setCongestiveHeartFailure(heartFailure);
        rcri.setCerebrovascularDisease(cerebrovascular);
        rcri.setInsulinDependentDiabetes(insulinDiabetes);
        rcri.setRenalImpairment(renalImpairment);
        return rcri;
    }

    private static PreopApfelInput apfel(boolean female, boolean ponvHistory, boolean nonSmoker,
            boolean opioid) {
        PreopApfelInput apfel = new PreopApfelInput();
        apfel.setFemale(female);
        apfel.setPonvOrMotionSicknessHistory(ponvHistory);
        apfel.setNonSmoker(nonSmoker);
        apfel.setPostoperativeOpioid(opioid);
        return apfel;
    }

    private static PreopStopBangInput stopBang(boolean snoring, boolean tiredness, boolean apnea,
            boolean hypertension, boolean bmi, boolean age, boolean neck, boolean male) {
        PreopStopBangInput stopBang = new PreopStopBangInput();
        stopBang.setSnoring(snoring);
        stopBang.setTiredness(tiredness);
        stopBang.setObservedApnea(apnea);
        stopBang.setHypertension(hypertension);
        stopBang.setBmiOver35(bmi);
        stopBang.setAgeOver50(age);
        stopBang.setNeckCircumferenceOver40(neck);
        stopBang.setMale(male);
        return stopBang;
    }
}
