package com.anesop.gateway.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.gateway.model.VitalSample;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 平台上报适配器单测（M3-M8-01，EXT-04 SIMULATED）：不依赖真实平台，用 JDK 内置
 * {@link HttpServer} 断言路径、网关令牌头、批次键确定性与失败语义。
 *
 * <p>关键断言是"可观察行为"：请求是否带上令牌与键、非 2xx 是否抛异常（上传器据此保留缓冲，
 * 由 {@code PlatformVitalUploadTest} 覆盖）。
 */
class PlatformVitalReporterTest {

    private static final String GATEWAY = "GW-TEST";
    private static final String DEVICE = "SIM-MON-01";
    private static final String TOKEN = "synth-gateway-token";
    private static final Instant BASE = Instant.parse("2026-11-14T01:30:00Z");

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int responseStatus = 202;
    private volatile String responseBody = "{\"acceptedRows\":2}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void postsBatchWithGatewayTokenAndDeterministicKey() throws IOException {
        PlatformVitalReporter reporter = reporter();
        List<VitalSample> samples = samples(1, 2);

        assertThat(reporter.insertAll(samples)).isEqualTo(2);
        assertThat(lastPath.get()).isEqualTo("/api/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest");
        assertThat(lastToken.get()).isEqualTo(TOKEN);
        String expectedKey = GATEWAY + "-" + DEVICE + "-1-2-" + BASE.toEpochMilli();
        assertThat(lastBody.get()).contains("\"batchKey\":\"" + expectedKey + "\"");
        assertThat(lastBody.get()).contains("\"measuredAt\":\"2026-11-14T01:30:00");
        assertThat(lastBody.get()).contains("\"quality\":0");

        // 同一批重试必须复用同一 batchKey（平台据此判定重复批次）。
        reporter.insertAll(samples);
        assertThat(lastBody.get()).contains("\"batchKey\":\"" + expectedKey + "\"");
    }

    @Test
    void duplicateBatchResponseYieldsZeroInserted() throws IOException {
        responseBody = "{\"acceptedRows\":0,\"duplicateBatch\":true}";
        PlatformVitalReporter reporter = reporter();

        assertThat(reporter.insertAll(samples(3, 3))).isZero();
    }

    @Test
    void nonSuccessStatusThrowsSoBufferIsKept() throws IOException {
        responseStatus = 500;
        PlatformVitalReporter reporter = reporter();
        List<VitalSample> samples = samples(4, 4);

        assertThatThrownBy(() -> reporter.insertAll(samples))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("httpStatus=500");
    }

    @Test
    void malformedResponseThrowsInsteadOfSilentlyAcking() throws IOException {
        responseBody = "not-json";
        PlatformVitalReporter reporter = reporter();

        assertThatThrownBy(() -> reporter.insertAll(samples(5, 5))).isInstanceOf(IOException.class);
    }

    @Test
    void mixedDeviceBatchIsRejectedBeforeSending() {
        PlatformVitalReporter reporter = reporter();
        VitalSample other = new VitalSample("SIM-OTHER", 1L, BASE, BASE, Instant.now(),
                70, 99, 110, 70, new BigDecimal("34.0"), new BigDecimal("36.5"),
                0, List.of(), "AUTO");

        assertThatThrownBy(() -> reporter.insertAll(List.of(samples(6, 6).get(0), other)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("同一设备");
        assertThat(lastPath.get()).isNull();
    }

    @Test
    void blankUrlOrTokenFailsClosedAtConstruction() {
        assertThatThrownBy(() -> new PlatformVitalReporter("", GATEWAY, TOKEN, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlatformVitalReporter("http://127.0.0.1:1/api", GATEWAY, " ", 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fail-closed");
        assertThatThrownBy(() -> new PlatformVitalReporter("http://127.0.0.1:1/api", GATEWAY, TOKEN, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pingFollowsPlatformHealthEndpoint() {
        PlatformVitalReporter reporter = reporter();
        assertThat(reporter.ping()).isTrue();

        stopServer();
        assertThat(reporter.ping()).isFalse();
    }

    private PlatformVitalReporter reporter() {
        return new PlatformVitalReporter(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/", GATEWAY, TOKEN, 2000);
    }

    private static List<VitalSample> samples(long fromSeq, long toSeq) {
        java.util.List<VitalSample> samples = new java.util.ArrayList<>();
        for (long seq = fromSeq; seq <= toSeq; seq++) {
            Instant measuredAt = BASE.plusSeconds(seq - fromSeq);
            samples.add(new VitalSample(DEVICE, seq, measuredAt, measuredAt, measuredAt.plusSeconds(1),
                    76, 98, 120, 80, new BigDecimal("35.0"), new BigDecimal("36.7"),
                    0, List.of(), "AUTO"));
        }
        return samples;
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        lastToken.set(exchange.getRequestHeaders().getFirst("X-Gateway-Token"));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(responseStatus, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
