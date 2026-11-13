package com.anesop.m1.impl.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 通知 outbox 定时轮询（M2-M2-02）。
 *
 * <p>默认开启；测试 profile 置 {@code anesop.m1.notification.scheduler-enabled=false}，
 * 避免定时派发与用例内显式 drain 竞争。真实短信/企业微信通道为 EXT。
 */
@Component
@ConditionalOnProperty(name = "anesop.m1.notification.scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class NotificationScheduler {

    /** 单轮最多派发条数，避免长事务与突发积压拖慢请求线程。 */
    private static final int MAX_PER_RUN = 20;

    private final NotificationDispatcher dispatcher;

    public NotificationScheduler(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(initialDelayString = "${anesop.m1.notification.initial-delay-ms:30000}",
            fixedDelayString = "${anesop.m1.notification.poll-interval-ms:30000}")
    public void poll() {
        dispatcher.drainAll(MAX_PER_RUN);
    }
}
