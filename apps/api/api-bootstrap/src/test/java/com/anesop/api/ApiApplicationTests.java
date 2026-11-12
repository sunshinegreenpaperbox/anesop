package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 上下文可加载：验证 Spring 装配点本身成立（不依赖数据库/中间件）。
 */
@SpringBootTest
class ApiApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
        assertThat(applicationContext.getBean(ApiApplication.class)).isNotNull();
        assertThat(applicationContext.getEnvironment().getProperty("spring.application.name"))
                .isEqualTo("anesop-api");
    }
}
