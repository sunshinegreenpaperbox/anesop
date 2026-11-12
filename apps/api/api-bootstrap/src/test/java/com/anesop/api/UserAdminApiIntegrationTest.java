package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.m0.impl.crypto.PhoneCrypto;
import com.anesop.m0.impl.crypto.PhoneHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 用户管理与手机号加解密写路径（M2-M0-02）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：创建带手机号用户后库中无明文、{@code phone_enc} 可解密、{@code phone_hash} 可精确查询；
 * 列表/详情只返回 {@code phoneMasked}；手机号变更与清除；关键字检索（含手机号哈希命中）；
 * 用户名大小写不敏感唯一 409；非法手机号 400；缺少 read/write 权限 403 + 审计；
 * 管理员重置口令后旧口令失效且 must_change_password=true；审计 detail 不含手机号明文。
 * 全部账号、手机号与口令均为 SYNTHETIC 合成数据。
 */
class UserAdminApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    /** 合成手机号（禁止使用真实号码）。 */
    private static final String PHONE = "13800005678";
    private static final String PHONE_MASKED = "138****5678";
    private static final String NEW_PHONE = "13900001234";
    private static final String INITIAL_PASSWORD = "Synth-Init-2026#A";

    /** S-10：只有 m0:user:read/write 的合成“受限用户管理员”，用于提权拒绝用例。 */
    private static final String LIMITED_ROLE_ID = "a0000000-0000-4000-8000-0000000000e1";
    private static final String LIMITED_USER_ID = "e0000000-0000-4000-8000-0000000000e2";
    private static final String LIMITED_USERNAME = "test.ua.limited";
    private static final String LIMITED_PASSWORD = "Synth@2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PhoneCrypto phoneCrypto;

    @Autowired
    private PhoneHasher phoneHasher;

    @BeforeEach
    void prepareAccounts() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        // 清掉本用例创建的账号，避免重复运行时的唯一约束冲突（只删 test.ua.* 前缀）。
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user WHERE username LIKE 'test.ua.%'");
        ensureLimitedUserAdmin();
    }

    @Test
    void createUserEncryptsPhoneAndNeverReturnsIt() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult created = createUser(adminToken, "test.ua.phone", PHONE);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String body = created.getResponse().getContentAsString();
        JsonNode user = objectMapper.readTree(body);
        String userId = user.get("userId").asText();

        assertThat(user.get("username").asText()).isEqualTo("test.ua.phone");
        assertThat(user.get("phoneMasked").asText()).isEqualTo(PHONE_MASKED);
        assertThat(user.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(user.get("roles").toString()).contains("SYS_ANESTHESIOLOGIST");
        assertThat(body).as("响应不得出现手机号明文/密文/哈希字段")
                .doesNotContain(PHONE)
                .doesNotContain("phoneEnc")
                .doesNotContain("phoneHash")
                .doesNotContain("phone_enc")
                .doesNotContain("password_hash");

        Map<String, Object> row = phoneRow("test.ua.phone");
        String phoneEnc = (String) row.get("phone_enc");
        String phoneHash = (String) row.get("phone_hash");
        String keyId = (String) row.get("phone_key_id");
        assertThat(phoneEnc).isNotBlank().doesNotContain(PHONE);
        assertThat(phoneHash).isEqualTo(phoneHasher.hash(PHONE)).hasSize(64);
        assertThat(keyId).isEqualTo(phoneCrypto.keyId());
        assertThat(row.get("phone_updated_at")).isNotNull();
        assertThat(phoneCrypto.decrypt(phoneEnc, keyId)).as("库内密文必须可解密回合成手机号").isEqualTo(PHONE);
        System.out.printf("[M2-M0-02][create] 库内密文可解密、hash 命中、keyId=%s%n", keyId);

        // 精确查询命中：按 hash 等值而不是手机号明文。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_user WHERE phone_hash = ?",
                Integer.class, phoneHasher.hash(PHONE))).isEqualTo(1);

        // 列表只给 masked：不得出现明文、密文、哈希或明文字段名。
        String listBody = mockMvc.perform(get("/v1/auth/users")
                        .param("keyword", "test.ua.phone")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].phoneMasked").value(PHONE_MASKED))
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain(PHONE).doesNotContain(phoneEnc).doesNotContain(phoneHash)
                .doesNotContain("passwordHash");

        // 详情同样只给 masked。
        String detailBody = mockMvc.perform(get("/v1/auth/users/" + userId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneMasked").value(PHONE_MASKED))
                .andReturn().getResponse().getContentAsString();
        assertThat(detailBody).doesNotContain(PHONE).doesNotContain(phoneEnc).doesNotContain(phoneHash);

        // 手机号精确检索：完整号码走 HMAC 哈希命中，明文不参与查询。
        mockMvc.perform(get("/v1/auth/users")
                        .param("keyword", PHONE)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].username").value("test.ua.phone"));
    }

    @Test
    void updatePhoneReEncryptsAndEmptyPhoneClearsAllFourColumns() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String userId = objectMapper.readTree(createUser(adminToken, "test.ua.update", PHONE)
                .getResponse().getContentAsString()).get("userId").asText();
        String firstEnc = (String) phoneRow("test.ua.update").get("phone_enc");

        mockMvc.perform(put("/v1/auth/users/" + userId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("phone", NEW_PHONE, "displayName", "演示改名（合成）"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneMasked").value("139****1234"))
                .andExpect(jsonPath("$.displayName").value("演示改名（合成）"));

        Map<String, Object> updated = phoneRow("test.ua.update");
        assertThat(updated.get("phone_enc")).isNotEqualTo(firstEnc);
        assertThat((String) updated.get("phone_hash")).isEqualTo(phoneHasher.hash(NEW_PHONE));
        assertThat(phoneCrypto.decrypt((String) updated.get("phone_enc"), (String) updated.get("phone_key_id")))
                .isEqualTo(NEW_PHONE);

        mockMvc.perform(put("/v1/auth/users/" + userId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneMasked").value(nullValue()));

        Map<String, Object> cleared = phoneRow("test.ua.update");
        assertThat(cleared.get("phone_enc")).isNull();
        assertThat(cleared.get("phone_hash")).isNull();
        assertThat(cleared.get("phone_key_id")).isNull();
        assertThat(cleared.get("phone_updated_at")).isNull();
    }

    @Test
    void invalidPhoneAndDuplicateUsernameAreRejected() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(post("/v1/auth/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "test.ua.badphone",
                                "displayName", "合成非法手机号",
                                "initialPassword", INITIAL_PASSWORD,
                                "phone", "12345"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("phone"));

        createUser(adminToken, "test.ua.dup", null);
        // 大小写不敏感唯一：TEST.UA.DUP 与 test.ua.dup 视为同一登录名。
        mockMvc.perform(post("/v1/auth/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "TEST.UA.DUP",
                                "displayName", "合成重复账号",
                                "initialPassword", INITIAL_PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void missingPermissionsReturn403AndWriteAudit() throws Exception {
        String surgeonToken = login(SyntheticAccounts.SURGEON_USERNAME, SyntheticAccounts.SURGEON_PASSWORD);

        MvcResult readDenied = mockMvc.perform(get("/v1/auth/users")
                        .header("Authorization", "Bearer " + surgeonToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andReturn();
        assertThat(audits(readDenied.getResponse().getHeader("X-Trace-Id")))
                .as("缺 m0:user:read 的越权必须写 ACCESS_DENIED 审计")
                .anySatisfy(row -> assertThat(row.get("action")).isEqualTo("ACCESS_DENIED"));

        MvcResult writeDenied = mockMvc.perform(post("/v1/auth/users")
                        .header("Authorization", "Bearer " + surgeonToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "test.ua.forbidden",
                                "displayName", "合成越权账号",
                                "initialPassword", INITIAL_PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andReturn();
        assertThat(audits(writeDenied.getResponse().getHeader("X-Trace-Id")))
                .as("缺 m0:user:write 的越权必须写 ACCESS_DENIED 审计")
                .anySatisfy(row -> assertThat(row.get("action")).isEqualTo("ACCESS_DENIED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_user WHERE username = 'test.ua.forbidden'",
                Integer.class)).as("越权请求不得落库").isZero();
    }

    @Test
    void resetPasswordInvalidatesOldPasswordAndForcesChange() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String userId = objectMapper.readTree(createUser(adminToken, "test.ua.reset", null)
                .getResponse().getContentAsString()).get("userId").asText();

        // 创建后可用初始口令登录（但被强制改密标记限制其他接口）。
        assertThat(login("test.ua.reset", INITIAL_PASSWORD)).isNotBlank();

        MvcResult reset = mockMvc.perform(post("/v1/auth/users/" + userId + "/password-reset")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.initialPassword").isNotEmpty())
                .andReturn();
        JsonNode resetBody = objectMapper.readTree(reset.getResponse().getContentAsString());
        String newPassword = resetBody.get("initialPassword").asText();
        assertThat(newPassword).hasSizeGreaterThanOrEqualTo(12).isNotEqualTo(INITIAL_PASSWORD);
        assertThat(reset.getResponse().getContentAsString()).as("响应只含一次性口令，不含手机号").doesNotContain(PHONE);

        // 旧口令立即失效；新口令可登录且仍为强制改密。
        mockMvc.perform(post("/v1/auth/login").contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "test.ua.reset", "password", INITIAL_PASSWORD))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/auth/login").contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", "test.ua.reset", "password", newPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT must_change_password, password_hash FROM m0_platform.sys_user WHERE id = ?::uuid",
                userId);
        assertThat(row.get("must_change_password")).isEqualTo(true);
        assertThat((String) row.get("password_hash")).startsWith("$2");
        assertThat((String) row.get("password_hash")).as("库内必须是 BCrypt 哈希，不是明文").doesNotContain(newPassword);
    }

    @Test
    void createAndResetAuditsDoNotContainPhonePlaintext() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        MvcResult created = createUser(adminToken, "test.ua.audit", PHONE);
        String userId = objectMapper.readTree(created.getResponse().getContentAsString()).get("userId").asText();

        List<Map<String, Object>> createAudits = jdbcTemplate.queryForList("""
                SELECT action, actor_name, result, resource_id, detail::text AS detail
                FROM m0_platform.sys_audit_log
                WHERE action = 'USER_CREATE' AND actor_name = ?
                ORDER BY occurred_at DESC LIMIT 1
                """, SyntheticAccounts.DEMO_ADMIN_USERNAME);
        assertThat(createAudits).hasSize(1);
        assertThat(createAudits.get(0).get("result")).isEqualTo(1);
        assertThat(createAudits.get(0).get("resource_id"))
                .as("S-5：USER_CREATE 审计必须指向新用户，而不是操作人会话")
                .isEqualTo(userId);
        assertThat((String) createAudits.get(0).get("detail"))
                .as("审计 detail 不得含手机号明文")
                .doesNotContain(PHONE)
                .doesNotContain(PHONE.substring(0, 7));

        mockMvc.perform(put("/v1/auth/users/" + userId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("displayName", "合成改名（审计）"))))
                .andExpect(status().isOk());
        Map<String, Object> updateAudit = jdbcTemplate.queryForMap("""
                SELECT resource_id FROM m0_platform.sys_audit_log
                WHERE action = 'USER_UPDATE' ORDER BY occurred_at DESC LIMIT 1
                """);
        assertThat(updateAudit.get("resource_id"))
                .as("S-5：USER_UPDATE 审计必须指向被更新用户")
                .isEqualTo(userId);

        mockMvc.perform(post("/v1/auth/users/" + userId + "/password-reset")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        Map<String, Object> resetAudit = jdbcTemplate.queryForMap("""
                SELECT resource_id, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE action = 'PASSWORD_RESET' ORDER BY occurred_at DESC LIMIT 1
                """);
        assertThat((String) resetAudit.get("detail")).doesNotContain(PHONE);
        assertThat(resetAudit.get("resource_id"))
                .as("PASSWORD_RESET 审计必须指向被重置的用户")
                .isEqualTo(userId);
    }

    @Test
    void resetPasswordRevokesAllRefreshTokens() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String userId = objectMapper.readTree(createUser(adminToken, "test.ua.session", null)
                .getResponse().getContentAsString()).get("userId").asText();
        JsonNode tokens = loginTokens("test.ua.session", INITIAL_PASSWORD);
        String oldRefreshToken = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/v1/auth/users/" + userId + "/password-reset")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // S-4：重置口令同一事务撤销目标用户全部刷新令牌，旧会话无法续期。
        mockMvc.perform(post("/v1/auth/refresh").contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", oldRefreshToken))))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_refresh_token
                WHERE user_id = ?::uuid AND revoked_at IS NULL
                """, Integer.class, userId)).as("重置后不得残留未撤销刷新令牌").isZero();
    }

    @Test
    void updateEmployeeNoConflictReturns409() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        createUser(adminToken, "test.ua.emp1", null, "SYN-UA-EMP-001");
        String secondId = objectMapper.readTree(createUser(adminToken, "test.ua.emp2", null, "SYN-UA-EMP-002")
                .getResponse().getContentAsString()).get("userId").asText();

        // S-9：改工号撞 uk_sys_user_employee_no 必须 409，不能 500 或静默覆盖。
        mockMvc.perform(put("/v1/auth/users/" + secondId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("employeeNo", "SYN-UA-EMP-001"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT employee_no FROM m0_platform.sys_user WHERE id = ?::uuid",
                String.class, secondId)).isEqualTo("SYN-UA-EMP-002");
    }

    @Test
    void formattedPhoneKeywordMatchesHash() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        createUser(adminToken, "test.ua.formatted", PHONE);

        // S-11：带 +86/空格/短横线的手机号先 normalize 再走 HMAC 等值匹配。
        mockMvc.perform(get("/v1/auth/users")
                        .param("keyword", "+86 138-0000-5678")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].username").value("test.ua.formatted"));
        mockMvc.perform(get("/v1/auth/users")
                        .param("keyword", "138 0000 5678")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void roleEscalationAndSelfRoleChangeAreForbidden() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String targetId = objectMapper.readTree(createUser(adminToken, "test.ua.target", null)
                .getResponse().getContentAsString()).get("userId").asText();
        String limitedToken = login(LIMITED_USERNAME, LIMITED_PASSWORD);

        // S-10：受限管理员把目标用户升为 SYS_ADMIN（权限点超集）→ 403 + ACCESS_DENIED 审计。
        MvcResult escalation = mockMvc.perform(put("/v1/auth/users/" + targetId)
                        .header("Authorization", "Bearer " + limitedToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("roles", List.of("SYS_ADMIN")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andReturn();
        assertThat(audits(escalation.getResponse().getHeader("X-Trace-Id")))
                .anySatisfy(row -> assertThat(row.get("action")).isEqualTo("ACCESS_DENIED"));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_user_role ur
                JOIN m0_platform.sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = ?::uuid AND r.code = 'SYS_ADMIN'
                """, Integer.class, targetId)).as("提权请求不得落库").isZero();

        // S-10：禁止修改自己的角色。
        MvcResult selfChange = mockMvc.perform(put("/v1/auth/users/" + LIMITED_USER_ID)
                        .header("Authorization", "Bearer " + limitedToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("roles", List.of("SYS_ANESTHESIOLOGIST")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"))
                .andReturn();
        assertThat(audits(selfChange.getResponse().getHeader("X-Trace-Id")))
                .anySatisfy(row -> assertThat(row.get("action")).isEqualTo("ACCESS_DENIED"));
    }

    @Test
    void unknownUserReturns404() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(get("/v1/auth/users/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private MvcResult createUser(String adminToken, String username, String phone) throws Exception {
        return createUser(adminToken, username, phone, null);
    }

    private MvcResult createUser(String adminToken, String username, String phone, String employeeNo)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "合成测试用户");
        body.put("initialPassword", INITIAL_PASSWORD);
        body.put("roles", List.of("SYS_ANESTHESIOLOGIST"));
        if (employeeNo != null) {
            body.put("employeeNo", employeeNo);
        }
        if (phone != null) {
            body.put("phone", phone);
        }
        return mockMvc.perform(post("/v1/auth/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.roles", hasItem("SYS_ANESTHESIOLOGIST")))
                .andReturn();
    }

    private String login(String username, String password) throws Exception {
        return loginTokens(username, password).get("accessToken").asText();
    }

    private JsonNode loginTokens(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * S-10 用例：合成“受限用户管理员”，只授予 m0:user:read / m0:user:write 两个权限点。
     * 角色与账号均由测试直接写入（幂等），不改迁移基线、不使用真实数据。
     */
    private void ensureLimitedUserAdmin() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_USER_ADMIN_LIMITED', '合成受限用户管理员',
                        'SYNTHETIC：S-10 提权校验用例', 'ALL')
                ON CONFLICT (id) DO NOTHING
                """, LIMITED_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试受限用户管理员（合成）', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, LIMITED_USER_ID, LIMITED_USERNAME, new BCryptPasswordEncoder().encode(LIMITED_PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, LIMITED_USER_ID, LIMITED_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                VALUES (?::uuid, 'b0000000-0000-4000-8000-000000000022') ON CONFLICT DO NOTHING
                """, LIMITED_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                VALUES (?::uuid, 'b0000000-0000-4000-8000-000000000023') ON CONFLICT DO NOTHING
                """, LIMITED_ROLE_ID);
    }

    private Map<String, Object> phoneRow(String username) {
        return jdbcTemplate.queryForMap("""
                SELECT phone_enc, phone_hash, phone_key_id, phone_updated_at
                FROM m0_platform.sys_user WHERE username = ?
                """, username);
    }

    private List<Map<String, Object>> audits(String traceId) {
        return jdbcTemplate.queryForList("""
                SELECT action, result, actor_name, resource_type
                FROM m0_platform.sys_audit_log WHERE trace_id = ?
                """, traceId);
    }
}
