package com.anesop.m2.impl.preop;

import com.anesop.contracts.anesthesia.model.PreopApfelInput;
import com.anesop.contracts.anesthesia.model.PreopRcriInput;
import com.anesop.contracts.anesthesia.model.PreopScoreInput;
import com.anesop.contracts.anesthesia.model.PreopScores;
import com.anesop.contracts.anesthesia.model.PreopStopBangInput;
import com.anesop.m2.api.preop.PreopValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Component;

/**
 * 术前评分计算器（M2-M4-01，高风险：临床规则，需人工逐行）。
 *
 * <p>只接受量表条目（勾选/活动项），输出分数与风险分级；客户端提交的分数一律不参与。
 * 每个量表的口径、公式与切点见各方法 Javadoc。未获院方确认的阈值标【待确认】，
 * 不得据此替代临床判断（AGENTS H-06）。
 *
 * <p>量表来源（公开文献）：
 * <ul>
 *   <li>RCRI：Lee TH, Marcantonio ER, Mangione CM, et al. Derivation and prospective
 *       validation of a simple index for prediction of cardiac risk of major noncardiac
 *       surgery. Circulation. 1999;100(10):1043-1049.</li>
 *   <li>Apfel：Apfel CC, Läärä E, Koivuranta M, Greim CA, Roewer N. A simplified risk score
 *       for predicting postoperative nausea and vomiting. Anesthesiology. 1999;91(3):693-700.</li>
 *   <li>STOP-BANG：Chung F, Abdullah HR, Liao P. STOP-Bang Questionnaire: A Practical Approach
 *       to Screen for Obstructive Sleep Apnea. Chest. 2016;149(3):631-638（原始量表
 *       Chung F, et al. Anesthesiology. 2008;108(5):812-821）。</li>
 *   <li>DASI：Hlatky MA, Boineau RE, Higginbotham MB, et al. A brief self-administered
 *       questionnaire to determine functional capacity (the Duke Activity Status Index).
 *       Am J Cardiol. 1989;64(10):651-654.</li>
 *   <li>Mallampati：Samsoon GL, Young JR. Difficult tracheal intubation: a retrospective study.
 *       Anaesthesia. 1987;42(5):487-490（改良 Mallampati I–IV）。</li>
 *   <li>ASA：American Society of Anesthesiologists. ASA Physical Status Classification System
 *       (2020).</li>
 * </ul>
 */
@Component
public class PreopScoreCalculator {

    /** ASA 分级取值（I–VI）。 */
    private static final Set<String> ASA_CLASSES = Set.of("I", "II", "III", "IV", "V", "VI");

    /** 改良 Mallampati 分级取值（I–IV）。 */
    private static final Set<String> MALLAMPATI_CLASSES = Set.of("I", "II", "III", "IV");

    /**
     * DASI 活动项权重（Hlatky 1989 表 2）：12 项合计 58.2。
     * 权重以 0.05 为最小单位，使用 BigDecimal 避免浮点累加误差。
     */
    private static final Map<PreopScoreInput.DasiActivitiesEnum, BigDecimal> DASI_WEIGHTS =
            new EnumMap<>(PreopScoreInput.DasiActivitiesEnum.class);

