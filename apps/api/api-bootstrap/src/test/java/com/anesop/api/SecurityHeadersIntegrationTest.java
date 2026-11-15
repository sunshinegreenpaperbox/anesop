package com.anesop.api;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M5-M19-02：API 层安全响应头（与 nginx 同等语义）。
 *
 * <p>断言 {@code X-Content-Type-Options}、{@code X-Frame-Options DENY}、
 * {@code Referrer-Policy}、{@code Permissions-Policy}、最小化 CSP；HSTS 仅在
 * HTTPS 请求（{@code secure(true)}）下发，明文请求不得声明 HSTS。错误响应（401）
 * 同样带安全头，避免异常路径漏头。
 */
class SecurityHeadersIntegrationTest extends AbstractApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiResponsesCarrySecurityHeaders() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Permissions-Policy", allOf(
                        containsString("camera=()"),
                        containsString("microphone=()"),
                        containsString("geolocation=()"))))
                .andExpect(header().string("Content-Security-Policy", allOf(
                        containsString("default-src 'none'"),
                        containsString("frame-ancestors 'none'"),
                        containsString("base-uri 'none'"),
                        containsString("form-action 'none'"))))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void hstsIsOnlySentForSecureRequests() throws Exception {
        mockMvc.perform(get("/actuator/health").secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", allOf(
                        containsString("max-age=31536000"),
                        containsString("includeSubDomains"))));
    }

    @Test
    void unauthorizedErrorAlsoCarriesSecurityHeaders() throws Exception {
        mockMvc.perform(get("/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
