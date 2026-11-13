package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 术中预警规则、告警留痕与抢救记录（M3-M2-02）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：规则版本化与 configure 仅管理员、阈值边界（等于阈值不触发）、持续时长、窗口去重
 * 幂等、告警确认/忽略状态机 409、抢救记录 append-only（55000）与时间窗校验、数据范围 404、
 * 无权限 403、审计 ALERT_TRIGGERED/ALERT_ACK/RESCUE_RECORD（无原因原文）、V15 结构/权限/种子。
 * 全部数据为 SYNTHETIC，体征由测试夹具直接写入 {@code m2_anes.anesthesia_vital}（不连真实设备）。
 */
class AlertRescueApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String PATIENT_ID = "54000000-0000-4000-8000-000000000019";
    private static final String HIS_NO = "SYNTH-HIS-P-9419";
    private static final String ROOM = "f2000000-0000-4000-8000-0000000000c1";
    private static final String GATEWAY = "GW-ALERT-01";
    private static final String GATEWAY_TOKEN = "synth-test-gateway-token";
    private static final String DEVICE = "SIM-ALERT-01";
    private static final String RECORD_SCENARIO = "12000000-0000-4000-8000-000000000019";
    private static final String DEPT_ORG = "f1000000-0000-4000-8000-0000000000e8";

    private static final String CLIN_ID = "e0000000-0000-4000-8000-0000000000e3";
    private static final String CLIN_USERNAME = "test.alert.clin";
    private static final String CLIN_PASSWORD = "Synth@2026";
    private static final String CLIN_ROLE_ID = "a0000000-0000-4000-8000-0000000000f5";

    private static final String SCOPE_ID = "e0000000-0000-4000-8000-0000000000e4";
    private static final String SCOPE_USERNAME = "test.alert.dept";
    private static final String SCOPE_PASSWORD = "Synth@2026";
    private static final String SCOPE_ROLE_ID = "a0000000-0000-4000-8000-0000000000f6";

    private static final String NO_PERM_ID = "e0000000-0000-4000-8000-0000000000e5";
    private static final String NO_PERM_USERNAME = "test.alert.none";
    private static final String NO_PERM_PASSWORD = "Synth@2026";
    private static final String NO_PERM_ROLE_ID = "a0000000-0000-4000-8000-0000000000f7";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareData() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        ensureOrg();
        ensureRoles();
        ensureUser(CLIN_ID, CLIN_USERNAME, CLIN_PASSWORD, CLIN_ROLE_ID, null);
        ensureUser(SCOPE_ID, SCOPE_USERNAME, SCOPE_PASSWORD, SCOPE_ROLE_ID, DEPT_ORG);
        ensureUser(NO_PERM_ID, NO_PERM_USERNAME, NO_PERM_PASSWORD, NO_PERM_ROLE_ID, null);
        resetAlertState();
        deleteClinicalRows();
        clearSchedules();
    }

    @AfterEach
    void cleanUpAfterTest() {
        resetAlertState();
        deleteClinicalRows();
        clearSchedules();
        for (String userId : List.of(CLIN_ID, SCOPE_ID, NO_PERM_ID)) {
            jdbcTemplate.update("DELETE FROM m0_platform.sys_user_org WHERE user_id = ?::uuid", userId);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_user_role WHERE user_id = ?::uuid", userId);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_user WHERE id = ?::uuid", userId);
        }
        for (String roleId : List.of(CLIN_ROLE_ID, SCOPE_ROLE_ID, NO_PERM_ROLE_ID)) {
            jdbcTemplate.update("DELETE FROM m0_platform.sys_role_permission WHERE role_id = ?::uuid",
                    roleId);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_role WHERE id = ?::uuid", roleId);
        }
        jdbcTemplate.update("DELETE FROM m0_platform.sys_org WHERE id = ?::uuid", DEPT_ORG);
    }

    // ------------------------------------------------------- V15 结构与权限 --

    @Test
    void v15CreatesRulesAlertsResuscitationPermissionsAndSeedRules() {
        // V16（M3-M2-02 评审 blocking）：术间快照、评估水位、待确认标记、抢救人员留痕。
        assertThat(columnExists("m2_anes", "anesthesia_record", "room_id")).isTrue();
        assertThat(tableExists("m2_anes", "alert_watermark")).isTrue();
        assertThat(columnExists("m2_anes", "alert_rule", "pending_confirmation")).isTrue();
        assertThat(columnExists("m2_anes", "resuscitation_record", "leader")).isTrue();
        assertThat(columnExists("m2_anes", "resuscitation_record", "rescuers")).isTrue();
        assertThat(constraintExists("ck_resuscitation_leader", "m2_anes.resuscitation_record"))
                .isTrue();
        assertThat(constraintExists("ck_resuscitation_rescuers", "m2_anes.resuscitation_record"))
                .isTrue();
        assertThat(indexExists("idx_anesthesia_record_room_active")).isTrue();

        assertThat(constraintExists("uk_alert_rule_code_version", "m2_anes.alert_rule")).isTrue();
        assertThat(constraintExists("ck_alert_rule_thresholds", "m2_anes.alert_rule")).isTrue();
        assertThat(constraintExists("uk_alert_dedupe", "m2_anes.alert")).isTrue();
        assertThat(constraintExists("ck_alert_status", "m2_anes.alert")).isTrue();
        assertThat(indexExists("uk_alert_rule_enabled_code")).isTrue();
        assertThat(triggerNames("m2_anes.resuscitation_record"))
                .contains("trg_resuscitation_record_no_update", "trg_resuscitation_record_no_delete");
        assertThat(triggerNames("m2_anes.alert")).contains("trg_alert_guard_update");
        assertThat(triggerNames("m2_anes.alert_rule")).contains("trg_alert_rule_guard_update");

        Long permissions = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_permission
                 WHERE code IN ('m2:alert:read', 'm2:alert:ack', 'm2:alert:configure',
                                'm2:rescue:write')
                """, Long.class);
        assertThat(permissions).isEqualTo(4L);
        Long configureGrants = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                  JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE p.code = 'm2:alert:configure'
                """, Long.class);
        assertThat(configureGrants).isEqualTo(1L);
        Long ackGrants = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                  JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE p.code = 'm2:alert:ack'
                   AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001',
                                      'a0000000-0000-4000-8000-000000000002',
                                      'a0000000-0000-4000-8000-000000000003')
                """, Long.class);
        assertThat(ackGrants).isEqualTo(3L);

        Long seedRules = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m2_anes.alert_rule WHERE enabled = true AND version = 1",
                Long.class);
        assertThat(seedRules).isEqualTo(5L);
        String comment = jdbcTemplate.queryForObject("""
                SELECT obj_description('m2_anes.alert_rule'::regclass, 'pg_class')
                """, String.class);
        assertThat(comment).contains("待确认");
        // S-4：契约 createdBy 保持必填，默认种子写系统用户 UUID（不是真人）。
        Long systemCreated = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.alert_rule
                 WHERE created_by = '00000000-0000-4000-8000-000000000000'::uuid
                """, Long.class);
        assertThat(systemCreated).isEqualTo(5L);
        Long pending = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.alert_rule
                 WHERE version = 1 AND pending_confirmation = true
                """, Long.class);
        assertThat(pending).isEqualTo(5L);
    }

    // ----------------------------------------------------------- 规则版本化 --

    @Test
    void rulesAreVersionedAndConfigurationRequiresAdmin() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String clinician = login(CLIN_USERNAME, CLIN_PASSWORD);

        MvcResult listed = mockMvc.perform(get("/v1/anesthesia/alert-rules")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].version").value(1))
                // S-4 备选方案：契约 createdBy 保持必填，默认种子写系统用户 UUID。
                .andExpect(jsonPath("$.items[0].createdBy")
                        .value("00000000-0000-4000-8000-000000000000"))
                .andReturn();
        String spo2RuleId = ruleIdOf(listed, "SPO2_LOW");
        assertThat(spo2RuleId).isNotBlank();

        // 临床角色无 configure 权限：403（阈值由管理员代录临床顾问确认结果）。
        mockMvc.perform(put("/v1/anesthesia/alert-rules/" + spo2RuleId)
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(ruleBody("88", 0, "CRITICAL", true, 1))))
                .andExpect(status().isForbidden());

        MvcResult updated = mockMvc.perform(put("/v1/anesthesia/alert-rules/" + spo2RuleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(ruleBody("88", 15, "CRITICAL", true, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SPO2_LOW"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.thresholdLow").value(88))
                .andExpect(jsonPath("$.durationSeconds").value(15))
                .andReturn();
        String newRuleId = objectMapper.readTree(updated.getResponse().getContentAsString())
                .get("ruleId").asText();
        assertThat(newRuleId).isNotEqualTo(spo2RuleId);

        Long enabled = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.alert_rule WHERE code = 'SPO2_LOW' AND enabled = true
                """, Long.class);
        assertThat(enabled).isEqualTo(1L);
        Long oldEnabled = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.alert_rule WHERE code = 'SPO2_LOW' AND version = 1
                   AND enabled = false
                """, Long.class);
        assertThat(oldEnabled).isEqualTo(1L);

        // 旧版本/过期版本再提交：409，不静默覆盖（历史版本不可改）。
        mockMvc.perform(put("/v1/anesthesia/alert-rules/" + newRuleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(ruleBody("86", 0, "WARN", true, 1))))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/v1/anesthesia/alert-rules/" + spo2RuleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(ruleBody("86", 0, "WARN", true, 1))))
                .andExpect(status().isConflict());
    }

    // --------------------------------------------------- 触发/边界/窗口去重 --

    @Test
    void evaluationTriggersOncePerWindowAndSkipsThresholdEquality() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String clinician = login(CLIN_USERNAME, CLIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ALERT-APP-0001");
        String recordId = insertRecord(scheduleId);
        Instant base = Instant.now().minusSeconds(1800).truncatedTo(ChronoUnit.SECONDS);

        // SpO2 = 90 等于阈值不触发；HR = 45 自 10s 起连续 30s（10s 间隔）触发 HR_LOW。
        insertVital(1, base, 70, 90);
        insertVital(2, base.plusSeconds(10), 45, 90);
        insertVital(3, base.plusSeconds(20), 45, 90);
        insertVital(4, base.plusSeconds(30), 45, 90);
        insertVital(5, base.plusSeconds(40), 45, 90);

        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/alerts/evaluate")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluatedRules").value(5))
                .andExpect(jsonPath("$.sampledFrames").value(5))
                .andExpect(jsonPath("$.createdAlerts").value(1));

        // 重复评估同一水位：幂等，不重复插入。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/alerts/evaluate")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAlerts").value(0));

        // 读取告警列表只读、不再评估（评审 B-1：不用当前规则重评历史窗口）。
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/alerts")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].ruleCode").value("HR_LOW"))
                .andExpect(jsonPath("$.items[0].ruleVersion").value(1))
                .andExpect(jsonPath("$.items[0].severity").value("WARN"))
                .andExpect(jsonPath("$.items[0].status").value("TRIGGERED"))
                .andExpect(jsonPath("$.items[0].value").value(45));

        Long alertRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m2_anes.alert WHERE record_id = ?::uuid", Long.class,
                recordId);
        assertThat(alertRows).isEqualTo(1L);
        String dedupeKey = jdbcTemplate.queryForObject(
                "SELECT dedupe_key FROM m2_anes.alert WHERE record_id = ?::uuid", String.class,
                recordId);
        assertThat(dedupeKey)
                .isEqualTo(recordId + ":HR_LOW:" + base.plusSeconds(10).getEpochSecond());
        String alertId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM m2_anes.alert WHERE record_id = ?::uuid", String.class,
                recordId);

        // 审计：ALERT_TRIGGERED 存在，detail 只含标识/规则/数值，不含患者姓名。
        Long triggered = auditCount("ALERT_TRIGGERED");
        assertThat(triggered).isGreaterThanOrEqualTo(1L);
        Map<String, Object> detail = latestAuditDetail("ALERT_TRIGGERED");
        assertThat(detail).containsKeys("alertId", "recordId", "ruleCode", "ruleVersion", "metric",
                "value", "severity", "triggeredAt", "triggerSource");
        // S-1：审计以 alert 主键为 resourceId、resourceType=ALERT，可关联具体告警。
        assertThat(detail).containsEntry("alertId", alertId);
        Map<String, Object> auditRow = jdbcTemplate.queryForMap("""
                SELECT resource_type, resource_id, actor_id FROM m0_platform.sys_audit_log
                 WHERE action = 'ALERT_TRIGGERED' ORDER BY occurred_at DESC LIMIT 1
                """);
        assertThat(auditRow.get("resource_type")).isEqualTo("ALERT");
        assertThat(auditRow.get("resource_id")).isEqualTo(alertId);
        // S-1：手工/补偿评估记录调用人。
        assertThat(auditRow.get("actor_id")).isEqualTo(UUID.fromString(CLIN_ID));
        assertThat(detail.toString()).doesNotContain("演示患者", HIS_NO);
    }

    // ------------------------------------- 实时触发 / 水位 / DRAFT / 人员 --

    @Test
    void ingestEventTriggersAlertAfterCommit() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String clinician = login(CLIN_USERNAME, CLIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ALERT-APP-0006");
        String recordId = insertRecord(scheduleId);
        Instant base = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.SECONDS);

        // 一次 ingest 批量上报连续低心率（10s 间隔，30s 达到 HR_LOW 持续时长）。
        ingestFrames("BATCH-EVT-0001", List.of(
                ingestFrame(1, base, 45, 98),
                ingestFrame(2, base.plusSeconds(10), 45, 98),
                ingestFrame(3, base.plusSeconds(20), 45, 98),
                ingestFrame(4, base.plusSeconds(30), 45, 98)));

        // AFTER_COMMIT 监听器已评估落库：直接读取即可看到，无需手动 evaluate。
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/alerts")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].ruleCode").value("HR_LOW"))
                .andExpect(jsonPath("$.items[0].status").value("TRIGGERED"));

        Long atWatermark = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.alert_watermark
                 WHERE record_id = ?::uuid AND evaluated_through = ?::timestamptz
                """, Long.class, recordId,
                OffsetDateTime.ofInstant(base.plusSeconds(30), ZoneOffset.UTC));
        assertThat(atWatermark).isEqualTo(1L);
        assertThat(latestAuditDetail("ALERT_TRIGGERED"))
                .containsEntry("triggerSource", "INGEST_EVENT");
        // 自动评估 actor 为空（系统动作），不绑定真人。
        Map<String, Object> auditRow = jdbcTemplate.queryForMap("""
                SELECT actor_id, actor_name FROM m0_platform.sys_audit_log
                 WHERE action = 'ALERT_TRIGGERED' ORDER BY occurred_at DESC LIMIT 1
                """);
        assertThat(auditRow.get("actor_id")).isNull();
        assertThat(auditRow.get("actor_name")).isEqualTo("alert-engine");
    }

    @Test
    void draftRecordIsNotEvaluatedEvenWithSameRoomPreviousVitals() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String clinician = login(CLIN_USERNAME, CLIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ALERT-APP-0007");
        String draftId = insertDraftRecord(scheduleId);
        Instant base = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
        // 同术间“上一台次”体征（SpO2=85 若被误用会立即触发 SPO2_LOW）。
        insertVital(1, base, 45, 85);

        mockMvc.perform(post("/v1/anesthesia/records/" + draftId + "/alerts/evaluate")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluatedRules").value(0))
                .andExpect(jsonPath("$.sampledFrames").value(0))
                .andExpect(jsonPath("$.createdAlerts").value(0));
        mockMvc.perform(get("/v1/anesthesia/records/" + draftId + "/alerts")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // 采集落库事件同样不评估 DRAFT（只匹配 ONGOING 且 started_at 非空）。
        ingestFrames("BATCH-DRAFT-0001", List.of(
                ingestFrame(101, base.plusSeconds(10), 45, 85)));
        Long alertRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m2_anes.alert WHERE record_id = ?::uuid", Long.class,
                draftId);
        assertThat(alertRows).isZero();
    }

    @Test
    void incrementalEvaluationKeepsEarlyWindowStartAcrossWatermark() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String clinician = login(CLIN_USERNAME, CLIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ALERT-APP-0008");
        String recordId = insertRecord(scheduleId);
        Instant base = Instant.now().minusSeconds(900).truncatedTo(ChronoUnit.SECONDS);
        insertVital(1, base, 45, 98);
        insertVital(2, base.plusSeconds(10), 45, 98);
        insertVital(3, base.plusSeconds(20), 45, 98);

        // 首轮：持续 20s 未达到 30s，不触发；水位推进到 20s。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/alerts/evaluate")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAlerts").value(0));
        Long atWatermark = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.alert_watermark
                 WHERE record_id = ?::uuid AND evaluated_through = ?::timestamptz
                """, Long.class, recordId,
                OffsetDateTime.ofInstant(base.plusSeconds(20), ZoneOffset.UTC));
        assertThat(atWatermark).isEqualTo(1L);

        // 增量样本达到 30s：窗口起点仍是首轮之前的 base（早期数据未因水位丢失）。
        insertVital(4, base.plusSeconds(30), 45, 98);
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/alerts/evaluate")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAlerts").value(1));
        String dedupeKey = jdbcTemplate.queryForObject(
                "SELECT dedupe_key FROM m2_anes.alert WHERE record_id = ?::uuid", String.class,
                recordId);
        assertThat(dedupeKey).isEqualTo(recordId + ":HR_LOW:" + base.getEpochSecond());
    }

    @Test
    void resuscitationRequiresLeaderAndAtLeastOneRescuer() throws Exception {
        String clinician = login(CLIN_USERNAME, CLIN_PASSWORD);
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ALERT-APP-0009");
        String recordId = insertRecord(scheduleId);
        Instant started = Instant.now().minusSeconds(1500).truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> withoutLeader = resusBody(started, started.plusSeconds(300), "SUCCESS");
        withoutLeader.remove("leader");
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/resuscitations")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(withoutLeader)))
                .andExpect(status().isBadRequest());

        Map<String, Object> withoutRescuers = resusBody(started, started.plusSeconds(300),
                "SUCCESS");
        withoutRescuers.put("rescuers", List.of());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/resuscitations")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(withoutRescuers)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/resuscitations")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(resusBody(started, started.plusSeconds(300), "SUCCESS"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leader.name").value("演示负责人（合成）"))
                .andExpect(jsonPath("$.rescuers.length()").value(2))
                .andExpect(jsonPath("$.rescuers[1].name").value("演示护士（合成）"));
    }

    @Test
    void ruleHistoryNameAndMetricRangeAreGoverned() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        MvcResult listed = mockMvc.perform(get("/v1/anesthesia/alert-rules")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        String spo2RuleId = ruleIdOf(listed, "SPO2_LOW");

        // 阈值按指标范围校验：SpO2 101 越界 400。
        Map<String, Object> outOfRange = ruleBody("101", 0, "CRITICAL", true, 1);
        mockMvc.perform(put("/v1/anesthesia/alert-rules/" + spo2RuleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(outOfRange)))
                .andExpect(status().isBadRequest());

        // 名称可更新；阈值变更置 pendingConfirmation=true。
        Map<String, Object> update = ruleBody("88", 15, "CRITICAL", true, 1);
        update.put("name", "血氧过低（院内演示版）");
        MvcResult updated = mockMvc.perform(put("/v1/anesthesia/alert-rules/" + spo2RuleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("血氧过低（院内演示版）"))
                .andExpect(jsonPath("$.pendingConfirmation").value(true))
                .andReturn();
        String newRuleId = objectMapper.readTree(updated.getResponse().getContentAsString())
                .get("ruleId").asText();

        // 历史版本查询（additive）：v2 + v1，供告警 ruleVersion 追溯。
        mockMvc.perform(get("/v1/anesthesia/alert-rules/" + newRuleId + "/versions")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].name").value("血氧过低（院内演示版）"))
                .andExpect(jsonPath("$.items[1].version").value(1))
                .andExpect(jsonPath("$.items[1].enabled").value(false));
    }

    // ------------------------------------------------------ 告警处置状态机 --

    @Test
    void acknowledgeIsOneWayAndAuditsWithoutReasonText() throws Exception {
        String clinician = login(CLIN_USERNAME, CLIN_PASSWORD);
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ALERT-APP-0002");
        String recordId = insertRecord(scheduleId);
        Instant base = Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
        insertVital(1, base, 70, 85);
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/alerts/evaluate")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk());
        String alertId = firstAlertId(clinician, recordId);
        long ackBefore = auditCount("ALERT_ACK");

        mockMvc.perform(post("/v1/anesthesia/alerts/" + alertId + "/acknowledge")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(Map.of("decision", "CONFIRMED",
                                "reason", "已核对监护仪（合成演示）", "version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.acknowledgedBy").value(CLIN_ID));

        // 只允许一次流转：重复处理 409（即使版本正确）。
        mockMvc.perform(post("/v1/anesthesia/alerts/" + alertId + "/acknowledge")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(Map.of("decision", "IGNORED",
                                "reason", "重复处理", "version", 2))))
                .andExpect(status().isConflict());

        Long acked = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.alert WHERE id = ?::uuid AND status = 'ACKNOWLEDGED'
                   AND acknowledged_by = ?::uuid AND acknowledge_reason IS NOT NULL
                """, Long.class, alertId, CLIN_ID);
        assertThat(acked).isEqualTo(1L);
        assertThat(auditCount("ALERT_ACK")).isGreaterThanOrEqualTo(ackBefore + 1);
        Map<String, Object> detail = latestAuditDetail("ALERT_ACK");
        assertThat(detail).containsEntry("decision", "CONFIRMED").doesNotContainKey("reason");
        assertThat(detail.toString()).doesNotContain("已核对监护仪");

        // 新告警忽略流程 + 空原因 400。
        insertVital(2, base.plusSeconds(400), 70, 84);
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/alerts/evaluate")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk());
        String secondAlert = firstAlertId(clinician, recordId);
        assertThat(secondAlert).isNotEqualTo(alertId);
        mockMvc.perform(post("/v1/anesthesia/alerts/" + secondAlert + "/acknowledge")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(Map.of("decision", "IGNORED", "reason", " ",
                                "version", 1))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/anesthesia/alerts/" + secondAlert + "/acknowledge")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(Map.of("decision", "IGNORED",
                                "reason", "演示：无临床意义", "version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IGNORED"));
    }

    // ------------------------------------------------ 规则版本变更后的告警 --

    @Test
    void alertsUseNewRuleVersionAfterConfigurationChange() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String clinician = login(CLIN_USERNAME, CLIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ALERT-APP-0003");
        String recordId = insertRecord(scheduleId);

        MvcResult listed = mockMvc.perform(get("/v1/anesthesia/alert-rules")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        String spo2RuleId = ruleIdOf(listed, "SPO2_LOW");
        MvcResult updated = mockMvc.perform(put("/v1/anesthesia/alert-rules/" + spo2RuleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(write(ruleBody("95", 0, "CRITICAL", true, 1))))
                .andExpect(status().isOk()).andReturn();
        String newRuleId = objectMapper.readTree(updated.getResponse().getContentAsString())
                .get("ruleId").asText();

        // 阈值收紧到 95 后，SpO2=92 越界；新窗口告警必须使用规则版本 2。
        Instant base = Instant.now().minusSeconds(1200).truncatedTo(ChronoUnit.SECONDS);
        insertVital(1, base, 70, 92);
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/alerts/evaluate")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAlerts").value(1));
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/alerts")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ruleCode").value("SPO2_LOW"))
                .andExpect(jsonPath("$.items[0].ruleVersion").value(2))
                .andExpect(jsonPath("$.items[0].ruleId").value(newRuleId));
    }

    // ------------------------------------------------------ 抢救记录留痕 --

    @Test
    void resuscitationIsAppendOnlyAndWindowValidated() throws Exception {
        String clinician = login(CLIN_USERNAME, CLIN_PASSWORD);
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ALERT-APP-0004");
        String recordId = insertRecord(scheduleId);
        Instant started = Instant.now().minusSeconds(1500).truncatedTo(ChronoUnit.SECONDS);
        Instant ended = started.plusSeconds(300);

        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/resuscitations")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(resusBody(started, ended, "SUCCESS"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordId").value(recordId))
                .andExpect(jsonPath("$.resusType").value("CPR"))
                .andExpect(jsonPath("$.eventSummary.summary").value("演示：胸外按压（合成数据）"))
                .andExpect(jsonPath("$.drugs[0].drugName").value("肾上腺素（演示）"));

        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/resuscitations")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        // 时间窗校验：结束早于开始 400；早于记录开始 400。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/resuscitations")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(resusBody(ended, started, "SUCCESS"))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/resuscitations")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(resusBody(started.minusSeconds(7200),
                                started.minusSeconds(7000), "SUCCESS"))))
                .andExpect(status().isBadRequest());

        // append-only：数据库层拒绝 UPDATE/DELETE（55000）。
        String resuscitationId = jdbcTemplate.queryForObject("""
                SELECT id::text FROM m2_anes.resuscitation_record WHERE record_id = ?::uuid
                """, String.class, recordId);
        assertSqlState55000(() -> jdbcTemplate.update("""
                UPDATE m2_anes.resuscitation_record SET outcome = 'FAILED' WHERE id = ?::uuid
                """, resuscitationId), "append-only");
        assertSqlState55000(() -> jdbcTemplate.update("""
                DELETE FROM m2_anes.resuscitation_record WHERE id = ?::uuid
                """, resuscitationId), "append-only");

        // 审计：只含标识/类型/时间窗/条数，不含事件摘要原文。
        Map<String, Object> detail = latestAuditDetail("RESCUE_RECORD");
        assertThat(detail).containsKeys("recordId", "resuscitationId", "resusType", "startedAt",
                "endedAt", "outcome", "drugCount", "interventionCount");
        assertThat(detail.toString()).doesNotContain("胸外按压", "演示患者");

        // 无 rescue 权限（SYS_SURGEON）403。
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/resuscitations")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(write(resusBody(started, ended, "SUCCESS"))))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------- 数据范围与无权限 --

    @Test
    void dataScopeAndPermissionsAreEnforced() throws Exception {
        String clinician = login(CLIN_USERNAME, CLIN_PASSWORD);
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-ALERT-APP-0005");
        String recordId = insertRecord(scheduleId);
        insertVital(1, Instant.now().minusSeconds(900).truncatedTo(ChronoUnit.SECONDS), 70, 85);

        String noPerm = login(NO_PERM_USERNAME, NO_PERM_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/alerts")
                        .header("Authorization", "Bearer " + noPerm))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/anesthesia/alert-rules")
                        .header("Authorization", "Bearer " + noPerm))
                .andExpect(status().isForbidden());

        // DEPT 范围绑定到无关科室：记录单不可见 → 404（不泄露存在性）。
        String scoped = login(SCOPE_USERNAME, SCOPE_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/alerts")
                        .header("Authorization", "Bearer " + scoped))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/alerts/evaluate")
                        .header("Authorization", "Bearer " + scoped))
                .andExpect(status().isNotFound());

        // 不存在的告警：404。
        mockMvc.perform(post("/v1/anesthesia/alerts/"
                        + UUID.randomUUID() + "/acknowledge")
                        .header("Authorization", "Bearer " + clinician)
                        .contentType(JSON)
                        .content(write(Map.of("decision", "CONFIRMED", "reason", "演示",
                                "version", 1))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/records/" + UUID.randomUUID() + "/alerts")
                        .header("Authorization", "Bearer " + clinician))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------- fixtures --

    private void resetAlertState() {
        jdbcTemplate.execute("ALTER TABLE m2_anes.alert DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.alert_rule DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.resuscitation_record DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m2_anes.alert");
            jdbcTemplate.update("DELETE FROM m2_anes.resuscitation_record");
            jdbcTemplate.update("DELETE FROM m2_anes.alert_rule WHERE version > 1");
            jdbcTemplate.update("UPDATE m2_anes.alert_rule SET enabled = true WHERE version = 1");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.alert ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.alert_rule ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.resuscitation_record ENABLE TRIGGER USER");
        }
    }

    private void deleteClinicalRows() {
        jdbcTemplate.update("DELETE FROM m2_anes.device_ingest_log WHERE gateway_id = ?", GATEWAY);
        jdbcTemplate.update("DELETE FROM m2_anes.device_registry WHERE gateway_id = ?", GATEWAY);
        jdbcTemplate.update("DELETE FROM m2_anes.anesthesia_vital WHERE device_id = ?", DEVICE);
        jdbcTemplate.update("DELETE FROM m2_anes.alert_watermark");
        jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m2_anes.anesthesia_record");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record ENABLE TRIGGER USER");
        }
    }

    private void clearSchedules() {
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
            jdbcTemplate.execute(
                    "ALTER TABLE m1_sched.application_status_history ENABLE TRIGGER USER");
        }
        insertPatient();
    }

    private void insertPatient() {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, '演示患者戊', 'MALE', DATE '1968-03-12', 58, 'SYNT****9419', ?,
                        '预警抢救合成科室', ?::uuid, 'f1000000-0000-4000-8000-000000000001'::uuid,
                        '演示病区', '05', now(), now(), 1, false)
                """, PATIENT_ID, HIS_NO, "hash-" + HIS_NO, DEPT_ORG);
    }

    private void ensureOrg() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_org (id, code, name, org_type, path, sort, status)
                VALUES (?::uuid, 'SYN_ALERT_DEPT', '预警抢救合成科室', 'DEPT', ?, 99, 1)
                ON CONFLICT (id) DO NOTHING
                """, DEPT_ORG, "/" + DEPT_ORG);
    }

    private void ensureRoles() {
        ensureRole(CLIN_ROLE_ID, "SYN_ALERT_CLIN", "合成预警临床角色", "ALL",
                List.of("m2:alert:read", "m2:alert:ack", "m2:rescue:write", "m2:record:read",
                        "m2:record:write"));
        ensureRole(SCOPE_ROLE_ID, "SYN_ALERT_DEPT", "合成预警科室角色", "DEPT",
                List.of("m2:alert:read", "m2:record:read", "m2:record:write"));
        ensureRole(NO_PERM_ROLE_ID, "SYN_ALERT_NONE", "合成无预警权限角色", "ALL", List.of());
    }

    private void ensureRole(String id, String code, String name, String dataScope,
            List<String> permissions) {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, ?, ?, 'SYNTHETIC：M3-M2-02 预警/抢救用例', ?)
                ON CONFLICT (id) DO NOTHING
                """, id, code, name, dataScope);
        for (String permission : permissions) {
            jdbcTemplate.update("""
                    INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                    SELECT ?::uuid, id FROM m0_platform.sys_permission WHERE code = ?
                    ON CONFLICT DO NOTHING
                    """, id, permission);
        }
    }

    private void ensureUser(String id, String username, String password, String roleId,
            String orgId) {
        String hash = new BCryptPasswordEncoder().encode(password);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '预警抢救用例账号（合成）', 1, false, 0, 1, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, id, username, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, id, roleId);
        if (orgId != null) {
            jdbcTemplate.update("""
                    INSERT INTO m0_platform.sys_user_org (user_id, org_id, is_primary)
                    VALUES (?::uuid, ?::uuid, true) ON CONFLICT DO NOTHING
                    """, id, orgId);
        }
    }

    private String createSchedule(String token, String hisApplicationNo) throws Exception {
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("hisApplicationNo", hisApplicationNo);
        application.put("patientId", PATIENT_ID);
        application.put("surgeryName", "演示腹腔镜胆囊切除术（合成）");
        application.put("urgency", "ELECTIVE");
        application.put("plannedDurationMin", 120);
        application.put("surgeonName", "演示术者（合成）");
        MvcResult created = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(application)))
                .andExpect(status().isCreated())
                .andReturn();
        String applicationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("applicationId").asText();

        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("applicationId", applicationId);
        schedule.put("roomId", ROOM);
        schedule.put("plannedStart", "2026-12-03T08:00:00+08:00");
        schedule.put("plannedEnd", "2026-12-03T10:00:00+08:00");
        schedule.put("sequenceNo", 1);
        schedule.put("priority", "ELECTIVE");
        MvcResult scheduled = mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(schedule)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(scheduled.getResponse().getContentAsString())
                .get("scheduleId").asText();
    }

    /** 直接插入进行中的记录单（startedAt 在 1 小时前），避免事件时间必须晚于创建时间的约束。 */
    private String insertRecord(String scheduleId) {
        jdbcTemplate.update("""
                INSERT INTO m2_anes.anesthesia_record
                    (id, schedule_id, application_id, patient_id, room_id, status, anesthesia_type,
                     started_at, ended_at, created_at, created_by, updated_at, updated_by,
                     version, deleted)
                SELECT ?::uuid, s.id, s.application_id, ?::uuid, s.room_id, 'ONGOING', 'GENERAL',
                       now() - interval '1 hour', NULL, now() - interval '2 hours', NULL,
                       now() - interval '2 hours', NULL, 3, false
                  FROM m1_sched.schedule s WHERE s.id = ?::uuid
                """, RECORD_SCENARIO, PATIENT_ID, scheduleId);
        jdbcTemplate.update("""
                INSERT INTO m2_anes.device_registry
                    (id, gateway_id, device_id, room_id, device_type, status, last_seen_at,
                     quality_stats)
                VALUES (gen_random_uuid(), ?, ?, ?::uuid, 'MONITOR', 'ONLINE', now(), '{}'::jsonb)
                ON CONFLICT (gateway_id, device_id) DO UPDATE SET room_id = EXCLUDED.room_id
                """, GATEWAY, DEVICE, ROOM);
        return RECORD_SCENARIO;
    }

    /** 直接插入 DRAFT 记录单（started_at 必须为 NULL）：用于“草稿不评估”负向用例。 */
    private String insertDraftRecord(String scheduleId) {
        String recordId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO m2_anes.anesthesia_record
                    (id, schedule_id, application_id, patient_id, room_id, status, anesthesia_type,
                     started_at, ended_at, created_at, created_by, updated_at, updated_by,
                     version, deleted)
                SELECT ?::uuid, s.id, s.application_id, ?::uuid, s.room_id, 'DRAFT', 'GENERAL',
                       NULL, NULL, now() - interval '2 hours', NULL,
                       now() - interval '2 hours', NULL, 1, false
                  FROM m1_sched.schedule s WHERE s.id = ?::uuid
                """, recordId, PATIENT_ID, scheduleId);
        jdbcTemplate.update("""
                INSERT INTO m2_anes.device_registry
                    (id, gateway_id, device_id, room_id, device_type, status, last_seen_at,
                     quality_stats)
                VALUES (gen_random_uuid(), ?, ?, ?::uuid, 'MONITOR', 'ONLINE', now(), '{}'::jsonb)
                ON CONFLICT (gateway_id, device_id) DO UPDATE SET room_id = EXCLUDED.room_id
                """, GATEWAY, DEVICE, ROOM);
        return recordId;
    }

    /** 通过真实 ingest 端点上报体征（X-Gateway-Token），用于验证落库后领域事件评估。 */
    private void ingestFrames(String batchKey, List<Map<String, Object>> frames) throws Exception {
        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .contentType(JSON)
                        .content(write(Map.of("batchKey", batchKey, "frames", frames))))
                .andExpect(status().isAccepted());
    }

    private static Map<String, Object> ingestFrame(long seq, Instant measuredAt, int hr, int spo2) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("seq", seq);
        frame.put("measuredAt", measuredAt.toString());
        frame.put("hr", hr);
        frame.put("spo2", spo2);
        frame.put("nibpSys", 120);
        frame.put("nibpDia", 80);
        frame.put("etco2", 35.0);
        frame.put("temp", 36.7);
        frame.put("quality", 0);
        frame.put("flags", List.of());
        frame.put("source", "AUTO");
        return frame;
    }


    private void insertVital(long seq, Instant measuredAt, int hr, int spo2) {
        jdbcTemplate.update("""
                INSERT INTO m2_anes.anesthesia_vital
                    (gateway_id, device_id, seq, measured_at, measured_at_corrected, received_at,
                     hr, spo2, nibp_sys, nibp_dia, etco2, temp, quality, source, synthetic)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 120, 80, 35.0, 36.7, 0, 'AUTO', true)
                """, GATEWAY, DEVICE, seq,
                OffsetDateTime.ofInstant(measuredAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(measuredAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(measuredAt.plusSeconds(1), ZoneOffset.UTC), hr, spo2);
    }

    private Map<String, Object> ruleBody(String low, int duration, String severity,
            boolean enabled, int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("thresholdLow", low == null ? null : new java.math.BigDecimal(low));
        body.put("thresholdHigh", null);
        body.put("durationSeconds", duration);
        body.put("severity", severity);
        body.put("enabled", enabled);
        body.put("version", version);
        return body;
    }

    private Map<String, Object> resusBody(Instant startedAt, Instant endedAt, String outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resusType", "CPR");
        body.put("startedAt", startedAt.atOffset(ZoneOffset.UTC).toString());
        body.put("endedAt", endedAt.atOffset(ZoneOffset.UTC).toString());
        body.put("eventSummary", Map.of("summary", "演示：胸外按压（合成数据）",
                "interventions", List.of("胸外按压")));
        body.put("leader", Map.of("userId", CLIN_ID, "name", "演示负责人（合成）"));
        body.put("rescuers", List.of(
                Map.of("userId", CLIN_ID, "name", "演示负责人（合成）"),
                Map.of("name", "演示护士（合成）")));
        body.put("drugs", List.of(Map.of("drugName", "肾上腺素（演示）", "dose", 1, "unit", "mg",
                "route", "IV",
                "administeredAt", startedAt.plusSeconds(30).atOffset(ZoneOffset.UTC).toString())));
        body.put("outcome", outcome);
        return body;
    }

    private String write(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private String ruleIdOf(MvcResult result, String code) throws Exception {
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("items");
        for (JsonNode item : items) {
            if (code.equals(item.get("code").asText())) {
                return item.get("ruleId").asText();
            }
        }
        return null;
    }

    private String firstAlertId(String token, String recordId) throws Exception {
        MvcResult result = mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/alerts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(greaterThanOrEqualTo(1)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("items").get(0).get("alertId").asText();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password,
                                "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private boolean columnExists(String schema, String table, String column) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = ? AND table_name = ? AND column_name = ?
                """, Long.class, schema, table, column);
        return count != null && count > 0;
    }

    private boolean tableExists(String schema, String table) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = ? AND table_name = ?
                """, Long.class, schema, table);
        return count != null && count > 0;
    }

    private boolean constraintExists(String name, String table) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conname = ? AND conrelid = ?::regclass
                """, Long.class, name, table);
        return count != null && count > 0;
    }

    private boolean indexExists(String name) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname = 'm2_anes' AND indexname = ?
                """, Long.class, name);
        return count != null && count > 0;
    }

    private List<String> triggerNames(String table) {
        return jdbcTemplate.queryForList("""
                SELECT tgname FROM pg_trigger
                 WHERE tgrelid = (?::regclass) AND NOT tgisinternal ORDER BY tgname
                """, String.class, table);
    }

    private long auditCount(String action) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_audit_log WHERE action = ?", Long.class,
                action);
        return count == null ? 0 : count;
    }

    private Map<String, Object> latestAuditDetail(String action) {
        String json = jdbcTemplate.queryForObject("""
                SELECT detail::text FROM m0_platform.sys_audit_log
                 WHERE action = ? ORDER BY occurred_at DESC LIMIT 1
                """, String.class, action);
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static void assertSqlState55000(Runnable action, String messageFragment) {
        Throwable failure = org.assertj.core.api.Assertions.catchThrowable(action::run);
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(SQLException.class);
        assertThat(((SQLException) root).getSQLState()).isEqualTo("55000");
        assertThat(root.getMessage()).contains(messageFragment);
    }
}
