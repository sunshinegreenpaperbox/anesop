package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 用药记录实体，对应 {@code m2_anes.drug_administration}（V13）。
 *
 * <p>append-only；麻精类（narcotic=true）必须双人核对，核对人不得与给药人相同
 * （数据库 CHECK 为第二道防线，服务层先返回 400）。
 */
@TableName("m2_anes.drug_administration")
public class DrugAdministrationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID recordId;
    private String drugName;
    private BigDecimal dose;
    private String unit;
    private String route;
    private Instant administeredAt;
    private Boolean narcotic;
    private UUID secondCheckerId;
    private String secondCheckerName;
    private String note;
    private Instant createdAt;
    private UUID createdBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRecordId() {
        return recordId;
    }

    public void setRecordId(UUID recordId) {
        this.recordId = recordId;
    }

    public String getDrugName() {
        return drugName;
    }

    public void setDrugName(String drugName) {
        this.drugName = drugName;
    }

    public BigDecimal getDose() {
        return dose;
    }

    public void setDose(BigDecimal dose) {
        this.dose = dose;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public Instant getAdministeredAt() {
        return administeredAt;
    }

    public void setAdministeredAt(Instant administeredAt) {
        this.administeredAt = administeredAt;
    }

    public Boolean getNarcotic() {
        return narcotic;
    }

    public void setNarcotic(Boolean narcotic) {
        this.narcotic = narcotic;
    }

    public UUID getSecondCheckerId() {
        return secondCheckerId;
    }

    public void setSecondCheckerId(UUID secondCheckerId) {
        this.secondCheckerId = secondCheckerId;
    }

    public String getSecondCheckerName() {
        return secondCheckerName;
    }

    public void setSecondCheckerName(String secondCheckerName) {
        this.secondCheckerName = secondCheckerName;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
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
