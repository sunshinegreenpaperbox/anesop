package com.anesop.m2.impl.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

/**
 * PACU 转出留痕实体，对应 {@code m2_anes.pacu_discharge}（V18，M3-M10-01）。
 *
 * <p>每条复苏记录最多一条（pacu_id 唯一）；append-only（UPDATE/DELETE/TRUNCATE 由触发器
 * 拒绝，SQLSTATE 55000）。SBAR 交接与转出时 Aldrete 达标快照以 jsonb 保存。
 */
@TableName("m2_anes.pacu_discharge")
public class PacuDischargeEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;

    /** 所属复苏记录（FK，唯一）。 */
    private UUID pacuId;

    /** 转出时间（服务端时间）。 */
    private Instant dischargedAt;

    /** 转出去向枚举。 */
    private String destination;

    /** SBAR 交接 jsonb。 */
    private String handover;

    /** 转出时 Aldrete 达标快照 jsonb。 */
    private String criteriaSnapshot;

    /** 录入时间。 */
    private Instant createdAt;

    /** 转出操作人用户标识。 */
    private UUID createdBy;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPacuId() {
        return pacuId;
    }

    public void setPacuId(UUID pacuId) {
        this.pacuId = pacuId;
    }

    public Instant getDischargedAt() {
        return dischargedAt;
    }

    public void setDischargedAt(Instant dischargedAt) {
        this.dischargedAt = dischargedAt;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getHandover() {
        return handover;
    }

    public void setHandover(String handover) {
        this.handover = handover;
    }

    public String getCriteriaSnapshot() {
        return criteriaSnapshot;
    }

    public void setCriteriaSnapshot(String criteriaSnapshot) {
        this.criteriaSnapshot = criteriaSnapshot;
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