    static {
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.SELF_CARE, new BigDecimal("2.75"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.WALK_INDOORS, new BigDecimal("1.75"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.WALK_BLOCK, new BigDecimal("2.75"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.CLIMB_STAIRS, new BigDecimal("5.50"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.RUN_SHORT_DISTANCE, new BigDecimal("8.00"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.LIGHT_HOUSEHOLD_WORK, new BigDecimal("2.70"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.MODERATE_HOUSEHOLD_WORK, new BigDecimal("3.50"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.HEAVY_HOUSEHOLD_WORK, new BigDecimal("8.00"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.YARD_WORK, new BigDecimal("4.50"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.SEXUAL_RELATIONS, new BigDecimal("5.25"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.MODERATE_RECREATION, new BigDecimal("6.00"));
        DASI_WEIGHTS.put(PreopScoreInput.DasiActivitiesEnum.STRENUOUS_SPORTS, new BigDecimal("7.50"));
    }

    private static final String FIELD_RCRI = "scoreInput.rcri.";
    private static final String FIELD_APFEL = "scoreInput.apfel.";
    private static final String FIELD_STOPBANG = "scoreInput.stopBang.";

    private final Clock clock;
    private final PreopScoreProperties properties;

    /**
     * 构造时校验口径配置（fail-closed）：切点越界、moderate ≥ high、未知合成策略、
     * 非正回归系数一律拒绝启动，避免错误阈值静默产出临床评分。
     */
    @SuppressFBWarnings(value = "CT_CONSTRUCTOR_THROW",
            justification = "配置非法时必须在装配阶段快速失败；本类无 finalize/finalizer 子类化路径")
    public PreopScoreCalculator(Clock clock, PreopScoreProperties properties) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.properties = Objects.requireNonNull(properties, "properties");
        validateProperties();
    }

    /** 当前生效的量表实现版本（写入 scores.calculatorVersion）。 */
    public String calculatorVersion() {
        return properties.getCalculatorVersion();
    }

    /**
     * 计算 RCRI/Apfel/STOP-BANG/DASI 分数、各量表风险分级与综合风险等级。
     *
     * <p>任一条目为 null 抛 {@link PreopValidationException}（400），不做“缺项按 0 分”的
     * 静默兜底；这样缺项会阻断最终化而不是悄悄降低风险。
     *
     * @param input 评分输入（量表条目）
     * @return 服务端计算结果（含计算时间与量表版本）
     */
    public PreopScores calculate(PreopScoreInput input) {
        if (input == null) {
            throw new PreopValidationException("scoreInput", "评分输入不能为空");
        }
        int rcri = rcriScore(input.getRcri());
        int apfel = apfelScore(input.getApfel());
        int stopBang = stopBangScore(input.getStopBang());
        BigDecimal dasi = dasiScore(input.getDasiActivities());

        String rcriRisk = rcriRisk(rcri);
        String apfelRisk = apfelRisk(apfel);
        String stopBangRisk = stopBangRisk(stopBang);
        String riskLevel = maxRisk(rcriRisk, apfelRisk, stopBangRisk);

        PreopScores scores = new PreopScores();
        scores.setRcriScore(rcri);
        scores.setRcriRisk(PreopScores.RcriRiskEnum.fromValue(rcriRisk));
        scores.setApfelScore(apfel);
        scores.setApfelRisk(PreopScores.ApfelRiskEnum.fromValue(apfelRisk));
        scores.setStopBangScore(stopBang);
        scores.setStopBangRisk(PreopScores.StopBangRiskEnum.fromValue(stopBangRisk));
        scores.setDasiScore(dasi);
        scores.setFunctionalCapacityLow(functionalCapacityLow(dasi));
        scores.setRiskLevel(PreopScores.RiskLevelEnum.fromValue(riskLevel));
        scores.setCalculatedAt(OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS));
        scores.setCalculatorVersion(properties.getCalculatorVersion());
        return scores;
    }

    /**
     * 校验 ASA 分级与 Mallampati 分级（大小写敏感，只接受 I–VI / I–IV）。
     *
     * <p>契约枚举已由 Jackson 校验；此方法用于服务层防御与单元测试，异常字段为契约字段名。
     */
    public void validateClinicalEnums(String asaClass, String mallampati) {
        if (asaClass == null || !ASA_CLASSES.contains(asaClass)) {
            throw new PreopValidationException("asaClass", "ASA 分级必须为 I–VI");
        }
        if (mallampati == null || !MALLAMPATI_CLASSES.contains(mallampati)) {
            throw new PreopValidationException("airwayAssessment.mallampati",
                    "Mallampati 分级必须为 I–IV");
        }
    }

    /**
     * RCRI（Lee 1999）：6 个预测因子各 1 分，0–6 分。
     * 切点：0=低危、1=中危、≥2=高危（公开研究 MACE 约 3.9%/6.0%/≥10%；院方口径【待确认】）。
     */
    private int rcriScore(PreopRcriInput rcri) {
        if (rcri == null) {
            throw new PreopValidationException("scoreInput.rcri", "RCRI 评分项不能为空");
        }
        return countTrue(
                require(rcri.getHighRiskSurgery(), FIELD_RCRI + "highRiskSurgery"),
                require(rcri.getIschemicHeartDisease(), FIELD_RCRI + "ischemicHeartDisease"),
                require(rcri.getCongestiveHeartFailure(), FIELD_RCRI + "congestiveHeartFailure"),
                require(rcri.getCerebrovascularDisease(), FIELD_RCRI + "cerebrovascularDisease"),
                require(rcri.getInsulinDependentDiabetes(), FIELD_RCRI + "insulinDependentDiabetes"),
                require(rcri.getRenalImpairment(), FIELD_RCRI + "renalImpairment"));
    }

    /**
     * Apfel（1999）：女性、PONV/晕动症史、不吸烟、术后阿片 4 项各 1 分，0–4 分。
     * 切点：0–1=低危、2=中危、≥3=高危（对应研究 10%/21%/39%/61%/79%；院方口径【待确认】）。
     */
    private int apfelScore(PreopApfelInput apfel) {
        if (apfel == null) {
            throw new PreopValidationException("scoreInput.apfel", "Apfel 评分项不能为空");
        }
        return countTrue(
                require(apfel.getFemale(), FIELD_APFEL + "female"),
                require(apfel.getPonvOrMotionSicknessHistory(),
                        FIELD_APFEL + "ponvOrMotionSicknessHistory"),
                require(apfel.getNonSmoker(), FIELD_APFEL + "nonSmoker"),
                require(apfel.getPostoperativeOpioid(), FIELD_APFEL + "postoperativeOpioid"));
    }

    /**
     * STOP-BANG（Chung 2008）：8 个条目各 1 分，0–8 分。
     * 切点：0–2=低危、3–4=中危、5–8=高危（公开量表口径；院方口径【待确认】）。
     */
    private int stopBangScore(PreopStopBangInput stopBang) {
        if (stopBang == null) {
            throw new PreopValidationException("scoreInput.stopBang", "STOP-BANG 评分项不能为空");
        }
        return countTrue(
                require(stopBang.getSnoring(), FIELD_STOPBANG + "snoring"),
                require(stopBang.getTiredness(), FIELD_STOPBANG + "tiredness"),
                require(stopBang.getObservedApnea(), FIELD_STOPBANG + "observedApnea"),
                require(stopBang.getHypertension(), FIELD_STOPBANG + "hypertension"),
                require(stopBang.getBmiOver35(), FIELD_STOPBANG + "bmiOver35"),
                require(stopBang.getAgeOver50(), FIELD_STOPBANG + "ageOver50"),
                require(stopBang.getNeckCircumferenceOver40(),
                        FIELD_STOPBANG + "neckCircumferenceOver40"),
                require(stopBang.getMale(), FIELD_STOPBANG + "male"));
    }

    /**
     * DASI（Hlatky 1989）：对勾选的活动项按权重求和，0–58.2 分；结果保留 2 位小数
     *（权重最小单位 0.05，保留 2 位避免 18.95 这类合计被四舍五入丢失精度）。
     *
     * <p>权重表见 {@link #DASI_WEIGHTS}：自理 2.75、室内行走 1.75、平地 1–2 街区 2.75、
     * 上一层楼/爬坡 5.50、短距离跑 8.00、轻家务 2.70、中等家务 3.50、重家务 8.00、
     * 庭院劳动 4.50、性生活 5.25、中等娱乐 6.00、剧烈运动 7.50，合计 58.2。
     */
    private BigDecimal dasiScore(List<PreopScoreInput.DasiActivitiesEnum> activities) {
        if (activities == null) {
            throw new PreopValidationException("scoreInput.dasiActivities", "DASI 活动项不能为空");
        }
        Set<PreopScoreInput.DasiActivitiesEnum> seen =
                EnumSet.noneOf(PreopScoreInput.DasiActivitiesEnum.class);
        BigDecimal total = BigDecimal.ZERO;
        for (PreopScoreInput.DasiActivitiesEnum activity : activities) {
            BigDecimal weight = activity == null ? null : DASI_WEIGHTS.get(activity);
            if (weight == null) {
                throw new PreopValidationException("scoreInput.dasiActivities",
                        "DASI 活动项不在量表范围内");
            }
            if (!seen.add(activity)) {
                // 契约数组不再按 uniqueItems 静默去重：重复勾选是数据错误，显式 400。
                throw new PreopValidationException("scoreInput.dasiActivities",
                        "DASI 活动项不能重复");
            }
            total = total.add(weight);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 功能容量偏低：按 Hlatky 回归式估算 VO2peak &lt; 14 mL/kg/min（≈4 METs）。
     * 该估算与 4 METs 切点用于术前风险评估的适用性【待确认】，不参与综合风险合成。
     */
    private boolean functionalCapacityLow(BigDecimal dasi) {
        BigDecimal estimatedVo2 = dasi.multiply(properties.getVo2Slope())
                .add(properties.getVo2Intercept());
        return estimatedVo2.compareTo(properties.getFourMetsVo2Threshold()) < 0;
    }

    private String rcriRisk(int score) {
        return riskOf(score, properties.getRcriModerateMin(), properties.getRcriHighMin());
    }

    private String apfelRisk(int score) {
        return riskOf(score, properties.getApfelModerateMin(), properties.getApfelHighMin());
    }

    private String stopBangRisk(int score) {
        return riskOf(score, properties.getStopbangModerateMin(),
                properties.getStopbangHighMin());
    }

    /**
     * 三级切点：{@code score < moderateMin}=LOW、{@code score < highMin}=MODERATE，否则 HIGH。
     * 切点来自 {@link PreopScoreProperties}，默认值等同公开量表口径。
     */
    private static String riskOf(int score, int moderateMin, int highMin) {
        if (score < moderateMin) {
            return "LOW";
        }
        return score < highMin ? "MODERATE" : "HIGH";
    }

    /**
     * 综合风险等级 = 三个量表分级的最高级（保守取高）。这是展示约定而非验证过的复合模型，
     * 【待确认】；不得替代医师综合判断（AGENTS H-06）。合成策略由配置
     * {@code anesop.m2.preop.composite-risk-strategy} 控制，当前仅支持 {@code MAX_COMPONENT}。
     */
    private static String maxRisk(String... risks) {
        String result = "LOW";
        for (String risk : risks) {
            if ("HIGH".equals(risk)) {
                return "HIGH";
            }
            if ("MODERATE".equals(risk)) {
                result = "MODERATE";
            }
        }
        return result;
    }

    private void validateProperties() {
        String version = properties.getCalculatorVersion();
        if (version == null || version.isBlank() || version.length() > 16) {
            throw new IllegalStateException("术前评分配置非法：calculator-version 必须为 1–16 字符");
        }
        if (!"MAX_COMPONENT".equals(properties.getCompositeRiskStrategy())) {
            throw new IllegalStateException("术前评分配置非法：composite-risk-strategy 仅支持 "
                    + "MAX_COMPONENT");
        }
        requireCutPoints("rcri", properties.getRcriModerateMin(), properties.getRcriHighMin(),
                0, 6);
        requireCutPoints("apfel", properties.getApfelModerateMin(),
                properties.getApfelHighMin(), 0, 4);
        requireCutPoints("stopbang", properties.getStopbangModerateMin(),
                properties.getStopbangHighMin(), 0, 8);
        requirePositive("vo2-slope", properties.getVo2Slope());
        requirePositive("four-mets-vo2-threshold", properties.getFourMetsVo2Threshold());
        if (properties.getVo2Intercept() == null
                || properties.getVo2Intercept().signum() < 0) {
            throw new IllegalStateException("术前评分配置非法：vo2-intercept 必须 ≥ 0");
        }
    }

    private static void requireCutPoints(String scale, int moderateMin, int highMin,
            int minScore, int maxScore) {
        if (moderateMin <= minScore || highMin <= moderateMin || highMin > maxScore) {
            throw new IllegalStateException("术前评分配置非法：" + scale
                    + " 切点必须满足 " + minScore + " < moderate-min < high-min ≤ " + maxScore);
        }
    }

    private static void requirePositive(String name, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalStateException("术前评分配置非法：" + name + " 必须 > 0");
        }
    }

    private static boolean require(Boolean value, String field) {
        if (value == null) {
            throw new PreopValidationException(field, "评分项不能为空（缺项不得按 0 分处理）");
        }
        return value;
    }

    private static int countTrue(boolean... values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }
}
