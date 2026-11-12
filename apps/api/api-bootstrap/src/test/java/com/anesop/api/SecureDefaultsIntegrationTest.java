package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * B-2：prod profile 安全收紧的真实上下文用例（嵌入式 PG16 + 独立数据库）。
 *
 * <p>验证：springdoc 关闭（/v3/api-docs 404）、actuator 只暴露 health/info
 * （metrics 对匿名请求不可用）、健康检查仍可用。数据库为空（生产 locations 不含演示种子），
 * 启动守卫通过。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class SecureDefaultsIntegrationTest {

    private static final String DATABASE = "anesop_prod_secure";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl(DATABASE));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("anesop.security.jwt.secret", () -> "c3ludGhldGljLXRlc3Qtb25seS1zaWduaW5nLWtleS0wMTIzNDU2Nzg5");
    }

    @AfterAll
    static void dropDatabase() {
        EmbeddedPostgresSupport.dropDatabase(DATABASE);
    }

    @Test
    void springdocIsDisabledInProd() throws Exception {
        int apiDocs = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getStatus();
        int swaggerUi = mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getStatus();
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
        System.out.printf("[B-2][prod] GET /v3/api-docs -> %d；/swagger-ui/index.html -> %d%n",
                apiDocs, swaggerUi);
    }

    @Test
    void actuatorExposesOnlyHealthAndInfo() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        MvcResult info = mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult metrics = mockMvc.perform(get("/actuator/metrics")).andReturn();
        assertThat(metrics.getResponse().getStatus())
                .as("B-2：metrics 未暴露且未认证（401/404 均表示不可访问）")
                .isIn(401, 404);
        MvcResult prometheus = mockMvc.perform(get("/actuator/prometheus")).andReturn();
        assertThat(prometheus.getResponse().getStatus()).isIn(401, 404);
        System.out.printf("[B-2][prod] actuator health=200 info=%d metrics=%d prometheus=%d%n",
                info.getResponse().getStatus(), metrics.getResponse().getStatus(),
                prometheus.getResponse().getStatus());
    }
}
