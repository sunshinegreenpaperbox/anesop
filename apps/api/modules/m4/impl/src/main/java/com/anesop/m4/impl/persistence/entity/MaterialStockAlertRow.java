package com.anesop.m4.impl.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 库存预警聚合行（V28，{@code material_stock} + {@code material} 只读聚合，非表实体）。
 *
 * <p>粒度：材料 × 科室。{@code availableQuantity} 为该科室批次数量合计；
 * {@code nearestExpiryDate}/{@code nearExpiryBatchCount} 只统计数量 &gt; 0 的批次；
 * {@code lowStockThreshold} 为空表示未配置阈值（不产生 LOW_STOCK 预警）。
 *
 * <p>只承载聚合数量与标识，不含患者信息、供应商与批次明细原文；预警类型与建议补货量
 * 由服务层按契约 material.yaml 0.2.0 计算。
 */
public class MaterialStockAlertRow {

    private UUID materialId;
    private String materialCode;
    private String materialName;
    private String unit;
    private UUID departmentId;
    private BigDecimal lowStockThreshold;
    private BigDecimal availableQuantity;
    private Long batchCount;
    private LocalDate nearestExpiryDate;
    private Long nearExpiryBatchCount;

    public UUID getMaterialId() {
        return materialId;
    }

    public void setMaterialId(UUID materialId) {
        this.materialId = materialId;
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public void setMaterialCode(String materialCode) {
        this.materialCode = materialCode;
    }

    public String getMaterialName() {
        return materialName;
    }

    public void setMaterialName(String materialName) {
        this.materialName = materialName;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
    }

    public BigDecimal getLowStockThreshold() {
        return lowStockThreshold;
    }

    public void setLowStockThreshold(BigDecimal lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
    }

    public BigDecimal getAvailableQuantity() {
        return availableQuantity;
    }

    public void setAvailableQuantity(BigDecimal availableQuantity) {
        this.availableQuantity = availableQuantity;
    }

    public Long getBatchCount() {
        return batchCount;
    }

    public void setBatchCount(Long batchCount) {
        this.batchCount = batchCount;
    }

    public LocalDate getNearestExpiryDate() {
        return nearestExpiryDate;
    }

    public void setNearestExpiryDate(LocalDate nearestExpiryDate) {
        this.nearestExpiryDate = nearestExpiryDate;
    }

    public Long getNearExpiryBatchCount() {
        return nearExpiryBatchCount;
    }

    public void setNearExpiryBatchCount(Long nearExpiryBatchCount) {
        this.nearExpiryBatchCount = nearExpiryBatchCount;
    }
}
