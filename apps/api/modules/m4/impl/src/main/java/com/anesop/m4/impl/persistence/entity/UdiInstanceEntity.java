package com.anesop.m4.impl.persistence.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
/**
 * UDI 实例实体，对应 {@code m4_material.udi_instance}（V23，一物一码）。
 *
 * <p>UDI-DI + UDI-PI 唯一；状态 IN_STOCK/USED/RETURNED，状态迁移由触发器守卫
 * （IN_STOCK → USED/RETURNED，终态不可再变）；recordId/scheduleId 为跨模块逻辑关联。
 */
@TableName("m4_material.udi_instance")
public class UdiInstanceEntity {
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    private String udiCode;
    private String udiDi;
    private String udiPi;
    private String serialNumber;
    private UUID materialId;
    private String materialCode;
    private String materialName;
    private String batchNo;
    private LocalDate expiryDate;
    private UUID departmentId;
    private String status;
    private UUID recordId;
    private UUID scheduleId;
    private Instant usedAt;
    private UUID usedBy;
    private String usedByName;
    private Instant createdAt;
    private UUID createdBy;
    private Instant updatedAt;
    private UUID updatedBy;
    private Integer version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUdiCode() {
        return udiCode;
    }

    public void setUdiCode(String udiCode) {
        this.udiCode = udiCode;
    }

    public String getUdiDi() {
        return udiDi;
    }

    public void setUdiDi(String udiDi) {
        this.udiDi = udiDi;
    }

    public String getUdiPi() {
        return udiPi;
    }

    public void setUdiPi(String udiPi) {
        this.udiPi = udiPi;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

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

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public void setDepartmentId(UUID departmentId) {
        this.departmentId = departmentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getRecordId() {
        return recordId;
    }

    public void setRecordId(UUID recordId) {
        this.recordId = recordId;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(UUID scheduleId) {
        this.scheduleId = scheduleId;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }

    public UUID getUsedBy() {
        return usedBy;
    }

    public void setUsedBy(UUID usedBy) {
        this.usedBy = usedBy;
    }

    public String getUsedByName() {
        return usedByName;
    }

    public void setUsedByName(String usedByName) {
        this.usedByName = usedByName;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
