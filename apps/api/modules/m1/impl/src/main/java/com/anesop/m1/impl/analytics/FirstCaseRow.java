package com.anesop.m1.impl.analytics;

import java.time.Instant;
import java.util.UUID;

/**
 * 首台台次行（SQL 窗口函数取每术间每本地日最早开始台次，M2-M6-01）。
 *
 * <p>只包含状态 {@code IN_PROGRESS}/{@code COMPLETED} 的台次；MVP 以 {@code updatedAt}
 * 近似实际开台时间（HIS 未回写，【待确认】）。
 */
public class FirstCaseRow {

    private UUID roomId;
    private Instant startAt;
    private Instant updatedAt;
    private String status;

    public UUID getRoomId() {
        return roomId;
    }

    public void setRoomId(UUID roomId) {
        this.roomId = roomId;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public void setStartAt(Instant startAt) {
        this.startAt = startAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
