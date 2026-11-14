package com.anesop.m4.impl.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作量报表口径配置（M4-M16-01，口径 report-1.0.0）。
 *
 * <p>方案书 §2.7.2 要求工作量月报含“难度系数”，但**绩效权重与难度系数由院方另行制定**
 * （D-21：系统出数、绩效规则院方另定）。为避免臆造绩效口径（AGENTS H-04），系统默认
 * 所有台次权重为 **1.0**，并提供按术式编码的**可选**覆盖：
 * <pre>
 * anesop:
 *   m4:
 *     report:
 *       default-weight: 1.0
 *       surgery-weights:
 *         SYNTH-ART-001: 1.5
 * </pre>
 *
 * <p>权重只影响 {@code weightedCaseCount}（加权台次），不改变台次与麻醉时长；
 * 未配置的术式取 {@code default-weight}；权重必须 &gt; 0（启动时校验，fail-closed）。
 */
@ConfigurationProperties(prefix = "anesop.m4.report")
public class ReportProperties {

    /** 默认台次权重（未配置术式权重时使用），必须 > 0。 */
    private BigDecimal defaultWeight = BigDecimal.ONE;

    /** 术式编码 → 权重覆盖；默认空（不内置任何绩效系数，【待确认】）。 */
    private Map<String, BigDecimal> surgeryWeights = new LinkedHashMap<>();

    public BigDecimal getDefaultWeight() {
        return defaultWeight;
    }

    public void setDefaultWeight(BigDecimal defaultWeight) {
        this.defaultWeight = defaultWeight;
    }

    @SuppressFBWarnings(value = "EI_EXPOSE_REP",
            justification = "配置对象由 Spring 绑定、启动后只读；返回内部 Map 以避免每次读取复制")
    public Map<String, BigDecimal> getSurgeryWeights() {
        return surgeryWeights;
    }

    public void setSurgeryWeights(Map<String, BigDecimal> surgeryWeights) {
        this.surgeryWeights = surgeryWeights == null ? new LinkedHashMap<>() : surgeryWeights;
    }
}
