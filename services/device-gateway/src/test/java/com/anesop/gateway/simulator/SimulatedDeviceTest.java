package com.anesop.gateway.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 模拟设备单测（M2-M0-03）：合成帧格式稳定、main 按参数推送并可被 TCP 接收。
 */
class SimulatedDeviceTest {

    @Test
    void frameIsSyntheticAndDeterministic() {
        Instant measuredAt = Instant.parse("2026-11-13T00:00:00Z");

        String line = SimulatedDevice.frame("SIM-MON-99", 7, measuredAt);

        assertThat(line).startsWith("DEV|SIM-MON-99|7|2026-11-13T00:00:00Z|");
        assertThat(line).endsWith("|0");
        assertThat(line.split("\\|")).hasSize(11);
        assertThat(SimulatedDevice.frame("SIM-MON-99", 7, measuredAt)).isEqualTo(line);
    }

    @Test
    void mainPushesRequestedFramesToListeningSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread client = new Thread(() -> {
                try {
                    SimulatedDevice.main(new String[] {"127.0.0.1", String.valueOf(port),
                            "SIM-E2E-01", "2", "1"});
                } catch (IOException | InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            client.start();
            try (Socket accepted = server.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            accepted.getInputStream(), StandardCharsets.UTF_8))) {
                List<String> lines = reader.lines().toList();
                assertThat(lines).hasSize(2);
                assertThat(lines.get(0)).contains("|SIM-E2E-01|1|");
                assertThat(lines.get(1)).contains("|SIM-E2E-01|2|");
            }
            // SimulatedDevice.main 每帧真实 sleep 1s（2 帧），10s 仅为并发负载下的宽余量
            client.join(10_000L);
            assertThat(client.isAlive()).isFalse();
        }
    }

    @Test
    void mainFailsWhenTargetPortIsClosed() {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        assertThatThrownBy(() -> SimulatedDevice.main(
                new String[] {"127.0.0.1", String.valueOf(closedPort)}))
                .isInstanceOf(IOException.class);
    }
}
