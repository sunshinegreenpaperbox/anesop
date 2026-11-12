package com.anesop.api.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * API 集成测试基类：真实 Spring 上下文 + 真实嵌入式 PostgreSQL 16 + Flyway。
 *
 * <p>所有子类共享同一组注解，Spring TestContext 缓存只创建一个上下文；数据库指向
 * {@link EmbeddedPostgresSupport}。test profile 的 Flyway locations 为
 * {@code classpath:db/migration + classpath:db/seed/demo}（演示账号 SYNTHETIC）。
 *
 * <p>限流在基类中放大容量，避免同一 JVM 内大量登录用例触发 429；专门的
 * {@code RateLimitIntegrationTest} 用独立上下文验证低阈值 429。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractApiIntegrationTest {

    /**
     * 测试用固定 HS256 密钥：Base64（解码后 44 字节），仅测试类路径，不是生产密钥。
     * 由 {@code base64("synthetic-test-only-signing-key-0123456789")} 生成，不含占位词。
     */
    public static final String TEST_JWT_SECRET = "c3ludGhldGljLXRlc3Qtb25seS1zaWduaW5nLWtleS0wMTIzNDU2Nzg5";

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresSupport::jdbcUrl);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("anesop.security.jwt.secret", () -> TEST_JWT_SECRET);
        // 基类用例聚焦认证逻辑，限流阈值放大；限流本身由 RateLimitIntegrationTest 用低阈值验证。
        registry.add("anesop.security.rate-limit.capacity", () -> "100000");
        registry.add("anesop.security.rate-limit.refill-tokens", () -> "100000");
    }
}
