package com.anesop.m4.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 质控指标定义实体（版本化口径/阈值/数据来源）。
 *
 * <p>对应 {@code m4_qc.qc_indicator}（V25）；实体只做字段载体，业务规则在服务层与数据库触发器中实现。
 */
@TableName("m4_qc.qc_indicator")
public class QcIndicatorEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 指标编码 */
    private String code;

    /** 指标名称 */
    private String name;

    /** 类别 STRUCTURE/PROCESS/OUTCOME */
    private String category;

    /** 口径说明 */
    private String definition;

    /** 数据来源声明 */
    private String dataSource;

    /** 分子含义 */
    private String numeratorLabel;

    /** 分母含义 */
    private String denominatorLabel;

    /** 单位 PERCENT/PER_1000_CASES */
    private String unit;

    /** 达标方向 GTE/LTE */
    private String thresholdOperator;

    /** 阈值 */
    private BigDecimal thresholdValue;

    /** 口径版本 */
    private String version;

    /** 生效起始日 */
    private LocalDate effectiveFrom;

    /** 生效结束日（可空） */
    private LocalDate effectiveTo;

    /** 状态 ACTIVE/RETIRED */
    private String status;

    /** 是否合成参考指标 */
    private Boolean provisional;

    /** 是否计入达标统计（false 时 targetMet 固定 false） */
    private Boolean inTargetStatistics;

    /** 创建时间 */
    private Instant createdAt;

    /** 创建人 */
    private UUID createdBy;

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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDefinition() {
        return definition;
    }

    public void setDefinition(String definition) {
        this.definition = definition;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public String getNumeratorLabel() {
        return numeratorLabel;
    }

    public void setNumeratorLabel(String numeratorLabel) {
        this.numeratorLabel = numeratorLabel;
    }

    public String getDenominatorLabel() {
        return denominatorLabel;
    }

    public void setDenominatorLabel(String denominatorLabel) {
        this.denominatorLabel = denominatorLabel;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getThresholdOperator() {
        return thresholdOperator;
    }

    public void setThresholdOperator(String thresholdOperator) {
        this.thresholdOperator = thresholdOperator;
    }

    public BigDecimal getThresholdValue() {
        return thresholdValue;
    }

    public void setThresholdValue(BigDecimal thresholdValue) {
        this.thresholdValue = thresholdValue;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getProvisional() {
        return provisional;
    }

    public void setProvisional(Boolean provisional) {
        this.provisional = provisional;
    }

    public Boolean getInTargetStatistics() {
        return inTargetStatistics;
    }

    public void setInTargetStatistics(Boolean inTargetStatistics) {
        this.inTargetStatistics = inTargetStatistics;
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
}
