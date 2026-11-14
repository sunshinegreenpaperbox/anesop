package com.anesop.m4.impl.persistence.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
/**
 * 耗材主数据实体，对应 {@code m4_material.material}（V23）。
 *
 * <p>编码/名称唯一、UDI-DI 非空唯一（服务层先查重）；highValue 决定是否必须逐件 UDI 登记；
 * 乐观锁 version 由服务层回传并在 SQL 中递增，数据库守卫兜底（version 只增、编码不可改）。
 */
@TableName("m4_material.material")
public class MaterialEntity {
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    private String code;
    private String name;
    private String specification;
    private String unit;
    private String category;
    private String udiDi;
    private Boolean highValue;
    private Boolean expiryManaged;
    private String status;
    private String remark;
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

    public String getSpecification() {
        return specification;
    }

    public void setSpecification(String specification) {
        this.specification = specification;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getUdiDi() {
        return udiDi;
    }

    public void setUdiDi(String udiDi) {
        this.udiDi = udiDi;
    }

    public Boolean getHighValue() {
        return highValue;
    }

    public void setHighValue(Boolean highValue) {
        this.highValue = highValue;
    }

    public Boolean getExpiryManaged() {
        return expiryManaged;
    }

    public void setExpiryManaged(Boolean expiryManaged) {
        this.expiryManaged = expiryManaged;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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
