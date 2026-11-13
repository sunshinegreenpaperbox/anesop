package com.anesop.m1.impl.persistence.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * 冲突检测查询投影：候选窗口内与之外的已排台次 + 术间 + 申请/患者摘要键。
 *
 * <p>只承载规则判断必需字段（术间编号/类型、台次序号、患者标识、术者姓名、时段），
 * 不含患者姓名与住院号明文，避免冲突文案/日志出现患者标识。
 */
public class ScheduleOverlapRow {

    private UUID id;
    private UUID applicationId;
    private UUID roomId;
    private String roomNo;
    private String roomType;
    private Instant startAt;
    private Instant endAt;
    private Integer sequence;
    private String status;
    private UUID patientId;
    private String surgeonName;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(UUID applicationId) {
        this.applicationId = applicationId;
    }

    public UUID getRoomId() {
        return roomId;
    }

    public void setRoomId(UUID roomId) {
        this.roomId = roomId;
    }

    public String getRoomNo() {
        return roomNo;
    }

    public void setRoomNo(String roomNo) {
        this.roomNo = roomNo;
    }

    public String getRoomType() {
        return roomType;
    }

    public void setRoomType(String roomType) {
        this.roomType = roomType;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public void setEndAt(Instant endAt) {
        this.endAt = endAt;
    }

    public Integer getSequence() {
        return sequence;
    }

    public void setSequence(Integer sequence) {
        this.sequence = sequence;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public void setPatientId(UUID patientId) {
        this.patientId = patientId;
    }

    public String getSurgeonName() {
        return surgeonName;
    }

    public void setSurgeonName(String surgeonName) {
        this.surgeonName = surgeonName;
    }
}
