package com.anesop.m1.impl.persistence.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * 通知发件箱查询投影，对应 {@code m1_sched.notification_outbox}（V9）。
 *
 * <p>{@code payload} 以 JSON 文本承载（SQL 侧 {@code payload::text}），避免 m1 模块依赖
 * m0 的 jsonb TypeHandler。载荷只含标识与原因码，禁止 PHI。
 */
public class NotificationOutboxRow {

    private UUID id;
    private String eventType;
    private String payload;
    private String status;
    private Integer attempts;
    private Instant nextAttemptAt;
    private Instant createdAt;
    private Instant sentAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
