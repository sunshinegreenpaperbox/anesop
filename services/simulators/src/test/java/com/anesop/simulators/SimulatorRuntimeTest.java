package com.anesop.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.simulators.support.TestHttp;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 运行时：三服务同起、JSON 状态、端口冲突失败即降级关闭。 */
class SimulatorRuntimeTest {

    @Test
    void startsAllThreeServersAndReportsJsonStatus(@TempDir Path tempDir) throws Exception {
        SimulatorRuntime runtime = SimulatorRuntime.start(SimulatorConfig.defaults()
                .withPorts(0, 0, 0));
        try {
            String status = runtime.statusJson();
            JsonNode json = SimulatorJson.mapper().readTree(status);

            assertThat(json.path("status").asText()).isEqualTo("UP");
            assertThat(json.path("synthetic").asBoolean()).isTrue();
            assertThat(json.path("pid").asLong()).isPositive();
            assertThat(json.path("servers").size()).isEqualTo(3);
            assertThat(json.path("servers").get(0).path("name").asText()).isEqualTo("his");
            assertThat(json.path("servers").get(0).path("port").asInt()).isPositive();
            assertThat(json.path("servers").get(0).path("baseUrl").asText())
                    .startsWith("http://127.0.0.1:");

            Path statusFile = tempDir.resolve("nested/status.json");
            runtime.writeStatus(statusFile);
            assertThat(Files.readString(statusFile, StandardCharsets.UTF_8)).contains("\"status\":\"UP\"");
        } finally {
            runtime.close();
        }

        JsonNode down = SimulatorJson.mapper().readTree(runtime.statusJson());
        assertThat(down.path("status").asText()).isEqualTo("DOWN");
    }

    @Test
    void nullConfigAndIllegalPortAreRejectedBeforeBinding() {
        assertThatThrownBy(() -> SimulatorRuntime.start(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("配置不得为空");

        SimulatorConfig illegal = SimulatorConfig.defaults();
        illegal.setHis(new SimulatorConfig.Endpoint(70000));
        assertThatThrownBy(() -> SimulatorRuntime.start(illegal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("his.port");
    }

    @Test
    void portConflictFailsFastAndReleasesAlreadyStartedServers() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            SimulatorConfig config = SimulatorConfig.defaults()
                    .withPorts(0, occupied.getLocalPort(), 0);

            assertThatThrownBy(() -> SimulatorRuntime.start(config))
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void exposesStartedEndpointsForCallers() throws Exception {
        SimulatorRuntime runtime = SimulatorRuntime.start(SimulatorConfig.defaults()
                .withPorts(0, 0, 0));
        try {
            assertThat(runtime.his().baseUrl()).contains(String.valueOf(runtime.his().port()));
            assertThat(runtime.pharmacy().port()).isPositive();
            assertThat(runtime.spd().port()).isPositive();
            assertThat(TestHttp.get(runtime.his().baseUrl() + "/his/v1/surgery-applications?count=1")
                    .status()).isEqualTo(200);
        } finally {
            runtime.close();
        }
    }
}
