package com.anesop.gateway.support;

import com.anesop.gateway.config.GatewayProperties;
import java.nio.file.Path;

/**
 * 测试用配置实例（不可变 record，显式构造，不依赖 Spring 绑定）。
 */
public final class GatewayPropertiesFixtures {

    private GatewayPropertiesFixtures() {
    }

    /**
     * 默认测试配置。
     *
     * @param bufferDirectory 缓冲目录
     * @return 配置
     */
    public static GatewayProperties of(Path bufferDirectory) {
        return of(bufferDirectory, 500L, 30_000L);
    }

    /**
     * 指定退避参数的测试配置。
     *
     * @param bufferDirectory 缓冲目录
     * @param retryBaseMs     退避基数
     * @param retryMaxMs      退避上限
     * @return 配置
     */
    public static GatewayProperties of(Path bufferDirectory, long retryBaseMs, long retryMaxMs) {
        return new GatewayProperties(
                "GW-TEST",
                "AUTO",
                true,
                false,
                GatewayTestSupport.migrationLocations(),
                5L,
                120L,
                new GatewayProperties.Tcp(true, "127.0.0.1", 0, 4096),
                new GatewayProperties.Buffer(bufferDirectory.toString(), 8_388_608L, 600_000L, 67_108_864L),
                new GatewayProperties.Upload(true, 100L, 50, retryBaseMs, retryMaxMs, 64),
                new GatewayProperties.Database(GatewayTestSupport.jdbcUrl(), "postgres", "", 5));
    }
}
