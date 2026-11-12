package com.anesop.gateway.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.gateway.buffer.LocalBuffer;
import com.anesop.gateway.config.GatewayProperties;
import com.anesop.gateway.metrics.GatewayMetricNames;
import com.anesop.gateway.support.GatewayPropertiesFixtures;
import com.anesop.gateway.support.GatewayTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 上传幂等（真实 PostgreSQL 16）：
 * 重启恢复缓冲 → 首批全部入库；同一批数据再次补传 → 行数不增、duplicates 指标增加。
 */
class VitalUploaderIdempotencyTest {

    private static final String DEVICE = "SIM-UP-01";
    private static final Instant BASE = Instant.parse("2026-11-12T08:00:00Z");
    private static final long SEGMENT_BYTES = 8_388_608L;
    private static final long SEGMENT_INTERVAL_MS = 600_000L;
    private static final long MAX_BYTES = 67_108_864L;

    private static DataSource dataSource;

    @TempDir
    Path bufferDir;

    private SimpleMeterRegistry registry;
    private LocalBuffer buffer;
    private VitalUploader uploader;

    @BeforeAll
    static void migrate() {
        dataSource = GatewayTestSupport.dataSource();
        GatewayTestSupport.flyway(dataSource).migrate();
    }

    @BeforeEach
    void setUp() throws IOException, SQLException {
        GatewayTestSupport.truncateVital(dataSource);
        registry = new SimpleMeterRegistry();
        buffer = LocalBuffer.open(bufferDir, SEGMENT_BYTES, SEGMENT_INTERVAL_MS, MAX_BYTES, registry);
        uploader = newUploader(buffer);
    }

    @AfterEach
    void tearDown() {
        uploader.close();
        buffer.close();
    }

    @Test
    void restartRecoveryThenReplayIsIdempotent() throws IOException, SQLException {
        for (long seq = 1; seq <= 5; seq++) {
            buffer.append(GatewayTestSupport.sample(DEVICE, seq, BASE.plusSeconds(seq)));
        }
        buffer.close();

        buffer = LocalBuffer.open(bufferDir, SEGMENT_BYTES, SEGMENT_INTERVAL_MS, MAX_BYTES, registry);
        uploader = newUploader(buffer);
        uploader.drain();

        assertThat(rowCount()).isEqualTo(5);
        assertThat(counter(GatewayMetricNames.FRAMES_UPLOADED)).isEqualTo(5.0d);
        assertThat(counter(GatewayMetricNames.FRAMES_DUPLICATE)).isZero();
        assertThat(buffer.stats().pendingRecords()).isZero();
        assertThat(syntheticRowCount()).isEqualTo(5);

        for (long seq = 1; seq <= 5; seq++) {
            buffer.append(GatewayTestSupport.sample(DEVICE, seq, BASE.plusSeconds(seq)));
        }
        uploader.drain();

        assertThat(rowCount()).isEqualTo(5);
        assertThat(counter(GatewayMetricNames.FRAMES_DUPLICATE)).isEqualTo(5.0d);
        assertThat(counter(GatewayMetricNames.FRAMES_UPLOADED)).isEqualTo(5.0d);
        assertThat(buffer.stats().pendingRecords()).isZero();

        System.out.printf("[E2E-restart] device=%s rows=%d uploaded=%.0f duplicatesAfterReplay=%.0f pending=%d%n",
                DEVICE, rowCount(), counter(GatewayMetricNames.FRAMES_UPLOADED),
                counter(GatewayMetricNames.FRAMES_DUPLICATE), buffer.stats().pendingRecords());
    }

    private VitalUploader newUploader(LocalBuffer target) {
        GatewayProperties properties = GatewayPropertiesFixtures.of(bufferDir);
        return new VitalUploader(target, new VitalRepository(dataSource, "GW-TEST", true), properties, registry);
    }

    private double counter(String name) {
        Counter counter = registry.find(name).tag("device", DEVICE).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private int rowCount() throws SQLException {
        return queryCount("SELECT count(*) FROM m2_anes.anesthesia_vital WHERE device_id = ?");
    }

    private int syntheticRowCount() throws SQLException {
        return queryCount("SELECT count(*) FROM m2_anes.anesthesia_vital WHERE device_id = ? AND synthetic");
    }

    private int queryCount(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, DEVICE);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
