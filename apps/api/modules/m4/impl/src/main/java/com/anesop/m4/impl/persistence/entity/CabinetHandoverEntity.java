package com.anesop.m4.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 专柜交接实体，对应 {@code m4_drug.cabinet_handover}（V20）。
 *
 * <p>append-only；{@code items} 为品种数量明细 jsonb 数组的文本形式（服务层用 Jackson 序列化），
 * 双人签字不同人、交班 ≠ 接班由服务层 400 与数据库 CHECK 双重保证。
 */
@TableName("m4_drug.cabinet_handover")
public class CabinetHandoverEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    private String cabinetCode;
    private UUID departmentId;
    private String shift;
    private Instant handedOverAt;
    private UUID handoverFromId;
    private String handoverFromName;
    private UUID handoverToId;
    private String handoverToName;
    private String items;
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

    public String getCabinetCode() {
        return cabinetCode;
    }

    public void setCabinetCode(String cabinetCode) {
        this.cabinetCode = cabinetCode;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
    }

    public String getShift() {
        return shift;
    }

    public void setShift(String shift) {
        this.shift = shift;
    }

    public Instant getHandedOverAt() {
        return handedOverAt;
    }

    public void setHandedOverAt(Instant handedOverAt) {
        this.handedOverAt = handedOverAt;
    }

    public UUID getHandoverFromId() {
        return handoverFromId;
    }

    public void setHandoverFromId(UUID handoverFromId) {
        this.handoverFromId = handoverFromId;
    }

    public String getHandoverFromName() {
        return handoverFromName;
    }

    public void setHandoverFromName(String handoverFromName) {
        this.handoverFromName = handoverFromName;
    }

    public UUID getHandoverToId() {
        return handoverToId;
    }

    public void setHandoverToId(UUID handoverToId) {
        this.handoverToId = handoverToId;
    }

    public String getHandoverToName() {
        return handoverToName;
    }

    public void setHandoverToName(String handoverToName) {
        this.handoverToName = handoverToName;
    }

    public String getItems() {
        return items;
    }

    public void setItems(String items) {
        this.items = items;
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
