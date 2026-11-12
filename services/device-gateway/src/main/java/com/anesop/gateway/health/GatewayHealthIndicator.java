package com.anesop.gateway.health;

import com.anesop.gateway.buffer.BufferStats;
import com.anesop.gateway.buffer.LocalBuffer;
import com.anesop.gateway.config.GatewayProperties;
import com.anesop.gateway.protocol.TcpDeviceServer;
import com.anesop.gateway.upload.VitalRepository;
import com.anesop.gateway.upload.VitalUploader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * 网关健康检查（{@code /actuator/health}，组件 id 为 {@code gateway}）。
 *
 * <p>判定：
 * <ul>
 *   <li>数据库不可达或连续上传失败 ≥ {@value #FAILURE_THRESHOLD} 次 → DOWN（HTTP 503）；</li>
 *   <li>上传被人工暂停 → OUT_OF_SERVICE（HTTP 503，明确表示"非故障但不可用"）；</li>
 *   <li>其余 → UP。</li>
 * </ul>
 * 明细包含缓冲深度、淘汰/拒收计数、上传器状态、TCP 监听与连接数，
 * 监控页按这些字段做"离线术间"高亮（方案书 §3.6.5）。
 */
@Component
public final class GatewayHealthIndicator implements HealthIndicator {

    /** 连续失败判定阈值。 */
    private static final int FAILURE_THRESHOLD = 3;

    private final LocalBuffer buffer;
    private final VitalUploader uploader;
    private final VitalRepository repository;
    private final TcpDeviceServer tcpServer;
    private final GatewayProperties properties;

    /**
     * 构造健康检查。
     *
     * @param buffer      本地缓冲
     * @param uploader    上传器
     * @param repository  仓储（数据库探针）
     * @param tcpServer   TCP 接入
     * @param properties  网关配置
     */
    public GatewayHealthIndicator(LocalBuffer buffer, VitalUploader uploader, VitalRepository repository,
            TcpDeviceServer tcpServer, GatewayProperties properties) {
        this.buffer = buffer;
        this.uploader = uploader;
        this.repository = repository;
        this.tcpServer = tcpServer;
        this.properties = properties;
    }

    @Override
    public Health health() {
        BufferStats stats = buffer.stats();
        boolean databaseReachable = repository.ping();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("gatewayId", properties.gatewayId());
        details.put("synthetic", properties.synthetic());
        details.put("databaseReachable", databaseReachable);
        details.put("buffer", Map.of(
                "pendingRecords", stats.pendingRecords(),
                "bytesOnDisk", stats.bytesOnDisk(),
                "devices", stats.devices(),
                "evictedSegments", stats.evictedSegments(),
                "rejectedRecords", stats.rejectedRecords(),
                "corruptRecords", stats.corruptRecords()));
        details.put("uploader", Map.of(
                "paused", uploader.isPaused(),
                "connected", uploader.isConnected(),
                "consecutiveFailures", uploader.consecutiveFailures(),
                "lastSuccessAt", isoOrNever(uploader.lastSuccessEpochMillis())));
        details.put("tcp", Map.of(
                "enabled", properties.tcp().enabled(),
                "listening", tcpServer.listening(),
                "port", tcpServer.port(),
                "activeConnections", tcpServer.activeConnections()));

        Health.Builder builder;
        if (!databaseReachable || uploader.consecutiveFailures() >= FAILURE_THRESHOLD) {
            builder = Health.down();
        } else if (uploader.isPaused()) {
            builder = Health.status(Status.OUT_OF_SERVICE);
        } else {
            builder = Health.up();
        }
        return builder.withDetails(details).build();
    }

    private static String isoOrNever(long epochMillis) {
        return epochMillis <= 0 ? "never" : Instant.ofEpochMilli(epochMillis).toString();
    }
}
