package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.contracts.platform.model.MessageType;
import com.anesop.m0.api.platform.MessagePublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M0 公共支撑补全端到端用例（M8-M0-04，契约 platform.yaml 0.2.0）。
 *
 * <p>真实 Spring 上下文 + 真实嵌入式 PostgreSQL 16 + Flyway V27；覆盖字典读/写权限、
 * 消息仅本人与已读、文件上传/下载/越权、类型与大小校验、错误码与审计留痕。
 * 所有数据为 SYNTHETIC（演示账号、合成字典项 SYN 前缀、合成 CSV 内容，无 PHI）。
 */
class M0PlatformApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String SURGEON = SyntheticAccounts.SURGEON_USERNAME;
    private static final String SURGEON_PASSWORD = SyntheticAccounts.SURGEON_PASSWORD;
    private static final String NO_PERM_ID = "e0000000-0000-4000-8000-0000000000e7";
    private static final String NO_PERM_ROLE_ID = "a0000000-0000-4000-8000-0000000000e7";
    private static final String NO_PERM_USERNAME = "synth.m0.none";
    private static final String NO_PERM_PASSWORD = "Synth@2026";
    private static final String SYNTH_FILE_NAME = "SYN-科室排班模板.csv";
    private static final byte[] SYNTH_CSV =
            "operating_room,shift\nOR-01,DAY\nOR-02,NIGHT\n".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MessagePublisher messagePublisher;

    @BeforeEach
    void prepare() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        ensureNoPermissionUser();
        cleanUpFixtures();
    }

    @AfterEach
    void cleanUp() {
        cleanUpFixtures();
    }

    private void cleanUpFixtures() {
        jdbcTemplate.update("DELETE FROM m0_platform.sys_message WHERE recipient_user_id IN "
                + "(?::uuid, ?::uuid, ?::uuid)", SyntheticAccounts.DEMO_ADMIN_ID,
                SyntheticAccounts.DEMO_DEPT_ID, SyntheticAccounts.DEMO_SELF_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_file WHERE uploader_id IN "
                + "(?::uuid, ?::uuid, ?::uuid)", SyntheticAccounts.DEMO_ADMIN_ID,
                SyntheticAccounts.DEMO_DEPT_ID, SyntheticAccounts.DEMO_SELF_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_dict_item WHERE code LIKE 'SYN%'");
    }

    @Test
    void dictReadIsHospitalWideButRequiresPermission() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(get("/v1/dicts").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].type", hasItem("ASA")));

        mockMvc.perform(get("/v1/dicts/ASA").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("ASA"))
                .andExpect(jsonPath("$.items[*].code", hasItem("I")))
                .andExpect(jsonPath("$.items[*].enabled", hasItem(true)));

        mockMvc.perform(get("/v1/dicts/NO_SUCH_TYPE").header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 持 m0:dict:read 但无写权限：includeDisabled 必须 403（停用语义只对管理员开放）。
        String surgeonToken = login(SURGEON, SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/dicts/ASA").param("includeDisabled", "true")
                        .header("Authorization", bearer(surgeonToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        String noPermToken = login(NO_PERM_USERNAME, NO_PERM_PASSWORD);
        mockMvc.perform(get("/v1/dicts").header("Authorization", bearer(noPermToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/messages").header("Authorization", bearer(noPermToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/files").contentType(JSON)
                        .header("Authorization", bearer(noPermToken))
                        .content(objectMapper.writeValueAsString(uploadBody(SYNTH_FILE_NAME, SYNTH_CSV,
                                "TEMPLATE", null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminMaintainsDictItemsAndAuditIsWritten() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult created = mockMvc.perform(post("/v1/dicts/ASA/items")
                        .contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "SYN7", "name", "ASA VII 级（合成）", "sort", 70,
                                "enabled", true, "remark", "SYNTHETIC 示例"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SYN7"))
                .andExpect(jsonPath("$.type").value("ASA"))
                .andReturn();
        JsonNode item = objectMapper.readTree(created.getResponse().getContentAsString());
        String itemId = item.get("itemId").asText();

        // 编码唯一：重复新增 409，且不改写既有项。
        mockMvc.perform(post("/v1/dicts/ASA/items")
                        .contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "SYN7", "name", "重复", "sort", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        mockMvc.perform(put("/v1/dicts/ASA/items/" + itemId)
                        .contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "ASA VII 级（修订）", "sort", 71, "enabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ASA VII 级（修订）"))
                .andExpect(jsonPath("$.code").value("SYN7"));

        mockMvc.perform(delete("/v1/dicts/ASA/items/" + itemId)
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/v1/dicts/ASA").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].code", org.hamcrest.Matchers.not(hasItem("SYN7"))));
        mockMvc.perform(get("/v1/dicts/ASA").param("includeDisabled", "true")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.code == 'SYN7')].enabled", hasItem(false)));

        // 非管理员不能维护字典（V27 仅 SYS_ADMIN 持 m0:dict:write）。
        String surgeonToken = login(SURGEON, SURGEON_PASSWORD);
        mockMvc.perform(post("/v1/dicts/ASA/items")
                        .contentType(JSON)
                        .header("Authorization", bearer(surgeonToken))
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "SYN8", "name", "越权", "sort", 1))))
                .andExpect(status().isForbidden());

        assertThat(auditCount("DICT_ITEM_CREATE", itemId)).isEqualTo(1);
        assertThat(auditCount("DICT_ITEM_UPDATE", itemId)).isEqualTo(1);
        assertThat(auditCount("DICT_ITEM_DISABLE", itemId)).isEqualTo(1);
    }

    @Test
    void messagesAreVisibleOnlyToRecipientAndReadFlowWorks() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String deptToken = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        String adminMessageId = messagePublisher.publish(new MessagePublisher.MessageCommand(
                UUID.fromString(SyntheticAccounts.DEMO_ADMIN_ID), MessageType.SCHEDULE,
                "SCHEDULE_CHANGED", Map.of("roomNo", "OR-01",
                        "startAt", "2026-11-16T08:30:00+08:00"))).toString();
        String deptMessageId = messagePublisher.publish(new MessagePublisher.MessageCommand(
                UUID.fromString(SyntheticAccounts.DEMO_DEPT_ID), MessageType.SYSTEM,
                "SYSTEM_BROADCAST", Map.of())).toString();

        mockMvc.perform(get("/v1/messages").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].messageId", hasItem(adminMessageId)))
                .andExpect(jsonPath("$.items[*].messageId",
                        org.hamcrest.Matchers.not(hasItem(deptMessageId))));

        // 标记他人消息已读：404（不泄露存在性）。
        mockMvc.perform(post("/v1/messages/" + deptMessageId + "/read")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/v1/messages/" + adminMessageId + "/read")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").isNotEmpty());

        mockMvc.perform(get("/v1/messages").param("unreadOnly", "true")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].messageId",
                        org.hamcrest.Matchers.not(hasItem(adminMessageId))));

        mockMvc.perform(post("/v1/messages/read-all").header("Authorization", bearer(deptToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1));
        mockMvc.perform(post("/v1/messages/read-all").header("Authorization", bearer(deptToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(0));

        assertThat(auditCount("MESSAGE_READ", adminMessageId)).isEqualTo(1);
        assertThat(auditCount("MESSAGE_READ_ALL", SyntheticAccounts.DEMO_DEPT_ID)).isEqualTo(2);
    }

    @Test
    void fileUploadDownloadAndVisibilityAreEnforced() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String deptToken = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult uploaded = mockMvc.perform(post("/v1/files").contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(
                                uploadBody(SYNTH_FILE_NAME, SYNTH_CSV, "TEMPLATE", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.startsWith("file-")))
                .andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.endsWith(".csv")))
                .andExpect(jsonPath("$.contentType").value("text/csv"))
                .andExpect(jsonPath("$.scanStatus").value("NOT_SCANNED"))
                .andReturn();
        JsonNode meta = objectMapper.readTree(uploaded.getResponse().getContentAsString());
        String fileId = meta.get("fileId").asText();
        assertThat(meta.get("fileName").asText()).isEqualTo("file-" + fileId + ".csv");
        assertThat(uploaded.getResponse().getContentAsString())
                .as("不得回显客户端原始文件名")
                .doesNotContain(SYNTH_FILE_NAME);
        String expectedSha = sha256Hex(SYNTH_CSV);
        assertThat(meta.get("sha256").asText()).isEqualTo(expectedSha);
        assertThat(uploaded.getResponse().getContentAsString())
                .as("响应不得暴露存储路径")
                .doesNotContain("storageKey");

        MvcResult download = mockMvc.perform(get("/v1/files/" + fileId)
                        .header("Authorization", bearer(deptToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Content-Sha256", expectedSha))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(SYNTH_CSV);

        mockMvc.perform(get("/v1/files/" + fileId + "/meta")
                        .header("Authorization", bearer(deptToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value(fileId));

        // ATTACHMENT 仅上传者与 SYS_ADMIN：演示管理员上传后，麻醉科管理员（非上传者）403。
        MvcResult attachment = mockMvc.perform(post("/v1/files").contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(uploadBody("SYN-附件.txt",
                                "合成附件内容".getBytes(StandardCharsets.UTF_8), "ATTACHMENT",
                                "CASE-SYNTH-1"))))
                .andExpect(status().isCreated())
                .andReturn();
        String attachmentId = objectMapper.readTree(attachment.getResponse().getContentAsString())
                .get("fileId").asText();
        mockMvc.perform(get("/v1/files/" + attachmentId)
                        .header("Authorization", bearer(deptToken)))
                .andExpect(status().isForbidden());

        // 文件不存在 404；Base64 非法 400；大小与实际不一致 422。
        mockMvc.perform(get("/v1/files/" + UUID.randomUUID())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/files").contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fileName", "SYN-bad.csv", "contentType", "text/csv",
                                "sizeBytes", 3, "contentBase64", "not-base64!!",
                                "businessType", "TEMPLATE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(post("/v1/files").contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fileName", "SYN-size.csv", "contentType", "text/csv",
                                "sizeBytes", SYNTH_CSV.length + 1,
                                "contentBase64", Base64.getEncoder().encodeToString(SYNTH_CSV),
                                "businessType", "TEMPLATE"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        // 我的文件：只返回当前用户上传，不泄露他人文件。
        mockMvc.perform(get("/v1/files").param("page", "1").param("size", "50")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].fileId", hasItem(fileId)))
                .andExpect(jsonPath("$.items[*].fileId", hasItem(attachmentId)))
                .andExpect(jsonPath("$.meta.total").isNumber());
        mockMvc.perform(get("/v1/files").header("Authorization", bearer(deptToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].fileId",
                        org.hamcrest.Matchers.not(hasItem(fileId))));

        // schema 校验失败 → 400（contentType 不在枚举、sizeBytes 超 @Max）。
        mockMvc.perform(post("/v1/files").contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fileName", "SYN-dicom.csv", "contentType", "application/dicom",
                                "sizeBytes", 3, "contentBase64", "YWJj",
                                "businessType", "TEMPLATE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(post("/v1/files").contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fileName", "SYN-oversize.csv", "contentType", "text/csv",
                                "sizeBytes", 20971521, "contentBase64", "YWJj",
                                "businessType", "TEMPLATE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        // 自由文本 businessId → 400（拒绝把 PHI 写入业务关联）。
        mockMvc.perform(post("/v1/files").contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(uploadBody("SYN-phi.csv",
                                SYNTH_CSV, "ATTACHMENT", "患者张三 住院号 12345"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));

        // SCHEDULE 附件：业务对象不存在/超范围 → 404（fail-closed），不是 403。
        MvcResult scheduleFile = mockMvc.perform(post("/v1/files").contentType(JSON)
                        .header("Authorization", bearer(adminToken))
                        .content(objectMapper.writeValueAsString(uploadBody("SYN-排程.csv",
                                SYNTH_CSV, "SCHEDULE", UUID.randomUUID().toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        String scheduleFileId = objectMapper.readTree(scheduleFile.getResponse().getContentAsString())
                .get("fileId").asText();
        String surgeonToken = login(SURGEON, SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/files/" + scheduleFileId)
                        .header("Authorization", bearer(surgeonToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(auditCount("FILE_UPLOAD", fileId)).isEqualTo(1);
        assertThat(auditCount("FILE_DOWNLOAD", fileId)).isEqualTo(1);

        // 审计 detail 只含 businessType/sizeBytes/sha256Prefix，不含 fileName/businessId。
        String uploadDetail = jdbcTemplate.queryForObject(
                "SELECT detail::text FROM m0_platform.sys_audit_log "
                        + "WHERE action = 'FILE_UPLOAD' AND resource_id = ?",
                String.class, attachmentId);
        assertThat(uploadDetail)
                .doesNotContain("fileName").doesNotContain("businessId")
                .doesNotContain("CASE-SYNTH-1").doesNotContain("SYN-附件")
                .contains("businessType").contains("sha256Prefix");
    }

    private Map<String, Object> uploadBody(String fileName, byte[] content, String businessType,
            String businessId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fileName", fileName);
        body.put("contentType", "text/csv");
        body.put("sizeBytes", content.length);
        body.put("contentBase64", Base64.getEncoder().encodeToString(content));
        body.put("businessType", businessType);
        if (businessId != null) {
            body.put("businessId", businessId);
        }
        return body;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private int auditCount(String action, String resourceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_audit_log "
                        + "WHERE action = ? AND resource_id = ?",
                Integer.class, action, resourceId);
        return count == null ? 0 : count;
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** 无任何 M0 权限的合成账号（403 用例）；不绑定角色权限，仅登录使用。 */
    private void ensureNoPermissionUser() {
        String hash = new BCryptPasswordEncoder().encode(NO_PERM_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_M0_NO_PERMISSION', '合成无权限角色',
                        'SYNTHETIC：M0 platform 403 用例', 'SELF')
                ON CONFLICT (id) DO NOTHING
                """, NO_PERM_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '合成无权限账号', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, NO_PERM_ID, NO_PERM_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, NO_PERM_ID, NO_PERM_ROLE_ID);
    }
}
