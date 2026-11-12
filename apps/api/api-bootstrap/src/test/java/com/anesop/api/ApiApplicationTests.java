package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.api.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 上下文可加载：验证 Spring 装配点本身成立（S0-07 起含 Security/MyBatis/Flyway 运行时）。
 */
class ApiApplicationTests extends AbstractApiIntegrationTest {

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
