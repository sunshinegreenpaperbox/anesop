package com.anesop.m1.api.notification;

import java.util.Map;

/**
 * 通知事件（M2-M2-02）：由排程服务发布到发件箱（outbox）。
 *
 * <p>**患者数据红线**：{@code payload} 只允许承载标识（台次/申请/术间/变更记录 UUID）、
 * 枚举编码（changeType/status/reasonCode）与时间；禁止患者姓名、住院号、证件号、手机号、
 * 诊断等 PHI 明文。真实短信/企业微信通道为 EXT，接入前须复核载荷白名单。
 *
 * @param eventType 事件类型（大写字母/下划线，如 {@code SCHEDULE_STOPPED}）
 * @param payload   通知载荷（无 PHI）
 */
public record NotificationEvent(String eventType, Map<String, Object> payload) {

    public NotificationEvent {
        if (eventType == null || !eventType.matches("^[A-Z_]{2,64}$")) {
            throw new IllegalArgumentException("通知事件类型必须为 2–64 位大写字母或下划线");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
