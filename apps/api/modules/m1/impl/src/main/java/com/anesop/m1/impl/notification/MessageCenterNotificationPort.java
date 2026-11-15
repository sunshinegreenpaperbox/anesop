package com.anesop.m1.impl.notification;

import com.anesop.contracts.platform.model.MessageType;
import com.anesop.m0.api.platform.MessagePublisher;
import com.anesop.m0.api.platform.MessagePublisher.MessageCommand;
import com.anesop.m0.api.platform.MessageTemplates;
import com.anesop.m0.api.platform.NotificationRecipientDirectory;
import com.anesop.m1.api.notification.NotificationEvent;
import com.anesop.m1.api.notification.NotificationMessage;
import com.anesop.m1.api.notification.NotificationPort;
import com.anesop.m1.impl.persistence.mapper.NotificationOutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知端口实现（M2-M2-02 / M9-01）：入队 outbox，派发时写入 M0 站内消息中心。
 *
 * <p>两段式：
 * <ol>
 *   <li>{@link #publish} 只写 {@code m1_sched.notification_outbox}（PENDING），与业务事务同提交；</li>
 *   <li>{@link #deliver} 由 {@code NotificationDispatcher} 轮询调用，按<b>默认通知矩阵</b>把事件
 *       翻译成站内消息（{@link MessagePublisher}），接收人 = 角色下的启用用户。</li>
 * </ol>
 *
 * <p>默认通知矩阵（**频率/免打扰/升级路径【待确认】**，见 docs/modules/m1/README.md）：
 * <ul>
 *   <li>{@code SCHEDULE_MODIFIED}/{@code SCHEDULE_PREEMPTED} → 模板 {@code SCHEDULE_CHANGE}，
 *       接收角色 SYS_ANESTHESIOLOGIST + SYS_OR_MANAGER；</li>
 *   <li>{@code SCHEDULE_STOPPED} → {@code SCHEDULE_STOP}，同上；</li>
 *   <li>{@code EMERGENCY_SCHEDULED} → {@code EMERGENCY}，同上；</li>
 *   <li>{@code ALERT_CRITICAL}（含未确认）→ {@code CRITICAL_ALERT}，同上。</li>
 * </ul>
 * SYS_ADMIN 是否纳入**可选【待确认】**，本期不投递，避免管理员被临床告警淹没。
 *
 * <p>去重：同一 outbox 事件 + 接收人 + 模板生成唯一 {@code dedupeKey}，由 V29 唯一索引兜底；
 * 重复派发（重试/并发）命中唯一键时返回既有消息，不产生第二条。
 *
 * <p>无 PHI：参数只从模板白名单键取值（台次/术间/时间/原因码/告警标识/指标/严重度），
 * 不读取 payload 中的其他字段，日志只记事件类型/outbox 标识/接收人数。
 */
@Component
public class MessageCenterNotificationPort implements NotificationPort {

    private static final Logger LOG = LoggerFactory.getLogger(MessageCenterNotificationPort.class);

    /** 默认通知矩阵角色（【待确认】：频率/免打扰/升级路径待院方确认）。 */
    static final String ROLE_ANESTHESIOLOGIST = "SYS_ANESTHESIOLOGIST";
    static final String ROLE_OR_MANAGER = "SYS_OR_MANAGER";

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    /** 一条事件的路由：模板、消息类型与接收角色。 */
    private record Route(String templateCode, MessageType type, List<String> roleCodes) {
        Route {
            roleCodes = roleCodes == null ? List.of() : List.copyOf(roleCodes);
        }
    }

    private static final List<String> SCHEDULE_ROLES =
            List.of(ROLE_ANESTHESIOLOGIST, ROLE_OR_MANAGER);

    private static final Map<String, Route> ROUTES = Map.of(
            "SCHEDULE_MODIFIED", new Route(MessageTemplates.SCHEDULE_CHANGE,
                    MessageType.SCHEDULE, SCHEDULE_ROLES),
            "SCHEDULE_PREEMPTED", new Route(MessageTemplates.SCHEDULE_CHANGE,
                    MessageType.SCHEDULE, SCHEDULE_ROLES),
            "SCHEDULE_STOPPED", new Route(MessageTemplates.SCHEDULE_STOP,
                    MessageType.SCHEDULE, SCHEDULE_ROLES),
            "EMERGENCY_SCHEDULED", new Route(MessageTemplates.EMERGENCY,
                    MessageType.SCHEDULE, SCHEDULE_ROLES),
            "ALERT_CRITICAL", new Route(MessageTemplates.CRITICAL_ALERT,
                    MessageType.ALERT, SCHEDULE_ROLES));

    private final NotificationOutboxMapper outboxMapper;
    private final MessagePublisher messagePublisher;
    private final NotificationRecipientDirectory recipientDirectory;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/发布器/目录/Clock 由容器管理，服务不修改其内部状态")
    public MessageCenterNotificationPort(NotificationOutboxMapper outboxMapper,
            MessagePublisher messagePublisher,
            NotificationRecipientDirectory recipientDirectory,
            ObjectMapper objectMapper, Clock clock) {
        this.outboxMapper = outboxMapper;
        this.messagePublisher = messagePublisher;
        this.recipientDirectory = recipientDirectory;
        this.objectMapper = objectMapper.copy();
        this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UUID publish(NotificationEvent event) {
        UUID id = UUID.randomUUID();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event.payload());
        } catch (JsonProcessingException e) {
            // 固定文案，不拼接载荷内容（避免意外把 PHI 带入异常日志）。
            throw new IllegalStateException("通知载荷序列化失败", e);
        }
        outboxMapper.insert(id, event.eventType(), payload, Instant.now(clock));
        LOG.info("通知入队：eventType={}，outboxId={}", event.eventType(), id);
        return id;
    }

    @Override
    public boolean deliver(NotificationMessage message) {
        Route route = ROUTES.get(message.eventType());
        if (route == null) {
            // 未登记的事件不投递站内消息；返回成功避免无意义重试（事件仍在 outbox 留痕）。
            LOG.info("通知事件未登记站内消息路由，跳过投递：eventType={}，outboxId={}",
                    message.eventType(), message.outboxId());
            return true;
        }
        Map<String, Object> payload;
        try {
            payload = parsePayload(message.payload());
        } catch (JsonProcessingException invalid) {
            LOG.warn("通知载荷解析失败，按可重试失败处理：outboxId={}", message.outboxId());
            return false;
        }
        Map<String, String> params = paramsFor(route.templateCode(), payload);
        int delivered = 0;
        for (String roleCode : route.roleCodes()) {
            for (UUID recipient : recipientDirectory.enabledUserIdsByRole(roleCode)) {
                String dedupeKey = "OUTBOX:" + message.outboxId() + ":" + recipient + ":"
                        + route.templateCode();
                messagePublisher.publish(new MessageCommand(recipient, route.type(),
                        route.templateCode(), params, dedupeKey));
                delivered++;
            }
        }
        if (delivered == 0) {
            // 角色下无启用用户：不重试、不改投其他角色；只记录路由元数据（无 PHI）。
            LOG.warn("通知无可用接收人（角色未绑定启用用户）：eventType={}，template={}",
                    message.eventType(), route.templateCode());
        }
        return true;
    }

    private Map<String, Object> parsePayload(String payload) throws JsonProcessingException {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = objectMapper.readValue(payload, PAYLOAD_TYPE);
        return parsed == null ? Map.of() : parsed;
    }

    /**
     * 只从模板白名单键取值：payload 中其他字段一律不进入消息参数（防 PHI 夹带）。
     * 值只接受标量（字符串/数字/布尔），长度 ≤200；对象/数组/空值跳过。
     */
    private static Map<String, String> paramsFor(String templateCode, Map<String, Object> payload) {
        MessageTemplates.Template template = MessageTemplates.find(templateCode).orElse(null);
        if (template == null) {
            return Map.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (String key : template.allowedParams()) {
            Object value = payload.get(key);
            if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty() || text.length() > 200) {
                continue;
            }
            params.put(key, text);
        }
        return params;
    }
}
