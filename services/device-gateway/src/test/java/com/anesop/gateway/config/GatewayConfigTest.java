package com.anesop.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.anesop.gateway.upload.PlatformVitalReporter;
import com.anesop.gateway.upload.VitalRepository;
import com.anesop.gateway.upload.VitalSink;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * 上行适配器开关（M3-M8-01）：默认 jdbc 直连，配置 http 时切换为平台上报；
 * 缺 url/token 的 http 配置在构造配置对象时即失败（fail-closed）。
 */
class GatewayConfigTest {

    @Test
    void defaultsToJdbcRepository() {
        GatewayProperties properties = properties(new GatewayProperties.Platform("jdbc", "", "", 3000));

        VitalSink sink = new GatewayConfig().vitalSink(mock(DataSource.class), properties);

        assertThat(sink).isInstanceOf(VitalRepository.class);
    }

    @Test
    void switchesToPlatformReporterInHttpMode() {
        GatewayProperties properties = properties(new GatewayProperties.Platform(
                "http", "https://anesop.local/api", "synth-gateway-token", 2000));

        VitalSink sink = new GatewayConfig().vitalSink(mock(DataSource.class), properties);

        assertThat(sink).isInstanceOf(PlatformVitalReporter.class);
    }

    @Test
    void rejectsHttpModeWithoutUrlOrToken() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties(
                        new GatewayProperties.Platform("http", "", "token", 1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform.url");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties(
                        new GatewayProperties.Platform("http", "https://anesop.local/api", "", 1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fail-closed");
    }

    private static GatewayProperties properties(GatewayProperties.Platform platform) {
        return new GatewayProperties("GW-TEST", "AUTO", true, false, "filesystem:./db/migration",
                5L, 120L,
                new GatewayProperties.Tcp(false, "127.0.0.1", 0, 4096),
                new GatewayProperties.Buffer("./target/test-buffer", 8_388_608L, 600_000L, 67_108_864L),
                new GatewayProperties.Upload(true, 100L, 50, 500L, 30_000L, 64),
                new GatewayProperties.Database("jdbc:postgresql://localhost:5432/unused", "u", "", 5),
                platform);
    }
}
