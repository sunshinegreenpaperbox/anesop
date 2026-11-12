package com.anesop.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ANESOP 业务应用唯一启动类。
 *
 * <p>模块化单体的装配点：扫描 com.anesop 下的模块组件，不直接访问任何模块 impl 的内部实现。
 */
@SpringBootApplication
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
