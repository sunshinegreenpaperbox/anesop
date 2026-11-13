package com.anesop.gateway.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.gateway.buffer.LocalBuffer;
import com.anesop.gateway.config.GatewayProperties;
import com.anesop.gateway.metrics.GatewayMetricNames;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 平台上行适配器的"失败保留缓冲 → 恢复补传 → 重放幂等"（M3-M8-01，EXT-04 SIMULATED）。
 *
 * <p>不依赖数据库：用一个可切换成败的 {@link HttpServer} 模拟平台 ingest。断言的是上传器的
 * 可观察行为：失败时 ack 不推进（缓冲仍可读到）、恢复后入库并确认、同一批重放命中平台
 * 重复批次（acceptedRows=0）时仍确认且计入 duplicate 指标。
 */
class PlatformVitalUploadTest {

    private static final String DEVICE = "SIM-MON-01";
    private static final Instant BASE = Instant.parse("2026-11-14T01:30:00Z");

    @TempDir
    Path bufferDir;

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile boolean failRequests;
    private volatile int acceptedRows = 2;

    private SimpleMeterRegistry registry;
    private LocalBuffer buffer;
    private PlatformVitalReporter reporter;
    private VitalUploader uploader;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();

        registry = new SimpleMeterRegistry();
        GatewayProperties properties = properties();
        buffer = LocalBuffer.open(bufferDir, 8_388_608L, 600_000L, 67_108_864L, registry);
        reporter = new PlatformVitalReporter(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api", "GW-TEST", "synth-token", 2000);
        uploader = new VitalUploader(buffer, reporter, properties, registry);
        for (long seq = 1; seq <= 2; seq++) {
            buffer.append(sample(seq));
        }
    }

    @AfterEach
    void tearDown() {
        uploader.close();
        buffer.close();
        server.stop(0);
    }

    @Test
    void failureKeepsBufferThenRecoversAndReplayIsIdempotent() throws IOException {
        failRequests = true;
        assertThat(uploader.drain()).isZero();
        assertThat(buffer.peek(DEVICE, 10)).hasSize(2);
        assertThat(counter(GatewayMetricNames.FRAMES_FAILED)).isEqualTo(2.0d);
        assertThat(uploader.isConnected()).isFalse();

        failRequests = false;
        uploader.drain();
        assertThat(buffer.peek(DEVICE, 10)).isEmpty();
        assertThat(counter(GatewayMetricNames.FRAMES_UPLOADED)).isEqualTo(2.0d);
        assertThat(uploader.consecutiveFailures()).isZero();
        String firstKey = extractBatchKey(lastBody.get());
        assertThat(firstKey).startsWith("GW-TEST-" + DEVICE + "-1-2-");

        // 断网重放同一批：平台返回重复批次（acceptedRows=0），网关仍应确认并计 duplicate。
        acceptedRows = 0;
        for (long seq = 1; seq <= 2; seq++) {
            buffer.append(sample(seq));
        }
        uploader.drain();
        assertThat(buffer.peek(DEVICE, 10)).isEmpty();
        assertThat(extractBatchKey(lastBody.get())).isEqualTo(firstKey);
        assertThat(counter(GatewayMetricNames.FRAMES_DUPLICATE)).isEqualTo(2.0d);
        assertThat(counter(GatewayMetricNames.FRAMES_UPLOADED)).isEqualTo(2.0d);
    }

    /** 测试配置：不触碰嵌入式 PG（本用例只用 HTTP 上行与本地缓冲）。 */
    private static GatewayProperties properties() {
        return new GatewayProperties("GW-TEST", "AUTO", true, false, "filesystem:./db/migration",
                5L, 120L,
                new GatewayProperties.Tcp(false, "127.0.0.1", 0, 4096),
                new GatewayProperties.Buffer("./target/test-buffer", 8_388_608L, 600_000L, 67_108_864L),
                new GatewayProperties.Upload(true, 100L, 50, 500L, 30_000L, 64),
                new GatewayProperties.Database("jdbc:postgresql://localhost:5432/unused", "u", "", 5),
                new GatewayProperties.Platform("http", "http://127.0.0.1:1/api", "synth-token", 2000));
    }

    private static String extractBatchKey(String body) {
        int start = body.indexOf("\"batchKey\":\"") + "\"batchKey\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private double counter(String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0d : counter.count();
    }

    private static com.anesop.gateway.model.VitalSample sample(long seq) {
        Instant measuredAt = BASE.plusSeconds(seq);
        return new com.anesop.gateway.model.VitalSample(DEVICE, seq, measuredAt, measuredAt,
                measuredAt.plusSeconds(1), 76, 98, 120, 80, new BigDecimal("35.0"),
                new BigDecimal("36.7"), 0, List.of(), "AUTO");
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        int status = failRequests ? 503 : 202;
        String body = failRequests
                ? "{\"code\":\"SERVICE_UNAVAILABLE\"}"
                : "{\"acceptedRows\":" + acceptedRows + ",\"duplicateBatch\":" + (acceptedRows == 0) + "}";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
