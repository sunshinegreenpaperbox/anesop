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
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 上传失败语义：数据保留在缓冲、failed 指标增加、退避窗口内不重试、窗口过后按指数退避重试。
 *
 * <p>用 Mockito 模拟"数据库不可达"（外部协作者），被测对象 {@link VitalUploader} 未被 mock。
 * 退避窗口用注入的可控时钟（{@link AtomicLong}）推进：不 sleep、不赌真实调度时序，
 * 窗口内与窗口边界的判定完全确定（见 docs/testing/README.md §8）。
 */
class VitalUploaderRetryTest {

    private static final String DEVICE = "SIM-RETRY-01";
    private static final Instant MEASURED_AT = Instant.parse("2026-11-13T00:00:00Z");
    private static final long T0 = 1_700_000_000_000L;
    private static final long RETRY_BASE_MS = 500L;
    private static final long RETRY_MAX_MS = 1_000L;

    @TempDir
    Path bufferDir;

    /** 可控测试时钟：默认停在 T0，测试显式推进。 */
    private final AtomicLong clock = new AtomicLong(T0);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private LocalBuffer buffer;
    private VitalUploader uploader;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new SQLException("connection refused", "08001"));
        buffer = LocalBuffer.open(bufferDir, 8_388_608L, 600_000L, 67_108_864L, registry);
        GatewayProperties properties = GatewayPropertiesFixtures.of(bufferDir, RETRY_BASE_MS, RETRY_MAX_MS);
        uploader = new VitalUploader(buffer, new VitalRepository(failing, "GW-TEST", true),
                properties, registry, clock::get);
        buffer.append(GatewayTestSupport.sample(DEVICE, 1, MEASURED_AT));
    }

    @AfterEach
    void tearDown() {
        uploader.close();
        buffer.close();
    }

    @Test
    void keepsRecordsAndBacksOffWhenDatabaseUnavailable() throws IOException {
        uploader.drain();

        assertThat(uploader.consecutiveFailures()).isEqualTo(1);
        assertThat(uploader.isConnected()).isFalse();
        assertThat(uploader.lastSuccessEpochMillis()).isZero();
        assertThat(buffer.stats().pendingRecords()).as("断网不得丢数据").isEqualTo(1);
        assertThat(counter(registry, GatewayMetricNames.FRAMES_FAILED)).isEqualTo(1.0d);

        long windowEnd = uploader.nextAttemptEpochMillis();
        assertThat(windowEnd).as("nextAttempt 为正数且等于 失败时刻 + 基础退避").isPositive()
                .isEqualTo(T0 + RETRY_BASE_MS);

        // 窗口内（边界前 1ms）调度直接返回：不重试、失败数与 failed 指标不变、数据仍保留
        clock.set(windowEnd - 1L);
        uploader.scheduledUpload();
        assertThat(uploader.consecutiveFailures()).as("退避窗口内不得重试").isEqualTo(1);
        assertThat(counter(registry, GatewayMetricNames.FRAMES_FAILED)).isEqualTo(1.0d);
        assertThat(buffer.stats().pendingRecords()).isEqualTo(1);

        // 时钟推进到窗口边界：允许重试，连续失败 +1，退避按 2 倍基数推进
        clock.set(windowEnd);
        uploader.scheduledUpload();
        assertThat(uploader.consecutiveFailures()).as("窗口过后应重试").isEqualTo(2);
        assertThat(counter(registry, GatewayMetricNames.FRAMES_FAILED)).isEqualTo(2.0d);
        assertThat(uploader.isConnected()).isFalse();
        assertThat(buffer.stats().pendingRecords()).as("重试失败仍不得丢数据").isEqualTo(1);
        assertThat(uploader.nextAttemptEpochMillis()).isEqualTo(windowEnd + 2L * RETRY_BASE_MS);

        // 新窗口内同样不重试：证明判定用的是注入时钟，而不是墙钟
        clock.set(windowEnd + 2L * RETRY_BASE_MS - 1L);
        uploader.scheduledUpload();
        assertThat(uploader.consecutiveFailures()).isEqualTo(2);
        assertThat(counter(registry, GatewayMetricNames.FRAMES_FAILED)).isEqualTo(2.0d);
    }

    private static double counter(SimpleMeterRegistry registry, String name) {
        Counter counter = registry.find(name).tag("device", DEVICE).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
