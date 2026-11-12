package com.anesop.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 设备采集网关入口（S0-09 POC）。
 *
 * <p>独立部署单元：TCP 接入 → 解析 → 标准化 → 本地文件缓冲 → 幂等入库。
 * 设备只采集不控制；网关故障不阻塞临床主流程；断网数据留本地磁盘（≥4h）。
 *
 * <p>POC 依赖：Java 21、PostgreSQL 16；Docker 不可用（EXT-08），测试用嵌入式 PG。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class DeviceGatewayApplication {

    /**
     * 启动入口。
     *
     * @param args 命令行参数（Spring Boot 标准）
     */
    public static void main(String[] args) {
        SpringApplication.run(DeviceGatewayApplication.class, args);
    }
}
