package com.anesop.gateway.upload;

import com.anesop.gateway.buffer.BufferedRecord;
import com.anesop.gateway.buffer.LocalBuffer;
import com.anesop.gateway.config.GatewayProperties;
import com.anesop.gateway.metrics.GatewayMetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 体征上传器：从本地缓冲按设备顺序取批 → 幂等写库 → 确认位点。
 *
 * <p>关键不变量：
 * <ul>
 *   <li>先入库、后确认：数据库写成功才推进 ack，失败原样留在缓冲（断网不丢）。</li>
 *   <li>幂等：唯一键 + {@code ON CONFLICT DO NOTHING}，重复补传只增加 duplicate 指标。</li>
 *   <li>退避：连续失败按 {@link RetryBackoff} 指数退避，成功清零；退避期间调度直接返回，
 *       不空转数据库。</li>
 *   <li>限速：单轮最多 {@code maxBatchesPerCycle} 批，避免补传占满调度线程。</li>
 * </ul>
 *
 * <p>{@link #pause()} / {@link #resume()} 供运维与测试模拟断网（不停止采集与落盘）。
 */
@Component
public final class VitalUploader implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(VitalUploader.class);

    private final LocalBuffer buffer;
    private final VitalRepository repository;
    private final GatewayProperties.Upload config;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger connected = new AtomicInteger(1);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong lastSuccessEpochMillis = new AtomicLong(0L);
    private final AtomicLong nextAttemptEpochMillis = new AtomicLong(0L);

    /**
     * 构造上传器并注册 gauge。
     *
     * @param buffer       本地缓冲
     * @param repository   入库仓储
     * @param properties   网关配置
     * @param meterRegistry 指标注册表
     */
    public VitalUploader(LocalBuffer buffer, VitalRepository repository, GatewayProperties properties,
            MeterRegistry meterRegistry) {
        this.buffer = buffer;
        this.repository = repository;
        this.config = properties.upload();
        this.meterRegistry = meterRegistry;
        Gauge.builder(GatewayMetricNames.UPLOAD_CONNECTED, connected, AtomicInteger::doubleValue)
                .register(meterRegistry);
        Gauge.builder(GatewayMetricNames.UPLOAD_CONSECUTIVE_FAILURES, consecutiveFailures,
                        AtomicInteger::doubleValue)
                .register(meterRegistry);
        Gauge.builder(GatewayMetricNames.UPLOAD_PAUSED, paused, flag -> flag.get() ? 1.0d : 0.0d)
                .register(meterRegistry);
    }

    /**
     * 调度入口：按固定延迟触发；暂停、停用或处于退避窗口时直接返回。
     */
    @Scheduled(fixedDelayString = "${anesop.gateway.upload.interval-ms:1000}",
            initialDelayString = "${anesop.gateway.upload.initial-delay-ms:1500}")
    public void scheduledUpload() {
        if (!running.get() || paused.get() || !config.enabled()) {
            return;
        }
        if (System.currentTimeMillis() < nextAttemptEpochMillis.get()) {
            return;
        }
        drain();
    }

    /**
     * 立即执行一轮上传（忽略暂停与退避；供测试与运维手动触发）。
     *
     * @return 本轮成功入库的行数
     */
    public int drain() {
        int insertedTotal = 0;
        int batches = 0;
        try {
            for (String deviceQueue : buffer.devices()) {
                while (batches < config.maxBatchesPerCycle()) {
                    List<BufferedRecord> batch = buffer.peek(deviceQueue, config.batchSize());
                    if (batch.isEmpty()) {
                        break;
                    }
                    String deviceId = batch.get(0).sample().deviceId();
                    try {
                        int inserted = repository.insertAll(batch.stream().map(BufferedRecord::sample).toList());
                        buffer.acknowledge(deviceQueue, batch);
                        insertedTotal += inserted;
                        int duplicates = batch.size() - inserted;
                        meterRegistry.counter(GatewayMetricNames.FRAMES_UPLOADED, "device", deviceId)
                                .increment(inserted);
                        if (duplicates > 0) {
                            meterRegistry.counter(GatewayMetricNames.FRAMES_DUPLICATE, "device", deviceId)
                                    .increment(duplicates);
                        }
                        onSuccess();
                        batches++;
                    } catch (SQLException e) {
                        onFailure(deviceId, batch.size(), e);
                        return insertedTotal;
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("读取本地缓冲失败 cause={}", e.getMessage());
        }
        return insertedTotal;
    }

    /**
     * 暂停上传（模拟断网；采集与落盘继续）。
     */
    public void pause() {
        paused.set(true);
        LOG.warn("上传已暂停（模拟断网/维护）");
    }

    /**
     * 恢复上传。
     */
    public void resume() {
        paused.set(false);
        LOG.info("上传已恢复");
    }

    /**
     * 是否暂停。
     *
     * @return true 表示暂停
     */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * 最近一次上传是否成功（连接可用）。
     *
     * @return true 表示可用
     */
    public boolean isConnected() {
        return connected.get() == 1;
    }

    /**
     * 连续失败次数。
     *
     * @return 次数
     */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * 最近一次成功入库的服务端时间（epoch 毫秒，0 表示尚未成功）。
     *
     * @return epoch 毫秒
     */
    public long lastSuccessEpochMillis() {
        return lastSuccessEpochMillis.get();
    }

    /**
     * 下一次允许重试的时间（epoch 毫秒，0 表示无退避）。
     *
     * @return epoch 毫秒
     */
    public long nextAttemptEpochMillis() {
        return nextAttemptEpochMillis.get();
    }

    @Override
    public void close() {
        running.set(false);
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        connected.set(1);
        lastSuccessEpochMillis.set(System.currentTimeMillis());
        nextAttemptEpochMillis.set(0L);
    }

    private void onFailure(String deviceId, int batchSize, SQLException error) {
        int failures = consecutiveFailures.incrementAndGet();
        connected.set(0);
        long delay = RetryBackoff.delayMillis(failures, config.retryBaseMs(), config.retryMaxMs());
        nextAttemptEpochMillis.set(System.currentTimeMillis() + delay);
        meterRegistry.counter(GatewayMetricNames.FRAMES_FAILED, "device", deviceId).increment(batchSize);
        LOG.warn("上传失败，数据保留在缓冲 device={} failures={} delayMs={} sqlState={}",
                deviceId, failures, delay, error.getSQLState());
    }
}
