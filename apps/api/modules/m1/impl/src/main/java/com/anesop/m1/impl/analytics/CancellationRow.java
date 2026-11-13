package com.anesop.m1.impl.analytics;

import java.util.UUID;

/**
 * 取消率聚合行：每台次的状态与最近一条 STOP 原因码（无记录为 UNSPECIFIED）。
 */
public class CancellationRow {

    private UUID roomId;
    private String status;
    private String reasonCode;

    public UUID getRoomId() {
        return roomId;
    }

    public void setRoomId(UUID roomId) {
        this.roomId = roomId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }
}
