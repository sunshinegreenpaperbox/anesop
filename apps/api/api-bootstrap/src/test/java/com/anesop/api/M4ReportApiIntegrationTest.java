package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 工作量统计、多维报表与导出（M4-M16-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16 +
 * 真实 Flyway（含 V24 data-only 权限）。
 *
 * <p>固定 SYNTHETIC 数据（2031-03，避免与既有用例日期交叉；用例结束清理）：
 * <ul>
 *   <li>A：2031-03-02 麻醉科 / OR-01 / SYNTH-ART-001 / 记录单 08:00→10:05（125 分钟），
 *       记录单创建人 = 报表 SELF 账号（对应人员档案 SYN-AN-001）；</li>
 *   <li>B：2031-03-03 麻醉科 / OR-01 / SYNTH-GAS-002 / 无记录单（0 分钟，unrecorded）；</li>
 *   <li>C：2031-03-04 手术室 / OR-05 / 术式名 {@code =SUM(1,2)} / 记录单 08:00→09:30（90 分钟）；</li>
 *   <li>D：2031-03-05 已取消（CANCELLED）→ 不计台次；</li>
 *   <li>E：2031-02-28 区间外 → 不计台次。</li>
 * </ul>
 * 手算：台次 3、麻醉时长 215、加权台次 3.5（SYNTH-ART-001 权重 1.5 + 1 + 1）、
 * unrecorded 1；麻醉科桶 2/125/2.5，手术室桶 1/90/1.0。
 * 覆盖：白名单 400、跨度 &gt;366 天 400、DEPT/SELF 数据范围（403 与过滤）、多维分页与合计、
 * CSV BOM/列头/防公式注入/无 PHI、空数据 0、审计 REPORT_QUERY/REPORT_EXPORT、V24 权限。
 */
