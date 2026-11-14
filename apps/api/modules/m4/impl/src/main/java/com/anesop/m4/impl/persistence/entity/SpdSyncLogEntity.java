package com.anesop.m4.impl.persistence.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;
/**
 * SPD 适配器（EXT-03）同步留痕实体，对应 {@code m4_material.spd_sync_log}（V23）。
 *
 * <p>detail 只存状态摘要（错误码/条数），不含外部凭据、供应商明细与患者信息。
 */
@TableName("m4_material.spd_sync_log")
public class SpdSyncLogEntity {
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    private String adapterCode;
    private String operation;
    private Boolean success;
    private Boolean degraded;
    private String detail;
    private Instant occurredAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getAdapterCode() {
        return adapterCode;
    }

    public void setAdapterCode(String adapterCode) {
        this.adapterCode = adapterCode;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Boolean getDegraded() {
        return degraded;
    }

    public void setDegraded(Boolean degraded) {
        this.degraded = degraded;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
