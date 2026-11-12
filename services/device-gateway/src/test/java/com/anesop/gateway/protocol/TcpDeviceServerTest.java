package com.anesop.gateway.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.gateway.buffer.LocalBuffer;
import com.anesop.gateway.config.GatewayProperties;
import com.anesop.gateway.metrics.GatewayMetricNames;
import com.anesop.gateway.normalize.VitalNormalizer;
import com.anesop.gateway.support.GatewayTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TCP 接入单测（M2-M0-03）：监听/关闭、合法帧落盘、非法帧与超长帧计数、容量拒收、连接断开。
 */
class TcpDeviceServerTest {

    private static final Instant BASE = Instant.parse("2026-11-13T00:00:00Z");
    private static final String DEVICE = "SIM-TCP-01";

    @TempDir
    Path tempDir;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private GatewayProperties properties(boolean enabled, int maxFrameBytes, Path bufferDir) {
        return new GatewayProperties("GW-TEST", "AUTO", true, false, "filesystem:" + tempDir,
                5L, 120L,
                new GatewayProperties.Tcp(enabled, "127.0.0.1", 0, maxFrameBytes),
                new GatewayProperties.Buffer(bufferDir.toString(), 8_388_608L, 600_000L, 67_108_864L),
                new GatewayProperties.Upload(true, 1000L, 10, 500L, 30_000L, 64),
                new GatewayProperties.Database("jdbc:postgresql://localhost:5432/unused", "u", "", 5));
    }

    private static int peekSize(LocalBuffer buffer, String deviceId) {
        try {
            return buffer.peek(deviceId, 10).size();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void disabledServerDoesNotListen() throws IOException {
        LocalBuffer buffer = LocalBuffer.open(tempDir, 8_388_608L, 600_000L, 67_108_864L, registry);
        TcpDeviceServer server = new TcpDeviceServer(properties(false, 4096, tempDir),
                new FrameParser(), new VitalNormalizer(5_000L, 300_000L), buffer, registry);

        server.start();

        assertThat(server.listening()).isFalse();
        assertThat(server.port()).isEqualTo(-1);
        server.close();
        buffer.close();
    }

    @Test
    void acceptsValidFramesAndCountsInvalidAndOversizeFrames() throws Exception {
        LocalBuffer buffer = LocalBuffer.open(tempDir, 8_388_608L, 600_000L, 67_108_864L, registry);
        TcpDeviceServer server = new TcpDeviceServer(properties(true, 64, tempDir),
                new FrameParser(), new VitalNormalizer(5_000L, 300_000L), buffer, registry);
        server.start();
        assertThat(server.listening()).isTrue();
        assertThat(server.port()).isPositive();

        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            OutputStream out = socket.getOutputStream();
            out.write((GatewayTestSupport.frame(DEVICE, 1, BASE) + "\n").getBytes(StandardCharsets.UTF_8));
            out.write("BAD|FRAME\n".getBytes(StandardCharsets.UTF_8));
            out.write(("X".repeat(100) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            GatewayTestSupport.awaitTrue("合法帧落盘", Duration.ofSeconds(5),
                    () -> peekSize(buffer, DEVICE) == 1);
            GatewayTestSupport.awaitTrue("非法帧与超长帧计数", Duration.ofSeconds(5),
                    () -> registry.find(GatewayMetricNames.FRAMES_INVALID).counters().stream()
                            .mapToDouble(counter -> counter.count()).sum() >= 2.0d);
        }

        assertThat(buffer.peek(DEVICE, 10)).hasSize(1);
        assertThat(registry.find(GatewayMetricNames.FRAMES_RECEIVED).tag("device", DEVICE).counter().count())
                .isEqualTo(1.0d);
        assertThat(registry.find(GatewayMetricNames.FRAMES_BUFFERED).tag("device", DEVICE).counter().count())
                .isEqualTo(1.0d);
        GatewayTestSupport.awaitTrue("连接断开后计数归零", Duration.ofSeconds(5),
                () -> server.activeConnections() == 0);
        server.close();
        buffer.close();
    }

    @Test
    void capacityRejectionCountsFailedFrame() throws Exception {
        LocalBuffer buffer = LocalBuffer.open(tempDir, 8_388_608L, 600_000L, 10L, registry);
        TcpDeviceServer server = new TcpDeviceServer(properties(true, 4096, tempDir),
                new FrameParser(), new VitalNormalizer(5_000L, 300_000L), buffer, registry);
        server.start();

        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.getOutputStream().write(
                    (GatewayTestSupport.frame(DEVICE, 1, BASE) + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            GatewayTestSupport.awaitTrue("拒收计数", Duration.ofSeconds(5),
                    () -> registry.find(GatewayMetricNames.FRAMES_FAILED).tag("device", DEVICE)
                            .counter() != null);
        }

        assertThat(registry.find(GatewayMetricNames.FRAMES_FAILED).tag("device", DEVICE).counter().count())
                .isEqualTo(1.0d);
        server.close();
        buffer.close();
    }
}
