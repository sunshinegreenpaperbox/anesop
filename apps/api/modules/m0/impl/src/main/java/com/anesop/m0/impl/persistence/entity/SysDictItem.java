package com.anesop.m0.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * 字典项实体，对应 m0_platform.sys_dict_item（V1）。
 *
 * <p>同一 dictTypeId 下 code 唯一；停用不删除，保证历史记录按当时版本可解释。
 */
@TableName("m0_platform.sys_dict_item")
public class SysDictItem {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    private UUID dictTypeId;

    private String code;

    private String label;

    private Integer sort;

    private Integer status;

    private String remark;

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

    public UUID getDictTypeId() {
        return dictTypeId;
    }

    public void setDictTypeId(UUID dictTypeId) {
        this.dictTypeId = dictTypeId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
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
}
