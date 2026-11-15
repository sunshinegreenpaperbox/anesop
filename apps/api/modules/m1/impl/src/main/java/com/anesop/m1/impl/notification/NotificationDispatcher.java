package com.anesop.m1.impl.notification;

import com.anesop.m1.api.notification.NotificationMessage;
import com.anesop.m1.api.notification.NotificationPort;
import com.anesop.m1.impl.persistence.entity.NotificationOutboxRow;
import com.anesop.m1.impl.persistence.mapper.NotificationOutboxMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知派发消费者（M2-M2-02）：轮询 outbox 的 PENDING 记录，交给
 * {@link NotificationPort#deliver} 投递并标记 SENT。
 *
 * <p>语义：
 * <ul>
 *   <li>每次 {@link #drainOnce()} 只取 1 条（{@code FOR UPDATE SKIP LOCKED}），成功记 SENT；</li>
 *   <li>失败按 60s × 次数退避，重试 3 次仍失败记 FAILED（last_error 固定文案，无 PHI）；</li>
 *   <li>站内消息端口投递成功返回 true，因此自动化测试可直接断言 SENT。</li>
 * </ul>
 */
@Service
public class NotificationDispatcher {

    /** 单条通知最大尝试次数，超过记 FAILED，等待人工介入。 */
    static final int MAX_ATTEMPTS = 3;
    /** 固定失败文案：不回显通道返回内容，避免 PHI/凭据进入 last_error。 */
    static final String FAILURE_MESSAGE = "通知投递失败，已按退避重试";
    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationOutboxMapper outboxMapper;
    private final NotificationPort notificationPort;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/端口/Clock 由容器管理，服务不修改其内部状态")
    public NotificationDispatcher(NotificationOutboxMapper outboxMapper,
            NotificationPort notificationPort, Clock clock) {
        this.outboxMapper = outboxMapper;
        this.notificationPort = notificationPort;
        this.clock = clock;
    }

    /**
     * 派发一条待发通知。
     *
     * @return true=本次派发了一条（成功或失败）；false=没有待发记录
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean drainOnce() {
        return dispatchOne();
    }

    /**
     * 连续派发至多 {@code maxPerRun} 条（供定时任务使用，避免单次事务过长）。
     *
     * @return 实际处理条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int drainAll(int maxPerRun) {
        int processed = 0;
        int limit = Math.max(0, maxPerRun);
        while (processed < limit && dispatchOne()) {
            processed++;
        }
        return processed;
    }

    private boolean dispatchOne() {
        Instant now = Instant.now(clock);
        NotificationOutboxRow row = outboxMapper.selectPendingOne(now);
        if (row == null) {
            return false;
        }
        UUID id = row.getId();
        int attempts = (row.getAttempts() == null ? 0 : row.getAttempts()) + 1;
        try {
            boolean delivered = notificationPort.deliver(
                    new NotificationMessage(id, row.getEventType(), row.getPayload()));
            if (delivered) {
                outboxMapper.markSent(id, now);
                return true;
            }
            markFailure(id, attempts, now);
        } catch (RuntimeException e) {
            // 只记录异常类型，不输出 message/载荷，避免通道异常携带敏感信息。
            LOG.warn("通知投递异常：outboxId={}，error={}", id, e.getClass().getSimpleName());
            markFailure(id, attempts, now);
        }
        return true;
    }

    private void markFailure(UUID id, int attempts, Instant now) {
        String status = attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING";
        Instant nextAttemptAt = now.plusSeconds(60L * attempts);
        outboxMapper.markFailure(id, attempts, status, nextAttemptAt, FAILURE_MESSAGE);
        LOG.warn("通知投递失败：outboxId={}，attempts={}，status={}", id, attempts, status);
    }
}
