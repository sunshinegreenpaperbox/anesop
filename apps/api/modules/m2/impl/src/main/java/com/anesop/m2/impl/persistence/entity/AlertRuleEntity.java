package com.anesop.m2.impl.persistence.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 预警规则版本行实体，对应 {@code m2_anes.alert_rule}（V15）。
 *
 * <p>一个 code 有多个 version，仅一个 enabled；保存规则 = 生成新版本并停用旧版本。
 * 阈值列为 {@link BigDecimal}（numeric(8,2)），与体征数值比较时统一用 BigDecimal 避免
 * 浮点误差；配置列不可原地修改（数据库触发器兜底）。
 */
public class AlertRuleEntity {

    private UUID id;
    private String code;
    private String name;
    private String metric;
    private String operator;
    private BigDecimal thresholdLow;
    private BigDecimal thresholdHigh;
    private Integer durationSeconds;
    private String severity;
    private Integer version;
    private Boolean enabled;
    /** 阈值/持续时长变更后的临床待确认标记（V16）；种子默认【待确认】。 */
    private Boolean pendingConfirmation;
    private Instant createdAt;
    private UUID createdBy;
    private Instant updatedAt;
    private UUID updatedBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public BigDecimal getThresholdLow() {
        return thresholdLow;
    }

    public void setThresholdLow(BigDecimal thresholdLow) {
        this.thresholdLow = thresholdLow;
    }

    public BigDecimal getThresholdHigh() {
        return thresholdHigh;
    }

    public void setThresholdHigh(BigDecimal thresholdHigh) {
        this.thresholdHigh = thresholdHigh;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Boolean getPendingConfirmation() {
        return pendingConfirmation;
    }

    public void setPendingConfirmation(Boolean pendingConfirmation) {
        this.pendingConfirmation = pendingConfirmation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }
}
