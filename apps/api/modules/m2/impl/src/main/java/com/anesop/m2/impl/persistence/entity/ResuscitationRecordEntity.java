package com.anesop.m2.impl.persistence.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * 抢救记录实体，对应 {@code m2_anes.resuscitation_record}（V15）。
 *
 * <p>append-only：写入后不可修改/删除（数据库触发器拒绝 UPDATE/DELETE/TRUNCATE）。
 * jsonb 列以字符串读写（事件摘要对象、用药数组、负责人对象、参与人员数组），
 * 由服务层用 Jackson 序列化/解析，
 * 避免依赖 m0-impl 的 JsonbTypeHandler（模块边界）。
 */
public class ResuscitationRecordEntity {

    private UUID id;
    private UUID recordId;
    private String resusType;
    private Instant startedAt;
    private Instant endedAt;
    private String eventSummary;
    private String drugs;
    /** 抢救负责人 jsonb 对象 {userId?, name}（V16，name 必填）。 */
    private String leader;
    /** 抢救参与人员 jsonb 数组 [{userId?, name}]（V16，≥1）。 */
    private String rescuers;
    private String outcome;
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

    public String getResusType() {
        return resusType;
    }

    public void setResusType(String resusType) {
        this.resusType = resusType;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public String getEventSummary() {
        return eventSummary;
    }

    public void setEventSummary(String eventSummary) {
        this.eventSummary = eventSummary;
    }

    public String getDrugs() {
        return drugs;
    }

    public void setDrugs(String drugs) {
        this.drugs = drugs;
    }

    public String getLeader() {
        return leader;
    }

    public void setLeader(String leader) {
        this.leader = leader;
    }

    public String getRescuers() {
        return rescuers;
    }

    public void setRescuers(String rescuers) {
        this.rescuers = rescuers;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
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
