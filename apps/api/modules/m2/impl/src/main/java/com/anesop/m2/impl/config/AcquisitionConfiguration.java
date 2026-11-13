package com.anesop.m2.impl.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * M2 配置装配（M3-M8-01 设备采集 + M3-M2-01 记录单 + M3-M2-02 预警评估）：启用配置属性绑定。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AcquisitionProperties.class, RecordProperties.class,
        AlertProperties.class})
public class AcquisitionConfiguration {
}
