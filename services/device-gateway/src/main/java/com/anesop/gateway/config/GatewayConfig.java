package com.anesop.gateway.config;

import com.anesop.gateway.buffer.LocalBuffer;
import com.anesop.gateway.normalize.VitalNormalizer;
import com.anesop.gateway.protocol.FrameParser;
import com.anesop.gateway.upload.VitalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关组件装配（显式 new，便于单元测试脱离 Spring 上下文构造）。
 *
 * <p>时钟阈值来自配置：默认 5 秒偏差阈值、120 秒补传阈值（见 {@link VitalNormalizer} 规则）。
 */
@Configuration(proxyBeanMethods = false)
public class GatewayConfig {

    /**
     * 帧解析器（无状态）。
     *
     * @return 解析器
     */
    @Bean
    public FrameParser frameParser() {
        return new FrameParser();
    }

    /**
     * 体征标准化器。
     *
     * @param properties 网关配置
     * @return 标准化器
     */
    @Bean
    public VitalNormalizer vitalNormalizer(GatewayProperties properties) {
        return new VitalNormalizer(properties.maxClockSkewSeconds() * 1000L,
                properties.backfillThresholdSeconds() * 1000L);
    }

    /**
     * 本地文件缓冲。
     *
     * @param properties    网关配置
     * @param meterRegistry 指标注册表
     * @return 缓冲实例
     * @throws IOException 缓冲目录不可创建
     */
    @Bean(destroyMethod = "close")
    public LocalBuffer localBuffer(GatewayProperties properties, MeterRegistry meterRegistry) throws IOException {
        GatewayProperties.Buffer config = properties.buffer();
        return LocalBuffer.open(Path.of(config.directory()), config.segmentBytes(), config.segmentIntervalMs(),
                config.maxBytesPerDevice(), meterRegistry);
    }

    /**
     * 体征入库仓储。
     *
     * @param dataSource 数据源
     * @param properties 网关配置
     * @return 仓储
     */
    @Bean
    public VitalRepository vitalRepository(DataSource dataSource, GatewayProperties properties) {
        return new VitalRepository(dataSource, properties.gatewayId(), properties.synthetic());
    }
}
