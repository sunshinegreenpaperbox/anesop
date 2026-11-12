package com.anesop.gateway.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.gateway.buffer.LocalBuffer;
import com.anesop.gateway.metrics.GatewayMetricNames;
import com.anesop.gateway.protocol.TcpDeviceServer;
import com.anesop.gateway.support.GatewayTestSupport;
import com.anesop.gateway.upload.VitalUploader;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 端到端（真实 TCP 模拟设备 + 嵌入式 PostgreSQL 16 + 真实 actuator 端点）：
 *
 * <ol>
 *   <li>单设备连续推送 10 分钟（660 帧）→ 接收=入库，无丢失、无重复；非法帧丢弃并计数；</li>
 *   <li>停上传 5 分钟（数据窗口 300 帧）→ 缓冲不丢 → 恢复后全部入库 → 重放同一批不重复；</li>
 *   <li>health 200/UP 且包含 buffer/uploader 状态；关键 micrometer 指标存在且数值正确。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceGatewayEndToEndTest {

    private static final Path BUFFER_DIR = createBufferDir();
    private static final String DEVICE_TEN_MINUTES = "SIM-MON-01";
    private static final String DEVICE_OFFLINE = "SIM-MON-02";
    private static final String DEVICE_HEALTH = "SIM-MON-03";

    @Autowired
    private TcpDeviceServer tcpServer;

    @Autowired
    private VitalUploader uploader;

    @Autowired
    private LocalBuffer buffer;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("anesop.gateway.database.url", GatewayTestSupport::jdbcUrl);
        registry.add("anesop.gateway.database.username", () -> "postgres");
        registry.add("anesop.gateway.database.password", () -> "");
        registry.add("anesop.gateway.database.login-timeout-seconds", () -> "3");
        registry.add("anesop.gateway.buffer.directory", () -> BUFFER_DIR.toString());
        registry.add("anesop.gateway.flyway-locations", GatewayTestSupport::migrationLocations);
        registry.add("anesop.gateway.tcp.port", () -> "0");
        registry.add("anesop.gateway.upload.interval-ms", () -> "100");
        registry.add("anesop.gateway.upload.batch-size", () -> "50");
    }

    @BeforeEach
    void clearTable() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE m2_anes.anesthesia_vital");
        }
        uploader.resume();
    }

    @AfterEach
    void resumeUpload() {
        uploader.resume();
    }

    @Test
    void tenMinutesOfFramesAreStoredWithoutLoss() {
        Instant base = Instant.now().minusSeconds(900);
        List<String> lines = new ArrayList<>(GatewayTestSupport.frames(DEVICE_TEN_MINUTES, 1, 660, base));
        lines.add("DEV|" + DEVICE_TEN_MINUTES + "|661|" + base + "|76|98|120|80|35.0|36.7");
        lines.add("XYZ|" + DEVICE_TEN_MINUTES + "|662|" + base + "|76|98|120|80|35.0|36.7|0");
        lines.add("DEV|" + DEVICE_TEN_MINUTES + "|663|" + base + "|abc|98|120|80|35.0|36.7|0");

        send(lines);

        GatewayTestSupport.awaitTrue("660 帧全部入库", Duration.ofSeconds(120),
                () -> rowCount(DEVICE_TEN_MINUTES) == 660);

        assertThat(counter(GatewayMetricNames.FRAMES_RECEIVED, DEVICE_TEN_MINUTES)).isEqualTo(660.0d);
        assertThat(counter(GatewayMetricNames.FRAMES_BUFFERED, DEVICE_TEN_MINUTES)).isEqualTo(660.0d);
        assertThat(counter(GatewayMetricNames.FRAMES_UPLOADED, DEVICE_TEN_MINUTES)).isEqualTo(660.0d);
        assertThat(counter(GatewayMetricNames.FRAMES_DUPLICATE, DEVICE_TEN_MINUTES)).isZero();
        assertThat(counter(GatewayMetricNames.FRAMES_INVALID, "reason", "FIELD_COUNT")).isEqualTo(1.0d);
        assertThat(counter(GatewayMetricNames.FRAMES_INVALID, "reason", "BAD_PREFIX")).isEqualTo(1.0d);
        assertThat(counter(GatewayMetricNames.FRAMES_INVALID, "reason", "BAD_NUMBER")).isEqualTo(1.0d);

        assertThat(distinctSeqCount(DEVICE_TEN_MINUTES)).isEqualTo(660);
        assertThat(measuredSpanSeconds(DEVICE_TEN_MINUTES)).isEqualTo(659L);
        assertThat(qualityFlaggedCount(DEVICE_TEN_MINUTES, "ts:BACKFILL")).isEqualTo(660);
        assertThat(correctedEqualsOriginalCount(DEVICE_TEN_MINUTES)).isEqualTo(660);
        assertThat(buffer.stats().pendingRecords()).isZero();

        System.out.printf("[E2E-10min] device=%s injected=660 received=%.0f buffered=%.0f uploaded=%.0f "
                        + "duplicates=%.0f invalid=%d rows=%d distinctSeq=%d spanSeconds=%d pending=%d%n",
                DEVICE_TEN_MINUTES,
                counter(GatewayMetricNames.FRAMES_RECEIVED, DEVICE_TEN_MINUTES),
                counter(GatewayMetricNames.FRAMES_BUFFERED, DEVICE_TEN_MINUTES),
                counter(GatewayMetricNames.FRAMES_UPLOADED, DEVICE_TEN_MINUTES),
                counter(GatewayMetricNames.FRAMES_DUPLICATE, DEVICE_TEN_MINUTES),
                invalidTotal(),
                rowCount(DEVICE_TEN_MINUTES),
                distinctSeqCount(DEVICE_TEN_MINUTES),
                measuredSpanSeconds(DEVICE_TEN_MINUTES),
                buffer.stats().pendingRecords());
    }

    @Test
    void offlineFiveMinutesThenBackfillIsCompleteAndNotDuplicated() {
        uploader.pause();
        Instant base = Instant.now().minusSeconds(900);
        List<String> frames = GatewayTestSupport.frames(DEVICE_OFFLINE, 1, 300, base);

        send(frames);

        GatewayTestSupport.awaitTrue("300 帧全部落盘", Duration.ofSeconds(30),
                () -> buffer.stats().pendingRecords() >= 300);
        assertThat(rowCount(DEVICE_OFFLINE)).as("断网期间不得入库").isZero();
        assertThat(counter(GatewayMetricNames.FRAMES_BUFFERED, DEVICE_OFFLINE)).isEqualTo(300.0d);
        double bufferedWhileOffline = counter(GatewayMetricNames.FRAMES_BUFFERED, DEVICE_OFFLINE);

        uploader.resume();
        GatewayTestSupport.awaitTrue("恢复后 300 帧全部补传入库", Duration.ofSeconds(90),
                () -> rowCount(DEVICE_OFFLINE) == 300);
        assertThat(counter(GatewayMetricNames.FRAMES_UPLOADED, DEVICE_OFFLINE)).isEqualTo(300.0d);
        assertThat(counter(GatewayMetricNames.FRAMES_DUPLICATE, DEVICE_OFFLINE)).isZero();
        assertThat(distinctSeqCount(DEVICE_OFFLINE)).isEqualTo(300);

        send(frames);
        GatewayTestSupport.awaitTrue("重放被唯一键幂等吸收", Duration.ofSeconds(90),
                () -> counter(GatewayMetricNames.FRAMES_DUPLICATE, DEVICE_OFFLINE) >= 300.0d);
        assertThat(rowCount(DEVICE_OFFLINE)).isEqualTo(300);

        System.out.printf("[E2E-offline] device=%s bufferedWhileOffline=%.0f rowsDuringOutage=0 rowsAfterResume=%d "
                        + "duplicatesAfterReplay=%.0f rowsAfterReplay=%d distinctSeq=%d%n",
                DEVICE_OFFLINE,
                bufferedWhileOffline,
                rowCount(DEVICE_OFFLINE),
                counter(GatewayMetricNames.FRAMES_DUPLICATE, DEVICE_OFFLINE),
                rowCount(DEVICE_OFFLINE),
                distinctSeqCount(DEVICE_OFFLINE));
    }

    @Test
    void healthAndMetricsExposePipelineState() {
        Instant base = Instant.now().minusSeconds(30);
        send(GatewayTestSupport.frames(DEVICE_HEALTH, 1, 5, base));
        GatewayTestSupport.awaitTrue("5 帧入库", Duration.ofSeconds(60), () -> rowCount(DEVICE_HEALTH) == 5);

        ResponseEntity<Map<String, Object>> health = restTemplate.exchange("/actuator/health",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {
                });
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).containsEntry("status", "UP");
        Map<String, Object> components = detailsOf(health.getBody(), "components");
        Map<String, Object> gateway = detailsOf(components, "gateway");
        assertThat(gateway).containsEntry("status", "UP");
        Map<String, Object> details = detailsOf(gateway, "details");
        Map<String, Object> bufferDetails = detailsOf(details, "buffer");
        assertThat(bufferDetails).containsKeys("pendingRecords", "bytesOnDisk", "devices");
        Map<String, Object> uploaderDetails = detailsOf(details, "uploader");
        assertThat(uploaderDetails).containsEntry("paused", false).containsEntry("connected", true);
        Map<String, Object> tcpDetails = detailsOf(details, "tcp");
        assertThat(tcpDetails).containsEntry("listening", true);

        ResponseEntity<Map<String, Object>> names = restTemplate.exchange("/actuator/metrics",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {
                });
        assertThat(names.getStatusCode().value()).isEqualTo(200);
        Object metricNames = names.getBody() == null ? null : names.getBody().get("names");
        assertThat(metricNames).isInstanceOf(List.class);
        assertThat(listedNames(metricNames)).contains(
                GatewayMetricNames.FRAMES_RECEIVED,
                GatewayMetricNames.FRAMES_BUFFERED,
                GatewayMetricNames.FRAMES_UPLOADED,
                GatewayMetricNames.FRAMES_DUPLICATE,
                GatewayMetricNames.BUFFER_PENDING_RECORDS);

        ResponseEntity<Map<String, Object>> received = restTemplate.exchange(
                "/actuator/metrics/" + GatewayMetricNames.FRAMES_RECEIVED + "?tag=device:" + DEVICE_HEALTH,
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {
                });
        assertThat(received.getStatusCode().value()).isEqualTo(200);
        List<?> measurements = (List<?>) received.getBody().get("measurements");
        assertThat(measurements).hasSize(1);
        Map<?, ?> measurement = (Map<?, ?>) measurements.get(0);
        assertThat(((Number) measurement.get("value")).doubleValue()).isEqualTo(5.0d);
    }

    private void send(List<String> lines) {
        try (Socket socket = new Socket("127.0.0.1", tcpServer.port());
                Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private double counter(String name, String tagKey, String tagValue) {
        Counter counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private double counter(String name, String deviceId) {
        return counter(name, "device", deviceId);
    }

    private int rowCount(String deviceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM m2_anes.anesthesia_vital WHERE device_id = ?")) {
            statement.setString(1, deviceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询采集表失败", e);
        }
    }

    private int distinctSeqCount(String deviceId) {
        return queryInt("SELECT count(DISTINCT seq) FROM m2_anes.anesthesia_vital WHERE device_id = ?", deviceId);
    }

    private int invalidTotal() {
        return (int) (counter(GatewayMetricNames.FRAMES_INVALID, "reason", "FIELD_COUNT")
                + counter(GatewayMetricNames.FRAMES_INVALID, "reason", "BAD_PREFIX")
                + counter(GatewayMetricNames.FRAMES_INVALID, "reason", "BAD_NUMBER"));
    }

    private int correctedEqualsOriginalCount(String deviceId) {
        return queryInt("SELECT count(*) FROM m2_anes.anesthesia_vital "
                + "WHERE device_id = ? AND measured_at_corrected = measured_at", deviceId);
    }

    private int qualityFlaggedCount(String deviceId, String flag) {
        return queryInt("SELECT count(*) FROM m2_anes.anesthesia_vital WHERE device_id = ? AND quality_flags LIKE ?",
                deviceId, "%" + flag + "%");
    }

    private long measuredSpanSeconds(String deviceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT EXTRACT(EPOCH FROM (max(measured_at) - min(measured_at))) FROM "
                                + "m2_anes.anesthesia_vital WHERE device_id = ?")) {
            statement.setString(1, deviceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return Math.round(resultSet.getDouble(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询采集时间窗失败", e);
        }
    }

    private int queryInt(String sql, String... parameters) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询采集表失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listedNames(Object metricNames) {
        return (List<String>) metricNames;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailsOf(Map<String, Object> source, String key) {
        Object value = source.get(key);
        assertThat(value).as("health 明细缺少字段 %s", key).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static Path createBufferDir() {
        try {
            return Files.createTempDirectory("anesop-gw-e2e-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试缓冲目录", e);
        }
    }
}
