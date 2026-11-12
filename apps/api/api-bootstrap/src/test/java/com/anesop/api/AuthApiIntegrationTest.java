package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.security.RestAuthenticationEntryPoint;
import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.contracts.auth.model.LoginRequest;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.AuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 认证与权限端到端用例（S0-07）：MockMvc + @SpringBootTest + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：登录成功/失败、缺失/过期/伪造令牌、/me 权限快照、/sessions 分页、越权 403、
 * 刷新轮换、登出吊销与审计归属、参数校验、422 模糊文案、强制改密、并发锁定、锁定过期清零、
 * 401 审计采样。全部账号与口令为 SYNTHETIC 测试数据。
 */
class AuthApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final String DEMO_ADMIN = SyntheticAccounts.DEMO_ADMIN_USERNAME;
    private static final String DEMO_PASSWORD = SyntheticAccounts.DEMO_ADMIN_PASSWORD;
    private static final String TEST_SURGEON = SyntheticAccounts.SURGEON_USERNAME;
    private static final String TEST_SURGEON_PASSWORD = SyntheticAccounts.SURGEON_PASSWORD;
    private static final String DEMO_ADMIN_ID = SyntheticAccounts.DEMO_ADMIN_ID;
    private static final String LOCKOUT_USER = SyntheticAccounts.LOCKOUT_USERNAME;
    private static final String LOCKOUT_PASSWORD = SyntheticAccounts.LOCKOUT_PASSWORD;
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private AuthService authService;

    @Autowired
    private RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    @BeforeEach
    void prepareAccounts() {
        SyntheticAccounts.resetDemoAdmin(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        SyntheticAccounts.ensureLockoutUser(jdbcTemplate);
        restAuthenticationEntryPoint.resetAuditSampling();
    }

    @Test
    void loginReturnsTokensAndAuditsSuccess() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(loginBody(DEMO_ADMIN, DEMO_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(DEMO_ADMIN))
                .andExpect(jsonPath("$.user.roles", hasItem("SYS_ADMIN")))
                .andExpect(jsonPath("$.user.permissions", hasItem("m0:auth:read")))
                .andExpect(jsonPath("$.user.dataScopes[0].type").value("ALL"))
                .andExpect(jsonPath("$.user.mustChangePassword").value(false))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("accessToken").asText()).startsWith("eyJ");
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId).as("服务端必须回写 X-Trace-Id").isNotBlank();

        List<Map<String, Object>> audits = audits(traceId);
        assertThat(audits).as("登录成功必须写一条审计").hasSize(1);
        assertThat(audits.get(0).get("action")).isEqualTo("LOGIN");
        assertThat(audits.get(0).get("result")).isEqualTo(1);
        assertThat(audits.get(0).get("actor_name")).isEqualTo(DEMO_ADMIN);
        assertThat(String.valueOf(audits.get(0).get("actor_id"))).isEqualTo(DEMO_ADMIN_ID);
        assertThat(audits.get(0).get("ip")).isEqualTo("127.0.0.1");
    }

    @Test
    void wrongPasswordReturns401AndAuditsFailureWithoutSecrets() throws Exception {
        String attemptedPassword = "WRONG-PASSWORD-SYNTH-0001";

        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(loginBody(DEMO_ADMIN, attemptedPassword)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"))
                .andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(traceId).isNotBlank();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("traceId").asText())
                .isEqualTo(traceId);

        List<Map<String, Object>> audits = audits(traceId);
        assertThat(audits).as("口令错误必须写一条失败审计").hasSize(1);
        Map<String, Object> row = audits.get(0);
        assertThat(row.get("action")).isEqualTo("LOGIN");
        assertThat(row.get("result")).isEqualTo(0);
        assertThat(row.get("actor_name")).isEqualTo(DEMO_ADMIN);
        String detail = String.valueOf(row.get("detail"));
        assertThat(detail).contains("BadCredentialsException");
        assertThat(detail).as("S-5：reason 为白名单短文案").contains("凭据无效");
        assertThat(detail.toLowerCase())
                .as("审计明细不得包含口令明文或 SQL 关键词")
                .doesNotContain(attemptedPassword.toLowerCase())
                .doesNotContain("\"password\"")
                .doesNotContain("select")
                .doesNotContain("insert")
                .doesNotContain("update ")
                .doesNotContain("jdbc")
                .doesNotContain("sql");
    }

    @Test
    void meReturnsRolesPermissionsAndDataScopes() throws Exception {
        String accessToken = login(DEMO_ADMIN, DEMO_PASSWORD).get("accessToken").asText();

        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(DEMO_ADMIN))
                .andExpect(jsonPath("$.roles", hasItem("SYS_ADMIN")))
                .andExpect(jsonPath("$.permissions", hasItem("m0:auth:read")))
                .andExpect(jsonPath("$.permissions", hasItem("m0:auth:write")))
                .andExpect(jsonPath("$.dataScopes[0].type").value("ALL"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void missingTokenReturns401AndAuditsOnce() throws Exception {
        restAuthenticationEntryPoint.resetAuditSampling();

        MvcResult result = mockMvc.perform(get("/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"))
                .andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");

        assertThat(audits(traceId)).hasSize(1);
        assertThat(audits(traceId).get(0).get("action")).isEqualTo("AUTH_UNAUTHORIZED");
        assertThat(audits(traceId).get(0).get("result")).isEqualTo(0);
    }

    @Test
    void unauthorizedAuditsAreDeduplicatedPerIpAndPath() throws Exception {
        restAuthenticationEntryPoint.resetAuditSampling();

        String firstTrace = mockMvc.perform(get("/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getHeader("X-Trace-Id");
        String secondTrace = mockMvc.perform(get("/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getHeader("X-Trace-Id");

        Integer recorded = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                WHERE action = 'AUTH_UNAUTHORIZED' AND trace_id IN (?, ?)
                """, Integer.class, firstTrace, secondTrace);
        assertThat(recorded).as("S-6：同 IP+路径 1 分钟内只落 1 条 401 审计").isEqualTo(1);
        System.out.printf("[S-6][401-dedup] twoRequests=2 auditRows=%d%n", recorded);
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        String token = tokenSignedWithMainKey(Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKeyReturns401() throws Exception {
        SecretKey otherKey = new SecretKeySpec(
                "another-synthetic-signing-key-0123456789".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtEncoder forgedEncoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(otherKey));
        String token = forgedEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims(Instant.now(), Instant.now().plusSeconds(900))))
                .getTokenValue();

        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sessionsWithoutPermissionReturns403AndAudits() throws Exception {
        JsonNode surgeon = login(TEST_SURGEON, TEST_SURGEON_PASSWORD);

        MvcResult result = mockMvc.perform(get("/v1/auth/sessions")
                        .header("Authorization", "Bearer " + surgeon.get("accessToken").asText()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");

        List<Map<String, Object>> audits = audits(traceId);
        assertThat(audits).as("越权访问必须写一条失败审计").hasSize(1);
        assertThat(audits.get(0).get("action")).isEqualTo("ACCESS_DENIED");
        assertThat(audits.get(0).get("result")).isEqualTo(0);
        assertThat(audits.get(0).get("actor_name")).isEqualTo(TEST_SURGEON);
    }

    @Test
    void sessionsReturnsPagedListForAuthorizedUser() throws Exception {
        String accessToken = login(DEMO_ADMIN, DEMO_PASSWORD).get("accessToken").asText();

        mockMvc.perform(get("/v1/auth/sessions")
                        .param("page", "1")
                        .param("size", "5")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(5))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].sessionId").isNotEmpty())
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
    }

    @Test
    void refreshRotatesTokenAndInvalidatesOldOne() throws Exception {
        JsonNode login = login(DEMO_ADMIN, DEMO_PASSWORD);
        String oldRefresh = login.get("refreshToken").asText();

        MvcResult result = mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        JsonNode refreshed = objectMapper.readTree(result.getResponse().getContentAsString());
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        String newRefresh = refreshed.get("refreshToken").asText();
        assertThat(newRefresh).isNotEqualTo(oldRefresh);
        assertThat(refreshed.get("accessToken").asText()).isNotEqualTo(login.get("accessToken").asText());

        assertThat(audits(traceId)).hasSize(1);
        assertThat(audits(traceId).get(0).get("action")).isEqualTo("TOKEN_REFRESH");
        assertThat(audits(traceId).get(0).get("result")).isEqualTo(1);

        // 旧刷新令牌轮换后立即失效
        mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isUnauthorized());

        // 新刷新令牌仍可用
        mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", newRefresh))))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRevokesTokensAndAuditsActorAndSession() throws Exception {
        JsonNode login = login(DEMO_ADMIN, DEMO_PASSWORD);
        String accessToken = login.get("accessToken").asText();
        String refreshToken = login.get("refreshToken").asText();
        String sessionId = jdbcTemplate.queryForObject("""
                SELECT id::text FROM m0_platform.sys_refresh_token
                WHERE user_id = ?::uuid ORDER BY issued_at DESC LIMIT 1
                """, String.class, DEMO_ADMIN_ID);

        MvcResult logout = mockMvc.perform(post("/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andReturn();

        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isUnauthorized());

        // S-1：登出审计必须带 actor_id / actor_name / resource_id（会话 ID）。
        List<Map<String, Object>> audits = audits(logout.getResponse().getHeader("X-Trace-Id"));
        assertThat(audits).hasSize(1);
        Map<String, Object> row = audits.get(0);
        assertThat(row.get("action")).isEqualTo("LOGOUT");
        assertThat(String.valueOf(row.get("actor_id"))).isEqualTo(DEMO_ADMIN_ID);
        assertThat(row.get("actor_name")).isEqualTo(DEMO_ADMIN);
        assertThat(row.get("resource_id")).isEqualTo(sessionId);
    }

    @Test
    void accountStateFailuresUseGenericMessage() throws Exception {
        // 非本地认证方式（SSO 未开通）→ 422，但文案不得暴露账号状态/认证方式细节。
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", DEMO_ADMIN, "password", DEMO_PASSWORD, "authType", "SSO"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("账号状态或认证方式不允许登录"))
                .andExpect(jsonPath("$.details").isEmpty())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("未开通").doesNotContain("停用").doesNotContain("锁定");
    }

    @Test
    void mustChangePasswordRestrictsEndpointsUntilPasswordChanged() throws Exception {
        SyntheticAccounts.setMustChangePassword(jdbcTemplate, DEMO_ADMIN, true);
        JsonNode login = login(DEMO_ADMIN, DEMO_PASSWORD);
        assertThat(login.get("user").get("mustChangePassword").asBoolean()).isTrue();
        String accessToken = login.get("accessToken").asText();

        mockMvc.perform(get("/v1/auth/sessions").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("首次登录必须先修改口令"));
        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        // 弱口令 → 400；错误旧口令 → 401。
        mockMvc.perform(post("/v1/auth/password").header("Authorization", "Bearer " + accessToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", DEMO_PASSWORD, "newPassword", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("newPassword"));
        mockMvc.perform(post("/v1/auth/password").header("Authorization", "Bearer " + accessToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "WRONG-OLD-PASSWORD", "newPassword", "Anesop@2026-New"))))
                .andExpect(status().isUnauthorized());

        String newPassword = "Anesop@2026-New";
        mockMvc.perform(post("/v1/auth/password").header("Authorization", "Bearer " + accessToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", DEMO_PASSWORD, "newPassword", newPassword))))
                .andExpect(status().isNoContent());

        // 旧口令失效；新口令登录后强制改密解除，可访问 /sessions。
        mockMvc.perform(post("/v1/auth/login").contentType(JSON)
                        .content(loginBody(DEMO_ADMIN, DEMO_PASSWORD)))
                .andExpect(status().isUnauthorized());
        String freshToken = login(DEMO_ADMIN, newPassword).get("accessToken").asText();
        mockMvc.perform(get("/v1/auth/sessions").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentWrongPasswordsLockAccountAtomically() throws Exception {
        int threads = 10;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    LoginRequest request = new LoginRequest();
                    request.setUsername(LOCKOUT_USER);
                    request.setPassword("WRONG-CONCURRENT-PASSWORD");
                    request.setAuthType(LoginRequest.AuthTypeEnum.LOCAL);
                    try {
                        authService.login(request, AuthRequestContext.empty());
                    } catch (AuthenticationException expected) {
                        // 口令错误（或已被并发锁定）均属预期。
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        SyntheticAccounts.LockState state = SyntheticAccounts.lockState(jdbcTemplate, SyntheticAccounts.LOCKOUT_ID);
        System.out.printf("[B-3][concurrent] threads=%d failed_attempts=%d locked_until=%s%n",
                threads, state.failedAttempts(), state.lockedUntil());
        assertThat(state.failedAttempts()).as("10 个并发错误口令必须全部累计（原子 UPDATE）")
                .isGreaterThanOrEqualTo(5);
        assertThat(state.locked()).isTrue();

        // 锁定后即使口令正确也按契约返回 422（模糊文案）。
        mockMvc.perform(post("/v1/auth/login").contentType(JSON)
                        .content(loginBody(LOCKOUT_USER, LOCKOUT_PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("账号状态或认证方式不允许登录"));
    }

    @Test
    void expiredLockResetsFailedAttemptsBeforeNextFailure() {
        jdbcTemplate.update("""
                UPDATE m0_platform.sys_user
                SET failed_attempts = 5, locked_until = now() - interval '1 hour'
                WHERE id = ?::uuid
                """, SyntheticAccounts.LOCKOUT_ID);

        LoginRequest request = new LoginRequest();
        request.setUsername(LOCKOUT_USER);
        request.setPassword("WRONG-AFTER-EXPIRY");
        request.setAuthType(LoginRequest.AuthTypeEnum.LOCAL);
        try {
            authService.login(request, AuthRequestContext.empty());
        } catch (AuthenticationException expected) {
            // 预期口令错误。
        }

        SyntheticAccounts.LockState state = SyntheticAccounts.lockState(jdbcTemplate, SyntheticAccounts.LOCKOUT_ID);
        assertThat(state.failedAttempts()).as("过期锁定应先清零，本次失败重新计为 1").isEqualTo(1);
        assertThat(state.locked()).isFalse();
    }

    @Test
    void invalidLoginPayloadReturns400() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content("{\"username\":\"demo_admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("password"));
    }

    @Test
    void refreshWithUnknownTokenReturns401() throws Exception {
        mockMvc.perform(post("/v1/auth/refresh")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "SYNTH-UNKNOWN-REFRESH"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
    }

    @Test
    void openApiDocumentExposesContractAuthPaths() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode paths = objectMapper.readTree(result.getResponse().getContentAsString()).get("paths");

        assertThat(paths.has("/v1/auth/login")).isTrue();
        assertThat(paths.has("/v1/auth/refresh")).isTrue();
        assertThat(paths.has("/v1/auth/logout")).isTrue();
        assertThat(paths.has("/v1/auth/password")).isTrue();
        assertThat(paths.has("/v1/auth/me")).isTrue();
        assertThat(paths.has("/v1/auth/sessions")).isTrue();
    }

    private String loginBody(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", username, "password", password, "authType", "LOCAL"));
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<Map<String, Object>> audits(String traceId) {
        return jdbcTemplate.queryForList("""
                SELECT action, actor_id::text AS actor_id, actor_name, resource_id,
                       host(ip) AS ip, result, trace_id, detail::text AS detail
                FROM m0_platform.sys_audit_log
                WHERE trace_id = ?
                ORDER BY occurred_at DESC
                """, traceId);
    }

    private String tokenSignedWithMainKey(Instant issuedAt, Instant expiresAt) {
        return jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims(issuedAt, expiresAt)))
                .getTokenValue();
    }

    private JwtClaimsSet claims(Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer("anesop")
                .subject(DEMO_ADMIN_ID)
                .id(UUID.randomUUID().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("username", DEMO_ADMIN)
                .claim("roles", List.of("SYS_ADMIN"))
                .claim("permissions", List.of("m0:auth:read"))
                .build();
    }
}
