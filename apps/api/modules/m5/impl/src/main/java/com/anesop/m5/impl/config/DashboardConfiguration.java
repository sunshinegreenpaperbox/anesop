package com.anesop.m5.impl.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 看板域配置装配（M5-M18-01）。
 *
 * <p>只装配口径阈值（{@link DashboardProperties}）；不装配任何看板结果表或缓存——
 * 实时态势与 KPI 始终对源数据只读聚合。
 */
@Configuration
@EnableConfigurationProperties(DashboardProperties.class)
public class DashboardConfiguration {
}
