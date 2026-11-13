package com.anesop.m1.api.notification;

import java.util.UUID;

/**
 * 通知出站端口（M2-M2-02）。
 *
 * <p>M1 业务只依赖本接口，不感知短信/企业微信等具体通道。当前实现为
 * **SIMULATED（模拟端口）**：{@link #publish} 写入 {@code m1_sched.notification_outbox}，
 * {@link #deliver} 只记录日志并返回成功，不发送真实消息（EXT：真实通道待院方接入）。
 *
 * <p>实现方必须保证：日志与载荷不含患者标识；失败不得抛出 PHI；投递幂等由调用方
 * （outbox 状态机）保证。
 */
public interface NotificationPort {

    /**
     * 将通知写入发件箱（待 {@code NotificationDispatcher} 轮询投递）。
     *
     * @return 发件箱记录标识
     */
    UUID publish(NotificationEvent event);

    /**
     * 投递一条通知（模拟通道：记录日志即视为成功）。
     *
     * @return true=投递成功；false=可重试失败
     */
    boolean deliver(NotificationMessage message);
}
