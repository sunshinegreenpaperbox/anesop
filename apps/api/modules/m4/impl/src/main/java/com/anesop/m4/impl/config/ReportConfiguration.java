package com.anesop.m4.impl.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 报表域配置装配（M4-M16-01）。
 *
 * <p>{@link ReportProperties} 提供台次权重的可选覆盖（默认 1.0，绩效口径【待确认】）；
 * 不装配任何报表结果表或缓存，报表始终对源数据只读聚合。
 */
@Configuration
@EnableConfigurationProperties(ReportProperties.class)
public class ReportConfiguration {
}
