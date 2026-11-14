package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 质控指标、不良事件 RCA、病历抽查与导出（M4-M17-01）端到端用例：MockMvc + 真实嵌入式
 * PostgreSQL 16 + 真实 Flyway（含 V25 迁移：m4_qc schema、触发器与权限）。
 *
 * <p>固定 SYNTHETIC 数据（2026-01，避免与其他用例日期交叉；固定主键 + ON CONFLICT DO NOTHING
 * 保证重复执行幂等；用例结束只清理本域产生的随机行）：
 * <ul>
 *   <li>台次 4 条：麻醉科 3 条（2 条记录单已结束 + 1 条进行中；2 条有术前访视；1 条完成
 *       三方核查）+ 手术室 1 条（记录单已结束、有访视、完成三方核查）；</li>
 *   <li>麻精专册 2 条（双人签字完整 1 条，第二签字人姓名为空 1 条）；</li>
 *   <li>不良事件 1 条（REPORTED）；病历抽查 2 条（95/100 合格、80/100 不合格）；</li>
 *   <li>高值耗材 1 个 + 出库流水 3 条（2 条带 UDI）。</li>
 * </ul>
 * 手算（全院）：记录单完成率 3/4=75%、访视 3/4、三方核查 2/4、麻精双人 1/2、
 * 不良事件 1/4×1000=250‰、抽查合格 1/2、高值 UDI 2/3=66.67%。
 * 覆盖：指标手算、分母 0/达标、DEPT 数据范围与越界 403、无权限 403、不良事件状态机 409、
 * append-only 55000、抽查评分与整改闭环、导出 BOM/防注入/无 PHI/无自由文本、审计。
 */
