package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.contracts.anesthesia.model.CreateRecordEventRequest;
import com.anesop.contracts.anesthesia.model.RecordEvent;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.record.AnesthesiaRecordService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 麻醉记录单与时间轴（M3-M2-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16（Docker 不可用）。
 *
 * <p>覆盖：一个台次唯一记录单（409）、时间轴 seq/时间顺序（400）与 append-only（55000）、
 * 麻精双人核对（缺失/同人 400）、乐观锁 409、出入量汇总手算、跨科室 404、无权限 403、
 * 审计（detail 无 PHI）、V13 结构/权限/注释/触发器。全部数据为 SYNTHETIC。
 */
class AnesthesiaRecordApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String PATIENT_ANES = "54000000-0000-4000-8000-000000000011";
    private static final String HIS_ANES = "SYNTH-HIS-P-9401";
    private static final String OR_01 = "f2000000-0000-4000-8000-0000000000c1";
    private static final String RECORD_DEPT_USER_ID = "e0000000-0000-4000-8000-0000000000e2";
    private static final String RECORD_DEPT_USERNAME = "test.record.dept";
    private static final String RECORD_DEPT_PASSWORD = "Synth@2026";
    private static final String RECORD_DEPT_ROLE_ID = "a0000000-0000-4000-8000-0000000000f4";
    /** 本测试类专用合成组织：避免写入 DEMO_OR_ORG_ID 污染其他测试（DataScope 计数）。 */
    private static final String RECORD_DEPT_ORG_ID = "f1000000-0000-4000-8000-0000000000e9";

    private static final String SECOND_CHECKER_ID = "e0000000-0000-4000-8000-000000000003";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AnesthesiaRecordService anesthesiaRecordService;

    @BeforeEach
    void prepareData() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        ensureRecordDeptOrg();
        ensureRecordDeptUser();
        clearRecordRows();
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
        // 记录单 FK 指向台次/申请：必须在其他测试类清理申请前删除（触发器临时停用）。
        clearRecordRows();
        // 清理本类专用用户/角色/组织夹具，避免污染共享库上的其他测试类（用户计数/数据范围）。
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_org WHERE user_id = ?::uuid",
                RECORD_DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_role WHERE user_id = ?::uuid",
                RECORD_DEPT_USER_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user WHERE id = ?::uuid",
                RECORD_DEPT_USER_ID);
        jdbcTemplate.update("""
                DELETE FROM m0_platform.sys_role_permission
                WHERE role_id = ?::uuid
                """, RECORD_DEPT_ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_role WHERE id = ?::uuid",
                RECORD_DEPT_ROLE_ID);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_org WHERE id = ?::uuid",
                RECORD_DEPT_ORG_ID);
    }

    // -------------------------------------------------- 记录单/时间轴/出入量 --

    @Test
    void recordTimelineDrugsFluidsAndAuditFollowClinicalRules() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-M3-APP-0001");
        // 记录单创建时间 = 服务端 now；事件时间落在 now 附近（容差 600s 内），
        // 既满足"不得早于记录开始"，也不触发"未来时间"拦截。
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .truncatedTo(ChronoUnit.MILLIS).plusSeconds(30);

        // 创建记录单：DRAFT，无开始时间；同台次重复创建 409。
        MvcResult created = mockMvc.perform(post("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId, "anesthesiaType", "GENERAL"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.scheduleId").value(scheduleId))
                .andExpect(jsonPath("$.patientNo").value(HIS_ANES))
                .andExpect(jsonPath("$.vitalSigns", hasSize(0)))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        String recordId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("recordId").asText();

        mockMvc.perform(post("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId, "anesthesiaType", "GENERAL"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        mockMvc.perform(get("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + admin)
                        .param("scheduleId", scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].recordId").value(recordId));

        // 时间轴：ANESTHESIA_START 置 IN_PROGRESS 并记录开始时间。
        appendEvent(admin, recordId, "ANESTHESIA_START", base, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(1))
                .andExpect(jsonPath("$.eventType").value("ANESTHESIA_START"));
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.events", hasSize(0)));

        // 早于记录开始的事件 400；异常事件类型 400。
        appendEvent(admin, recordId, "INDUCTION", base.minusMinutes(30), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("occurredAt"));

        // 正常诱导/插管事件按 occurredAt 升序返回，同刻按 seq。
        appendEvent(admin, recordId, "INDUCTION", base.plusMinutes(2), "演示诱导（合成）")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(2));
        appendEvent(admin, recordId, "INTUBATION", base.plusMinutes(3), "演示插管（合成）")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(3));
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/events")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].eventType").value("ANESTHESIA_START"))
                .andExpect(jsonPath("$.items[1].eventType").value("INDUCTION"))
                .andExpect(jsonPath("$.items[2].eventType").value("INTUBATION"));

        // 重复开始事件 409；append-only：UPDATE/DELETE 55000。
        appendEvent(admin, recordId, "ANESTHESIA_START", base.plusMinutes(4), null)
                .andExpect(status().isConflict());
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.record_event SET event_type = 'CUSTOM' WHERE record_id = ?::uuid",
                recordId)), "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.record_event WHERE record_id = ?::uuid", recordId)),
                "append-only");

        // 乐观锁：正确版本可改，过期版本 409；PUT 不接受事件（append-only）。
        mockMvc.perform(put("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", 4, "anesthesiaType", "COMBINED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anesthesiaType").value("COMBINED"))
                .andExpect(jsonPath("$.version").value(5));
        mockMvc.perform(put("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", 4, "anesthesiaType", "GENERAL"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        mockMvc.perform(put("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "version", 5, "events", List.of(Map.of(
                                        "eventId", "13000000-0000-4000-8000-0000000000ff",
                                        "eventType", "INDUCTION",
                                        "occurredAt", base.plusMinutes(5).toString(),
                                        "source", "MANUAL",
                                        "recordedAt", base.plusMinutes(5).toString(),
                                        "recordedBy", SyntheticAccounts.DEMO_ADMIN_ID))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("events"));

        // 未来时间：超出服务端容差 400（字段 occurredAt）。
        appendEvent(admin, recordId, "CUSTOM", base.plusHours(1), "未来事件（合成）")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("occurredAt"));

        // 补录：早于最新事件（插管 base+3）必须填写 entryReason；补齐后成功并标记 backfilled。
        appendEvent(admin, recordId, "CUSTOM", base.plusMinutes(1), "补录事件（合成）")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("entryReason"));
        appendEventWithReason(admin, recordId, "CUSTOM", base.plusMinutes(1),
                "补录：关键事件由麻醉单补记", "补录事件（合成）")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(4))
                .andExpect(jsonPath("$.entryReason").value("补录：关键事件由麻醉单补记"));

        // 用药：麻精缺双人核对 400；同人核对 400；正常写入 201。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(drugBody(
                                "舒芬太尼（演示）", true, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("secondCheckerId"));
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(drugBody("舒芬太尼（演示）", true,
                                SyntheticAccounts.DEMO_ADMIN_ID, "本人（合成）"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("secondCheckerId"));
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(drugBody("舒芬太尼（演示）", true,
                                SECOND_CHECKER_ID, "演示核对护士（合成）"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.narcotic").value(true))
                .andExpect(jsonPath("$.secondCheckerName").value("演示核对护士（合成）"));
        // S-2 核对人资格：存在但在职校验/权限校验失败 → 400（失败审计 result=0）。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(drugBody("舒芬太尼（演示）", true,
                                SyntheticAccounts.SURGEON_ID, "演示术者（合成）"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("secondCheckerId"));
        jdbcTemplate.update("UPDATE m0_platform.sys_user SET status = 0 WHERE id = ?::uuid",
                SECOND_CHECKER_ID);
        try {
            mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(JSON)
                            .content(objectMapper.writeValueAsString(drugBody("舒芬太尼（演示）",
                                    true, SECOND_CHECKER_ID, "演示核对护士（合成）"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details[0].field").value("secondCheckerId"));
        } finally {
            jdbcTemplate.update("UPDATE m0_platform.sys_user SET status = 1 WHERE id = ?::uuid",
                    SECOND_CHECKER_ID);
        }
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(drugBody("舒芬太尼（演示）", true,
                                "e0000000-0000-4000-8000-0000000000ff", "不存在（合成）"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("secondCheckerId"));

        // M4-M15-01 收口 T-4：麻精属性以药品字典为准。
        // 舒芬太尼在字典中为麻精类，声明 false 被拒（400，field=narcotic），不能绕过双人核对。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                drugBody("舒芬太尼（演示）", false, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("narcotic"));
        // 字典未收录 → 400（field=drugName），不允许“字典外自由文本药品”绕过麻精判定。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                drugBody("未收录药品（演示）", false, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("drugName"));

        // 别名指向麻精药品：客户端声明 false 同样被拒（别名解析不能绕过双人核对）。
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                drugBody("舒芬（演示）", false, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("narcotic"));

        // 大小写与首尾空白：字典解析 trim + 忽略大小写后仍唯一，命中麻精并可正常登记。
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String asciiName = "T4 Case Drug " + suffix;
        String asciiAlias = "t4casealias" + suffix;
        Map<String, Object> asciiDrug = new LinkedHashMap<>();
        asciiDrug.put("code", "DRUG-CASE-" + suffix);
        asciiDrug.put("name", asciiName);
        asciiDrug.put("specification", "1ml");
        asciiDrug.put("unit", "支");
        asciiDrug.put("dosageForm", "INJECTION");
        asciiDrug.put("category", "OPIOID");
        asciiDrug.put("narcotic", true);
        asciiDrug.put("highRisk", false);
        asciiDrug.put("aliases", List.of(asciiAlias));
        mockMvc.perform(post("/v1/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(asciiDrug)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(drugBody(
                                "  " + asciiName.toUpperCase(java.util.Locale.ROOT) + "  ",
                                true, SECOND_CHECKER_ID, "演示核对护士（合成）"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.narcotic").value(true));
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(drugBody(
                                asciiAlias.toUpperCase(java.util.Locale.ROOT), false, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("narcotic"));

        // 歧义 fail-closed：名称/通用名交叉命中不同药品 → 422 DRUG_AMBIGUOUS（禁止静默取第一条）。
        String ambiguousName = "T4 歧义药 " + suffix;
        Map<String, Object> ambiguousDrug = new LinkedHashMap<>(asciiDrug);
        ambiguousDrug.put("code", "DRUG-AMB-A-" + suffix);
        ambiguousDrug.put("name", ambiguousName);
        ambiguousDrug.put("narcotic", false);
        ambiguousDrug.put("aliases", List.of());
        mockMvc.perform(post("/v1/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(ambiguousDrug)))
                .andExpect(status().isCreated());
        jdbcTemplate.update("""
                INSERT INTO m4_drug.drug
                    (id, code, name, generic_name, specification, unit, dosage_form, category,
                     narcotic, high_risk, status)
                VALUES (?::uuid, ?, ?, ?, '1ml', '支', 'INJECTION', 'OPIOID', false, false, 'ACTIVE')
                """, UUID.randomUUID().toString(), "DRUG-AMB-B-" + suffix,
                "T4 歧义对照 " + suffix, ambiguousName);
        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                drugBody(ambiguousName, false, null, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.details[0].field").value("drugName"))
                .andExpect(jsonPath("$.details[0].reason",
                        org.hamcrest.Matchers.containsString("DRUG_AMBIGUOUS")));

        mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                drugBody("丙泊酚（演示）", false, null, null))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/drugs")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)));

        // 输液/出入量：500 + 1000 入量、400 出量 → 汇总 1500/400/1100。
        recordFluid(admin, recordId, "INTAKE", "复方氯化钠（演示）", 500)
                .andExpect(status().isCreated());
        recordFluid(admin, recordId, "INTAKE", "琥珀酰明胶（演示）", 1000)
                .andExpect(status().isCreated());
        recordFluid(admin, recordId, "OUTPUT", "尿量（演示）", 400)
                .andExpect(status().isCreated());
        recordFluid(admin, recordId, "INTAKE", "非法量（演示）", 0)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("amountMl"));
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/io-summary")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intakeMl").value(1500))
                .andExpect(jsonPath("$.outputMl").value(400))
                .andExpect(jsonPath("$.balanceMl").value(1100))
                .andExpect(jsonPath("$.fluidCount").value(3));
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/fluids")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)));

        // 结束事件记录结束时间；记录单身份列不可改、不得物理删除。
        appendEvent(admin, recordId, "ANESTHESIA_END", base.plusMinutes(4), "演示结束（合成）")
                .andExpect(status().isCreated());
        // ANESTHESIA_END 之后不得再追加任何事件（409）。
        appendEvent(admin, recordId, "CUSTOM", base.plusMinutes(5), "结束后事件（合成）")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endedAt").isNotEmpty())
                .andExpect(jsonPath("$.events", hasSize(3)))
                .andExpect(jsonPath("$.events[0].eventType").value("OTHER"))
                .andExpect(jsonPath("$.events[1].eventType").value("INDUCTION"))
                .andExpect(jsonPath("$.events[2].eventType").value("INTUBATION"));
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.anesthesia_record SET schedule_id = gen_random_uuid() "
                        + "WHERE id = ?::uuid", recordId)), "identity");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.anesthesia_record WHERE id = ?::uuid", recordId)),
                "append-only");
        // V13 守卫单向：状态不得回退、started_at 不得清空、version 只增。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.anesthesia_record SET status = 'DRAFT', version = version + 1 "
                        + "WHERE id = ?::uuid", recordId)), "status");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.anesthesia_record SET started_at = NULL, version = version + 1 "
                        + "WHERE id = ?::uuid", recordId)), "started_at");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.anesthesia_record SET updated_at = now() WHERE id = ?::uuid",
                recordId)), "version");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m2_anes.drug_administration SET dose = 99 WHERE record_id = ?::uuid",
                recordId)), "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "DELETE FROM m2_anes.fluid_record WHERE record_id = ?::uuid", recordId)),
                "append-only");

        // 审计：动作齐全、detail 无患者姓名/院内患者号，药品名保留用于追溯。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'ANESTHESIA_RECORD' AND resource_id = ? AND result = 1
                ORDER BY occurred_at, action
                """, recordId);
        assertThat(audits).extracting(row -> row.get("action"))
                .contains("RECORD_CREATE", "RECORD_UPDATE", "EVENT_RECORD", "DRUG_ADMIN",
                        "FLUID_RECORD");
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail")).contains("演示患者"));
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail")).contains(HIS_ANES));
        assertThat(audits).anyMatch(row -> String.valueOf(row.get("detail"))
                .contains("舒芬太尼（演示）"));
        assertThat(audits).noneMatch(row -> String.valueOf(row.get("detail"))
                .contains("演示核对护士"));

        // 麻精核对失败（缺核对/同人/无权限/停用/不存在）写 result=0 审计，detail 无核对人姓名。
        List<Map<String, Object>> rejectedAudits = jdbcTemplate.queryForList("""
                SELECT action, result, detail::text AS detail FROM m0_platform.sys_audit_log
                WHERE resource_type = 'ANESTHESIA_RECORD' AND resource_id = ? AND result = 0
                """, recordId);
        assertThat(rejectedAudits).hasSizeGreaterThanOrEqualTo(5);
        assertThat(rejectedAudits).allMatch(row -> "DRUG_ADMIN".equals(row.get("action")));
        assertThat(rejectedAudits).allMatch(row -> !String.valueOf(row.get("detail"))
                .contains("演示核对护士"));
        assertThat(rejectedAudits).anyMatch(row -> String.valueOf(row.get("detail"))
                .contains("CHECKER_NO_PERMISSION"));
        assertThat(rejectedAudits).anyMatch(row -> String.valueOf(row.get("detail"))
                .contains("CHECKER_DISABLED"));

        System.out.printf("[M3-M2-01][record] record=%s schedule=%s duplicate=409 events=5 "
                + "started/ended=true appendOnly=55000 staleVersion=409 putEvents=400 "
                + "narcoticMissing/samePerson=400 io=1500/400/1100 audits=%d noPhi=true%n",
                recordId, scheduleId, audits.size());
    }

    // ------------------------------------------------------- 并发 seq（B-1） --

    /**
     * 双线程并发追加：真实 PostgreSQL 下行锁把同一记录单的事件追加串行化。
     *
     * <p>修复前：两个事务同时读到相同 MAX(seq)，后提交者命中唯一键冲突，PG 事务 aborted，
     * 重试查询抛 25P02 → 500。修复后：两事件 seq 连续（1、2）且都保存，记录单 version=3。
     */
    @Test
    void concurrentEventAppendsSerializeSequenceUnderRowLock() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-M3-APP-CONC");
        String recordId = createRecord(admin, scheduleId);

        AuthPrincipal principal = new AuthPrincipal(
                UUID.fromString(SyntheticAccounts.DEMO_ADMIN_ID),
                SyntheticAccounts.DEMO_ADMIN_USERNAME, "jti-concurrent",
                Instant.now().plusSeconds(900), "sid-concurrent");
        AuthRequestContext context =
                new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-CONC");
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .truncatedTo(ChronoUnit.MILLIS).plusSeconds(10);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<RecordEvent>> tasks = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                tasks.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    CreateRecordEventRequest request = new CreateRecordEventRequest();
                    request.setEventType(CreateRecordEventRequest.EventTypeEnum.CUSTOM);
                    request.setOccurredAt(occurredAt);
                    request.setNote("并发追加（合成）");
                    return anesthesiaRecordService.appendEvent(recordId, request, principal, context);
                });
            }
            List<Future<RecordEvent>> futures = pool.invokeAll(tasks);
            List<Integer> seqs = new ArrayList<>();
            for (Future<RecordEvent> future : futures) {
                seqs.add(future.get(20, TimeUnit.SECONDS).getSeq());
            }
            assertThat(seqs).containsExactlyInAnyOrder(1, 2);
        } finally {
            pool.shutdownNow();
        }

        List<Integer> storedSeqs = jdbcTemplate.queryForList("""
                SELECT seq FROM m2_anes.record_event WHERE record_id = ?::uuid ORDER BY seq
                """, Integer.class, recordId);
        assertThat(storedSeqs).containsExactly(1, 2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m2_anes.record_event WHERE record_id = ?::uuid
                """, Integer.class, recordId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT version FROM m2_anes.anesthesia_record WHERE id = ?::uuid
                """, Long.class, recordId)).isEqualTo(3L);
        System.out.printf("[M3-M2-01][concurrent] record=%s threads=2 seq=%s version=3 "
                + "duplicateKeyRetry=removed%n", recordId, storedSeqs);
    }

    // ------------------------------------------------------- 权限与数据范围 --

    @Test
    void permissionsAndCrossDepartmentScopeAreEnforced() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String scheduleId = createSchedule(admin, "SYNTH-M3-APP-0002");
        String recordId = createRecord(admin, scheduleId);

        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + surgeon)
                        .param("scheduleId", scheduleId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mockMvc.perform(post("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId, "anesthesiaType", "GENERAL"))))
                .andExpect(status().isForbidden());

        String deptUser = login(RECORD_DEPT_USERNAME, RECORD_DEPT_PASSWORD);
        mockMvc.perform(get("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + deptUser)
                        .param("scheduleId", scheduleId))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + deptUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId, "anesthesiaType", "GENERAL"))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        appendEvent(deptUser, recordId, "INDUCTION",
                OffsetDateTime.now(ZoneOffset.ofHours(8)).plusMinutes(10), null)
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/anesthesia/records/" + recordId + "/io-summary")
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound());

        System.out.println("[M3-M2-01][scope] noPermission=403 listOutOfScope=403 "
                + "createOutOfScope=422 getOutOfScope=404 eventOutOfScope=404 ioOutOfScope=404");
    }

    // ------------------------------------------------------------- 迁移结构 --

    @Test
    void migrationV13CreatesTablesConstraintsIndexesPermissionsAndComments() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'm2_anes' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """, String.class);
        assertThat(tables).contains("anesthesia_record", "record_event",
                "drug_administration", "fluid_record");

        assertThat(columnsOf("anesthesia_record")).contains("id", "schedule_id", "application_id",
                "patient_id", "status", "anesthesia_type", "started_at", "ended_at", "created_at",
                "created_by", "updated_at", "updated_by", "version", "deleted");
        assertThat(columnsOf("record_event")).contains("id", "record_id", "seq", "event_type",
                "occurred_at", "source", "payload", "created_at", "created_by");
        assertThat(columnsOf("drug_administration")).contains("id", "record_id", "drug_name",
                "dose", "unit", "route", "administered_at", "narcotic", "second_checker_id",
                "second_checker_name", "created_at", "created_by");
        assertThat(columnsOf("fluid_record")).contains("id", "record_id", "direction",
                "fluid_type", "amount_ml", "recorded_at", "created_at", "created_by");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'm2_anes' AND indexname = 'uk_anesthesia_record_schedule'
                """, String.class)).contains("UNIQUE", "schedule_id");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'uk_record_event_record_seq'
                """, String.class)).contains("UNIQUE", "record_id", "seq");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_drug_administration_narcotic_checker'
                """, String.class)).contains("narcotic", "second_checker_id");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_anesthesia_record_status'
                """, String.class)).contains("DRAFT", "ONGOING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_fluid_record_amount'
                """, String.class)).contains("amount_ml > 0");

        // V17 在 append-only 触发器之外新增“锁单后禁止 INSERT”守卫（BEFORE INSERT）。
        assertThat(triggerNames("record_event")).containsExactly(
                "trg_record_event_locked_insert", "trg_record_event_no_delete",
                "trg_record_event_no_truncate", "trg_record_event_no_update");
        assertThat(triggerNames("drug_administration")).containsExactly(
                "trg_drug_administration_locked_insert", "trg_drug_administration_no_delete",
                "trg_drug_administration_no_truncate", "trg_drug_administration_no_update");
        assertThat(triggerNames("fluid_record")).containsExactly(
                "trg_fluid_record_locked_insert", "trg_fluid_record_no_delete",
                "trg_fluid_record_no_truncate", "trg_fluid_record_no_update");
        assertThat(triggerNames("anesthesia_record")).containsExactly(
                "trg_anesthesia_record_guard_delete", "trg_anesthesia_record_guard_truncate",
                "trg_anesthesia_record_guard_update");

        // V17 追加 m2:record:lock / m2:record:revise（锁单与修订）。
        assertThat(jdbcTemplate.queryForList("""
                SELECT code FROM m0_platform.sys_permission
                WHERE code LIKE 'm2:record:%' ORDER BY code
                """, String.class)).containsExactly("m2:record:lock", "m2:record:read",
                "m2:record:revise", "m2:record:write");
        Integer grants = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                WHERE p.code LIKE 'm2:record:%'
                  AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001',
                                     'a0000000-0000-4000-8000-000000000002',
                                     'a0000000-0000-4000-8000-000000000003')
                """, Integer.class);
        // read/write 各授 3 个角色（6），lock/revise 各授 SYS_ADMIN/SYS_ANESTHESIOLOGIST（4）。
        assertThat(grants).isEqualTo(10);

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

        System.out.printf("[M3-M2-01][v13] tables=4 permissions=2 grants=%d triggers=12 "
                + "comments=true%n", grants);
    }

    // ------------------------------------------------------------- helpers --

    private org.springframework.test.web.servlet.ResultActions appendEvent(String token,
            String recordId, String eventType, OffsetDateTime occurredAt, String note)
            throws Exception {
        return appendEventWithReason(token, recordId, eventType, occurredAt, null, note);
    }

    private org.springframework.test.web.servlet.ResultActions appendEventWithReason(String token,
            String recordId, String eventType, OffsetDateTime occurredAt, String entryReason,
            String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", eventType);
        body.put("occurredAt", occurredAt.toString());
        if (note != null) {
            body.put("note", note);
        }
        if (entryReason != null) {
            body.put("entryReason", entryReason);
        }
        return mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/events")
                .header("Authorization", "Bearer " + token)
                .contentType(JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions recordFluid(String token,
            String recordId, String direction, String fluidType, int amountMl) throws Exception {
        return mockMvc.perform(post("/v1/anesthesia/records/" + recordId + "/fluids")
                .header("Authorization", "Bearer " + token)
                .contentType(JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "direction", direction, "fluidType", fluidType, "amountMl", amountMl))));
    }

    private Map<String, Object> drugBody(String drugName, boolean narcotic, String checkerId,
            String checkerName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drugName", drugName);
        body.put("dose", 10);
        body.put("unit", "ug");
        body.put("route", "IV");
        body.put("administeredAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
        body.put("narcotic", narcotic);
        if (checkerId != null) {
            body.put("secondCheckerId", checkerId);
        }
        if (checkerName != null) {
            body.put("secondCheckerName", checkerName);
        }
        return body;
    }

    private String createRecord(String token, String scheduleId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId, "anesthesiaType", "GENERAL"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("recordId").asText();
    }

    private String createSchedule(String token, String hisApplicationNo) throws Exception {
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("hisApplicationNo", hisApplicationNo);
        application.put("patientId", PATIENT_ANES);
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
        schedule.put("roomId", OR_01);
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

    private void clearRecordRows() {
        // append-only 临床表：测试夹具临时 DISABLE TRIGGER USER 清理后立即恢复。
        // V17 的 record_revision FK 指向记录单，必须先清理（修订版本 append-only）。
        jdbcTemplate.execute("ALTER TABLE m2_anes.record_revision DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.fluid_record DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.drug_administration DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.record_event DISABLE TRIGGER USER");
        jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m2_anes.record_revision");
            jdbcTemplate.update("DELETE FROM m2_anes.fluid_record");
            jdbcTemplate.update("DELETE FROM m2_anes.drug_administration");
            jdbcTemplate.update("DELETE FROM m2_anes.record_event");
            jdbcTemplate.update("DELETE FROM m2_anes.anesthesia_record");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.record_revision ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.fluid_record ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.drug_administration ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.record_event ENABLE TRIGGER USER");
            jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record ENABLE TRIGGER USER");
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

    private void insertPatient() {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, '演示患者丁', 'FEMALE', DATE '1971-07-21', 55, 'SYNT****9401', ?,
                        '麻醉记录单合成科室', ?::uuid, 'f1000000-0000-4000-8000-000000000001'::uuid,
                        '演示病区', '03', now(), now(), 1, false)
                """, PATIENT_ANES, HIS_ANES, "hash-" + HIS_ANES, RECORD_DEPT_ORG_ID);
    }

    private void ensureRecordDeptOrg() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_org (id, code, name, org_type, path, sort, status)
                VALUES (?::uuid, 'SYN_RECORD_DEPT', '麻醉记录单合成科室', 'DEPT', ?, 99, 1)
                ON CONFLICT (id) DO NOTHING
                """, RECORD_DEPT_ORG_ID, "/" + RECORD_DEPT_ORG_ID);
    }

    private void ensureRecordDeptUser() {
        String hash = new BCryptPasswordEncoder().encode(RECORD_DEPT_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_RECORD_DEPT', '合成记录单科室角色',
                        'SYNTHETIC：m2 麻醉记录单 DEPT 数据范围用例', 'DEPT')
                ON CONFLICT (id) DO NOTHING
                """, RECORD_DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试记录单科室用户（合成）', 1, false, 0, 1, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, RECORD_DEPT_USER_ID, RECORD_DEPT_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, RECORD_DEPT_USER_ID, RECORD_DEPT_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_org (user_id, org_id, is_primary)
                VALUES (?::uuid, ?::uuid, true) ON CONFLICT DO NOTHING
                """, RECORD_DEPT_USER_ID, RECORD_DEPT_ORG_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                SELECT ?::uuid, id FROM m0_platform.sys_permission
                WHERE code IN ('m2:record:read', 'm2:record:write')
                ON CONFLICT DO NOTHING
                """, RECORD_DEPT_ROLE_ID);
    }
}
