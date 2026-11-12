package com.anesop.gateway.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anesop.gateway.buffer.LocalBuffer;
import com.anesop.gateway.config.GatewayProperties;
import com.anesop.gateway.metrics.GatewayMetricNames;
import com.anesop.gateway.support.GatewayPropertiesFixtures;
import com.anesop.gateway.support.GatewayTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 上传失败语义：数据保留在缓冲、failed 指标增加、退避窗口内不重试、窗口过后按指数退避重试。
 *
 * <p>用 Mockito 模拟"数据库不可达"（外部协作者），被测对象 {@link VitalUploader} 未被 mock。
 */
class VitalUploaderRetryTest {

    private static final String DEVICE = "SIM-RETRY-01";

    @TempDir
    Path bufferDir;

    @Test
    void keepsRecordsAndBacksOffWhenDatabaseUnavailable() throws IOException, SQLException {
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new SQLException("connection refused", "08001"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LocalBuffer buffer = LocalBuffer.open(bufferDir, 8_388_608L, 600_000L, 67_108_864L, registry);
        GatewayProperties properties = GatewayPropertiesFixtures.of(bufferDir, 500L, 1000L);
        VitalUploader uploader = new VitalUploader(buffer, new VitalRepository(failing, "GW-TEST", true),
                properties, registry);

        buffer.append(GatewayTestSupport.sample(DEVICE, 1, Instant.now()));

        uploader.drain();

        assertThat(uploader.consecutiveFailures()).isEqualTo(1);
        assertThat(uploader.isConnected()).isFalse();
        assertThat(buffer.stats().pendingRecords()).isEqualTo(1);
        assertThat(counter(registry, GatewayMetricNames.FRAMES_FAILED)).isEqualTo(1.0d);
        assertThat(uploader.nextAttemptEpochMillis()).isPositive();

        uploader.scheduledUpload();
        assertThat(uploader.consecutiveFailures()).as("退避窗口内不得重试").isEqualTo(1);

        GatewayTestSupport.sleep(600L);
        uploader.scheduledUpload();
        assertThat(uploader.consecutiveFailures()).isEqualTo(2);
        assertThat(buffer.stats().pendingRecords()).isEqualTo(1);

        uploader.close();
        buffer.close();
    }

    private static double counter(SimpleMeterRegistry registry, String name) {
        Counter counter = registry.find(name).tag("device", DEVICE).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
