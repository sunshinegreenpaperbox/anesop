package com.anesop.m2.impl.preop;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 术前评分口径配置（{@code anesop.m2.preop.*}，M2-M4-01）。
 *
 * <p>把原先散落在 {@link PreopScoreCalculator} 中的三级切点、DASI 功能容量切点、
 * 综合风险合成策略与量表版本集中为可配置属性，默认值等于 1.0.0 版实现口径
 * （RCRI 0/1/≥2、Apfel 0–1/2/≥3、STOP-BANG 0–2/3–4/≥5、VO2peak &lt; 14 ≈ &lt;4 METs）。
 * 院方口径确认后只改配置并同步升级 {@code calculator-version}，不改代码分支。
 *
 * <p>属性在启动时由 {@link PreopScoreCalculator} 统一校验：区间越界、moderate ≥ high
 * 或未知合成策略一律拒绝启动（fail-closed），避免错误阈值静默产出临床评分。
 * 属性命名与 {@code application.yml} 一致，全部为标量，无 PHI。
 */
@Component
@ConfigurationProperties(prefix = "anesop.m2.preop")
public class PreopScoreProperties {

    /** 量表实现版本；写入 scores.calculatorVersion 供复算追溯，口径调整必须升级。 */
    private String calculatorVersion = "1.0.0";

    /** 综合风险合成策略；当前仅支持 {@code MAX_COMPONENT}（取最高单项风险，展示约定）。 */
    private String compositeRiskStrategy = "MAX_COMPONENT";

    /** RCRI 达到中危的最小分（默认 1，即 0=低危）。 */
    private int rcriModerateMin = 1;

    /** RCRI 达到高危的最小分（默认 2）。 */
    private int rcriHighMin = 2;

    /** Apfel 达到中危的最小分（默认 2，即 0–1=低危）。 */
    private int apfelModerateMin = 2;

    /** Apfel 达到高危的最小分（默认 3）。 */
    private int apfelHighMin = 3;

    /** STOP-BANG 达到中危的最小分（默认 3，即 0–2=低危）。 */
    private int stopbangModerateMin = 3;

    /** STOP-BANG 达到高危的最小分（默认 5）。 */
    private int stopbangHighMin = 5;

    /** DASI → VO2peak 回归斜率（Hlatky 1989，默认 0.43）。 */
    private BigDecimal vo2Slope = new BigDecimal("0.43");

    /** DASI → VO2peak 回归截距（默认 9.6 mL/kg/min）。 */
    private BigDecimal vo2Intercept = new BigDecimal("9.6");

    /** 功能容量偏低阈值：VO2peak 低于该值（默认 14 mL/kg/min ≈ 4 METs）判为偏低。 */
    private BigDecimal fourMetsVo2Threshold = new BigDecimal("14");

    public String getCalculatorVersion() {
        return calculatorVersion;
    }

    public void setCalculatorVersion(String calculatorVersion) {
        this.calculatorVersion = calculatorVersion;
    }

    public String getCompositeRiskStrategy() {
        return compositeRiskStrategy;
    }

    public void setCompositeRiskStrategy(String compositeRiskStrategy) {
        this.compositeRiskStrategy = compositeRiskStrategy;
    }

    public int getRcriModerateMin() {
        return rcriModerateMin;
    }

    public void setRcriModerateMin(int rcriModerateMin) {
        this.rcriModerateMin = rcriModerateMin;
    }

    public int getRcriHighMin() {
        return rcriHighMin;
    }

    public void setRcriHighMin(int rcriHighMin) {
        this.rcriHighMin = rcriHighMin;
    }

    public int getApfelModerateMin() {
        return apfelModerateMin;
    }

    public void setApfelModerateMin(int apfelModerateMin) {
        this.apfelModerateMin = apfelModerateMin;
    }

    public int getApfelHighMin() {
        return apfelHighMin;
    }

    public void setApfelHighMin(int apfelHighMin) {
        this.apfelHighMin = apfelHighMin;
    }

    public int getStopbangModerateMin() {
        return stopbangModerateMin;
    }

    public void setStopbangModerateMin(int stopbangModerateMin) {
        this.stopbangModerateMin = stopbangModerateMin;
    }

    public int getStopbangHighMin() {
        return stopbangHighMin;
    }

    public void setStopbangHighMin(int stopbangHighMin) {
        this.stopbangHighMin = stopbangHighMin;
    }

    public BigDecimal getVo2Slope() {
        return vo2Slope;
    }

    public void setVo2Slope(BigDecimal vo2Slope) {
        this.vo2Slope = vo2Slope;
    }

    public BigDecimal getVo2Intercept() {
        return vo2Intercept;
    }

    public void setVo2Intercept(BigDecimal vo2Intercept) {
        this.vo2Intercept = vo2Intercept;
    }

    public BigDecimal getFourMetsVo2Threshold() {
        return fourMetsVo2Threshold;
    }

    public void setFourMetsVo2Threshold(BigDecimal fourMetsVo2Threshold) {
        this.fourMetsVo2Threshold = fourMetsVo2Threshold;
    }
}
