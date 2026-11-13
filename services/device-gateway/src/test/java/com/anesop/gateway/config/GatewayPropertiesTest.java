package com.anesop.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 网关配置自检：错配置必须在启动期失败，而不是运行期每帧入库失败、数据积压。
 */
class GatewayPropertiesTest {

    @Test
    void acceptsValidConfiguration() {
        GatewayProperties properties = properties("AUTO", 5000L, 120_000L, 50, 500L, 30_000L);

        assertThat(properties.gatewayId()).isEqualTo("GW-TEST");
        assertThat(properties.tcp().port()).isZero();
        assertThat(properties.buffer().maxBytesPerDevice()).isPositive();
    }

    @Test
    void rejectsUnknownSource() {
        assertThatThrownBy(() -> properties("SYNTHETIC", 5000L, 120_000L, 50, 500L, 30_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void rejectsInvertedClockThresholds() {
        assertThatThrownBy(() -> properties("AUTO", 120_000L, 5000L, 50, 500L, 30_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时钟阈值");
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> properties("AUTO", 5000L, 120_000L, 0, 500L, 30_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upload");
    }

    private static GatewayProperties properties(String source, long maxSkew, long backfill,
            int batchSize, long retryBase, long retryMax) {
        return new GatewayProperties(
                "GW-TEST",
                source,
                true,
                false,
                "filesystem:/tmp/db/migration",
                maxSkew,
                backfill,
                new GatewayProperties.Tcp(true, "127.0.0.1", 0, 4096),
                new GatewayProperties.Buffer("/tmp/buffer", 8_388_608L, 600_000L, 67_108_864L),
                new GatewayProperties.Upload(true, 1000L, batchSize, retryBase, retryMax, 64),
                new GatewayProperties.Database("jdbc:postgresql://localhost:5432/anesop", "anesop", "", 5),
                new GatewayProperties.Platform("jdbc", "", "", 3000));
    }
}
