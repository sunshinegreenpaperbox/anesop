package com.anesop.m1.impl.notification;

import com.anesop.m1.api.notification.NotificationEvent;
import com.anesop.m1.api.notification.NotificationMessage;
import com.anesop.m1.api.notification.NotificationPort;
import com.anesop.m1.impl.persistence.mapper.NotificationOutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模拟通知端口（M2-M2-02，**SIMULATED**）。
 *
 * <p>{@link #publish} 只把事件写入 {@code m1_sched.notification_outbox}（PENDING），不发送真实
 * 消息；{@link #deliver} 仅记录事件类型与发件箱标识后返回成功。真实短信/企业微信通道为
 * **EXT**（待院方提供接口与白名单），接入时替换本实现即可，业务代码不变。
 *
 * <p>日志与载荷均不含患者姓名、住院号、证件号、手机号、诊断（payload 白名单构造 + 数据库
 * CHECK 双保险）。
 */
@Component
public class SimulatedNotificationPort implements NotificationPort {

    private static final Logger LOG = LoggerFactory.getLogger(SimulatedNotificationPort.class);

    private final NotificationOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/端口/Clock 由容器管理，服务不修改其内部状态")
    public SimulatedNotificationPort(NotificationOutboxMapper outboxMapper,
            ObjectMapper objectMapper, Clock clock) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
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
        LOG.info("[SIMULATED] 通知入队：eventType={}，outboxId={}", event.eventType(), id);
        return id;
    }

    @Override
    public boolean deliver(NotificationMessage message) {
        // 模拟通道：只记录路由元数据，不输出 payload。
        LOG.info("[SIMULATED] 通知已投递（模拟通道，EXT 待接入）：eventType={}，outboxId={}",
                message.eventType(), message.outboxId());
        return true;
    }
}
