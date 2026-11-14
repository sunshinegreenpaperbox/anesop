package com.anesop.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ANESOP 业务应用唯一启动类。
 *
 * <p>模块化单体的装配点：扫描 com.anesop 下的模块组件，不直接访问任何模块 impl 的内部实现。
 * S0-07 起在此装配 M0 持久层 Mapper（MapperScan），M2-M1-01 起追加 M1 Mapper，
 * M2-M4-01 起追加 M2 Mapper；M4-M15-01 起追加 M4 药品/五专 Mapper；
 * 生产启动断言见 {@code ProductionDemoAccountGuard}。
 */
@SpringBootApplication(scanBasePackages = "com.anesop")
@MapperScan({"com.anesop.m0.impl.persistence.mapper", "com.anesop.m1.impl.persistence.mapper",
        "com.anesop.m2.impl.persistence.mapper", "com.anesop.m4.impl.persistence.mapper"})
@EnableScheduling
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
