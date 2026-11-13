package com.anesop.m1.impl.analytics;

import java.util.UUID;

/**
 * 术间利用率聚合行（SQL 只读聚合，M2-M6-01）。
 *
 * <p>{@code totalMinutes} 为台次与查询区间重叠部分的计划分钟数；跨日/跨区间按重叠裁剪。
 */
public class RoomUsageRow {

    private UUID roomId;
    private Integer caseCount;
    private Double totalMinutes;

    public UUID getRoomId() {
        return roomId;
    }

    public void setRoomId(UUID roomId) {
        this.roomId = roomId;
    }

    public Integer getCaseCount() {
        return caseCount;
    }

    public void setCaseCount(Integer caseCount) {
        this.caseCount = caseCount;
    }

    public Double getTotalMinutes() {
        return totalMinutes;
    }

    public void setTotalMinutes(Double totalMinutes) {
        this.totalMinutes = totalMinutes;
    }
}
