package com.anesop.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 进程入口：参数解析、配置加载、--self-test 与用法。 */
class SimulatorsMainTest {

    @Test
    void parsesConfigFileAndPortOverrides(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("simulators.json");
        Files.writeString(configFile, """
                {
                  "his": {"port": 28081, "latencyMs": 5, "failFirstRequests": 2,
                          "errorStatus": 503, "failureMode": "HTTP_ERROR"},
                  "pharmacy": {"port": 28082},
                  "spd": {"port": 28083, "failureMode": "MALFORMED_JSON"}
                }
                """, StandardCharsets.UTF_8);

        SimulatorsMain.Options options = SimulatorsMain.Options.parse(new String[] {
            "--config=" + configFile,
            "--his-port=0",
            "--status-file=" + tempDir.resolve("status.json"),
            "--self-test"
        });

        assertThat(options.toConfig().getHis().getPort()).isZero();
        assertThat(options.toConfig().getPharmacy().getPort()).isEqualTo(28082);
        assertThat(options.toConfig().getSpd().getFailureMode()).isEqualTo(FailureMode.MALFORMED_JSON);
        assertThat(options.toConfig().getHis().getLatencyMs()).isEqualTo(5);
        assertThat(options.toConfig().getHis().getFailFirstRequests()).isEqualTo(2);
        assertThat(options.statusFile()).isEqualTo(tempDir.resolve("status.json"));
        assertThat(options.selfTest()).isTrue();
        assertThat(options.help()).isFalse();
    }

    @Test
    void defaultsApplyWhenNoConfigGiven() throws Exception {
        SimulatorsMain.Options options = SimulatorsMain.Options.parse(new String[] {"--pharmacy-port=0"});

        assertThat(options.toConfig().getHis().getPort()).isEqualTo(SimulatorConfig.DEFAULT_HIS_PORT);
        assertThat(options.toConfig().getPharmacy().getPort()).isZero();
        assertThat(options.toConfig().getSpd().getPort()).isEqualTo(SimulatorConfig.DEFAULT_SPD_PORT);
        assertThat(options.selfTest()).isFalse();
        assertThat(options.statusFile()).isNull();
    }

    @Test
    void helpAndSelfTestAreRecognized() throws Exception {
        assertThat(SimulatorsMain.Options.parse(new String[] {"--help"}).help()).isTrue();
        assertThat(SimulatorsMain.Options.parse(new String[] {"-h"}).help()).isTrue();
        assertThat(SimulatorsMain.usage()).contains("HIS", "药房", "SPD");
    }

    @Test
    void unknownAndNonNumericArgumentsFailClosed() {
        assertThatThrownBy(() -> SimulatorsMain.Options.parse(new String[] {"--nope"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知参数");
        assertThatThrownBy(() -> SimulatorsMain.Options.parse(new String[] {"--his-port=abc"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是整数");
        SimulatorsMain.Options missingConfig = SimulatorsMain.Options.parse(
                new String[] {"--config=/no/such/file.json"});
        assertThatThrownBy(missingConfig::toConfig).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void selfTestStartsAndStopsWithoutBlocking() throws Exception {
        SimulatorsMain.main(new String[] {
            "--his-port=0", "--pharmacy-port=0", "--spd-port=0", "--self-test"
        });

        // 自检返回即代表三个服务已启动并关闭；再次自检证明端口已释放。
        SimulatorsMain.main(new String[] {
            "--his-port=0", "--pharmacy-port=0", "--spd-port=0", "--self-test"
        });
    }
}