class M4QcApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String DEPT_ANES = SyntheticAccounts.DEMO_ANES_ORG_ID;
    private static final String DEPT_OR = SyntheticAccounts.DEMO_OR_ORG_ID;
    private static final String SITE = "f1000000-0000-4000-8000-000000000001";
    private static final String ROOM_01 = "f2000000-0000-4000-8000-0000000000c1";
    private static final String ROOM_05 = "f2000000-0000-4000-8000-0000000000c5";
    private static final String ADMIN_ID = SyntheticAccounts.DEMO_ADMIN_ID;
    private static final String FROM = "2026-01-01";
    private static final String TO = "2026-01-31";
    private static final String DRUG_SUFENTANIL = "2a000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedFixtures() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        seedCases();
        seedPreopAndThreeWay();
        seedNarcoticRegister();
        seedAdverseEvent();
        seedChartReviews();
        seedHighValueMaterial();
    }

    @AfterEach
    void cleanFixtures() {
        // 质控留痕与临床/核查/记录单 append-only：测试夹具临时停用触发器，结束后立即恢复。
        for (String table : List.of("m4_qc.qc_indicator", "m4_qc.adverse_event",
                "m4_qc.adverse_rca", "m4_qc.chart_review", "m4_qc.rectification",
                "m2_anes.anesthesia_record", "m2_anes.three_way_check",
                "m2_anes.preop_assessment")) {
            jdbcTemplate.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
        }
        try {
            jdbcTemplate.update("DELETE FROM m4_qc.rectification");
            jdbcTemplate.update("DELETE FROM m4_qc.adverse_rca");
            jdbcTemplate.update("DELETE FROM m4_qc.adverse_event");
            jdbcTemplate.update("DELETE FROM m4_qc.chart_review");
            jdbcTemplate.update("DELETE FROM m4_qc.qc_indicator_result");
            // 版本选择/重叠用例临时插入的指标定义（触发器已在上面停用）。
            jdbcTemplate.update(
                    "DELETE FROM m4_qc.qc_indicator WHERE code LIKE 'QC-TEST-%'");
            // 清理本类真实排程/申请夹具：先删子表（访视/核查/记录单），再删台次与申请，
            // 避免其他测试类清理 m1_sched.schedule/surgery_application 时被外键阻塞。
            jdbcTemplate.update("""
                    DELETE FROM m2_anes.preop_assessment
                     WHERE application_id IN (
                         SELECT id FROM m1_sched.surgery_application
                          WHERE remark = 'SYNTH-M4-QC')
                    """);
            jdbcTemplate.update("""
                    DELETE FROM m2_anes.three_way_check
                     WHERE application_id IN (
                         SELECT id FROM m1_sched.surgery_application
                          WHERE remark = 'SYNTH-M4-QC')
                    """);
            jdbcTemplate.update("""
                    DELETE FROM m2_anes.anesthesia_record
                     WHERE schedule_id IN (
                         SELECT id FROM m1_sched.schedule WHERE reason = 'SYNTH-M4-QC')
                    """);
            jdbcTemplate.update("DELETE FROM m1_sched.schedule WHERE reason = 'SYNTH-M4-QC'");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.surgery_application WHERE remark = 'SYNTH-M4-QC'");
            jdbcTemplate.update("""
                    DELETE FROM m1_sched.patient_snapshot
                     WHERE his_patient_no LIKE 'SYNTH-M4-QC-%'
                    """);
        } finally {
            for (String table : List.of("m4_qc.qc_indicator", "m4_qc.adverse_event",
                    "m4_qc.adverse_rca", "m4_qc.chart_review", "m4_qc.rectification",
                    "m2_anes.anesthesia_record", "m2_anes.three_way_check",
                "m2_anes.preop_assessment")) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ENABLE TRIGGER USER");
            }
        }
    }

    // ------------------------------------------------------------------
    // 指标：手算 + 数据范围 + 权限
    // ------------------------------------------------------------------

    @Test
    void indicatorResultsMatchHandCalculatedValues() throws Exception {
        String admin = loginAdmin();
        MvcResult result = mockMvc.perform(get("/v1/qc/indicator-results")
                        .param("from", FROM).param("to", TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.items.length()").value(7))
                .andReturn();
        Map<String, JsonNode> items = resultsByCode(result);
        assertIndicator(items, "QC-SYN-STRUCT-001", 3, 4, "75.00", false);
        assertIndicator(items, "QC-SYN-PROC-002", 3, 4, "75.00", false);
        assertIndicator(items, "QC-SYN-PROC-003", 2, 4, "50.00", false);
        assertIndicator(items, "QC-SYN-PROC-004", 1, 2, "50.00", false);
        assertIndicator(items, "QC-SYN-OUT-005", 1, 4, "250.00", true);
        assertIndicator(items, "QC-SYN-PROC-006", 1, 2, "50.00", false);
        assertIndicator(items, "QC-SYN-STRUCT-007", 2, 3, "66.67", false);
        assertThat(items.get("QC-SYN-STRUCT-001").get("provisional").asBoolean()).isTrue();
        assertThat(items.get("QC-SYN-STRUCT-001").get("inTargetStatistics").asBoolean()).isTrue();
        // 结构合规口径不计入达标统计（S-1）。
        JsonNode structureCompliance = items.get("QC-SYN-PROC-004");
        assertThat(structureCompliance.get("category").asText()).isEqualTo("STRUCTURE");
        assertThat(structureCompliance.get("inTargetStatistics").asBoolean()).isFalse();
        assertThat(structureCompliance.get("targetMet").asBoolean()).isFalse();
        // 结果快照留存（7 条指标 × 全院口径）。
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m4_qc.qc_indicator_result WHERE period_from = DATE '2026-01-01'",
                Integer.class);
        assertThat(stored).isEqualTo(7);
        // 审计：指标查询/计算均可追溯，detail 不含患者标识。
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_audit_log WHERE action = 'QC_INDICATOR_COMPUTE'",
                Integer.class);
        assertThat(audits).isGreaterThanOrEqualTo(1);
    }

    @Test
    void indicatorResultsRespectDepartmentScopeAndRejectOutOfScope() throws Exception {
        String deptUser = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        MvcResult result = mockMvc.perform(get("/v1/qc/indicator-results")
                        .param("from", FROM).param("to", TO)
                        .param("deptId", DEPT_ANES)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isOk())
                .andReturn();
        Map<String, JsonNode> items = resultsByCode(result);
        // 麻醉科 3 台次：2 条记录单已结束、1 条完成三方核查。
        assertIndicator(items, "QC-SYN-STRUCT-001", 2, 3, "66.67", false);
        assertIndicator(items, "QC-SYN-PROC-003", 1, 3, "33.33", false);

        mockMvc.perform(get("/v1/qc/indicator-results")
                        .param("from", FROM).param("to", TO)
                        .param("deptId", DEPT_OR)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isForbidden());
    }

    @Test
    void indicatorsRequireQcReadPermission() throws Exception {
        String self = login(SyntheticAccounts.DEMO_SELF_USERNAME,
                SyntheticAccounts.DEMO_SELF_PASSWORD);
        mockMvc.perform(get("/v1/qc/indicators")
                        .header("Authorization", "Bearer " + self))
                .andExpect(status().isForbidden());
    }

    @Test
    void indicatorResultsSelectVersionByEffectivePeriodAndSplitCrossVersionWindow() throws Exception {
        insertIndicatorVersion("QC-TEST-VER-901", "1.0.0", "2027-01-01", "2027-01-31");
        insertIndicatorVersion("QC-TEST-VER-901", "2.0.0", "2027-02-01", null);
        String admin = loginAdmin();

        // 旧版本 effective_to 之前：只返回旧版本。
        MvcResult january = mockMvc.perform(get("/v1/qc/indicator-results")
                        .param("from", "2027-01-10").param("to", "2027-01-20")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        List<JsonNode> jan = itemsByCode(january, "QC-TEST-VER-901");
        assertThat(jan).hasSize(1);
        assertThat(jan.get(0).get("indicatorVersion").asText()).isEqualTo("1.0.0");
        assertThat(jan.get(0).get("periodFrom").asText()).isEqualTo("2027-01-10");
        assertThat(jan.get(0).get("periodTo").asText()).isEqualTo("2027-01-20");

        // 新版本生效之后：只返回新版本。
        MvcResult february = mockMvc.perform(get("/v1/qc/indicator-results")
                        .param("from", "2027-02-10").param("to", "2027-02-20")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        List<JsonNode> feb = itemsByCode(february, "QC-TEST-VER-901");
        assertThat(feb).hasSize(1);
        assertThat(feb.get(0).get("indicatorVersion").asText()).isEqualTo("2.0.0");
        assertThat(feb.get(0).get("periodFrom").asText()).isEqualTo("2027-02-10");
        assertThat(feb.get(0).get("periodTo").asText()).isEqualTo("2027-02-20");

        // 区间跨版本：按有效期切分两段，各自使用当期版本，不回溯合并。
        MvcResult spanning = mockMvc.perform(get("/v1/qc/indicator-results")
                        .param("from", "2027-01-10").param("to", "2027-02-20")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        List<JsonNode> split = itemsByCode(spanning, "QC-TEST-VER-901");
        assertThat(split).hasSize(2);
        assertThat(split.get(0).get("indicatorVersion").asText()).isEqualTo("1.0.0");
        assertThat(split.get(0).get("periodFrom").asText()).isEqualTo("2027-01-10");
        assertThat(split.get(0).get("periodTo").asText()).isEqualTo("2027-01-31");
        assertThat(split.get(1).get("indicatorVersion").asText()).isEqualTo("2.0.0");
        assertThat(split.get(1).get("periodFrom").asText()).isEqualTo("2027-02-01");
        assertThat(split.get(1).get("periodTo").asText()).isEqualTo("2027-02-20");
    }

    @Test
    void indicatorResultsRejectOverlappingEffectiveVersions() throws Exception {
        insertIndicatorVersion("QC-TEST-OVER-902", "1.0.0", "2027-03-01", "2027-03-31");
        insertIndicatorVersion("QC-TEST-OVER-902", "2.0.0", "2027-03-15", null);
        String admin = loginAdmin();

        mockMvc.perform(get("/v1/qc/indicator-results")
                        .param("from", "2027-03-01").param("to", "2027-03-31")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.details[0].field").value("code"))
                .andExpect(jsonPath("$.details[0].reason").value(
                        org.hamcrest.Matchers.containsString("重叠")));
    }

    @Test
    void qcIndicatorDefinitionsRejectDeleteAndTruncate() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM m4_qc.qc_indicator WHERE code = 'QC-SYN-STRUCT-001'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("55000");
        assertThatThrownBy(() -> jdbcTemplate.execute(
                "TRUNCATE m4_qc.qc_indicator CASCADE"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("55000");
    }

    @Test
    void qcReadWriteRoleCannotExportOrReview() throws Exception {
        String deptUser = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        // 对照：SYS_OR_MANAGER 有 read+write，指标目录可读。
        mockMvc.perform(get("/v1/qc/indicators")
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isOk());
        // 无 m4:qc:export → 403。
        mockMvc.perform(get("/v1/qc/export")
                        .param("type", "adverse").param("format", "csv")
                        .param("from", FROM).param("to", TO)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isForbidden());
        // 无 m4:qc:review → 403（权限先于资源存在性）。
        mockMvc.perform(post("/v1/qc/rca/7e000000-0000-4000-8000-0000000000f1/close")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + deptUser)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reviewerName", "演示复核人（合成）",
                                "conclusion", "复核（合成）",
                                "rectificationCompleted", false))))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // 不良事件状态机与 append-only
    // ------------------------------------------------------------------

    @Test
    void adverseEventStateMachineRejectsSkippingAndClosesInTwoStages() throws Exception {
        String admin = loginAdmin();
        MvcResult created = mockMvc.perform(post("/v1/qc/adverse-events")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "eventType", "MEDICATION",
                                "severity", "LEVEL_II",
                                "occurredAt", "2026-01-12T10:30:00+08:00",
                                "deptId", DEPT_ANES,
                                "description", "诱导后一过性血压下降（合成，已脱敏）",
                                "anonymous", false))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REPORTED"))
                .andExpect(jsonPath("$.reporterName").value("demo_admin"))
                .andReturn();
        String eventId = read(created).get("id").asText();
        String eventNo = read(created).get("eventNo").asText();

        MvcResult rcaCreated = mockMvc.perform(post("/v1/qc/adverse-events/" + eventId + "/rca")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeline", List.of(Map.of(
                                        "occurredAt", "2026-01-12T10:30:00+08:00",
                                        "description", "诱导后 3 分钟血压下降（合成）")),
                                "rootCause", "诱导期容量评估不足（合成）",
                                "correctiveAction", "修订容量评估清单（合成）",
                                "responsibleName", "=SUM(1,2)",
                                "dueDate", "2026-02-15"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventStatus").value("RCA"))
                .andReturn();
        String rcaId = read(rcaCreated).get("id").asText();

        // 重复提交 RCA → 409（状态机不允许重复/越级）。
        mockMvc.perform(post("/v1/qc/adverse-events/" + eventId + "/rca")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeline", List.of(Map.of(
                                        "occurredAt", "2026-01-12T10:30:00+08:00",
                                        "description", "重复提交（合成）")),
                                "rootCause", "重复（合成）",
                                "correctiveAction", "重复（合成）",
                                "responsibleName", "演示责任人（合成）",
                                "dueDate", "2026-02-15"))))
                .andExpect(status().isConflict());

        // 阶段 1：复核（不得提前声明整改完成）。
        mockMvc.perform(post("/v1/qc/rca/" + rcaId + "/close")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reviewerName", "演示复核人（合成）",
                                "conclusion", "根因认可（合成）",
                                "rectificationCompleted", true))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/qc/rca/" + rcaId + "/close")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reviewerName", "演示复核人（合成）",
                                "conclusion", "根因认可（合成）",
                                "rectificationCompleted", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventStatus").value("RECTIFYING"));

        // 阶段 2：必须确认整改完成，复核人须与阶段 1 一致；确认后事件 CLOSED。
        mockMvc.perform(post("/v1/qc/rca/" + rcaId + "/close")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reviewerName", "演示复核人（合成）",
                                "conclusion", "整改完成（合成）",
                                "rectificationCompleted", false))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/qc/rca/" + rcaId + "/close")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reviewerName", "演示复核人（合成）",
                                "conclusion", "整改完成（合成）",
                                "rectificationCompleted", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.eventStatus").value("CLOSED"));

        // 已闭环再次闭环 → 409；数据库层状态亦为 CLOSED。
        mockMvc.perform(post("/v1/qc/rca/" + rcaId + "/close")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reviewerName", "演示复核人（合成）",
                                "conclusion", "重复闭环（合成）",
                                "rectificationCompleted", true))))
                .andExpect(status().isConflict());
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM m4_qc.adverse_event WHERE event_no = ?", String.class,
                eventNo);
        assertThat(status).isEqualTo("CLOSED");
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_audit_log WHERE detail->>'eventNo' = ?"
                        + " AND action IN ('RCA_CREATE','RCA_REVIEW','RCA_CLOSE',"
                        + "'ADVERSE_EVENT_STATUS')",
                Integer.class, eventNo);
        assertThat(audits).isGreaterThanOrEqualTo(4);
    }

    @Test
    void adverseEventRegistrationRejectsPhiAndIsAudited() throws Exception {
        String admin = loginAdmin();
        mockMvc.perform(post("/v1/qc/adverse-events")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "eventType", "MEDICATION",
                                "severity", "LEVEL_II",
                                "occurredAt", "2026-01-12T10:30:00+08:00",
                                "deptId", DEPT_ANES,
                                "description", "患者住院号 12345678901 血压下降"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));

        MvcResult created = mockMvc.perform(post("/v1/qc/adverse-events")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "eventType", "FALL",
                                "severity", "LEVEL_III",
                                "occurredAt", "2026-01-13T09:00:00+08:00",
                                "deptId", DEPT_ANES,
                                "description", "转运途中床栏未固定（合成，已脱敏）",
                                "anonymous", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reporterName").value("匿名上报"))
                .andReturn();
        String eventNo = read(created).get("eventNo").asText();
        String detail = jdbcTemplate.queryForObject(
                "SELECT detail::text FROM m0_platform.sys_audit_log"
                        + " WHERE action = 'ADVERSE_EVENT_CREATE' AND detail->>'eventNo' = ?",
                String.class, eventNo);
        assertThat(detail).contains(eventNo).doesNotContain("床栏未固定");
    }

    @Test
    void adverseEventAndRcaAreAppendOnlyAtDatabaseLevel() throws Exception {
        String admin = loginAdmin();
        String eventId = createEventWithRca(admin);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE m4_qc.adverse_event SET description = '改描述' WHERE id = ?::uuid",
                eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("55000");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM m4_qc.adverse_event WHERE id = ?::uuid", eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("55000");
        // 越级状态迁移（REPORTED → CLOSED）同样被触发器拒绝。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE m4_qc.adverse_event SET status = 'CLOSED', version = version + 1"
                        + " WHERE id = ?::uuid", eventId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("55000");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE m4_qc.adverse_rca SET root_cause = '改根因'"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("55000");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM m4_qc.adverse_rca"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("55000");
    }

    // ------------------------------------------------------------------
    // 病历抽查与整改
    // ------------------------------------------------------------------

    @Test
    void adverseEventAndChartReviewRejectMissingOrCrossDepartmentReferences() throws Exception {
        String admin = loginAdmin();
        String missingSchedule = "56000000-0000-4000-8000-000000009999";
        String orSchedule = "56000000-0000-4000-8000-000000001004";
        String anesSchedule = "56000000-0000-4000-8000-000000001001";

        // 台次不存在 → 422。
        mockMvc.perform(post("/v1/qc/adverse-events")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(
                                adverseEventRequest(missingSchedule))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.details[0].field").value("scheduleId"));
        // 台次属他科 → 403。
        mockMvc.perform(post("/v1/qc/adverse-events")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(
                                adverseEventRequest(orSchedule))))
                .andExpect(status().isForbidden());
        // 本科室台次 → 201。
        mockMvc.perform(post("/v1/qc/adverse-events")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(
                                adverseEventRequest(anesSchedule))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduleId").value(anesSchedule));

        String missingRecord = "57000000-0000-4000-8000-000000009999";
        String orRecord = "57000000-0000-4000-8000-000000001004";
        String anesRecord = "57000000-0000-4000-8000-000000001001";
        // 记录单不存在 → 422。
        mockMvc.perform(post("/v1/qc/chart-reviews")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(
                                chartReviewRequest(missingRecord))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("recordId"));
        // 记录单属他科 → 403。
        mockMvc.perform(post("/v1/qc/chart-reviews")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(
                                chartReviewRequest(orRecord))))
                .andExpect(status().isForbidden());
        // 本科室记录单 → 201。
        mockMvc.perform(post("/v1/qc/chart-reviews")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(
                                chartReviewRequest(anesRecord))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordId").value(anesRecord));
    }

    @Test
    void chartReviewScoresAndRectificationCloses() throws Exception {
        String admin = loginAdmin();
        MvcResult created = mockMvc.perform(post("/v1/qc/chart-reviews")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deptId", DEPT_ANES,
                                "reviewedAt", "2026-01-14T09:00:00+08:00",
                                "items", List.of(
                                        reviewItem("COMPLETENESS", 28, 30, "缺术后随访（合成）"),
                                        reviewItem("TIMELINESS", 25, 25, null),
                                        reviewItem("SIGNATURE", 20, 20, null),
                                        reviewItem("CONSISTENCY", 15, 25, "出入量不一致（合成）"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalScore").value(88))
                .andExpect(jsonPath("$.maxScore").value(100))
                .andExpect(jsonPath("$.grade").value("C"))
                .andExpect(jsonPath("$.defectCount").value(2))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();
        JsonNode review = read(created);
        String reviewId = review.get("id").asText();

        // 重复提交整改 → 409；越权复核人（=责任人）→ 422。
        MvcResult rectified = mockMvc.perform(post("/v1/qc/chart-reviews/" + reviewId + "/rectify")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "correctiveAction", "补录术后随访并核对出入量（合成）",
                                "responsibleName", "演示责任人（合成）",
                                "dueDate", "2026-01-25"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewStatus").value("RECTIFYING"))
                .andReturn();
        String rectificationId = read(rectified).get("id").asText();
        mockMvc.perform(post("/v1/qc/chart-reviews/" + reviewId + "/rectify")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "correctiveAction", "重复整改（合成）",
                                "responsibleName", "演示责任人（合成）",
                                "dueDate", "2026-01-26"))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/qc/rectify/" + rectificationId + "/close")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reviewerName", "演示责任人（合成）",
                                "conclusion", "自审（合成）"))))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(post("/v1/qc/rectify/" + rectificationId + "/close")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reviewerName", "演示复核人（合成）",
                                "conclusion", "缺陷已整改（合成）"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.reviewStatus").value("CLOSED"));

        // 列表返回整改标识，供前端闭环；审计含创建/闭环动作。
        MvcResult list = mockMvc.perform(get("/v1/qc/chart-reviews")
                        .param("status", "CLOSED")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode item = read(list).get("items").get(0);
        assertThat(item.get("rectificationId").asText()).isEqualTo(rectificationId);
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_audit_log"
                        + " WHERE action IN ('CHART_REVIEW_CREATE','RECTIFICATION_CREATE',"
                        + "'RECTIFICATION_CLOSE')", Integer.class);
        assertThat(audits).isGreaterThanOrEqualTo(3);
    }

    @Test
    void chartReviewRejectsDuplicateItemsAndMissingDefectNote() throws Exception {
        String admin = loginAdmin();
        mockMvc.perform(post("/v1/qc/chart-reviews")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deptId", DEPT_ANES,
                                "reviewedAt", "2026-01-14T09:00:00+08:00",
                                "items", List.of(
                                        reviewItem("COMPLETENESS", 28, 30, "缺陷（合成）"),
                                        reviewItem("COMPLETENESS", 25, 25, null))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].reason").value("评分项 itemCode 不得重复"));

        mockMvc.perform(post("/v1/qc/chart-reviews")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + admin)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deptId", DEPT_ANES,
                                "reviewedAt", "2026-01-14T09:00:00+08:00",
                                "items", List.of(reviewItem("COMPLETENESS", 28, 30, null))))))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // 导出：BOM / 防注入 / 无 PHI / 无自由文本
    // ------------------------------------------------------------------

    @Test
    void exportIsBomMaskedInjectionSafeAndFreeOfPhiAndFreeText() throws Exception {
        String admin = loginAdmin();
        // 先产生一条带公式注入责任人（=SUM(1,2)）与自由文本的 RCA。
        String eventId = createEventWithRca(admin);
        assertThat(eventId).isNotBlank();

        MvcResult exported = mockMvc.perform(get("/v1/qc/export")
                        .param("type", "adverse").param("format", "csv")
                        .param("from", FROM).param("to", TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("qc-adverse-2026-01-01-2026-01-31.csv")))
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.parseMediaType("text/csv")))
                .andReturn();
        String csv = exported.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("type,eventNo,eventType");
        assertThat(csv).contains("'=SUM(1,2)");
        // 无自由文本列（描述/根因/整改）与患者标识。
        assertThat(csv).doesNotContain("description").doesNotContain("rootCause")
                .doesNotContain("床栏未固定").doesNotContain("患者").doesNotContain("住院号");
        Integer exportAudits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_audit_log WHERE action = 'QC_EXPORT'",
                Integer.class);
        assertThat(exportAudits).isGreaterThanOrEqualTo(1);
    }

    @Test
    void exportIndicatorsAndReviewsKeepOnlyAggregateColumns() throws Exception {
        String admin = loginAdmin();
        MvcResult indicators = mockMvc.perform(get("/v1/qc/export")
                        .param("type", "indicator").param("format", "csv")
                        .param("from", FROM).param("to", TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        String indicatorCsv = indicators.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(indicatorCsv).startsWith("\uFEFF");
        assertThat(indicatorCsv).contains("qc-indicator,QC-SYN-STRUCT-001")
                .contains(",targetMet,inTargetStatistics,provisional,timezone")
                .contains(",3,4,75.00,PERCENT,GTE,95,false,true,true,Asia/Shanghai");
        // 结果重算留审计（S-4）：导出路径同样写 QC_INDICATOR_COMPUTE。
        Integer recomputes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_audit_log"
                        + " WHERE action = 'QC_INDICATOR_COMPUTE'"
                        + " AND detail->>'source' = 'export'", Integer.class);
        assertThat(recomputes).isGreaterThanOrEqualTo(1);

        MvcResult reviews = mockMvc.perform(get("/v1/qc/export")
                        .param("type", "review").param("format", "csv")
                        .param("from", FROM).param("to", TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        String reviewCsv = reviews.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(reviewCsv).startsWith("\uFEFF");
        assertThat(reviewCsv).contains("type,reviewNo,reviewedAt");
        // 抽查导出不含缺陷说明原文（仅计数）。
        assertThat(reviewCsv).doesNotContain("缺术后随访").doesNotContain("defectNote");
    }

    @Test
    void exportRejectsInvalidTypeAndFormat() throws Exception {
        String admin = loginAdmin();
        mockMvc.perform(get("/v1/qc/export")
                        .param("type", "unknown").param("format", "csv")
                        .param("from", FROM).param("to", TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(get("/v1/qc/export")
                        .param("type", "indicator").param("format", "xlsx")
                        .param("from", FROM).param("to", TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportOverLimitReturns422AndAuditsRealRowCount() throws Exception {
        insertBulkAdverseEvents();
        String admin = loginAdmin();

        mockMvc.perform(get("/v1/qc/export")
                        .param("type", "adverse").param("format", "csv")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.details[0].field").value("type"));

        String detail = jdbcTemplate.queryForObject(
                "SELECT detail::text FROM m0_platform.sys_audit_log"
                        + " WHERE action = 'QC_EXPORT' AND detail->>'truncated' = 'true'",
                String.class);
        assertThat(detail).contains("\"dataRows\": 50001")
                .contains("\"truncated\": true")
                .contains("\"limit\": 50000");
    }

    // ------------------------------------------------------------------
    // 夹具与工具
    // ------------------------------------------------------------------

    private void insertIndicatorVersion(String code, String version, String from, String to) {
        jdbcTemplate.update("""
                INSERT INTO m4_qc.qc_indicator
                    (id, code, name, category, definition, data_source, numerator_label,
                     denominator_label, unit, threshold_operator, threshold_value, version,
                     effective_from, effective_to, status, provisional, in_target_statistics)
                VALUES (?::uuid, ?, ?, 'PROCESS', '合成版本化口径（测试）', '合成数据源（测试）',
                        '分子', '分母', 'PERCENT', 'GTE', 90.00, ?, ?::date, ?::date,
                        'ACTIVE', true, true)
                ON CONFLICT DO NOTHING
                """, java.util.UUID.randomUUID().toString(), code,
                "版本化指标 " + code + " " + version, version, from, to);
    }

    private List<JsonNode> itemsByCode(MvcResult result, String code) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        List<JsonNode> items = new ArrayList<>();
        for (JsonNode item : body.get("items")) {
            if (code.equals(item.get("code").asText())) {
                items.add(item);
            }
        }
        return items;
    }

    private static Map<String, Object> adverseEventRequest(String scheduleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", "MEDICATION");
        body.put("severity", "LEVEL_II");
        body.put("occurredAt", "2026-01-12T10:30:00+08:00");
        body.put("deptId", DEPT_ANES);
        body.put("scheduleId", scheduleId);
        body.put("description", "关联台次校验（合成，已脱敏）");
        return body;
    }

    private static Map<String, Object> chartReviewRequest(String recordId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deptId", DEPT_ANES);
        body.put("reviewedAt", "2026-01-14T09:00:00+08:00");
        body.put("recordId", recordId);
        body.put("items", List.of(reviewItem("COMPLETENESS", 30, 30, null)));
        return body;
    }

    private void insertBulkAdverseEvents() {
        jdbcTemplate.update("""
                INSERT INTO m4_qc.adverse_event
                    (id, event_no, event_type, severity, occurred_at, dept_id, dept_name,
                     description, reporter_id, reporter_name, anonymous, status, version)
                SELECT gen_random_uuid(), 'AE-BULK-' || lpad(g::text, 6, '0'), 'OTHER',
                       'LEVEL_IV',
                       TIMESTAMPTZ '2026-03-01 08:00:00+08' + (g || ' seconds')::interval,
                       ?::uuid, '麻醉科（合成）', '批量合成事件（已脱敏）', ?::uuid,
                       'demo_admin', false, 'REPORTED', 1
                  FROM generate_series(1, 50001) AS g
                """, DEPT_ANES, ADMIN_ID);
    }

    private void seedCases() {
        insertCase("000000001001", ROOM_01, DEPT_ANES, "麻醉科（合成）", "SYNTH-QC-A",
                "2026-01-02T08:00:00+08:00", "2026-01-02T10:00:00+08:00", "COMPLETED", true);
        insertCase("000000001002", ROOM_01, DEPT_ANES, "麻醉科（合成）", "SYNTH-QC-B",
                "2026-01-03T08:00:00+08:00", "2026-01-03T09:30:00+08:00", "COMPLETED", true);
        insertCase("000000001003", ROOM_01, DEPT_ANES, "麻醉科（合成）", "SYNTH-QC-C",
                "2026-01-04T08:00:00+08:00", "2026-01-04T09:00:00+08:00", "PLANNED", false);
        insertCase("000000001004", ROOM_05, DEPT_OR, "手术室（合成）", "SYNTH-QC-D",
                "2026-01-05T08:00:00+08:00", "2026-01-05T11:00:00+08:00", "COMPLETED", true);
    }

    private void insertCase(String suffix, String roomId, String deptId, String deptName,
            String surgeryCode, String startAt, String endAt, String status, boolean ended) {
        String patientId = "54000000-0000-4000-8000-" + suffix;
        String applicationId = "55000000-0000-4000-8000-" + suffix;
        String scheduleId = "56000000-0000-4000-8000-" + suffix;
        String recordId = "57000000-0000-4000-8000-" + suffix;
        String hisNo = "SYNTH-M4-QC-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, '演示患者质控（合成）', 'MALE', DATE '1975-06-01', 56, ?,
                        ?, ?, ?::uuid, ?::uuid, '演示病区', '01', now(), now(), 1, false)
                ON CONFLICT (id) DO NOTHING
                """, patientId, hisNo, "SYNT****QC" + suffix.substring(suffix.length() - 1),
                "hash-" + hisNo, deptName, deptId, SITE);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, surgery_code, urgency,
                     planned_duration_min, dept_name, dept_id, site_id, surgeon_name, status,
                     source, remark, version, deleted)
                VALUES (?::uuid, ?, ?::uuid, '演示术式（合成）', ?, 'ELECTIVE', 120, ?, ?::uuid,
                        ?::uuid, '演示术者（合成）', 'SCHEDULED', 'MANUAL', 'SYNTH-M4-QC', 1,
                        false)
                ON CONFLICT (id) DO NOTHING
                """, applicationId, hisNo + "-APP", patientId, surgeryCode, deptName, deptId,
                SITE);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority, status,
                     reason, version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::timestamptz, ?::timestamptz, 1, 0, ?,
                        'SYNTH-M4-QC', 1, false)
                ON CONFLICT (id) DO NOTHING
                """, scheduleId, applicationId, roomId, startAt, endAt, status);
        jdbcTemplate.update("""
                INSERT INTO m2_anes.anesthesia_record
                    (id, schedule_id, application_id, patient_id, status, anesthesia_type,
                     started_at, ended_at, created_by, updated_by, version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'ONGOING', 'GENERAL', ?::timestamptz,
                        ?::timestamptz, ?::uuid, ?::uuid, 1, false)
                ON CONFLICT (id) DO NOTHING
                """, recordId, scheduleId, applicationId, patientId, startAt,
                ended ? endAt : null, ADMIN_ID, ADMIN_ID);
    }

    private void seedPreopAndThreeWay() {
        for (String suffix : List.of("000000001001", "000000001002", "000000001004")) {
            String applicationId = "55000000-0000-4000-8000-" + suffix;
            String patientId = "54000000-0000-4000-8000-" + suffix;
            String preopId = "58000000-0000-4000-8000-" + suffix;
            jdbcTemplate.update("""
                    INSERT INTO m2_anes.preop_assessment
                        (id, application_id, patient_id, patient_snapshot, history_summary,
                         asa_class, mallampati, airway_details, score_input, rcri_score,
                         apfel_score, stopbang_score, dasi_score, rcri_risk, apfel_risk,
                         stopbang_risk, functional_capacity_low, risk_level, calculator_version,
                         scores, plan_type, status)
                    VALUES (?::uuid, ?::uuid, ?::uuid, '{}'::jsonb, '合成病史（无患者标识）',
                            'II', 'I', '{}'::jsonb, '{}'::jsonb, 1, 1, 2, 40.0, 'LOW', 'LOW',
                            'LOW', false, 'LOW', 'preop-1.0.0', '{}'::jsonb, 'GENERAL', 'DRAFT')
                    ON CONFLICT (id) DO NOTHING
                    """, preopId, applicationId, patientId);
        }
        for (String suffix : List.of("000000001001", "000000001004")) {
            String applicationId = "55000000-0000-4000-8000-" + suffix;
            String checkId = "59000000-0000-4000-8000-" + suffix;
            jdbcTemplate.update("""
                    INSERT INTO m2_anes.three_way_check
                        (id, application_id, check_version, time_out_at, time_out_by, sign_out_at,
                         sign_out_by, specimen_checked, implant_checked)
                    VALUES (?::uuid, ?::uuid, 1, TIMESTAMPTZ '2026-01-02 08:10:00+08',
                            ?::uuid, TIMESTAMPTZ '2026-01-02 10:10:00+08', ?::uuid, true, true)
                    ON CONFLICT (id) DO NOTHING
                    """, checkId, applicationId, ADMIN_ID, ADMIN_ID);
        }
    }

    private void seedNarcoticRegister() {
        String firstSigner = "e0000000-0000-4000-8000-0000000000d1";
        String secondSigner = "e0000000-0000-4000-8000-0000000000d2";
        jdbcTemplate.update("""
                INSERT INTO m4_drug.narcotic_register
                    (id, drug_id, drug_name, batch_no, quantity, unit, prescription_no, used_by,
                     department_id, usage_at, destination, first_signer_id, first_signer_name,
                     second_signer_id, second_signer_name)
                VALUES ('5a000000-0000-4000-8000-000000000001'::uuid, ?::uuid,
                        '舒芬太尼（演示）', 'SYNTH-QC-B1', 2, '支', 'SYNTH-RX-1',
                        '演示使用人（合成）', ?::uuid, TIMESTAMPTZ '2026-01-02 09:00:00+08',
                        '术中', ?::uuid, '演示签字人甲（合成）', ?::uuid, '演示签字人乙（合成）')
                ON CONFLICT (id) DO NOTHING
                """, DRUG_SUFENTANIL, DEPT_ANES, firstSigner, secondSigner);
        jdbcTemplate.update("""
                INSERT INTO m4_drug.narcotic_register
                    (id, drug_id, drug_name, batch_no, quantity, unit, prescription_no, used_by,
                     department_id, usage_at, destination, first_signer_id, first_signer_name,
                     second_signer_id, second_signer_name)
                VALUES ('5a000000-0000-4000-8000-000000000002'::uuid, ?::uuid,
                        '舒芬太尼（演示）', 'SYNTH-QC-B2', 1, '支', 'SYNTH-RX-2',
                        '演示使用人（合成）', ?::uuid, TIMESTAMPTZ '2026-01-03 09:00:00+08',
                        '术中', ?::uuid, '演示签字人甲（合成）', ?::uuid, '')
                ON CONFLICT (id) DO NOTHING
                """, DRUG_SUFENTANIL, DEPT_ANES, firstSigner, secondSigner);
    }

    private void seedAdverseEvent() {
        jdbcTemplate.update("""
                INSERT INTO m4_qc.adverse_event
                    (id, event_no, event_type, severity, occurred_at, dept_id, dept_name,
                     description, reporter_id, reporter_name, anonymous, status, version)
                VALUES ('7d000000-0000-4000-8000-0000000000a1'::uuid, 'AE-20310506-0000a1',
                        'MEDICATION', 'LEVEL_II', TIMESTAMPTZ '2026-01-06 10:30:00+08',
                        ?::uuid, '麻醉科（合成）', '术中一过性血压下降（合成，已脱敏）',
                        ?::uuid, 'demo_admin', false, 'REPORTED', 1)
                ON CONFLICT (id) DO NOTHING
                """, DEPT_ANES, ADMIN_ID);
    }

    private void seedChartReviews() {
        jdbcTemplate.update("""
                INSERT INTO m4_qc.chart_review
                    (id, review_no, dept_id, dept_name, reviewer_id, reviewer_name, reviewed_at,
                     items, total_score, max_score, grade, defect_count, status, version)
                VALUES ('7f000000-0000-4000-8000-0000000000a1'::uuid, 'CR-20310510-0000a1',
                        ?::uuid, '麻醉科（合成）', ?::uuid, 'demo_admin',
                        TIMESTAMPTZ '2026-01-10 09:00:00+08',
                        ('[{"itemCode":"COMPLETENESS","itemName":"完整性","score":95,'
                        || '"maxScore":100,"defectNote":"合成缺陷"}]')::jsonb,
                        95, 100, 'A', 1, 'OPEN', 1)
                ON CONFLICT (id) DO NOTHING
                """, DEPT_ANES, ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO m4_qc.chart_review
                    (id, review_no, dept_id, dept_name, reviewer_id, reviewer_name, reviewed_at,
                     items, total_score, max_score, grade, defect_count, status, version)
                VALUES ('7f000000-0000-4000-8000-0000000000a2'::uuid, 'CR-20310511-0000a2',
                        ?::uuid, '麻醉科（合成）', ?::uuid, 'demo_admin',
                        TIMESTAMPTZ '2026-01-11 09:00:00+08',
                        ('[{"itemCode":"COMPLETENESS","itemName":"完整性","score":80,'
                        || '"maxScore":100,"defectNote":"合成缺陷"}]')::jsonb,
                        80, 100, 'C', 1, 'OPEN', 1)
                ON CONFLICT (id) DO NOTHING
                """, DEPT_ANES, ADMIN_ID);
    }

    private void seedHighValueMaterial() {
        String materialId = "8a000000-0000-4000-8000-0000000000a1";
        jdbcTemplate.update("""
                INSERT INTO m4_material.material
                    (id, code, name, specification, unit, category, high_value, expiry_managed,
                     status, remark, version)
                VALUES (?::uuid, 'MAT-QC-001', '演示高值耗材（合成）', '7F', '个', 'HIGH_VALUE',
                        true, false, 'ACTIVE', 'SYNTH-M4-QC', 1)
                ON CONFLICT (id) DO NOTHING
                """, materialId);
        for (int index = 1; index <= 3; index++) {
            jdbcTemplate.update("""
                    INSERT INTO m4_material.stock_movement
                        (id, movement_type, material_id, material_code, material_name, batch_no,
                         udi_code, quantity, unit, department_id, counterparty, reason,
                         balance_after, operator_id, operator_name, occurred_at)
                    VALUES (?::uuid, 'OUT', ?::uuid, 'MAT-QC-001', '演示高值耗材（合成）',
                            'SYNTH-QC-B1', ?, 1, '个', ?::uuid, '手术室', 'SYNTH-M4-QC', 1,
                            ?::uuid, 'demo_admin', ?::timestamptz)
                    ON CONFLICT (id) DO NOTHING
                    """, "8b000000-0000-4000-8000-00000000000" + index, materialId,
                    index <= 2 ? "0106900000000000^SYNTH-QC-" + index : null, DEPT_ANES,
                    ADMIN_ID, "2026-01-0" + (index + 6) + "T09:00:00+08:00");
        }
    }

    private String seedEventId() {
        return "7d000000-0000-4000-8000-0000000000a1";
    }

    private String createEventWithRca(String token) throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/qc/adverse-events")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "eventType", "MEDICATION",
                                "severity", "LEVEL_II",
                                "occurredAt", "2026-01-15T10:30:00+08:00",
                                "deptId", DEPT_ANES,
                                "description", "诱导后一过性血压下降（合成，已脱敏）"))))
                .andExpect(status().isCreated())
                .andReturn();
        String eventId = read(created).get("id").asText();
        mockMvc.perform(post("/v1/qc/adverse-events/" + eventId + "/rca")
                        .contentType(JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeline", List.of(Map.of(
                                        "occurredAt", "2026-01-15T10:30:00+08:00",
                                        "description", "血压下降（合成）")),
                                "rootCause", "容量评估不足（合成）",
                                "correctiveAction", "修订清单（合成）",
                                "responsibleName", "=SUM(1,2)",
                                "dueDate", "2026-02-15"))))
                .andExpect(status().isCreated());
        return eventId;
    }

    private static Map<String, Object> reviewItem(String code, int score, int maxScore,
            String defectNote) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("itemCode", code);
        item.put("itemName", switch (code) {
            case "COMPLETENESS" -> "完整性";
            case "TIMELINESS" -> "时效性";
            case "SIGNATURE" -> "签名";
            default -> "逻辑一致性";
        });
        item.put("score", score);
        item.put("maxScore", maxScore);
        if (defectNote != null) {
            item.put("defectNote", defectNote);
        }
        return item;
    }

    private static void assertIndicator(Map<String, JsonNode> items, String code, int numerator,
            int denominator, String value, boolean targetMet) {
        JsonNode item = items.get(code);
        assertThat(item).as("指标 %s 必须存在", code).isNotNull();
        assertThat(item.get("numerator").asInt()).isEqualTo(numerator);
        assertThat(item.get("denominator").asInt()).isEqualTo(denominator);
        assertThat(item.get("value").decimalValue()).isEqualByComparingTo(value);
        assertThat(item.get("targetMet").asBoolean()).isEqualTo(targetMet);
    }

    private Map<String, JsonNode> resultsByCode(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Map<String, JsonNode> items = new LinkedHashMap<>();
        for (JsonNode item : body.get("items")) {
            items.put(item.get("code").asText(), item);
        }
        return items;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String loginAdmin() throws Exception {
        return login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }
}
