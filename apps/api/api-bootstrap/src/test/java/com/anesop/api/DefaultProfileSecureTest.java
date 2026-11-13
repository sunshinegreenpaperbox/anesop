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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * B-2：**不激活任何 profile** 时的默认安全基线（漏设 SPRING_PROFILES_ACTIVE 的场景）。
 *
 * <p>默认 application.yml 即 fail-closed：springdoc 关闭（404）、actuator 只暴露 health/info、
 * Flyway 只含 db/migration（无演示种子）、demo 账号与自动生成密钥默认禁止。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DefaultProfileSecureTest {

    private static final String DATABASE = "anesop_default_secure";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl(DATABASE));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("anesop.security.jwt.secret", () -> "c3ludGhldGljLXRlc3Qtb25seS1zaWduaW5nLWtleS0wMTIzNDU2Nzg5");
        // M2-M0-02：默认（不激活 profile）同样按生产语义拒绝生成随机字段密钥，需显式提供。
        registry.add("anesop.security.field-key", () -> "CD1yp9wWS4C16iRZjsP4Mmec0QtAdarfGU6DuO0nXJE=");
        registry.add("anesop.security.phone-pepper",
                () -> "DlGU1x9ipegwc7b5QYTHD1KV2CBjpukxdLf6QoXIEFOW2SFkp+oydbj7Q4bJEVSX");
        // M1（B-2）：住院号 pepper 生产同样 fail-closed，此处注入合成值。
        registry.add("anesop.m1.inpatient-pepper.value",
                () -> "c3ludGhldGljLXBlcHBlci0wMTIzNDU2Nzg5LWFuZXNvcC1tMS10ZXN0");
    }

    @AfterAll
    static void dropDatabase() {
        EmbeddedPostgresSupport.dropDatabase(DATABASE);
    }

    @Test
    void defaultProfileDisablesSpringdoc() throws Exception {
        int status = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getStatus();
        System.out.printf("[B-2][default] GET /v3/api-docs -> %d%n", status);
    }

    @Test
    void defaultProfileExposesOnlyHealthAndInfo() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());

        MvcResult metrics = mockMvc.perform(get("/actuator/metrics")).andReturn();
        assertThat(metrics.getResponse().getStatus()).isIn(401, 404);
        System.out.printf("[B-2][default] actuator health=200 info=200 metrics=%d%n",
                metrics.getResponse().getStatus());
    }
}
