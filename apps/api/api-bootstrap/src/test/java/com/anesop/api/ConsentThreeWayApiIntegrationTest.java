package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.m2.api.threeway.ThreeWayCheckService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 知情同意与三方核查（M2-M5-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16（Docker 不可用）。
 *
 * <p>覆盖：V11 结构/权限/注释/触发器、模板版本不可变、同意状态机（签署/拒绝/撤销/重复签署 409）、
 * CA 占位签名持久化与“不存原文”、三方核查 append-only 与完成判定（M3 锁单集成点）、
 * 权限 403 / 跨科室 404、审计留痕（detail 无签署人姓名与原因原文）。全部数据为 SYNTHETIC。
 */
class ConsentThreeWayApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    private static final String PATIENT_ANES = "53000000-0000-4000-8000-000000000011";
    private static final String HIS_ANES = "SYNTH-HIS-P-9301";
    private static final String GENERAL_V2_ID = "b1000000-0000-4000-8000-000000000002";
    private static final String SEDATION_V1_ID = "b1000000-0000-4000-8000-000000000003";

    private static final String CONSENT_DEPT_USER_ID = "e0000000-0000-4000-8000-0000000000d2";
    private static final String CONSENT_DEPT_USERNAME = "test.consent.dept";
    private static final String CONSENT_DEPT_PASSWORD = "Synth@2026";
    private static final String CONSENT_DEPT_ROLE_ID = "a0000000-0000-4000-8000-0000000000f3";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ThreeWayCheckService threeWayCheckService;

    @BeforeEach
    void prepareData() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        ensureConsentDeptUser();
        clearClinicalRows();
        resetTemplates();
        jdbcTemplate.update("DELETE FROM m1_sched.notification_outbox");
        jdbcTemplate.execute("ALTER TABLE m1_sched.schedule_change_request DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m1_sched.schedule_change_request");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.schedule_change_request ENABLE TRIGGER USER");
        }
        jdbcTemplate.update("DELETE FROM m1_sched.schedule");
        jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m1_sched.application_status_history");
            jdbcTemplate.update("DELETE FROM m1_sched.surgery_application");
            jdbcTemplate.update("DELETE FROM m1_sched.patient_snapshot");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history ENABLE TRIGGER USER");
        }
        insertPatient();
    }

    @AfterEach
    void cleanUpAfterTest() {
        // 同意/核查记录 FK 指向申请：必须在其他测试类清理申请前删除（触发器临时停用）。
        clearClinicalRows();
    }

    // -------------------------------------------------------- 模板版本不可变 --

    @Test
    void consentTemplatesAreListedAndImmutable() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(get("/v1/anesthesia/consent-templates")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].templateCode").value("ANES_GENERAL"))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[1].version").value(1))
                .andExpect(jsonPath("$.items[2].templateCode").value("ANES_SEDATION"));

        mockMvc.perform(get("/v1/anesthesia/consent-templates")
                        .header("Authorization", "Bearer " + admin)
                        .param("templateCode", "ANES_SEDATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].templateCode").value("ANES_SEDATION"));

        Throwable updateFailure = catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_template SET title = ? WHERE id = ?::uuid",
                "篡改标题", GENERAL_V2_ID));
        assertSqlState55000(updateFailure, "append-only");
        Throwable deleteFailure = catchThrowable(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.consent_template WHERE id = ?::uuid", GENERAL_V2_ID));
        assertSqlState55000(deleteFailure, "append-only");
        // consent_template 被 consent_record 外键引用：TRUNCATE 先被 PG 以 0A000 拒绝
        // （比触发器更早），两种拒绝都保证模板不会被批量清除。
        Throwable truncateFailure = catchThrowable(() -> jdbcTemplate.execute(
                "TRUNCATE m2_anes.consent_template"));
        assertThat(truncateFailure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(sqlStateOf(truncateFailure)).isIn("55000", "0A000");

        System.out.println("[M2-M5-01][template] templates=3 versions=GENERAL2/GENERAL1/SEDATION1 "
                + "update/delete=55000 truncate=0A000(FK)");
    }

    // ------------------------------------------------------ 同意签署与撤销 --

    @Test
    void consentSignRevokeLifecyclePersistsSimulatedSignatureAndAudit() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M5-APP-0001");

        MvcResult created = mockMvc.perform(post("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(consentBody(applicationId, GENERAL_V2_ID, 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_SIGN"))
                .andExpect(jsonPath("$.caStatus").value("PENDING"))
                .andExpect(jsonPath("$.templateVersion").value(2))
                .andExpect(jsonPath("$.signatureRef").doesNotExist())
                .andReturn();
        String consentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("consentId").asText();

        // S-2：签署人留痕在 WAITING_SIGN（未签署）状态也不可改写。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_record SET signer_name = ? WHERE id = ?::uuid",
                "他人姓名", consentId)), "signer");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_record SET signer_relation = 'OTHER' WHERE id = ?::uuid",
                consentId)), "signer");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_record SET sign_method = 'PAPER' WHERE id = ?::uuid",
                consentId)), "signer");

        MvcResult signed = mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"))
                .andExpect(jsonPath("$.caStatus").value("SIMULATED"))
                .andExpect(jsonPath("$.signatureRef").value("SIM-CA-" + consentId))
                .andExpect(jsonPath("$.signatureHash").isNotEmpty())
                .andExpect(jsonPath("$.signedAt").isNotEmpty())
                .andReturn();
        JsonNode signedBody = objectMapper.readTree(signed.getResponse().getContentAsString());
        String signatureHash = signedBody.get("signatureHash").asText();
        assertThat(signatureHash).hasSize(64).matches("^[0-9a-f]{64}$");

        Map<String, Object> stored = jdbcTemplate.queryForMap("""
                SELECT status, ca_status, signature_ref, signature_hash, signer_name, signed_by
                FROM m2_anes.consent_record WHERE id = ?::uuid
                """, consentId);
        assertThat(stored.get("status")).isEqualTo("SIGNED");
        assertThat(stored.get("ca_status")).isEqualTo("SIMULATED");
        assertThat(stored.get("signature_ref")).isEqualTo("SIM-CA-" + consentId);
        assertThat(stored.get("signature_hash")).isEqualTo(signatureHash);
        assertThat(stored.get("signer_name")).isEqualTo("演示患者");
        assertThat(stored.get("signed_by")).isNotNull();

        // 不存签名原文/图片/证书链：consent_record 只有 signature_ref 与 signature_hash。
        List<String> signatureColumns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'm2_anes' AND table_name = 'consent_record'
                  AND column_name LIKE '%signature%'
                ORDER BY column_name
                """, String.class);
        assertThat(signatureColumns).containsExactly("signature_hash", "signature_ref");

        // S-2：SIGNED 状态签署人留痕同样不可改；S-4：已签署后拒绝 409 也写失败审计。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_record SET signer_name = ? WHERE id = ?::uuid",
                "他人姓名", consentId)), "signer");
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/decline")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", 2, "reason", "演示原因：已签署不可拒绝（合成数据）"))))
                .andExpect(status().isConflict());

        // 重复签署 409；已签署行签名留痕不可改写（55000）。
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_record SET signed_at = now() WHERE id = ?::uuid", consentId)),
                "SIGNED");

        // 撤销必须填原因；撤销后不可再签。
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/revoke")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/revoke")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", 2, "reason", "演示原因：患者术前改变意愿（合成数据）"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeReason").value("演示原因：患者术前改变意愿（合成数据）"));
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 3))))
                .andExpect(status().isConflict());
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_record SET status = 'SIGNED' WHERE id = ?::uuid", consentId)),
                "terminal");
        // S-2：REVOKED 状态签署人留痕仍不可改（先于终态校验拒绝）。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_record SET sign_method = 'PAPER' WHERE id = ?::uuid",
                consentId)), "signer");

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'CONSENT_RECORD' AND resource_id = ? AND result = 1
                ORDER BY occurred_at
                """, consentId);
        assertThat(audits).extracting(row -> row.get("action"))
                .containsExactly("CONSENT_CREATE", "CONSENT_SIGN", "CONSENT_REVOKE");
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail")).contains("演示患者"));
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail")).contains("改变意愿"));
        assertThat(String.valueOf(audits.get(1).get("detail"))).contains("SIMULATED");

        // S-4：重复签署 / 撤销后再签的 409 也写 result=0 审计，detail 无 PHI。
        List<Map<String, Object>> failures = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'CONSENT_RECORD' AND resource_id = ? AND result = 0
                ORDER BY occurred_at
                """, consentId);
        assertThat(failures).hasSize(3);
        assertThat(failures).extracting(row -> row.get("action"))
                .containsExactly("CONSENT_DECLINE", "CONSENT_SIGN", "CONSENT_SIGN");
        assertThat(failures).allMatch(row -> !String.valueOf(row.get("detail")).contains("演示患者"));
        assertThat(failures).allMatch(row -> !String.valueOf(row.get("detail")).contains("改变意愿"));

        System.out.printf("[M2-M5-01][consent] consent=%s signed=SIMULATED hash=%s… "
                + "repeatSign=409 revoke=REVOKED signAfterRevoke=409 signedRowGuard=55000 "
                + "signerImmutable=WAITING/SIGNED/REVOKED failureAudit=3 auditSuccess=3 noPhi=true%n",
                consentId, signatureHash.substring(0, 12));
    }

    // ------------------------------------------------- 拒绝与重复创建冲突 --

    @Test
    void consentDeclineAndDuplicateActiveRecord() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M5-APP-0002");

        String consentId = createConsent(admin, applicationId, SEDATION_V1_ID, 1);
        mockMvc.perform(post("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(consentBody(applicationId, SEDATION_V1_ID, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/decline")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 1))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/decline")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", 1, "reason", "演示原因：患者要求暂缓手术（合成数据）"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 2))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/revoke")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", 2, "reason", "演示原因：无效撤销（合成数据）"))))
                .andExpect(status().isConflict());
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.consent_record WHERE id = ?::uuid", consentId)), "append-only");
        // S-2：DECLINED 状态签署人留痕不可改。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_record SET signer_relation = 'OTHER' WHERE id = ?::uuid",
                consentId)), "signer");

        // S-4：sign-after-decline 409 与 revoke-declined 409 均写 result=0 审计。
        List<Map<String, Object>> failures = jdbcTemplate.queryForList("""
                SELECT action FROM m0_platform.sys_audit_log
                WHERE resource_type = 'CONSENT_RECORD' AND resource_id = ? AND result = 0
                ORDER BY occurred_at
                """, consentId);
        assertThat(failures).extracting(row -> row.get("action"))
                .containsExactly("CONSENT_SIGN", "CONSENT_REVOKE");

        // 拒绝后同一模板版本允许重新知情同意（新记录，保留原记录留痕）。
        mockMvc.perform(post("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(consentBody(applicationId, SEDATION_V1_ID, 1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_SIGN"));

        System.out.println("[M2-M5-01][decline] duplicateActive=409 decline=DECLINED "
                + "signAfterDecline=409 revokeDeclined=409 delete=55000 reConsentAllowed=true "
                + "signerImmutable=DECLINED failureAudit=2");
    }

    // ------------------------------------------ 三方核查 append-only 与完成 --

    @Test
    void threeWayChecksAreAppendOnlyAndCompletionDrivesM3IntegrationPoint() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M5-APP-0003");

        mockMvc.perform(get("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.items", hasSize(0)));
        assertThat(threeWayCheckService.isThreeWayCheckCompleted(applicationId)).isFalse();

        String timeOutAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusHours(1)
                .truncatedTo(ChronoUnit.SECONDS).toString();
        String signOutAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(10)
                .truncatedTo(ChronoUnit.SECONDS).toString();

        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeOutAt", timeOutAt,
                                "specimenChecked", true,
                                "implantChecked", false,
                                "issues", List.of("演示问题项：标本标签待复核（合成数据）")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.signOutAt").doesNotExist())
                .andExpect(jsonPath("$.issues", hasSize(1)));
        mockMvc.perform(get("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.items", hasSize(1)));
        assertThat(threeWayCheckService.isThreeWayCheckCompleted(applicationId)).isFalse();

        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeOutAt", timeOutAt,
                                "signOutAt", signOutAt,
                                "specimenChecked", true,
                                "implantChecked", true,
                                "issues", List.of()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.signOutAt").isNotEmpty());
        mockMvc.perform(get("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[1].version").value(1));
        assertThat(threeWayCheckService.isThreeWayCheckCompleted(applicationId)).isTrue();
        assertThat(threeWayCheckService.isThreeWayCheckCompleted("invalid")).isFalse();

        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.three_way_check SET implant_checked = true "
                        + "WHERE application_id = ?::uuid", applicationId)), "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.three_way_check WHERE application_id = ?::uuid", applicationId)),
                "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.execute(
                "TRUNCATE m2_anes.three_way_check")), "append-only");

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'THREE_WAY_CHECK' AND detail->>'applicationId' = ?
                ORDER BY occurred_at
                """, applicationId);
        assertThat(audits).hasSize(2);
        assertThat(audits).allMatch(row -> "THREEWAY_RECORD".equals(row.get("action")));
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail")).contains("标签待复核"));
        assertThat(String.valueOf(audits.get(0).get("detail"))).contains("\"issuesCount\": 1");

        System.out.printf("[M2-M5-01][threeway] app=%s versions=2 completed=true "
                + "update/delete/truncate=55000 audit=2 noPhi=true m3IntegrationPoint=true%n",
                applicationId);
    }

    // ------------------------------------------------------- 权限与数据范围 --

    @Test
    void permissionsAndCrossDepartmentScopeAreEnforced() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M5-APP-0004");
        String consentId = createConsent(admin, applicationId, GENERAL_V2_ID, 2);

        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME, SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/consent-templates")
                        .header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mockMvc.perform(get("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + surgeon)
                        .param("applicationId", applicationId))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 1))))
                .andExpect(status().isForbidden());

        String deptUser = login(CONSENT_DEPT_USERNAME, CONSENT_DEPT_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + deptUser)
                        .param("applicationId", applicationId))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/anesthesia/consents/" + consentId)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + deptUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 1))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + deptUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeOutAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).minusHours(1)
                                        .truncatedTo(ChronoUnit.SECONDS).toString(),
                                "specimenChecked", true,
                                "implantChecked", true))))
                .andExpect(status().isNotFound());

        System.out.println("[M2-M5-01][scope] noPermission=403 listOutOfScope=403 "
                + "getOutOfScope=404 signOutOfScope=404 threeWayOutOfScope=404");
    }

    // ------------------------------------------------------------- 迁移结构 --

    @Test
    void migrationV11CreatesTablesConstraintsIndexesPermissionsAndComments() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'm2_anes' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """, String.class);
        assertThat(tables).contains("consent_template", "consent_record", "three_way_check");

        assertThat(columnsOf("consent_template")).contains(
                "id", "template_code", "version", "title", "content_text", "effective_at",
                "status", "retired_at", "retired_by",
                "created_at", "created_by", "updated_at", "updated_by");
        assertThat(columnsOf("consent_record")).contains(
                "id", "application_id", "patient_id", "template_id", "template_version", "status",
                "signer_name", "signer_relation", "sign_method", "signed_at", "signed_by",
                "signature_ref", "signature_hash", "ca_status", "decline_reason", "declined_at",
                "revoked_at", "revoked_by", "revoke_reason", "created_at", "created_by",
                "updated_at", "updated_by", "version", "deleted");
        assertThat(columnsOf("three_way_check")).contains(
                "id", "application_id", "check_version", "time_out_at", "time_out_by",
                "sign_out_at", "sign_out_by", "specimen_checked", "implant_checked", "issues",
                "created_at", "created_by", "updated_at", "updated_by");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'm2_anes' AND indexname = 'uk_consent_record_active'
                """, String.class)).contains("UNIQUE", "application_id", "template_version", "WHERE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'm2_anes' AND indexname = 'uk_consent_record_signed_application'
                """, String.class)).contains("UNIQUE", "application_id", "SIGNED", "deleted = false");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'fk_consent_record_application'
                """, String.class)).contains("FOREIGN KEY", "surgery_application");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'uk_three_way_check_application_version'
                """, String.class)).contains("UNIQUE", "application_id", "check_version");

        assertThat(triggerNames("consent_template")).containsExactly(
                "trg_consent_template_no_delete", "trg_consent_template_no_truncate",
                "trg_consent_template_no_update");
        assertThat(triggerNames("consent_record")).containsExactly(
                "trg_consent_record_guard_delete", "trg_consent_record_guard_truncate",
                "trg_consent_record_guard_update");
        assertThat(triggerNames("three_way_check")).containsExactly(
                "trg_three_way_check_no_delete", "trg_three_way_check_no_truncate",
                "trg_three_way_check_no_update");

        assertThat(jdbcTemplate.queryForList("""
                SELECT code FROM m0_platform.sys_permission
                WHERE code LIKE 'm2:consent:%' OR code LIKE 'm2:threeway:%'
                ORDER BY code
                """, String.class)).containsExactly(
                "m2:consent:read", "m2:consent:write", "m2:threeway:read", "m2:threeway:write");
        Integer grants = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                WHERE (p.code LIKE 'm2:consent:%' OR p.code LIKE 'm2:threeway:%')
                  AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001',
                                     'a0000000-0000-4000-8000-000000000002',
                                     'a0000000-0000-4000-8000-000000000003')
                """, Integer.class);
        assertThat(grants).isEqualTo(12);

        Integer tablesWithoutComment = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = 'm2_anes' AND c.relkind = 'r'
                  AND (obj_description(c.oid, 'pg_class') IS NULL
                       OR btrim(obj_description(c.oid, 'pg_class')) = '')
                """, Integer.class);
        assertThat(tablesWithoutComment).isZero();
        Integer columnsWithoutComment = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_attribute a
                JOIN pg_class c ON a.attrelid = c.oid
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = 'm2_anes' AND c.relkind = 'r'
                  AND a.attnum > 0 AND NOT a.attisdropped
                  AND (col_description(c.oid, a.attnum) IS NULL
                       OR btrim(col_description(c.oid, a.attnum)) = '')
                """, Integer.class);
        assertThat(columnsWithoutComment).isZero();

        System.out.printf("[M2-M5-01][v11] tables=3 permissions=4 grants=%d "
                + "triggers=9 comments=true%n", grants);
    }

    // ------------------------------------------------- 模板停用（S-3） --

    @Test
    void retiredTemplateCannotBeUsedButHistoryStillReferencesIt() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M5-APP-0005");
        String consentId = createConsent(admin, applicationId, SEDATION_V1_ID, 1);
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateVersion").value(1));

        mockMvc.perform(post("/v1/anesthesia/consent-templates/" + SEDATION_V1_ID + "/retire")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateId").value(SEDATION_V1_ID))
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.retiredAt").isNotEmpty());

        // 默认列表只返回 ACTIVE；includeRetired=true 才包含停用版本。
        JsonNode activeItems = listTemplates(admin, false);
        assertThat(activeItems.findValuesAsText("templateCode")).doesNotContain("ANES_SEDATION");
        JsonNode allItems = listTemplates(admin, true);
        assertThat(allItems.findValuesAsText("templateCode")).contains("ANES_SEDATION");
        JsonNode retired = null;
        for (JsonNode item : allItems) {
            if (SEDATION_V1_ID.equals(item.get("templateId").asText())) {
                retired = item;
            }
        }
        assertThat(retired).isNotNull();
        assertThat(retired.get("status").asText()).isEqualTo("RETIRED");

        // 停用后不可再用于新签署（422）；历史同意记录仍引用旧版本。
        mockMvc.perform(post("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                consentBody(applicationId, SEDATION_V1_ID, 1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
        mockMvc.perform(get("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + admin)
                        .param("applicationId", applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].templateId").value(SEDATION_V1_ID))
                .andExpect(jsonPath("$.items[0].templateVersion").value(1))
                .andExpect(jsonPath("$.items[0].status").value("SIGNED"));

        // 内容仍 append-only；重复停用 409。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_template SET content_text = ? WHERE id = ?::uuid",
                "篡改正文", SEDATION_V1_ID)), "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.consent_template SET status = 'ACTIVE' WHERE id = ?::uuid",
                SEDATION_V1_ID)), "RETIRED");
        mockMvc.perform(post("/v1/anesthesia/consent-templates/" + SEDATION_V1_ID + "/retire")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'CONSENT_TEMPLATE' AND resource_id = ?
                ORDER BY occurred_at
                """, SEDATION_V1_ID);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("action")).isEqualTo("CONSENT_TEMPLATE_RETIRE");
        assertThat(((Number) audits.get(0).get("result")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(audits.get(0).get("detail")))
                .contains("RETIRED").doesNotContain("{{").doesNotContain("SYNTHETIC 演示模板");

        System.out.printf("[M2-M5-01][retire] template=%s status=RETIRED defaultListExcludes=true "
                + "includeRetired=true newConsent=422 history=%s contentImmutable=55000 "
                + "repeatRetire=422 audit=1%n", SEDATION_V1_ID, consentId);
    }

    // ------------------------------------- 当前有效同意唯一（S-6）与非活动申请（S-7） --

    @Test
    void onlyOneUnrevokedSignedConsentPerApplication() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M5-APP-0006");
        String generalConsent = createConsent(admin, applicationId, GENERAL_V2_ID, 2);
        signConsent(admin, generalConsent, 1);
        String secondConsent = createConsent(admin, applicationId, SEDATION_V1_ID, 1);

        mockMvc.perform(post("/v1/anesthesia/consents/" + secondConsent + "/sign")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        // 撤销原有效同意后释放唯一槽位，第二份同意可以签署。
        revokeConsent(admin, generalConsent, 2);
        signConsent(admin, secondConsent, 1);

        Integer signedCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.consent_record
                WHERE application_id = ?::uuid AND status = 'SIGNED' AND deleted = false
                """, Integer.class, applicationId);
        assertThat(signedCount).isEqualTo(1);
        String currentConsentId = jdbcTemplate.queryForObject("""
                SELECT id::text FROM m2_anes.consent_record
                WHERE application_id = ?::uuid AND status = 'SIGNED' AND deleted = false
                """, String.class, applicationId);
        assertThat(currentConsentId).isEqualTo(secondConsent);

        List<Map<String, Object>> failures = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'CONSENT_RECORD' AND resource_id = ? AND result = 0
                """, secondConsent);
        assertThat(failures).isNotEmpty();
        assertThat(failures).allMatch(row -> "CONSENT_SIGN".equals(row.get("action")));

        System.out.printf("[M2-M5-01][consent-unique] app=%s first=%s second=%s "
                + "secondBeforeRevoke=409 secondAfterRevoke=SIGNED signedCount=1%n",
                applicationId, generalConsent, secondConsent);
    }

    @Test
    void signRejectedWhenApplicationCancelled() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M5-APP-0007");
        String consentId = createConsent(admin, applicationId, GENERAL_V2_ID, 2);
        jdbcTemplate.update("UPDATE m1_sched.surgery_application SET status = 'CANCELLED' "
                + "WHERE id = ?::uuid", applicationId);

        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        String status = jdbcTemplate.queryForObject("""
                SELECT status FROM m2_anes.consent_record WHERE id = ?::uuid
                """, String.class, consentId);
        assertThat(status).isEqualTo("WAITING_SIGN");
        List<Map<String, Object>> failures = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'CONSENT_RECORD' AND resource_id = ? AND result = 0
                """, consentId);
        assertThat(failures).hasSize(1);
        assertThat(String.valueOf(failures.get(0).get("detail")))
                .contains("STATE_CONFLICT").doesNotContain("演示患者");

        System.out.printf("[M2-M5-01][sign-active] app=%s cancelled=true sign=409 "
                + "status=WAITING_SIGN failureAudit=1%n", applicationId);
    }

    // ------------------------------------- 跨版本 Time-out 继承/变更确认（S-1） --

    @Test
    void threeWayTimeOutIsInheritedAcrossVersionsUnlessExplicitlyConfirmed() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M5-APP-0008");
        String timeOutAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusHours(2)
                .truncatedTo(ChronoUnit.SECONDS).toString();
        String signOutAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusHours(1)
                .truncatedTo(ChronoUnit.SECONDS).toString();
        String changedTimeOutAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(90)
                .truncatedTo(ChronoUnit.SECONDS).toString();

        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeOutAt", timeOutAt,
                                "specimenChecked", true,
                                "implantChecked", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        Map<String, Object> inherited = new LinkedHashMap<>();
        inherited.put("signOutAt", signOutAt);
        inherited.put("specimenChecked", true);
        inherited.put("implantChecked", true);
        MvcResult inheritedResult = mockMvc.perform(post(
                                "/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(inherited)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andReturn();
        assertThat(OffsetDateTime.parse(objectMapper.readTree(
                inheritedResult.getResponse().getContentAsString()).get("timeOutAt").asText())
                .toInstant()).isEqualTo(OffsetDateTime.parse(timeOutAt).toInstant());

        // 显式改 Time-out 未确认 → 409；确认后允许新增 v3。
        mockMvc.perform(post("/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeOutAt", changedTimeOutAt,
                                "specimenChecked", true,
                                "implantChecked", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        MvcResult changedResult = mockMvc.perform(post(
                                "/v1/anesthesia/applications/" + applicationId + "/three-way-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeOutAt", changedTimeOutAt,
                                "confirmTimeOutChange", true,
                                "specimenChecked", true,
                                "implantChecked", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(3))
                .andReturn();
        assertThat(OffsetDateTime.parse(objectMapper.readTree(
                changedResult.getResponse().getContentAsString()).get("timeOutAt").asText())
                .toInstant()).isEqualTo(OffsetDateTime.parse(changedTimeOutAt).toInstant());

        List<Map<String, Object>> successAudits = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'THREE_WAY_CHECK' AND detail->>'applicationId' = ?
                  AND result = 1
                """, applicationId);
        assertThat(successAudits).hasSize(3);
        List<Map<String, Object>> failures = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'THREE_WAY_CHECK' AND detail->>'applicationId' = ?
                  AND result = 0
                """, applicationId);
        assertThat(failures).hasSize(1);
        assertThat(String.valueOf(failures.get(0).get("detail")))
                .contains("STATE_CONFLICT").doesNotContain("timeOutAt").doesNotContain("issues");

        System.out.printf("[M2-M5-01][timeout] app=%s v2Inherited=%s changeUnconfirmed=409 "
                + "changeConfirmed=v3 successAudit=3 failureAudit=1%n", applicationId, timeOutAt);
    }

    // ------------------------------------------------------------- helpers --

    private JsonNode listTemplates(String token, boolean includeRetired) throws Exception {
        var request = get("/v1/anesthesia/consent-templates")
                .header("Authorization", "Bearer " + token);
        if (includeRetired) {
            request = request.param("includeRetired", "true");
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("items");
    }

    private void signConsent(String token, String consentId, int version) throws Exception {
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/sign")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("version", version))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SIGNED"));
    }

    private void revokeConsent(String token, String consentId, int version) throws Exception {
        mockMvc.perform(post("/v1/anesthesia/consents/" + consentId + "/revoke")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", version,
                                "reason", "演示原因：更换同意版本（合成数据）"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    private void resetTemplates() {
        // 模板内容 append-only：测试间恢复 ACTIVE 需临时停用触发器（仅测试清理）。
        jdbcTemplate.execute("ALTER TABLE m2_anes.consent_template DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("""
                    UPDATE m2_anes.consent_template
                       SET status = 'ACTIVE', retired_at = NULL, retired_by = NULL,
                           updated_at = created_at, updated_by = created_by
                    """);
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.consent_template ENABLE TRIGGER USER");
        }
    }

    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'm2_anes' AND table_name = ?
                """, String.class, table);
    }

    private List<String> triggerNames(String table) {
        return jdbcTemplate.queryForList("""
                SELECT tgname FROM pg_trigger
                WHERE tgrelid = ('m2_anes.' || ?)::regclass AND NOT tgisinternal
                ORDER BY tgname
                """, String.class, table);
    }

    private void clearClinicalRows() {
        // 同一 Spring 上下文/数据库被多个集成测试类共享：按外键依赖顺序清理。
        // V10/V11 的临床记录 append-only，测试清理需临时停用用户触发器。
        jdbcTemplate.execute("ALTER TABLE m2_anes.consent_record DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.three_way_check DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.preop_assessment DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m2_anes.consent_record");
            jdbcTemplate.update("DELETE FROM m2_anes.three_way_check");
            // 术前访视同为本任务上游用例残留：FK 指向申请，必须在删除申请前清理。
            jdbcTemplate.update("DELETE FROM m2_anes.preop_assessment");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.consent_record ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.three_way_check ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.preop_assessment ENABLE TRIGGER USER");
        }
    }

    private static void assertSqlState55000(Throwable failure, String messageFragment) {
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo("55000");
        assertThat(root.getMessage()).contains(messageFragment);
    }

    private static String sqlStateOf(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return ((SQLException) root).getSQLState();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password, "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String createApplication(String token, String hisApplicationNo) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("hisApplicationNo", hisApplicationNo);
        request.put("patientId", PATIENT_ANES);
        request.put("surgeryName", "演示腹腔镜胆囊切除术（合成）");
        request.put("urgency", "ELECTIVE");
        request.put("plannedDurationMin", 120);
        request.put("surgeonName", "演示术者（合成）");
        MvcResult result = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("applicationId").asText();
    }

    private String createConsent(String token, String applicationId, String templateId, int version)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/anesthesia/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                consentBody(applicationId, templateId, version))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("consentId").asText();
    }

    private Map<String, Object> consentBody(String applicationId, String templateId, int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId);
        body.put("templateId", templateId);
        body.put("templateVersion", version);
        body.put("signerName", "演示患者");
        body.put("signerRelation", "SELF");
        body.put("signMethod", "ELECTRONIC");
        return body;
    }

    private void insertPatient() {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, '演示患者丙', 'MALE', DATE '1968-03-09', 58, 'SYNT****9301', ?,
                        '麻醉科（合成）', ?::uuid, 'f1000000-0000-4000-8000-000000000001'::uuid,
                        '演示病区', '02', now(), now(), 1, false)
                """, PATIENT_ANES, HIS_ANES, "hash-" + HIS_ANES,
                SyntheticAccounts.DEMO_ANES_ORG_ID);
    }

    private void ensureConsentDeptUser() {
        String hash = new BCryptPasswordEncoder().encode(CONSENT_DEPT_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_CONSENT_DEPT', '合成知情同意科室角色',
                        'SYNTHETIC：m2 知情同意/三方核查 DEPT 数据范围用例', 'DEPT')
                ON CONFLICT (id) DO NOTHING
                """, CONSENT_DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试知情同意科室用户（合成）', 1, false, 0, 1, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, CONSENT_DEPT_USER_ID, CONSENT_DEPT_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, CONSENT_DEPT_USER_ID, CONSENT_DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_org (user_id, org_id, is_primary)
                VALUES (?::uuid, ?::uuid, true) ON CONFLICT DO NOTHING
                """, CONSENT_DEPT_USER_ID, SyntheticAccounts.DEMO_OR_ORG_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                SELECT ?::uuid, id FROM m0_platform.sys_permission
                WHERE code IN ('m2:consent:read', 'm2:consent:write',
                               'm2:threeway:read', 'm2:threeway:write')
                ON CONFLICT DO NOTHING
                """, CONSENT_DEPT_ROLE_ID);
    }
}
