package com.anesop.m4.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 麻精专册登记实体，对应 {@code m4_drug.narcotic_register}（V20）。
 *
 * <p>append-only：只 INSERT/查询；双人签字不同人由服务层 400 与数据库 CHECK 双重保证。
 */
@TableName("m4_drug.narcotic_register")
public class NarcoticRegisterEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    private UUID drugId;
    private String drugName;
    private String batchNo;
    private BigDecimal quantity;
    private String unit;
    private String prescriptionNo;
    private String usedBy;
    private UUID departmentId;
    private Instant usageAt;
    private String destination;
    private UUID firstSignerId;
    private String firstSignerName;
    private UUID secondSignerId;
    private String secondSignerName;
    private Instant createdAt;
    private UUID createdBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDrugId() {
        return drugId;
    }

    public void setDrugId(UUID drugId) {
        this.drugId = drugId;
    }

    public String getDrugName() {
        return drugName;
    }

    public void setDrugName(String drugName) {
        this.drugName = drugName;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getPrescriptionNo() {
        return prescriptionNo;
    }

    public void setPrescriptionNo(String prescriptionNo) {
        this.prescriptionNo = prescriptionNo;
    }

    public String getUsedBy() {
        return usedBy;
    }

    public void setUsedBy(String usedBy) {
        this.usedBy = usedBy;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
    }

    public Instant getUsageAt() {
        return usageAt;
    }

    public void setUsageAt(Instant usageAt) {
        this.usageAt = usageAt;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public UUID getFirstSignerId() {
        return firstSignerId;
    }

    public void setFirstSignerId(UUID firstSignerId) {
        this.firstSignerId = firstSignerId;
    }

    public String getFirstSignerName() {
        return firstSignerName;
    }

    public void setFirstSignerName(String firstSignerName) {
        this.firstSignerName = firstSignerName;
    }

    public UUID getSecondSignerId() {
        return secondSignerId;
    }

    public void setSecondSignerId(UUID secondSignerId) {
        this.secondSignerId = secondSignerId;
    }

    public String getSecondSignerName() {
        return secondSignerName;
    }

    public void setSecondSignerName(String secondSignerName) {
        this.secondSignerName = secondSignerName;
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
