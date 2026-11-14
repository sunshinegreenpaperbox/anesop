package com.anesop.m4.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 指标结果快照实体（周期/科室/版本唯一）。
 *
 * <p>对应 {@code m4_qc.qc_indicator_result}（V25）；实体只做字段载体，业务规则在服务层与数据库触发器中实现。
 */
@TableName("m4_qc.qc_indicator_result")
public class QcIndicatorResultEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 指标标识 */
    private UUID indicatorId;

    /** 指标编码快照 */
    private String indicatorCode;

    /** 口径版本 */
    private String indicatorVersion;

    /** 周期起始日 */
    private LocalDate periodFrom;

    /** 周期结束日 */
    private LocalDate periodTo;

    /** 科室标识（全 0=全院） */
    private UUID deptId;

    /** 分子 */
    private Integer numerator;

    /** 分母 */
    private Integer denominator;

    /** 指标值 */
    private BigDecimal value;

    /** 是否达标 */
    private Boolean targetMet;

    /** 计算时间 */
    private Instant computedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getIndicatorId() {
        return indicatorId;
    }

    public void setIndicatorId(UUID indicatorId) {
        this.indicatorId = indicatorId;
    }

    public String getIndicatorCode() {
        return indicatorCode;
    }

    public void setIndicatorCode(String indicatorCode) {
        this.indicatorCode = indicatorCode;
    }

    public String getIndicatorVersion() {
        return indicatorVersion;
    }

    public void setIndicatorVersion(String indicatorVersion) {
        this.indicatorVersion = indicatorVersion;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public void setPeriodFrom(LocalDate periodFrom) {
        this.periodFrom = periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public void setPeriodTo(LocalDate periodTo) {
        this.periodTo = periodTo;
    }

    public UUID getDeptId() {
        return deptId;
    }

    public void setDeptId(UUID deptId) {
        this.deptId = deptId;
    }

    public Integer getNumerator() {
        return numerator;
    }

    public void setNumerator(Integer numerator) {
        this.numerator = numerator;
    }

    public Integer getDenominator() {
        return denominator;
    }

    public void setDenominator(Integer denominator) {
        this.denominator = denominator;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public Boolean getTargetMet() {
        return targetMet;
    }

    public void setTargetMet(Boolean targetMet) {
        this.targetMet = targetMet;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(Instant computedAt) {
        this.computedAt = computedAt;
    }
}