@TestPropertySource(properties = "anesop.m4.report.surgery-weights.SYNTH-ART-001=1.5")
class M4ReportApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String DEPT_ANES = SyntheticAccounts.DEMO_ANES_ORG_ID;
    private static final String DEPT_OR = SyntheticAccounts.DEMO_OR_ORG_ID;
    private static final String SITE = "f1000000-0000-4000-8000-000000000001";
    private static final String ROOM_01 = "f2000000-0000-4000-8000-0000000000c1";
    private static final String ROOM_05 = "f2000000-0000-4000-8000-0000000000c5";
    private static final String STAFF_ID = "3a000000-0000-4000-8000-0000000000e1";
    private static final String STAFF_NO = "SYN-AN-001";
    private static final String SELF_ROLE_ID = "a0000000-0000-4000-8000-0000000000f2";
    private static final String SELF_USER_ID = "e0000000-0000-4000-8000-0000000000e5";
    private static final String SELF_USERNAME = "test.report.self";
    /**
     * 台次 C 的记录单创建人：专用账号（不建人员档案），保证 STAFF 维度稳定落入 UNASSIGNED。
     * 不使用 demo_admin 之类共享账号——其他用例可能为其建立人员档案，导致桶数随执行顺序变化。
     */
    private static final String OTHER_USER_ID = "e0000000-0000-4000-8000-0000000000e6";
    private static final String SELF_PASSWORD = "Synth@2026";
    private static final String PATIENT_NAME = "演示患者报表（合成）";
    private static final String FROM = "2031-03-01";
    private static final String TO = "2031-03-31";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareData() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        ensureSelfScopedReportUser();
        insertStaffProfile();
        insertCase("0001", ROOM_01, DEPT_ANES, "麻醉科（合成）", "SYNTH-ART-001",
                "演示关节置换术（合成）", "2031-03-02T08:00:00+08:00", "2031-03-02T10:05:00+08:00",
                "COMPLETED", SELF_USER_ID);
        insertCase("0002", ROOM_01, DEPT_ANES, "麻醉科（合成）", "SYNTH-GAS-002",
                "演示胃肠手术（合成）", "2031-03-03T08:00:00+08:00", "2031-03-03T09:00:00+08:00",
                "PLANNED", null);
        insertCase("0003", ROOM_05, DEPT_OR, "手术室（合成）", "=SUM(1,2)", "=SUM(1,2)",
                "2031-03-04T08:00:00+08:00", "2031-03-04T09:30:00+08:00", "COMPLETED",
                OTHER_USER_ID);
        insertCase("0004", ROOM_01, DEPT_ANES, "麻醉科（合成）", "SYNTH-CANCEL", "已取消台次（合成）",
                "2031-03-05T08:00:00+08:00", "2031-03-05T09:00:00+08:00", "CANCELLED", null);
        insertCase("0005", ROOM_01, DEPT_ANES, "麻醉科（合成）", "SYNTH-OUT", "区间外台次（合成）",
                "2031-02-28T08:00:00+08:00", "2031-02-28T09:00:00+08:00", "COMPLETED", null);
    }

    @AfterEach
    void cleanUpFixtures() {
        // 临床记录单 append-only：夹具临时停用触发器后立即恢复（与既有集成测试一致）。
        jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update(
                    "DELETE FROM m2_anes.anesthesia_record WHERE schedule_id IN "
                            + "(SELECT id FROM m1_sched.schedule WHERE reason = 'SYNTH-M4-REPORT')");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.schedule WHERE reason = 'SYNTH-M4-REPORT'");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.surgery_application WHERE remark = 'SYNTH-M4-REPORT'");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.patient_snapshot WHERE his_patient_no LIKE 'SYNTH-M4-REP-%'");
            jdbcTemplate.update("DELETE FROM m4_staff.staff_profile WHERE staff_no = ?", STAFF_NO);
            // SELF 用例账号/角色/授权一并清理（先关联表、后主表，避免残留跨用例脏数据）。
            jdbcTemplate.update(
                    "DELETE FROM m0_platform.sys_user_role "
                            + "WHERE user_id = ?::uuid OR role_id = ?::uuid",
                    SELF_USER_ID, SELF_ROLE_ID);
            jdbcTemplate.update(
                    "DELETE FROM m0_platform.sys_role_permission WHERE role_id = ?::uuid",
                    SELF_ROLE_ID);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_user WHERE id = ?::uuid",
                    SELF_USER_ID);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_role WHERE id = ?::uuid",
                    SELF_ROLE_ID);
        } finally {
            jdbcTemplate.execute("ALTER TABLE m2_anes.anesthesia_record ENABLE TRIGGER USER");
        }
    }

    // ------------------------------------------------------------------
    // 工作量：手算期望与维度桶
    // ------------------------------------------------------------------

    @Test
    void workloadMatchesHandCalculatedTotalsAndBuckets() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", TO).param("dimension", "DEPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.dimension").value("DEPT"))
                .andExpect(jsonPath("$.caseCount").value(3))
                .andExpect(jsonPath("$.anesthesiaMinutes").value(215))
                .andExpect(jsonPath("$.weightedCaseCount").value(3.5))
                .andExpect(jsonPath("$.unrecordedCaseCount").value(1))
                .andExpect(jsonPath("$.formula").value(org.hamcrest.Matchers.containsString(
                        "started_at")))
                .andExpect(jsonPath("$.buckets.length()").value(2))
                .andExpect(jsonPath("$.buckets[0].key").value(DEPT_ANES))
                .andExpect(jsonPath("$.buckets[0].label").value("麻醉科（合成）"))
                .andExpect(jsonPath("$.buckets[0].caseCount").value(2))
                .andExpect(jsonPath("$.buckets[0].anesthesiaMinutes").value(125))
                .andExpect(jsonPath("$.buckets[0].weightedCaseCount").value(2.5))
                .andExpect(jsonPath("$.buckets[0].unrecordedCaseCount").value(1))
                .andExpect(jsonPath("$.buckets[0].surgeryDistribution[0].surgeryCode")
                        .value("SYNTH-ART-001"))
                .andExpect(jsonPath("$.buckets[0].surgeryDistribution[0].anesthesiaMinutes")
                        .value(125))
                .andExpect(jsonPath("$.buckets[1].key").value(DEPT_OR))
                .andExpect(jsonPath("$.buckets[1].caseCount").value(1))
                .andExpect(jsonPath("$.buckets[1].anesthesiaMinutes").value(90));

        // 医师维度：SELF 账号有人员档案 → 工号+姓名标签；无档案台次进 UNASSIGNED 且排最后。
        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", TO).param("dimension", "STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(3))
                .andExpect(jsonPath("$.buckets.length()").value(2))
                .andExpect(jsonPath("$.buckets[0].label").value("SYN-AN-001 演示麻醉医师（合成）"))
                .andExpect(jsonPath("$.buckets[1].key").value("UNASSIGNED"))
                .andExpect(jsonPath("$.buckets[1].label").value("未匹配医师"));

        // 术式维度：三术式各 1 台；取消/区间外台次不参与。
        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", TO).param("dimension", "SURGERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(3))
                .andExpect(jsonPath("$.caseCount").value(3));

        // 查询写审计，detail 含口径要素且不含患者标识。
        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE action = 'REPORT_QUERY' AND resource_type = 'REPORT'
                 ORDER BY occurred_at DESC LIMIT 1
                """);
        assertThat(audit.get("detail").toString())
                .contains("\"dimension\": \"SURGERY\"")
                .contains("\"timezone\": \"Asia/Shanghai\"")
                .doesNotContain("演示患者", "住院号");
    }

    // ------------------------------------------------------------------
    // 白名单、跨度与空数据
    // ------------------------------------------------------------------

    @Test
    void rejectsNonWhitelistedDimensionMetricsAndOversizedRange() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", TO).param("dimension", "PATIENT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("dimension"));

        mockMvc.perform(get("/v1/reports/multi")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", TO)
                        .param("dimensions", "DEPT,FOO")
                        .param("metrics", "caseCount"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                // /multi 的字段名统一为契约参数 dimensions（不是单数 dimension）。
                .andExpect(jsonPath("$.details[0].field").value("dimensions"));

        mockMvc.perform(get("/v1/reports/multi")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", TO)
                        .param("dimensions", "DEPT")
                        .param("metrics", "revenue"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("metrics"));

        // 366 天（含首尾）允许，367 天 400。
        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", "2033-01-01").param("to", "2034-01-01")
                        .param("dimension", "DEPT"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", "2033-01-01").param("to", "2034-01-02")
                        .param("dimension", "DEPT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].reason")
                        .value(org.hamcrest.Matchers.containsString("366")));

        // 空数据：0 计数、空桶，不返回 404。
        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", "2032-01-01").param("to", "2032-01-02")
                        .param("dimension", "DEPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(0))
                .andExpect(jsonPath("$.anesthesiaMinutes").value(0))
                .andExpect(jsonPath("$.weightedCaseCount").value(0))
                .andExpect(jsonPath("$.unrecordedCaseCount").value(0))
                .andExpect(jsonPath("$.buckets.length()").value(0));
    }

    // ------------------------------------------------------------------
    // 数据范围：DEPT 过滤/越界 403、SELF 本人过滤、缺权限 403
    // ------------------------------------------------------------------

    @Test
    void dataScopeDeptAndSelfAreRestricted() throws Exception {
        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + dept)
                        .param("from", FROM).param("to", TO).param("dimension", "DEPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(2))
                .andExpect(jsonPath("$.anesthesiaMinutes").value(125))
                .andExpect(jsonPath("$.buckets.length()").value(1))
                .andExpect(jsonPath("$.buckets[0].key").value(DEPT_ANES));

        // 指定越界科室（手术室）→ 403，不返回空页掩盖越权。
        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + dept)
                        .param("from", FROM).param("to", TO).param("dimension", "DEPT")
                        .param("deptId", DEPT_OR))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        // SELF 范围：本人（记录单创建人）只有 A 一台。
        String self = login(SELF_USERNAME, SELF_PASSWORD);
        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + self)
                        .param("from", FROM).param("to", TO).param("dimension", "STAFF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(1))
                .andExpect(jsonPath("$.anesthesiaMinutes").value(125))
                .andExpect(jsonPath("$.buckets.length()").value(1))
                .andExpect(jsonPath("$.buckets[0].label").value("SYN-AN-001 演示麻醉医师（合成）"));

        // 无 m4:report:read（SYS_SURGEON）→ 403。
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/reports/workload")
                        .header("Authorization", "Bearer " + surgeon)
                        .param("from", FROM).param("to", TO).param("dimension", "DEPT"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // 多维报表：分页、排序与全量合计
    // ------------------------------------------------------------------

    @Test
    void multiReportPaginatesWithStableOrderingAndGlobalTotals() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(get("/v1/reports/multi")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", TO)
                        .param("dimensions", "DEPT,SURGERY")
                        .param("metrics", "caseCount,anesthesiaMinutes")
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimensions[0]").value("DEPT"))
                .andExpect(jsonPath("$.dimensions[1]").value("SURGERY"))
                .andExpect(jsonPath("$.metrics[0]").value("caseCount"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].deptId").value(DEPT_ANES))
                .andExpect(jsonPath("$.rows[0].surgeryCode").value("SYNTH-ART-001"))
                .andExpect(jsonPath("$.rows[0].anesthesiaMinutes").value(125))
                .andExpect(jsonPath("$.rows[0].weightedCaseCount").value(1.5))
                .andExpect(jsonPath("$.rows[1].surgeryCode").value("SYNTH-GAS-002"))
                // 合计覆盖全部分组，不是当前页。
                .andExpect(jsonPath("$.totals.caseCount").value(3))
                .andExpect(jsonPath("$.totals.anesthesiaMinutes").value(215))
                .andExpect(jsonPath("$.totals.weightedCaseCount").value(3.5))
                .andExpect(jsonPath("$.totals.groupCount").value(3));

        mockMvc.perform(get("/v1/reports/multi")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", TO)
                        .param("dimensions", "DEPT,SURGERY")
                        .param("metrics", "caseCount")
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].deptId").value(DEPT_OR))
                .andExpect(jsonPath("$.rows[0].surgeryCode").value("=SUM(1,2)"));
    }

    // ------------------------------------------------------------------
    // CSV 导出：BOM、列头、行数、防注入、无 PHI、审计
    // ------------------------------------------------------------------

    @Test
    void csvExportHasBomHeaderFormulaGuardNoPhiAndAudit() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult result = mockMvc.perform(get("/v1/reports/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("report", "workload").param("format", "csv")
                        .param("from", FROM).param("to", TO).param("dimension", "DEPT"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "workload-DEPT-2031-03-01-2031-03-31.csv")))
                .andReturn();

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(3);
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);

        String csv = new String(bytes, StandardCharsets.UTF_8);
        List<String> lines = csv.lines().toList();
        assertThat(lines.get(0)).contains("report,level,dimension,key,label,caseCount,"
                + "anesthesiaMinutes,weightedCaseCount,unrecordedCaseCount,surgeryCode,"
                + "surgeryName,from,to,timezone");
        // TOTAL + 2 BUCKET + 3 SURGERY = 6 数据行。
        assertThat(lines).hasSize(7);
        assertThat(csv).contains("workload,TOTAL,DEPT,ALL,全部,3,215,3.5,1");
        // 公式注入防护：术式名以 = 开头 → 前置单引号（含逗号故同时加引号）。
        assertThat(csv).contains("'=SUM(1,2)");
        // 无 PHI：患者姓名、脱敏住院号、诊断均不出现。
        assertThat(csv).doesNotContain(PATIENT_NAME, "SYNT****R", "术前诊断");
        // 导出审计（detail 无 PHI，含格式与行数）。
        Map<String, Object> audit = jdbcTemplate.queryForMap("""
                SELECT detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE action = 'REPORT_EXPORT' AND resource_type = 'REPORT'
                 ORDER BY occurred_at DESC LIMIT 1
                """);
        assertThat(audit.get("detail").toString())
                .contains("\"report\": \"workload\"")
                .contains("\"dataRows\": 6")
                .doesNotContain(PATIENT_NAME, "SYNT****R");

        // 导出参数非法仍返回 JSON 400（不写已提交的 CSV 响应）。
        mockMvc.perform(get("/v1/reports/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("report", "workload").param("format", "csv")
                        .param("from", FROM).param("to", TO))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
    }

    // ------------------------------------------------------------------
    // V24 data-only 权限基线
    // ------------------------------------------------------------------

    @Test
    void v24SeedsReportPermissionsAndGrants() {
        Integer permissions = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_permission
                 WHERE code IN ('m4:report:read', 'm4:report:export')
                """, Integer.class);
        assertThat(permissions).isEqualTo(2);

        Integer grants = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM m0_platform.sys_role_permission rp
                  JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
                 WHERE sp.code IN ('m4:report:read', 'm4:report:export')
                   AND rp.role_id IN ('a0000000-0000-4000-8000-000000000001'::uuid,
                                      'a0000000-0000-4000-8000-000000000005'::uuid,
                                      'a0000000-0000-4000-8000-000000000006'::uuid)
                """, Integer.class);
        assertThat(grants).isEqualTo(6);

        // read 权限 path 用通配覆盖 workload 与 multi（S-5），不新增第三个权限码。
        String readPath = jdbcTemplate.queryForObject("""
                SELECT path FROM m0_platform.sys_permission WHERE code = 'm4:report:read'
                """, String.class);
        assertThat(readPath).isEqualTo("/api/v1/reports/*");
    }

    // ------------------------------------------------------------------
    // 固定数据构造与登录
    // ------------------------------------------------------------------

    private void ensureSelfScopedReportUser() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_REPORT_SELF', '合成报表本人角色',
                        'SYNTHETIC：报表 SELF 数据范围用例', 'SELF')
                ON CONFLICT (id) DO NOTHING
                """, SELF_ROLE_ID);
        String hash = new BCryptPasswordEncoder().encode(SELF_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试报表本人账号（合成）', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, SELF_USER_ID, SELF_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, SELF_USER_ID, SELF_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                VALUES (?::uuid, 'b0000000-0000-4000-8000-000000000077'),
                       (?::uuid, 'b0000000-0000-4000-8000-000000000078')
                ON CONFLICT DO NOTHING
                """, SELF_ROLE_ID, SELF_ROLE_ID);
    }

    private void insertStaffProfile() {
        jdbcTemplate.update("""
                INSERT INTO m4_staff.staff_profile
                    (id, user_id, staff_no, name, department_id, site_id, title, employment_status)
                VALUES (?::uuid, ?::uuid, ?, '演示麻醉医师（合成）', ?::uuid, ?::uuid,
                        'ATTENDING', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, STAFF_ID, SELF_USER_ID, STAFF_NO, DEPT_ANES, SITE);
    }

    private void insertCase(String suffix, String roomId, String deptId, String deptName,
            String surgeryCode, String surgeryName, String startAt, String endAt, String status,
            String recordCreatedBy) {
        String patientId = "54000000-0000-4000-8000-00000000" + suffix;
        String applicationId = "55000000-0000-4000-8000-00000000" + suffix;
        String scheduleId = "56000000-0000-4000-8000-00000000" + suffix;
        String recordId = "57000000-0000-4000-8000-00000000" + suffix;
        String hisNo = "SYNTH-M4-REP-" + suffix;
        OffsetDateTime start = OffsetDateTime.parse(startAt);
        OffsetDateTime end = OffsetDateTime.parse(endAt);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, ?, 'MALE', DATE '1975-06-01', 56, ?, ?, ?, ?::uuid, ?::uuid,
                        '演示病区', '01', now(), now(), 1, false)
                ON CONFLICT (id) DO NOTHING
                """, patientId, hisNo, PATIENT_NAME, "SYNT****R" + suffix,
                "hash-" + hisNo, deptName, deptId, SITE);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, surgery_code, urgency,
                     planned_duration_min, dept_name, dept_id, site_id, surgeon_name, status,
                     source, remark, version, deleted)
                VALUES (?::uuid, ?, ?::uuid, ?, ?, 'ELECTIVE', 120, ?, ?::uuid, ?::uuid,
                        '演示术者（合成）', 'SCHEDULED', 'MANUAL', 'SYNTH-M4-REPORT', 1, false)
                ON CONFLICT (id) DO NOTHING
                """, applicationId, hisNo + "-APP", patientId, surgeryName, surgeryCode, deptName,
                deptId, SITE);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority, status,
                     reason, version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 1, 0, ?, 'SYNTH-M4-REPORT', 1, false)
                ON CONFLICT (id) DO NOTHING
                """, scheduleId, applicationId, roomId, start, end, status);
        if (recordCreatedBy != null) {
            jdbcTemplate.update("""
                    INSERT INTO m2_anes.anesthesia_record
                        (id, schedule_id, application_id, patient_id, status, anesthesia_type,
                         started_at, ended_at, created_by, updated_by, version, deleted)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'ONGOING', 'GENERAL', ?, ?,
                            ?::uuid, ?::uuid, 1, false)
                    ON CONFLICT (id) DO NOTHING
                    """, recordId, scheduleId, applicationId, patientId, start, end,
                    recordCreatedBy, recordCreatedBy);
        }
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
}
