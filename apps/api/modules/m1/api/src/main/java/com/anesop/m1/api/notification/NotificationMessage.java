package com.anesop.m1.api.notification;

import java.util.UUID;

/**
 * 一条待投递通知（从 outbox 读出后交给通知端口）。
 *
 * @param outboxId  发件箱记录标识
 * @param eventType 事件类型
 * @param payload   通知载荷 JSON 文本（无 PHI）
 */
public record NotificationMessage(UUID outboxId, String eventType, String payload) {
}
