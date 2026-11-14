package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * M4 管理域专项验收（M4-QA-01）：MockMvc + 真实嵌入式 PostgreSQL 16 + 真实 Flyway V20–V25。
 *
 * <p>把 M4 五域的发布准入不变量收敛为一组聚合用例，失败即阻断 M4 验收：
 * <ul>
 *   <li>权限矩阵：SYS_ADMIN / SYS_PHARMACIST / SYS_AUTH_MANAGER / SYS_OR_MANAGER /
 *       SYS_QC_MANAGER / SYS_ANESTHESIOLOGIST / 匿名 × 药品写、五专、人员授权
 *       （双人审批/自授禁止）、耗材 UDI、报表导出、质控 export/review 的正负例；</li>
 *   <li>DataScope：DEPT 越界 403、ROOM fail-closed 403、SELF 仅本人只读；</li>
 *   <li>导出脱敏/注入：报表（workload/multi）与质控（indicator/adverse/review）CSV 的
 *       BOM、列头、无 PHI、`= + - @` 前置单引号、审计 dataRows；</li>
 *   <li>五专对账：专册/基数/交接/日结合计一致（期初 + 入库 − 使用 − 退回 = 期末）、
 *       不平 409、双人签字不同人；</li>
 *   <li>UDI 追溯：入库 → 使用 → 按 UDI 反查记录/台次、重复使用 409、过期/批次不一致守卫；</li>
 *   <li>手算对照：固定 SYNTHETIC 数据集的台次/时长/权重与质控率与 API 一致。</li>
 * </ul>
 *
 * <p>固定 SYNTHETIC 数据（与其他用例日期不交叉，用例结束清理）：
 * <ul>
 *   <li>报表 2032-06：R1 麻醉科记录单 125 分钟（创建人 = 麻醉医师 SELF 账号）、R2 麻醉科
 *       无记录单、R3 手术室记录单 90 分钟、R4–R6 手术室三条无记录单且术式名分别以
 *       {@code + - @} 开头；手算台次 6、时长 215、加权 6.5（SYNTH-ART-001 权重 1.5）、
 *       unrecorded 4，麻醉科桶 2/125/2.5、手术室桶 4/90/4.0。</li>
 *   <li>质控 2026-10：非取消台次 4（2 条记录单已结束、2 条有访视、1 条三方核查）、
 *       麻精专册 2（1 条双签完整）、不良事件 1、抽查 2（1 条 ≥90%）、
 *       高值出库 3（2 条带 UDI）；手算 50.00/50.00/25.00/50.00/250.00/50.00/66.67。</li>
 *   <li>五专（当前时点，业务日 T−3）：基数 10 + 交接入库 4 − 专册使用 3 − 退回 0 = 期末 11。</li>
 *   <li>UDI：高值耗材登记入库 1 → 绑定台次/记录使用 1 → 追溯链 2 条流水。</li>
 * </ul>
 * 全部数据为 SYNTHETIC；不新增生产代码、契约或迁移。
 */
@TestPropertySource(properties = "anesop.m4.report.surgery-weights.SYNTH-ART-001=1.5")
class M4AcceptanceTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String ADMIN = SyntheticAccounts.DEMO_ADMIN_USERNAME;
    private static final String ADMIN_PASSWORD = SyntheticAccounts.DEMO_ADMIN_PASSWORD;
    private static final String ADMIN_ID = SyntheticAccounts.DEMO_ADMIN_ID;
    private static final String DEPT_ANES = SyntheticAccounts.DEMO_ANES_ORG_ID;
    private static final String DEPT_OR = SyntheticAccounts.DEMO_OR_ORG_ID;
    private static final String SITE = "f1000000-0000-4000-8000-000000000001";
    private static final String ROOM_01 = "f2000000-0000-4000-8000-0000000000c1";
    private static final String ROOM_05 = "f2000000-0000-4000-8000-0000000000c5";
    private static final String SUFENTANIL_ID = "2a000000-0000-4000-8000-000000000001";
    private static final String SIGNER_A = "10000000-0000-4000-8000-000000000003";
    private static final String SIGNER_B = "10000000-0000-4000-8000-000000000004";
    private static final String APPROVER = SyntheticAccounts.APPROVER_1_ID;
    private static final String SECOND_APPROVER = SyntheticAccounts.APPROVER_2_ID;
    private static final String QA_PASSWORD = "Synth@2026";

    private static final String PHARMACIST_USER_ID = "e0000000-0000-4000-8000-0000000000a7";
    private static final String PHARMACIST_USERNAME = "test.m4.pharmacist";
    private static final String AUTH_MGR_USER_ID = "e0000000-0000-4000-8000-0000000000a8";
    private static final String AUTH_MGR_USERNAME = "test.m4.authmgr";
    private static final String OR_MGR_USER_ID = "e0000000-0000-4000-8000-0000000000a9";
    private static final String OR_MGR_USERNAME = "test.m4.ormgr";
    private static final String QC_MGR_USER_ID = "e0000000-0000-4000-8000-0000000000aa";
    private static final String QC_MGR_USERNAME = "test.m4.qcmgr";
    private static final String ROOM_USER_ID = "e0000000-0000-4000-8000-0000000000ab";
    private static final String ROOM_USERNAME = "test.m4.room";
    private static final String ROOM_ROLE_ID = "a0000000-0000-4000-8000-0000000000ab";
    private static final String SELF_REPORT_USER_ID = "e0000000-0000-4000-8000-0000000000ac";
    private static final String SELF_REPORT_USERNAME = "test.m4.selfreport";
    private static final String SELF_REPORT_ROLE_ID = "a0000000-0000-4000-8000-0000000000ac";
    private static final String ROLE_OR_MGR = "a0000000-0000-4000-8000-000000000005";
    private static final String ROLE_QC_MGR = "a0000000-0000-4000-8000-000000000006";
    private static final String ROLE_PHARMACIST = "a0000000-0000-4000-8000-000000000007";
    private static final String ROLE_AUTH_MGR = "a0000000-0000-4000-8000-000000000008";
    private static final String QA_PASSWORD_HASH = new BCryptPasswordEncoder().encode(QA_PASSWORD);

    private static final String REPORT_FROM = "2032-06-01";
    private static final String REPORT_TO = "2032-06-30";
    private static final String QC_FROM = "2026-10-01";
    private static final String QC_TO = "2026-10-31";
    private static final String PATIENT_NAME = "演示患者质控验收（合成）";
    private static final String QA_UDI_RECORD_ID = "57000000-0000-4000-8000-000000000051";
    private static final String QA_UDI_SCHEDULE_ID = "56000000-0000-4000-8000-000000000051";
    private static final String EVENT_ID = "7d000000-0000-4000-8000-0000000000b1";
    private static final String RCA_ID = "7e000000-0000-4000-8000-0000000000b1";
    private static final String REVIEW_PASS_ID = "7f000000-0000-4000-8000-0000000000b1";
    private static final String REVIEW_FAIL_ID = "7f000000-0000-4000-8000-0000000000b2";
    private static final String HIGH_VALUE_MATERIAL_ID = "8a000000-0000-4000-8000-0000000000b1";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedFixtures() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        SyntheticAccounts.ensureStaffGovernanceAccounts(jdbcTemplate);
        ensureM4Accounts();
        seedReportCases();
        seedQcCases();
        seedQcLedger();
        seedHighValueMaterial();
    }

    @AfterEach
    void cleanFixtures() {
        List<String> appendOnlyTables = List.of("m2_anes.anesthesia_record",
                "m2_anes.preop_assessment", "m2_anes.three_way_check",
                "m4_qc.chart_review", "m4_qc.rectification", "m4_qc.adverse_rca",
                "m4_qc.adverse_event", "m4_qc.qc_indicator_result");
        for (String table : appendOnlyTables) {
            jdbcTemplate.execute("ALTER TABLE " + table + " DISABLE TRIGGER USER");
        }
        try {
            jdbcTemplate.update("DELETE FROM m4_qc.rectification WHERE review_id IN (?::uuid, ?::uuid)",
                    REVIEW_PASS_ID, REVIEW_FAIL_ID);
            jdbcTemplate.update("DELETE FROM m4_qc.adverse_rca WHERE event_id = ?::uuid", EVENT_ID);
            jdbcTemplate.update("DELETE FROM m4_qc.chart_review WHERE id IN (?::uuid, ?::uuid)",
                    REVIEW_PASS_ID, REVIEW_FAIL_ID);
            jdbcTemplate.update("DELETE FROM m4_qc.adverse_event WHERE id = ?::uuid", EVENT_ID);
            jdbcTemplate.update(
                    "DELETE FROM m4_qc.qc_indicator_result WHERE period_from = DATE '2026-10-01'");
            jdbcTemplate.update("""
                    DELETE FROM m2_anes.anesthesia_record
                     WHERE schedule_id IN (
                         SELECT id FROM m1_sched.schedule WHERE reason LIKE 'SYNTH-M4-QA-%')
                    """);
            jdbcTemplate.update("""
                    DELETE FROM m2_anes.preop_assessment
                     WHERE application_id IN (
                         SELECT id FROM m1_sched.surgery_application
                          WHERE remark LIKE 'SYNTH-M4-QA-%')
                    """);
            jdbcTemplate.update("""
                    DELETE FROM m2_anes.three_way_check
                     WHERE application_id IN (
                         SELECT id FROM m1_sched.surgery_application
                          WHERE remark LIKE 'SYNTH-M4-QA-%')
                    """);
            jdbcTemplate.update("DELETE FROM m1_sched.schedule WHERE reason LIKE 'SYNTH-M4-QA-%'");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.surgery_application WHERE remark LIKE 'SYNTH-M4-QA-%'");
            jdbcTemplate.update(
                    "DELETE FROM m1_sched.patient_snapshot WHERE his_patient_no LIKE 'SYNTH-M4-QA-%'");
            // 清理数据范围绑定，避免污染 DataScopeApiIntegrationTest 的科室/术间成员精确断言。
            jdbcTemplate.update("""
                    DELETE FROM m0_platform.sys_user_org
                     WHERE user_id IN (?::uuid, ?::uuid)
                    """, AUTH_MGR_USER_ID, OR_MGR_USER_ID);
            jdbcTemplate.update("DELETE FROM m0_platform.sys_user_room WHERE user_id = ?::uuid",
                    ROOM_USER_ID);
            // 基数台账参与 /v1/narcotic/baselines 全量列表断言，用例结束删除本类柜号。
            jdbcTemplate.update(
                    "DELETE FROM m4_drug.drug_baseline WHERE cabinet_code LIKE 'CAB-QA-%'");
        } finally {
            for (String table : appendOnlyTables) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ENABLE TRIGGER USER");
            }
        }
    }

    // ------------------------------------------------------------------
    // 1. 权限矩阵：角色 × 端点（正/负例）+ 匿名 401
    // ------------------------------------------------------------------

    @Test
    void roleEndpointMatrixCoversPositiveAndNegativeCases() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        String pharmacist = login(PHARMACIST_USERNAME, QA_PASSWORD);
        String authMgr = login(AUTH_MGR_USERNAME, QA_PASSWORD);
        String orMgr = login(OR_MGR_USERNAME, QA_PASSWORD);
        String qcMgr = login(QC_MGR_USERNAME, QA_PASSWORD);
        String anes = login(SyntheticAccounts.DEMO_SELF_USERNAME,
                SyntheticAccounts.DEMO_SELF_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String staffTail = String.valueOf(SEQ.incrementAndGet());

        // 匿名：代表性端点一律 401（不是 403，避免把未认证误判为无权限）。
        mockMvc.perform(get("/v1/drugs")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/drugs").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/narcotic/registers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/staff")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/materials/stock")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/reports/workload").param("from", REPORT_FROM)
                .param("to", REPORT_TO).param("dimension", "DEPT"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/qc/indicators")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/qc/export").param("type", "indicator").param("format", "csv")
                .param("from", QC_FROM).param("to", QC_TO))
                .andExpect(status().isUnauthorized());

        // 药品目录：读 = 管理员/药师/麻醉医师；写 = 管理员/药师；其余 403。
        expect(admin, 200, get("/v1/drugs"));
        expect(pharmacist, 200, get("/v1/drugs"));
        expect(anes, 200, get("/v1/drugs"));
        expect(authMgr, 403, get("/v1/drugs"));
        expect(orMgr, 403, get("/v1/drugs"));
        expect(qcMgr, 403, get("/v1/drugs"));
        expect(admin, 201, post("/v1/drugs").contentType(JSON)
                .content(json(drugBody("QA-DRUG-A" + suffix))));
        expect(pharmacist, 201, post("/v1/drugs").contentType(JSON)
                .content(json(drugBody("QA-DRUG-P" + suffix))));
        expect(anes, 403, post("/v1/drugs").contentType(JSON)
                .content(json(drugBody("QA-DRUG-N" + suffix))));
        expect(authMgr, 403, post("/v1/drugs").contentType(JSON)
                .content(json(drugBody("QA-DRUG-M" + suffix))));
        expect(orMgr, 403, post("/v1/drugs").contentType(JSON)
                .content(json(drugBody("QA-DRUG-O" + suffix))));
        expect(qcMgr, 403, post("/v1/drugs").contentType(JSON)
                .content(json(drugBody("QA-DRUG-Q" + suffix))));

        // 麻精五专：科室选项与专册登记 = 管理员/药师/麻醉医师；其余 403。
        expect(admin, 200, get("/v1/narcotic/departments"));
        expect(pharmacist, 200, get("/v1/narcotic/departments"));
        expect(anes, 200, get("/v1/narcotic/departments"));
        expect(authMgr, 403, get("/v1/narcotic/departments"));
        expect(orMgr, 403, get("/v1/narcotic/departments"));
        expect(qcMgr, 403, get("/v1/narcotic/departments"));
        expect(admin, 201, post("/v1/narcotic/registers").contentType(JSON)
                .content(json(registerBody("QA-RX-A" + suffix, "QA-B-A" + suffix))));
        expect(pharmacist, 201, post("/v1/narcotic/registers").contentType(JSON)
                .content(json(registerBody("QA-RX-P" + suffix, "QA-B-P" + suffix))));
        expect(anes, 201, post("/v1/narcotic/registers").contentType(JSON)
                .content(json(registerBody("QA-RX-S" + suffix, "QA-B-S" + suffix))));
        expect(authMgr, 403, post("/v1/narcotic/registers").contentType(JSON)
                .content(json(registerBody("QA-RX-M" + suffix, "QA-B-M" + suffix))));
        expect(orMgr, 403, post("/v1/narcotic/registers").contentType(JSON)
                .content(json(registerBody("QA-RX-O" + suffix, "QA-B-O" + suffix))));
        expect(qcMgr, 403, post("/v1/narcotic/registers").contentType(JSON)
                .content(json(registerBody("QA-RX-Q" + suffix, "QA-B-Q" + suffix))));

        // 人员档案写：管理员与授权管理员（DEPT 内）可写；手术室管理员/药师/质控/麻醉医师 403。
        String adminStaff = createStaff(admin, staffBody("QA-STAFF-A" + staffTail, DEPT_ANES, null));
        expect(authMgr, 201, post("/v1/staff").contentType(JSON)
                .content(json(staffBody("QA-STAFF-M" + staffTail, DEPT_ANES, null))));
        expect(orMgr, 403, post("/v1/staff").contentType(JSON)
                .content(json(staffBody("QA-STAFF-O" + staffTail, DEPT_ANES, null))));
        expect(pharmacist, 403, post("/v1/staff").contentType(JSON)
                .content(json(staffBody("QA-STAFF-P" + staffTail, DEPT_ANES, null))));
        expect(qcMgr, 403, post("/v1/staff").contentType(JSON)
                .content(json(staffBody("QA-STAFF-Q" + staffTail, DEPT_ANES, null))));
        expect(anes, 403, post("/v1/staff").contentType(JSON)
                .content(json(staffBody("QA-STAFF-S" + staffTail, DEPT_ANES, null))));
        expect(admin, 200, get("/v1/staff"));
        expect(authMgr, 200, get("/v1/staff"));
        expect(orMgr, 200, get("/v1/staff"));
        expect(anes, 200, get("/v1/staff"));
        expect(qcMgr, 403, get("/v1/staff"));
        expect(pharmacist, 403, get("/v1/staff"));

        // 人员授权：授权管理员与管理员可写；其余角色 403。
        String authStaff = createStaff(authMgr, staffBody("QA-STAFF-G" + staffTail, DEPT_ANES, null));
        expect(admin, 200, put("/v1/staff/" + adminStaff + "/authorizations").contentType(JSON)
                .content(json(grant(0, "R3", "ASA3", false))));
        expect(authMgr, 200, put("/v1/staff/" + authStaff + "/authorizations").contentType(JSON)
                .content(json(grant(0, "R3", "ASA3", false))));
        expect(orMgr, 403, put("/v1/staff/" + adminStaff + "/authorizations").contentType(JSON)
                .content(json(grant(1, "R3", "ASA3", false))));
        expect(pharmacist, 403, put("/v1/staff/" + adminStaff + "/authorizations").contentType(JSON)
                .content(json(grant(1, "R3", "ASA3", false))));
        expect(qcMgr, 403, put("/v1/staff/" + adminStaff + "/authorizations").contentType(JSON)
                .content(json(grant(1, "R3", "ASA3", false))));
        expect(anes, 403, put("/v1/staff/" + adminStaff + "/authorizations").contentType(JSON)
                .content(json(grant(1, "R3", "ASA3", false))));

        // 耗材 UDI：管理员与手术室管理员（DEPT 内）可登记；库存只读同上；其余 403。
        String materialId = createMaterial(admin, "QA-MAT-" + suffix, "0693711" + suffix + "01");
        expect(admin, 201, post("/v1/materials/udi-instances").contentType(JSON)
                .content(json(udiBody(materialId, "0693711" + suffix + "01", "SN-QA-" + suffix,
                        "QA-UB-" + suffix))));
        expect(orMgr, 201, post("/v1/materials/udi-instances").contentType(JSON)
                .content(json(udiBody(materialId, "0693711" + suffix + "01", "SN-QB-" + suffix,
                        "QA-UB-" + suffix))));
        expect(pharmacist, 403, post("/v1/materials/udi-instances").contentType(JSON)
                .content(json(udiBody(materialId, "0693711" + suffix + "01", "SN-QC-" + suffix,
                        "QA-UB-" + suffix))));
        expect(authMgr, 403, post("/v1/materials/udi-instances").contentType(JSON)
                .content(json(udiBody(materialId, "0693711" + suffix + "01", "SN-QD-" + suffix,
                        "QA-UB-" + suffix))));
        expect(qcMgr, 403, post("/v1/materials/udi-instances").contentType(JSON)
                .content(json(udiBody(materialId, "0693711" + suffix + "01", "SN-QE-" + suffix,
                        "QA-UB-" + suffix))));
        expect(anes, 403, post("/v1/materials/udi-instances").contentType(JSON)
                .content(json(udiBody(materialId, "0693711" + suffix + "01", "SN-QF-" + suffix,
                        "QA-UB-" + suffix))));
        expect(admin, 200, get("/v1/materials/stock").param("materialId", materialId));
        expect(orMgr, 200, get("/v1/materials/stock").param("materialId", materialId));
        expect(qcMgr, 403, get("/v1/materials/stock").param("materialId", materialId));
        expect(pharmacist, 403, get("/v1/materials/stock").param("materialId", materialId));
        expect(anes, 403, get("/v1/materials/stock").param("materialId", materialId));

        // 报表：读/导出 = 管理员、手术室管理员、质控管理员；药师/授权管理员/麻醉医师 403。
        expect(admin, 200, workloadReport(REPORT_FROM, REPORT_TO, "DEPT", null));
        expect(orMgr, 200, workloadReport(REPORT_FROM, REPORT_TO, "DEPT", null));
        expect(qcMgr, 200, workloadReport(REPORT_FROM, REPORT_TO, "DEPT", null));
        expect(pharmacist, 403, workloadReport(REPORT_FROM, REPORT_TO, "DEPT", null));
        expect(authMgr, 403, workloadReport(REPORT_FROM, REPORT_TO, "DEPT", null));
        expect(anes, 403, workloadReport(REPORT_FROM, REPORT_TO, "DEPT", null));
        expect(admin, 200, reportExport(REPORT_FROM, REPORT_TO));
        expect(orMgr, 200, reportExport(REPORT_FROM, REPORT_TO));
        expect(qcMgr, 200, reportExport(REPORT_FROM, REPORT_TO));
        expect(pharmacist, 403, reportExport(REPORT_FROM, REPORT_TO));
        expect(authMgr, 403, reportExport(REPORT_FROM, REPORT_TO));
        expect(anes, 403, reportExport(REPORT_FROM, REPORT_TO));

        // 质控：读 = 管理员/质控管理员/手术室管理员（DEPT）；导出 = 管理员/质控管理员；
        // 复核 = 管理员/质控管理员（手术室管理员无 review）。
        expect(admin, 200, get("/v1/qc/indicators"));
        expect(qcMgr, 200, get("/v1/qc/indicators"));
        expect(orMgr, 200, get("/v1/qc/indicators"));
        expect(pharmacist, 403, get("/v1/qc/indicators"));
        expect(authMgr, 403, get("/v1/qc/indicators"));
        expect(anes, 403, get("/v1/qc/indicators"));
        expect(admin, 200, qcExport("indicator", QC_FROM, QC_TO));
        expect(qcMgr, 200, qcExport("indicator", QC_FROM, QC_TO));
        expect(orMgr, 403, qcExport("indicator", QC_FROM, QC_TO));
        expect(pharmacist, 403, qcExport("indicator", QC_FROM, QC_TO));
        expect(authMgr, 403, qcExport("indicator", QC_FROM, QC_TO));
        expect(anes, 403, qcExport("indicator", QC_FROM, QC_TO));

        // 质控复核闭环：质控管理员阶段 1（REPORTED/RCA → RECTIFYING），管理员阶段 2 → CLOSED。
        expect(qcMgr, 200, closeRca(false));
        expect(orMgr, 403, closeRca(false));
        expect(pharmacist, 403, closeRca(false));
        expect(authMgr, 403, closeRca(false));
        expect(anes, 403, closeRca(false));
        expect(admin, 200, closeRca(true));
    }

    // ------------------------------------------------------------------
    // 2. 分级授权：双人审批 + 自授禁止 + 审批人资质
    // ------------------------------------------------------------------

    @Test
    void authorizationRequiresDualApprovalAndForbidsSelfGrant() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        String authMgr = login(AUTH_MGR_USERNAME, QA_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String staffId = createStaff(admin, staffBody("QA-AUTH-" + suffix, DEPT_ANES, null));
        String path = "/v1/staff/" + staffId + "/authorizations";

        // 伪造审批人 → 400（不落库）。
        Map<String, Object> fake = grant(0, "R2", "ASA3", true);
        fake.put("approvedBy", UUID.randomUUID().toString());
        expect(admin, 400, put(path).contentType(JSON).content(json(fake)));

        // 双人审批缺第二审批人 → 400；第二审批人与第一审批人相同 → 400。
        Map<String, Object> missingSecond = grant(0, "R2", "ASA3", true);
        missingSecond.remove("secondApproverId");
        missingSecond.remove("secondApproverReason");
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(json(missingSecond)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("secondApproverId")));
        Map<String, Object> sameSecond = grant(0, "R2", "ASA3", true);
        sameSecond.put("secondApproverId", APPROVER);
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(json(sameSecond)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("secondApproverId")));

        // 两名不同审批人 → 200，且响应记录双方。
        MvcResult granted = mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(json(grant(0, "R2", "ASA3", true))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode current = objectMapper.readTree(granted.getResponse().getContentAsString())
                .get("current");
        assertThat(current.get("approvedBy").asText()).isEqualTo(APPROVER);
        assertThat(current.get("secondApproverId").asText()).isEqualTo(SECOND_APPROVER);
        assertThat(current.get("secondApprovedAt").asText()).isNotBlank();

        // 自授禁止：被授权人 = 操作者本人 → 403（即使审批人合法）。
        String selfStaffId = ensureStaffBoundTo(admin, "QA-AUTH-SELF", ADMIN_ID);
        mockMvc.perform(put("/v1/staff/" + selfStaffId + "/authorizations")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(json(grant(0, "R3", "ASA3", false))))
                .andExpect(status().isForbidden());

        // 授权管理员同样不得自授（被授权人 = 操作者本人）。
        String managerStaffId = ensureStaffBoundTo(authMgr, "QA-AUTH-MGRSELF", AUTH_MGR_USER_ID);
        mockMvc.perform(put("/v1/staff/" + managerStaffId + "/authorizations")
                        .header("Authorization", "Bearer " + authMgr)
                        .contentType(JSON).content(json(grant(0, "R3", "ASA3", false))))
                .andExpect(status().isForbidden());

        // 授权审计不写审批人姓名/证件号，只留标识。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'STAFF_AUTHORIZATION' AND resource_id LIKE ?
                 ORDER BY occurred_at DESC LIMIT 5
                """, "%" + staffId + "%");
        assertThat(audits).isNotEmpty();
        assertThat(audits).allMatch(row ->
                !String.valueOf(row.get("detail")).contains("SYNTH-CERT-"));
    }

    // ------------------------------------------------------------------
    // 3. 数据范围：DEPT 越界 403、ROOM fail-closed、SELF 只读本人
    // ------------------------------------------------------------------

    @Test
    void dataScopeEnforcesDeptBoundaryRoomFailClosedAndSelfReadOnly() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        String orMgr = login(OR_MGR_USERNAME, QA_PASSWORD);
        String room = login(ROOM_USERNAME, QA_PASSWORD);
        String self = login(SyntheticAccounts.DEMO_SELF_USERNAME,
                SyntheticAccounts.DEMO_SELF_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String foreignStaffId = createStaff(admin,
                staffBody("QA-SCOPE-FOREIGN-" + suffix, DEPT_OR, null));

        // DEPT 范围：本科室 2 台次/125 分钟；显式越界科室 403（不返回空页掩盖越权）。
        mockMvc.perform(workloadReport(REPORT_FROM, REPORT_TO, "DEPT", null)
                        .header("Authorization", "Bearer " + orMgr))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"caseCount\":2")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"anesthesiaMinutes\":125")));
        mockMvc.perform(workloadReport(REPORT_FROM, REPORT_TO, "DEPT", DEPT_OR)
                        .header("Authorization", "Bearer " + orMgr))
                .andExpect(status().isForbidden());
        expect(orMgr, 403, get("/v1/staff").param("departmentId", DEPT_OR));

        // ROOM 范围：报表按术间收窄（仅 ROOM-01 的 2 台次），人员/耗材无术间语义 → fail-closed 403。
        mockMvc.perform(workloadReport(REPORT_FROM, REPORT_TO, "DEPT", null)
                        .header("Authorization", "Bearer " + room))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"caseCount\":2")));
        expect(room, 403, get("/v1/staff"));
        expect(room, 403, get("/v1/materials/stock"));

        // SELF：报表只统计本人创建记录单的台次（R1，125 分钟）。
        String selfReport = login(SELF_REPORT_USERNAME, QA_PASSWORD);
        mockMvc.perform(workloadReport(REPORT_FROM, REPORT_TO, "STAFF", null)
                        .header("Authorization", "Bearer " + selfReport))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"caseCount\":1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"anesthesiaMinutes\":125")));
        // SELF：仅本人档案可读；他人档案 404 不泄露存在性；写操作 403。
        mockMvc.perform(get("/v1/staff/" + foreignStaffId)
                        .header("Authorization", "Bearer " + self))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/staff").header("Authorization", "Bearer " + self)
                        .contentType(JSON).content(json(staffBody("QA-SCOPE-SELF" + suffix,
                                DEPT_ANES, null))))
                .andExpect(status().isForbidden());
        // ROOM 的耗材只读即使被授予也 fail-closed（与 M4Material 用例互补）。
        mockMvc.perform(get("/v1/materials/departments")
                        .header("Authorization", "Bearer " + room))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // 4. 导出脱敏/防注入：报表 2 类 + 质控 3 类 CSV
    // ------------------------------------------------------------------

    @Test
    void csvExportsAreBomMaskedInjectionSafePhiFreeAndAudited() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);

        // 报表 workload：BOM + 列头 + 行数 + 四种公式前缀 + 无 PHI + 审计 dataRows。
        MvcResult workload = mockMvc.perform(reportExport(REPORT_FROM, REPORT_TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "workload-DEPT-2032-06-01-2032-06-30.csv")))
                .andReturn();
        String workloadCsv = csvOf(workload);
        assertThat(workloadCsv).startsWith("\uFEFF");
        assertThat(headerLine(workloadCsv)).contains(
                "report,level,dimension,key,label,caseCount,anesthesiaMinutes,"
                        + "weightedCaseCount,unrecordedCaseCount,surgeryCode,surgeryName,"
                        + "from,to,timezone");
        // TOTAL + 2 BUCKET + 6 SURGERY = 9 数据行。
        assertThat(workloadCsv.lines().count()).as("CSV 应含表头 + 9 数据行").isEqualTo(10);
        assertThat(workloadCsv).contains("workload,TOTAL,DEPT,ALL,全部,6,215,6.5,4");
        assertThat(workloadCsv).contains("'=SUM(1,2)").contains("'+1+2")
                .contains("'-1+2").contains("'@SUM(1,2)");
        assertThat(workloadCsv).doesNotContain(PATIENT_NAME, "SYNTH-M4-QA", "SYNT****QA",
                "住院号", "身份证", "手机号");
        assertAuditDataRows("REPORT_EXPORT", "report", "workload", 9);
        assertReportAuditHasNoPhi("workload");

        // 报表 multi：全部分组（DEPT,SURGERY）导出 6 行。
        MvcResult multi = mockMvc.perform(get("/v1/reports/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("report", "multi").param("format", "csv")
                        .param("from", REPORT_FROM).param("to", REPORT_TO)
                        .param("dimensions", "DEPT,SURGERY")
                        .param("metrics", "caseCount,anesthesiaMinutes"))
                .andExpect(status().isOk())
                .andReturn();
        String multiCsv = csvOf(multi);
        assertThat(multiCsv).startsWith("\uFEFF");
        assertThat(multiCsv).contains("report,level,dimension,key,label");
        assertThat(multiCsv).contains("'=SUM(1,2)").contains("'@SUM(1,2)");
        assertThat(multiCsv).doesNotContain(PATIENT_NAME, "SYNTH-M4-QA", "身份证", "手机号");
        assertAuditDataRows("REPORT_EXPORT", "report", "multi", 6);

        // 质控 indicator：19 列聚合口径 + 7 条指标 + 审计 dataRows。
        MvcResult indicator = mockMvc.perform(qcExport("indicator", QC_FROM, QC_TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "qc-indicator-2026-10-01-2026-10-31.csv")))
                .andReturn();
        String indicatorCsv = csvOf(indicator);
        assertThat(indicatorCsv).startsWith("\uFEFF");
        assertThat(headerLine(indicatorCsv)).isEqualTo(
                "type,code,name,category,indicatorVersion,periodFrom,periodTo,deptId,deptName,"
                        + "numerator,denominator,value,unit,thresholdOperator,thresholdValue,"
                        + "targetMet,inTargetStatistics,provisional,timezone");
        assertThat(indicatorCsv).contains("qc-indicator,QC-SYN-STRUCT-001")
                .contains(",2,4,50.00,PERCENT,GTE,95");
        assertThat(indicatorCsv.lines().count()).as("表头 + 7 条指标").isEqualTo(8);
        assertThat(indicatorCsv).doesNotContain(PATIENT_NAME, "SYNTH-M4-QA", "身份证", "手机号");
        assertAuditDataRows("QC_EXPORT", "type", "indicator", 7);

        // 质控 adverse：责任人公式注入被中和；无自由文本（描述/根因/整改）。
        MvcResult adverse = mockMvc.perform(qcExport("adverse", QC_FROM, QC_TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        String adverseCsv = csvOf(adverse);
        assertThat(adverseCsv).startsWith("\uFEFF");
        assertThat(headerLine(adverseCsv)).isEqualTo(
                "type,eventNo,eventType,severity,occurredAt,deptId,deptName,status,rcaStatus,"
                        + "responsibleName,dueDate,reviewerName,closedAt,anonymous,timezone");
        assertThat(adverseCsv).contains("qc-adverse,AE-20261006-0000b1")
                .contains("'@SUM(1,2)");
        assertThat(adverseCsv).doesNotContain("床栏未固定", "rootCause", "description",
                PATIENT_NAME, "SYNTH-M4-QA", "身份证", "手机号");
        assertAuditDataRows("QC_EXPORT", "type", "adverse", 1);

        // 质控 review：整改责任人公式注入被中和；缺陷说明原文不导出。
        MvcResult review = mockMvc.perform(qcExport("review", QC_FROM, QC_TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        String reviewCsv = csvOf(review);
        assertThat(reviewCsv).startsWith("\uFEFF");
        assertThat(headerLine(reviewCsv)).isEqualTo(
                "type,reviewNo,reviewedAt,deptId,deptName,totalScore,maxScore,grade,defectCount,"
                        + "status,rectificationStatus,responsibleName,dueDate,closedAt,timezone");
        assertThat(reviewCsv).contains("qc-review").contains("'=SUM(1,2)");
        assertThat(reviewCsv).doesNotContain("床栏未固定", "defectNote",
                PATIENT_NAME, "SYNTH-M4-QA", "身份证", "手机号");
        assertAuditDataRows("QC_EXPORT", "type", "review", 2);
    }

    // ------------------------------------------------------------------
    // 5. 五专对账：基数 + 交接 + 专册 → 日结
    // ------------------------------------------------------------------

    @Test
    void narcoticLedgerReconcilesAndRejectsUnbalancedClose() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String cabinet = "CAB-QA-" + suffix;
        String batch = "QA-NB-" + suffix;

        // 基数 10。
        mockMvc.perform(put("/v1/narcotic/baselines")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(Map.of("items", List.of(baselineItem(cabinet, 10))))))
                .andExpect(status().isOk());
        // 双人签字同一人 → 400。
        Map<String, Object> sameSigner = registerBody("QA-RX-SAME-" + suffix, batch);
        sameSigner.put("secondSignerId", SIGNER_A);
        mockMvc.perform(post("/v1/narcotic/registers")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(sameSigner)))
                .andExpect(status().isBadRequest());
        // 专册使用 3 支（3 条 × 1）。
        for (int index = 1; index <= 3; index++) {
            mockMvc.perform(post("/v1/narcotic/registers")
                            .header("Authorization", "Bearer " + admin).contentType(JSON)
                            .content(json(registerBody("QA-RX-" + suffix + "-" + index, batch))))
                    .andExpect(status().isCreated());
        }
        // 专柜交接入库 4 支；交班人 = 接班人 → 400。
        Map<String, Object> sameHandover = handoverBody(cabinet);
        sameHandover.put("handoverToId", SIGNER_A);
        mockMvc.perform(post("/v1/narcotic/cabinet-handovers")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(sameHandover)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/narcotic/cabinet-handovers")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(handoverBody(cabinet))))
                .andExpect(status().isCreated());

        // 从 API 读取三类台账，独立求和后与日结参数对照（不是复制页面数字）。
        JsonNode baselines = read(mockMvc.perform(get("/v1/narcotic/baselines")
                        .param("cabinetCode", cabinet)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
        int opening = baselines.get("items").get(0).get("quantity").asInt();
        JsonNode handovers = read(mockMvc.perform(get("/v1/narcotic/cabinet-handovers")
                        .param("cabinetCode", cabinet)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
        int inbound = 0;
        for (JsonNode item : handovers.get("items").get(0).get("items")) {
            inbound += item.get("quantity").asInt();
        }
        JsonNode registers = read(mockMvc.perform(get("/v1/narcotic/registers")
                        .param("batchNo", batch)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
        int used = 0;
        for (JsonNode item : registers.get("items")) {
            if (batch.equals(item.get("batchNo").asText())) {
                used += item.get("quantity").asInt();
            }
        }
        int returned = 0;
        int expectedClosing = opening + inbound - used - returned;
        assertThat(opening).isEqualTo(10);
        assertThat(inbound).isEqualTo(4);
        assertThat(used).isEqualTo(3);
        assertThat(expectedClosing).isEqualTo(11);

        // 不平 409：details 给出期望值与实盘值；白天班另提交一条平账。
        LocalDate closeDate = TODAY.minusDays(3);
        mockMvc.perform(post("/v1/narcotic/daily-closes")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(closeBody(closeDate, "NIGHT", cabinet, 10))))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("期望 11")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("实盘 10")));
        MvcResult closed = mockMvc.perform(post("/v1/narcotic/daily-closes")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(closeBody(closeDate, "DAY", cabinet, expectedClosing))))
                .andExpect(status().isCreated())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"balanced\":true")))
                .andReturn();
        String closeId = read(closed).get("closeId").asText();
        JsonNode saved = read(mockMvc.perform(get("/v1/narcotic/daily-closes/" + closeId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
        assertThat(saved.get("openingQuantity").asInt()).isEqualTo(opening);
        assertThat(saved.get("inboundQuantity").asInt()).isEqualTo(inbound);
        assertThat(saved.get("usedQuantity").asInt()).isEqualTo(used);
        assertThat(saved.get("returnedQuantity").asInt()).isZero();
        assertThat(saved.get("closingQuantity").asInt()).isEqualTo(expectedClosing);
        // 同日同班次重复日结 → 409（append-only，不允许合并改写）。
        mockMvc.perform(post("/v1/narcotic/daily-closes")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(closeBody(closeDate, "DAY", cabinet, expectedClosing))))
                .andExpect(status().isConflict());
        // 审计：成功日结合同带柜号；不平失败审计按白名单只记 rejection/field（无柜号/处方号）。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE action = 'NARCOTIC_DAILY_CLOSE' AND result = 1
                   AND detail->>'cabinetCode' = ?
                """, Long.class, cabinet)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE action = 'NARCOTIC_DAILY_CLOSE' AND result = 0
                   AND detail->>'rejection' = 'STATE_CONFLICT'
                """, Long.class)).isGreaterThanOrEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // 6. UDI 追溯链：入库 → 使用 → 反查；重复使用/过期/批次不一致
    // ------------------------------------------------------------------

    @Test
    void udiTraceChainCoversInboundUseLookupAndGuards() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String udiDi = "0693712" + suffix + "01";
        String materialId = createMaterial(admin, "QA-UDI-" + suffix, udiDi);
        String batch = "QA-UDIB-" + suffix;
        String udi = udiDi + "^SN-QA-" + suffix;
        LocalDate future = TODAY.plusYears(1);

        // 入库（登记 = 入库）：IN_STOCK + 批次库存 1。
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(udiBody(materialId, udiDi, "SN-QA-" + suffix, batch))))
                .andExpect(status().isCreated())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"status\":\"IN_STOCK\"")));
        mockMvc.perform(get("/v1/materials/stock").param("materialId", materialId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"quantity\":1")));
        // 重复登记 409；UDI-DI 与耗材不一致 422。
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(udiBody(materialId, udiDi, "SN-QA-" + suffix, batch))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(udiBody(materialId, "0693712999" + suffix, "SN-X-" + suffix,
                                batch))))
                .andExpect(status().isUnprocessableEntity());

        // 使用前：不存在的记录 422（不消耗库存）。
        mockMvc.perform(post("/v1/materials/udi-instances/" + udi + "/use")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(Map.of("recordId",
                                "7a000000-0000-4000-8000-0000000000ff"))))
                .andExpect(status().isUnprocessableEntity());
        // 绑定真实台次/记录使用：IN_STOCK → USED，库存 0。
        mockMvc.perform(post("/v1/materials/udi-instances/" + udi + "/use")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(Map.of("recordId", QA_UDI_RECORD_ID,
                                "scheduleId", QA_UDI_SCHEDULE_ID))))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"status\":\"USED\"")));
        mockMvc.perform(get("/v1/materials/stock").param("materialId", materialId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"quantity\":0")));
        // 一物一码：重复使用 409。
        mockMvc.perform(post("/v1/materials/udi-instances/" + udi + "/use")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(Map.of("recordId", QA_UDI_RECORD_ID))))
                .andExpect(status().isConflict());

        // 按 UDI 反查：实例 → 耗材 → 流水 2 条（IN/OUT）→ 记录/台次。
        mockMvc.perform(get("/v1/materials/udi-instances/" + udi)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"status\":\"USED\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"recordId\":\"" + QA_UDI_RECORD_ID + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"scheduleId\":\"" + QA_UDI_SCHEDULE_ID + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"movements\":[{")));

        // 过期登记 422；同批次效期不一致/批次不一致 409。
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(udiBodyWithExpiry(materialId, udiDi, "SN-EXP-" + suffix,
                                batch, TODAY.minusDays(1)))))
                .andExpect(status().isUnprocessableEntity());
        String udi2 = udiDi + "^SN-QA2-" + suffix;
        String batch2 = "QA-UDIB2-" + suffix;
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(udiBodyWithExpiry(materialId, udiDi, "SN-QA2-" + suffix,
                                batch2, future))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(stockReturn(materialId, batch, future, udi2))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"field\":\"batchNo\"")));
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(json(stockReturn(materialId, batch2, future, udi2))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/v1/materials/udi-instances/" + udi2)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"status\":\"RETURNED\"")));
        // 审计 UDI_USE 只记录 UDI-DI/记录/台次，不含患者标识。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE action = 'UDI_USE' AND result = 1 AND detail->>'udiDi' = ?
                 ORDER BY occurred_at DESC LIMIT 1
                """, udiDi);
        assertThat(audits).hasSize(1);
        assertThat(String.valueOf(audits.get(0).get("detail")))
                .contains(QA_UDI_RECORD_ID).doesNotContain(PATIENT_NAME, "SYNTH-M4-QA");
    }

    // ------------------------------------------------------------------
    // 7. 报表手算对照：台次/时长/权重/维度桶
    // ------------------------------------------------------------------

    @Test
    void workloadMatchesHandCalculatedTotalsAndWeights() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        JsonNode workload = read(mockMvc.perform(workloadReport(REPORT_FROM, REPORT_TO, "DEPT",
                        null).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
        // 手算：台次 6、麻醉时长 125+90=215、加权 1.5+1+1+1+1+1=6.5、无记录单 4。
        assertThat(workload.get("caseCount").asInt()).isEqualTo(6);
        assertThat(workload.get("anesthesiaMinutes").asInt()).isEqualTo(215);
        assertThat(workload.get("weightedCaseCount").decimalValue())
                .isEqualByComparingTo("6.5");
        assertThat(workload.get("unrecordedCaseCount").asInt()).isEqualTo(4);
        JsonNode anesBucket = bucket(workload, DEPT_ANES);
        assertThat(anesBucket.get("caseCount").asInt()).isEqualTo(2);
        assertThat(anesBucket.get("anesthesiaMinutes").asInt()).isEqualTo(125);
        assertThat(anesBucket.get("weightedCaseCount").decimalValue())
                .isEqualByComparingTo("2.5");
        assertThat(anesBucket.get("unrecordedCaseCount").asInt()).isEqualTo(1);
        JsonNode orBucket = bucket(workload, DEPT_OR);
        assertThat(orBucket.get("caseCount").asInt()).isEqualTo(4);
        assertThat(orBucket.get("anesthesiaMinutes").asInt()).isEqualTo(90);
        assertThat(orBucket.get("weightedCaseCount").decimalValue())
                .isEqualByComparingTo("4.0");
        // 术式桶：SYNTH-ART-001 权重 1.5 来自配置（@TestPropertySource），时长为真实记录单 125。
        JsonNode surgery = surgeryIn(anesBucket, "SYNTH-ART-001");
        assertThat(surgery.get("anesthesiaMinutes").asInt()).isEqualTo(125);
        assertThat(surgery.get("caseCount").asInt()).isEqualTo(1);

        JsonNode multi = read(mockMvc.perform(get("/v1/reports/multi")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", REPORT_FROM).param("to", REPORT_TO)
                        .param("dimensions", "DEPT,SURGERY")
                        .param("metrics", "caseCount,anesthesiaMinutes,weightedCaseCount")
                        .param("size", "100"))
                .andExpect(status().isOk()).andReturn());
        assertThat(multi.get("total").asInt()).isEqualTo(6);
        assertThat(multi.get("totals").get("caseCount").asInt()).isEqualTo(6);
        assertThat(multi.get("totals").get("anesthesiaMinutes").asInt()).isEqualTo(215);
        assertThat(multi.get("totals").get("weightedCaseCount").decimalValue())
                .isEqualByComparingTo("6.5");
    }

    // ------------------------------------------------------------------
    // 8. 质控率手算对照
    // ------------------------------------------------------------------

    @Test
    void qcIndicatorRatesMatchHandCalculation() throws Exception {
        String admin = login(ADMIN, ADMIN_PASSWORD);
        JsonNode body = read(mockMvc.perform(get("/v1/qc/indicator-results")
                        .param("from", QC_FROM).param("to", QC_TO)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn());
        // 手算（全院，2026-10）：台次 4（2 条记录单/2 条访视/1 条三方核查）、
        // 麻精 1/2、不良事件 1/4、抽查 1/2、高值 UDI 2/3。
        assertIndicator(body, "QC-SYN-STRUCT-001", 2, 4, "50.00", false);
        assertIndicator(body, "QC-SYN-PROC-002", 2, 4, "50.00", false);
        assertIndicator(body, "QC-SYN-PROC-003", 1, 4, "25.00", false);
        assertIndicator(body, "QC-SYN-PROC-004", 1, 2, "50.00", false);
        assertIndicator(body, "QC-SYN-OUT-005", 1, 4, "250.00", true);
        assertIndicator(body, "QC-SYN-PROC-006", 1, 2, "50.00", false);
        assertIndicator(body, "QC-SYN-STRUCT-007", 2, 3, "66.67", false);
        // 结构合规口径不计入达标统计（S-1），结果快照可追溯。
        JsonNode structure = itemByCode(body, "QC-SYN-PROC-004");
        assertThat(structure.get("inTargetStatistics").asBoolean()).isFalse();
        assertThat(structure.get("targetMet").asBoolean()).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m4_qc.qc_indicator_result
                 WHERE period_from = DATE '2026-10-01'
                """, Integer.class)).isEqualTo(7);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private void ensureM4Accounts() {
        ensureRoleUser(PHARMACIST_USER_ID, PHARMACIST_USERNAME, ROLE_PHARMACIST, null);
        ensureRoleUser(AUTH_MGR_USER_ID, AUTH_MGR_USERNAME, ROLE_AUTH_MGR, DEPT_ANES);
        ensureRoleUser(OR_MGR_USER_ID, OR_MGR_USERNAME, ROLE_OR_MGR, DEPT_ANES);
        ensureRoleUser(QC_MGR_USER_ID, QC_MGR_USERNAME, ROLE_QC_MGR, null);
        // SELF 数据范围 + 报表只读：验证“只统计本人记录单台次”。
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_QA_REPORT_SELF', '合成报表本人验收角色',
                        'SYNTHETIC：M4 报表 SELF 数据范围用例', 'SELF')
                ON CONFLICT (id) DO NOTHING
                """, SELF_REPORT_ROLE_ID);
        ensureRoleUser(SELF_REPORT_USER_ID, SELF_REPORT_USERNAME, SELF_REPORT_ROLE_ID, null);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                SELECT ?::uuid, id FROM m0_platform.sys_permission
                 WHERE code = 'm4:report:read'
                ON CONFLICT DO NOTHING
                """, SELF_REPORT_ROLE_ID);
        // ROOM 数据范围：授予 M4 三个只读权限，使 403 来自数据范围 fail-closed。
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_QA_ROOM', '合成术间验收角色',
                        'SYNTHETIC：M4 数据范围 ROOM fail-closed 用例', 'ROOM')
                ON CONFLICT (id) DO NOTHING
                """, ROOM_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试术间验收账号（合成）', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, ROOM_USER_ID, ROOM_USERNAME, QA_PASSWORD_HASH);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_role WHERE user_id = ?::uuid",
                ROOM_USER_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, ROOM_USER_ID, ROOM_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                SELECT ?::uuid, id FROM m0_platform.sys_permission
                 WHERE code IN ('m4:staff:read', 'm4:report:read', 'm4:material:read')
                ON CONFLICT DO NOTHING
                """, ROOM_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_room (user_id, room_id, status)
                VALUES (?::uuid, ?::uuid, 1) ON CONFLICT DO NOTHING
                """, ROOM_USER_ID, ROOM_01);
    }

    private void ensureRoleUser(String userId, String username, String roleId, String deptId) {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, 'M4 验收角色账号（合成）', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, userId, username, QA_PASSWORD_HASH);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_role WHERE user_id = ?::uuid",
                userId);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid)
                """, userId, roleId);
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_org WHERE user_id = ?::uuid",
                userId);
        if (deptId != null) {
            jdbcTemplate.update("""
                    INSERT INTO m0_platform.sys_user_org
                        (user_id, org_id, is_primary, created_at, created_by, updated_at,
                         updated_by)
                    VALUES (?::uuid, ?::uuid, true, now(), NULL, now(), NULL)
                    """, userId, deptId);
        }
    }

    private void seedReportCases() {
        insertCase("31", "SYNTH-M4-QA-RPT", ROOM_01, DEPT_ANES, "麻醉科（合成）",
                "SYNTH-ART-001", "演示关节置换术（合成）", "2032-06-02T08:00:00+08:00",
                "2032-06-02T10:05:00+08:00", "COMPLETED", SELF_REPORT_USER_ID, true);
        insertCase("32", "SYNTH-M4-QA-RPT", ROOM_01, DEPT_ANES, "麻醉科（合成）",
                "SYNTHQA-B", "演示胃肠手术（合成）", "2032-06-03T08:00:00+08:00",
                "2032-06-03T09:00:00+08:00", "PLANNED", null, false);
        insertCase("33", "SYNTH-M4-QA-RPT", ROOM_05, DEPT_OR, "手术室（合成）",
                "SYNTHQA-EQ", "=SUM(1,2)", "2032-06-04T08:00:00+08:00",
                "2032-06-04T09:30:00+08:00", "COMPLETED", ADMIN_ID, true);
        insertCase("34", "SYNTH-M4-QA-RPT", ROOM_05, DEPT_OR, "手术室（合成）",
                "SYNTHQA-PLUS", "+1+2", "2032-06-05T08:00:00+08:00",
                "2032-06-05T09:00:00+08:00", "PLANNED", null, false);
        insertCase("35", "SYNTH-M4-QA-RPT", ROOM_05, DEPT_OR, "手术室（合成）",
                "SYNTHQA-MINUS", "-1+2", "2032-06-06T08:00:00+08:00",
                "2032-06-06T09:00:00+08:00", "PLANNED", null, false);
        insertCase("36", "SYNTH-M4-QA-RPT", ROOM_05, DEPT_OR, "手术室（合成）",
                "SYNTHQA-AT", "@SUM(1,2)", "2032-06-07T08:00:00+08:00",
                "2032-06-07T09:00:00+08:00", "PLANNED", null, false);
        // UDI 绑定用台次/记录（2026-08，避开质控与报表窗口）。
        insertCase("51", "SYNTH-M4-QA-UDI", ROOM_01, DEPT_ANES, "麻醉科（合成）",
                "SYNTHUDI-A", "演示 UDI 绑定术式（合成）", "2026-08-15T08:00:00+08:00",
                "2026-08-15T10:00:00+08:00", "COMPLETED", ADMIN_ID, true);
    }

    private void seedQcCases() {
        insertCase("41", "SYNTH-M4-QA-QC", ROOM_01, DEPT_ANES, "麻醉科（合成）",
                "SYNTHQC-A", "演示质控术式甲（合成）", "2026-10-02T08:00:00+08:00",
                "2026-10-02T10:00:00+08:00", "COMPLETED", ADMIN_ID, true);
        insertCase("42", "SYNTH-M4-QA-QC", ROOM_01, DEPT_ANES, "麻醉科（合成）",
                "SYNTHQC-B", "演示质控术式乙（合成）", "2026-10-03T08:00:00+08:00",
                "2026-10-03T09:30:00+08:00", "COMPLETED", ADMIN_ID, true);
        insertCase("43", "SYNTH-M4-QA-QC", ROOM_01, DEPT_ANES, "麻醉科（合成）",
                "SYNTHQC-C", "演示质控术式丙（合成）", "2026-10-04T08:00:00+08:00",
                "2026-10-04T09:00:00+08:00", "PLANNED", null, false);
        insertCase("44", "SYNTH-M4-QA-QC", ROOM_05, DEPT_OR, "手术室（合成）",
                "SYNTHQC-D", "演示质控术式丁（合成）", "2026-10-05T08:00:00+08:00",
                "2026-10-05T11:00:00+08:00", "PLANNED", null, false);
        // 访视 2 条（41/42）、三方核查 1 条（41）。
        for (String tail : List.of("41", "42")) {
            String applicationId = "55000000-0000-4000-8000-0000000000" + tail;
            String patientId = "54000000-0000-4000-8000-0000000000" + tail;
            String preopId = "58000000-0000-4000-8000-0000000000" + tail;
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
        jdbcTemplate.update("""
                INSERT INTO m2_anes.three_way_check
                    (id, application_id, check_version, time_out_at, time_out_by, sign_out_at,
                     sign_out_by, specimen_checked, implant_checked)
                VALUES ('59000000-0000-4000-8000-000000000041'::uuid,
                        '55000000-0000-4000-8000-000000000041'::uuid, 1,
                        TIMESTAMPTZ '2026-10-02 08:10:00+08', ?::uuid,
                        TIMESTAMPTZ '2026-10-02 10:10:00+08', ?::uuid, true, true)
                ON CONFLICT (id) DO NOTHING
                """, ADMIN_ID, ADMIN_ID);
    }

    private void seedQcLedger() {
        // 麻精专册 2 条：1 条双签完整、1 条第二签字人姓名为空。
        String[][] registers = {
                {"5a000000-0000-4000-8000-0000000000b1", "QA-QC-B1", "QA-QC-RX-1",
                        "演示签字人乙（合成）", "2026-10-02T09:00:00+08:00"},
                {"5a000000-0000-4000-8000-0000000000b2", "QA-QC-B2", "QA-QC-RX-2",
                        "", "2026-10-03T09:00:00+08:00"},
        };
        for (String[] register : registers) {
            jdbcTemplate.update("""
                    INSERT INTO m4_drug.narcotic_register
                        (id, drug_id, drug_name, batch_no, quantity, unit, prescription_no,
                         used_by, department_id, usage_at, destination, first_signer_id,
                         first_signer_name, second_signer_id, second_signer_name)
                    VALUES (?::uuid, ?::uuid, '舒芬太尼（演示）', ?, 1, '支', ?,
                            '演示使用人（合成）', ?::uuid, ?::timestamptz, '术中',
                            ?::uuid, '演示签字人甲（合成）', ?::uuid, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, register[0], SUFENTANIL_ID, register[1], register[2], DEPT_ANES,
                    register[4], SIGNER_A, SIGNER_B, register[3]);
        }
        // 不良事件 1 条（RCA 阶段，供复核权限正例）；责任人姓名用公式前缀验证导出中和。
        jdbcTemplate.update("""
                INSERT INTO m4_qc.adverse_event
                    (id, event_no, event_type, severity, occurred_at, dept_id, dept_name,
                     description, reporter_id, reporter_name, anonymous, status, version)
                VALUES (?::uuid, 'AE-20261006-0000b1', 'MEDICATION', 'LEVEL_II',
                        TIMESTAMPTZ '2026-10-06 10:30:00+08', ?::uuid, '麻醉科（合成）',
                        '术中一过性血压下降（合成，已脱敏）', ?::uuid, 'demo_admin', false,
                        'RCA', 1)
                ON CONFLICT (id) DO NOTHING
                """, EVENT_ID, DEPT_ANES, ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO m4_qc.adverse_rca
                    (id, event_id, event_no, timeline, root_cause, corrective_action,
                     responsible_name, due_date, status)
                VALUES (?::uuid, ?::uuid, 'AE-20261006-0000b1',
                        ('[{"occurredAt":"2026-10-06T10:30:00+08:00",'
                        || '"description":"术中血压下降（合成）"}]')::jsonb,
                        '诱导期容量评估不足（合成）', '修订容量评估清单（合成）',
                        '@SUM(1,2)', DATE '2026-11-30', 'OPEN')
                ON CONFLICT (id) DO NOTHING
                """, RCA_ID, EVENT_ID);
        // 病历抽查 2 条：95/100 合格、80/100 不合格；合格项带整改与公式前缀责任人。
        insertChartReview(REVIEW_PASS_ID, "CR-20261010-0000b1", "2026-10-10T09:00:00+08:00",
                95, "A");
        insertChartReview(REVIEW_FAIL_ID, "CR-20261011-0000b2", "2026-10-11T09:00:00+08:00",
                80, "C");
        jdbcTemplate.update("""
                INSERT INTO m4_qc.rectification
                    (id, review_id, review_no, corrective_action, responsible_name, due_date,
                     status)
                VALUES ('80000000-0000-4000-8000-0000000000b1'::uuid, ?::uuid,
                        'CR-20261010-0000b1', '补充术后随访核对（合成）', '=SUM(1,2)',
                        DATE '2026-11-30', 'OPEN')
                ON CONFLICT (id) DO NOTHING
                """, REVIEW_PASS_ID);
    }

    private void insertChartReview(String id, String reviewNo, String reviewedAt, int score,
            String grade) {
        jdbcTemplate.update("""
                INSERT INTO m4_qc.chart_review
                    (id, review_no, dept_id, dept_name, reviewer_id, reviewer_name, reviewed_at,
                     items, total_score, max_score, grade, defect_count, status, version)
                VALUES (?::uuid, ?, ?::uuid, '麻醉科（合成）', ?::uuid, 'demo_admin',
                        ?::timestamptz,
                        ('[{"itemCode":"COMPLETENESS","itemName":"完整性","score":' || ?
                        || ',"maxScore":100,"defectNote":"床栏未固定（合成）"}]')::jsonb,
                        ?, 100, ?, 1, 'OPEN', 1)
                ON CONFLICT (id) DO NOTHING
                """, id, reviewNo, DEPT_ANES, ADMIN_ID, reviewedAt, score, score, grade);
    }

    private void seedHighValueMaterial() {
        jdbcTemplate.update("""
                INSERT INTO m4_material.material
                    (id, code, name, specification, unit, category, high_value, expiry_managed,
                     status, remark, version)
                VALUES (?::uuid, 'QA-MAT-HV-001', '演示高值耗材验收（合成）', '7F', '个',
                        'HIGH_VALUE', true, false, 'ACTIVE', 'SYNTH-M4-QA', 1)
                ON CONFLICT (id) DO NOTHING
                """, HIGH_VALUE_MATERIAL_ID);
        for (int index = 1; index <= 3; index++) {
            jdbcTemplate.update("""
                    INSERT INTO m4_material.stock_movement
                        (id, movement_type, material_id, material_code, material_name, batch_no,
                         udi_code, quantity, unit, department_id, counterparty, reason,
                         balance_after, operator_id, operator_name, occurred_at)
                    VALUES (?::uuid, 'OUT', ?::uuid, 'QA-MAT-HV-001', '演示高值耗材验收（合成）',
                            'QA-HV-B1', ?, 1, '个', ?::uuid, '手术室', 'SYNTH-M4-QA', 1,
                            ?::uuid, 'demo_admin', ?::timestamptz)
                    ON CONFLICT (id) DO NOTHING
                    """, "8b000000-0000-4000-8000-0000000000b" + index, HIGH_VALUE_MATERIAL_ID,
                    index <= 2 ? "0106900000000000^SYNTH-QA-" + index : null, DEPT_ANES,
                    ADMIN_ID, "2026-10-0" + (index + 6) + "T09:00:00+08:00");
        }
    }

    private void insertCase(String tail, String marker, String roomId, String deptId,
            String deptName, String surgeryCode, String surgeryName, String startAt,
            String endAt, String status, String recordCreatedBy, boolean ended) {
        String patientId = "54000000-0000-4000-8000-0000000000" + tail;
        String applicationId = "55000000-0000-4000-8000-0000000000" + tail;
        String scheduleId = "56000000-0000-4000-8000-0000000000" + tail;
        String recordId = "57000000-0000-4000-8000-0000000000" + tail;
        String hisNo = "SYNTH-M4-QA-" + tail;
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, ?, 'MALE', DATE '1975-06-01', 56, 'SYNT****QA', ?,
                        ?, ?::uuid, ?::uuid, '演示病区', '01', now(), now(), 1, false)
                ON CONFLICT (id) DO NOTHING
                """, patientId, hisNo, PATIENT_NAME, "hash-" + hisNo, deptName, deptId, SITE);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, surgery_code, urgency,
                     planned_duration_min, dept_name, dept_id, site_id, surgeon_name, status,
                     source, remark, version, deleted)
                VALUES (?::uuid, ?, ?::uuid, ?, ?, 'ELECTIVE', 120, ?, ?::uuid, ?::uuid,
                        '演示术者（合成）', 'SCHEDULED', 'MANUAL', ?, 1, false)
                ON CONFLICT (id) DO NOTHING
                """, applicationId, hisNo + "-APP", patientId, surgeryName, surgeryCode,
                deptName, deptId, SITE, marker);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority, status,
                     reason, version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::timestamptz, ?::timestamptz, 1, 0, ?,
                        ?, 1, false)
                ON CONFLICT (id) DO NOTHING
                """, scheduleId, applicationId, roomId, startAt, endAt, status, marker);
        if (recordCreatedBy != null) {
            jdbcTemplate.update("""
                    INSERT INTO m2_anes.anesthesia_record
                        (id, schedule_id, application_id, patient_id, status, anesthesia_type,
                         started_at, ended_at, created_by, updated_by, version, deleted)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'ONGOING', 'GENERAL',
                            ?::timestamptz, ?::timestamptz, ?::uuid, ?::uuid, 1, false)
                    ON CONFLICT (id) DO NOTHING
                    """, recordId, scheduleId, applicationId, patientId, startAt,
                    ended ? endAt : null, recordCreatedBy, recordCreatedBy);
        }
    }

    // ==================================================================
    // 请求与断言辅助
    // ==================================================================

    private void expect(String token, int expectedStatus, MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().is(expectedStatus));
    }

    private MockHttpServletRequestBuilder workloadReport(String from, String to, String dimension,
            String deptId) {
        MockHttpServletRequestBuilder request = get("/v1/reports/workload")
                .param("from", from).param("to", to).param("dimension", dimension);
        return deptId == null ? request : request.param("deptId", deptId);
    }

    private MockHttpServletRequestBuilder reportExport(String from, String to) {
        return get("/v1/reports/export")
                .param("report", "workload").param("format", "csv")
                .param("from", from).param("to", to).param("dimension", "DEPT");
    }

    private MockHttpServletRequestBuilder qcExport(String type, String from, String to) {
        return get("/v1/qc/export")
                .param("type", type).param("format", "csv")
                .param("from", from).param("to", to);
    }

    private MockHttpServletRequestBuilder closeRca(boolean completed) throws Exception {
        return post("/v1/qc/rca/" + RCA_ID + "/close").contentType(JSON).content(json(Map.of(
                "reviewerName", "演示质控复核人（合成）",
                "conclusion", "复核结论（合成）",
                "rectificationCompleted", completed)));
    }

    private String csvOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void assertAuditDataRows(String action, String keyColumn, String keyValue,
            int expectedRows) {
        Integer dataRows = jdbcTemplate.queryForObject("""
                SELECT (detail->>'dataRows')::int FROM m0_platform.sys_audit_log
                 WHERE action = ? AND detail->>? = ?
                 ORDER BY occurred_at DESC LIMIT 1
                """, Integer.class, action, keyColumn, keyValue);
        assertThat(dataRows).isEqualTo(expectedRows);
    }

    private void assertReportAuditHasNoPhi(String report) {
        String detail = jdbcTemplate.queryForObject("""
                SELECT detail::text FROM m0_platform.sys_audit_log
                 WHERE action = 'REPORT_EXPORT' AND detail->>'report' = ?
                 ORDER BY occurred_at DESC LIMIT 1
                """, String.class, report);
        assertThat(detail).contains("\"dataRows\"").doesNotContain(PATIENT_NAME, "SYNTH-M4-QA",
                "身份证", "手机号");
    }

    private JsonNode bucket(JsonNode workload, String key) {
        for (JsonNode item : workload.get("buckets")) {
            if (key.equals(item.get("key").asText())) {
                return item;
            }
        }
        throw new AssertionError("缺少维度桶 " + key);
    }

    private static String headerLine(String csv) {
        String first = csv.lines().findFirst().orElseThrow();
        return first.startsWith("\uFEFF") ? first.substring(1) : first;
    }

    private JsonNode surgeryIn(JsonNode bucket, String surgeryCode) {
        for (JsonNode item : bucket.get("surgeryDistribution")) {
            if (surgeryCode.equals(item.get("surgeryCode").asText())) {
                return item;
            }
        }
        throw new AssertionError("缺少术式桶 " + surgeryCode);
    }

    private JsonNode itemByCode(JsonNode body, String code) {
        for (JsonNode item : body.get("items")) {
            if (code.equals(item.get("code").asText())) {
                return item;
            }
        }
        throw new AssertionError("缺少指标 " + code);
    }

    private void assertIndicator(JsonNode body, String code, int numerator, int denominator,
            String value, boolean targetMet) {
        JsonNode item = itemByCode(body, code);
        assertThat(item.get("numerator").asInt()).as("%s numerator", code).isEqualTo(numerator);
        assertThat(item.get("denominator").asInt()).as("%s denominator", code)
                .isEqualTo(denominator);
        assertThat(item.get("value").decimalValue()).as("%s value", code)
                .isEqualByComparingTo(value);
        assertThat(item.get("targetMet").asBoolean()).as("%s targetMet", code)
                .isEqualTo(targetMet);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createStaff(String token, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("staffId").asText();
    }

    /** 幂等复用“绑定指定用户”的人员档案（user_id 唯一，避免重复创建 409）。 */
    private String ensureStaffBoundTo(String token, String staffNoPrefix, String userId)
            throws Exception {
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT id::text FROM m4_staff.staff_profile WHERE user_id = ?::uuid",
                String.class, userId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return createStaff(token, staffBody(staffNoPrefix + "-" + SEQ.incrementAndGet(),
                DEPT_ANES, userId));
    }

    private String createMaterial(String token, String code, String udiDi) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "QA 高值耗材（" + code + "）（合成）");
        body.put("specification", "演示规格");
        body.put("unit", "个");
        body.put("category", "HIGH_VALUE");
        body.put("udiDi", udiDi);
        body.put("highValue", true);
        body.put("expiryManaged", true);
        body.put("remark", "SYNTHETIC 演示备注");
        MvcResult result = mockMvc.perform(post("/v1/materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("materialId").asText();
    }

    private static Map<String, Object> drugBody(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "QA 药品（" + code + "）");
        body.put("specification", "1ml");
        body.put("unit", "支");
        body.put("dosageForm", "INJECTION");
        body.put("category", "OPIOID");
        body.put("narcotic", false);
        body.put("highRisk", false);
        body.put("aliases", List.of());
        return body;
    }

    private static Map<String, Object> registerBody(String prescriptionNo, String batch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drugId", SUFENTANIL_ID);
        body.put("batchNo", batch);
        body.put("quantity", 1);
        body.put("unit", "支");
        body.put("prescriptionNo", prescriptionNo);
        body.put("usedBy", "演示使用人（合成）");
        body.put("departmentId", DEPT_ANES);
        body.put("usageAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(10)
                .toString());
        body.put("destination", "术中镇痛（合成）");
        body.put("firstSignerId", SIGNER_A);
        body.put("firstSignerName", "演示给药人（合成）");
        body.put("secondSignerId", SIGNER_B);
        body.put("secondSignerName", "演示核对人（合成）");
        return body;
    }

    private static Map<String, Object> baselineItem(String cabinet, int quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("drugId", SUFENTANIL_ID);
        item.put("cabinetCode", cabinet);
        item.put("departmentId", DEPT_ANES);
        item.put("quantity", quantity);
        return item;
    }

    private static Map<String, Object> handoverBody(String cabinet) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("drugId", SUFENTANIL_ID);
        item.put("quantity", 4);
        item.put("unit", "支");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cabinetCode", cabinet);
        body.put("departmentId", DEPT_ANES);
        body.put("shift", "DAY");
        body.put("handedOverAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(5)
                .toString());
        body.put("handoverFromId", SIGNER_A);
        body.put("handoverFromName", "演示交班人（合成）");
        body.put("handoverToId", SIGNER_B);
        body.put("handoverToName", "演示接班人（合成）");
        body.put("items", List.of(item));
        body.put("firstSignerId", SIGNER_A);
        body.put("firstSignerName", "演示交班人（合成）");
        body.put("secondSignerId", SIGNER_B);
        body.put("secondSignerName", "演示接班人（合成）");
        return body;
    }

    private static Map<String, Object> closeBody(LocalDate closeDate, String shift,
            String cabinet, int closingQuantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("closeDate", closeDate.toString());
        body.put("shift", shift);
        body.put("cabinetCode", cabinet);
        body.put("departmentId", DEPT_ANES);
        body.put("openingQuantity", 10);
        body.put("inboundQuantity", 4);
        body.put("usedQuantity", 3);
        body.put("returnedQuantity", 0);
        body.put("closingQuantity", closingQuantity);
        return body;
    }

    private static Map<String, Object> udiBody(String materialId, String udiDi, String udiPi,
            String batch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("materialId", materialId);
        body.put("udiDi", udiDi);
        body.put("udiPi", udiPi);
        body.put("serialNumber", udiPi);
        body.put("batchNo", batch);
        body.put("expiryDate", TODAY.plusYears(1).toString());
        body.put("departmentId", DEPT_ANES);
        body.put("counterparty", "中心库房（合成）");
        return body;
    }

    private static Map<String, Object> udiBodyWithExpiry(String materialId, String udiDi,
            String udiPi, String batch, LocalDate expiry) {
        Map<String, Object> body = udiBody(materialId, udiDi, udiPi, batch);
        body.put("expiryDate", expiry.toString());
        return body;
    }

    private static Map<String, Object> stockReturn(String materialId, String batch,
            LocalDate expiry, String udi) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("movementType", "RETURN");
        body.put("materialId", materialId);
        body.put("batchNo", batch);
        body.put("expiryDate", expiry.toString());
        body.put("quantity", 1);
        body.put("departmentId", DEPT_ANES);
        body.put("counterparty", "中心库房（合成）");
        body.put("udi", udi);
        return body;
    }

    private static Map<String, Object> staffBody(String staffNo, String departmentId,
            String userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (userId != null) {
            body.put("userId", userId);
        }
        body.put("staffNo", staffNo);
        body.put("name", "QA 演示医师（合成）");
        body.put("departmentId", departmentId);
        body.put("title", "主任医师");
        body.put("jobGrade", "正高");
        body.put("employmentStatus", "ACTIVE");
        body.put("specialties", List.of("心血管麻醉"));
        Map<String, Object> qualification = new LinkedHashMap<>();
        qualification.put("qualificationType", "PRACTICE_LICENSE");
        qualification.put("certificateNo", "QA-CERT-" + staffNo);
        qualification.put("issuingAuthority", "省卫生健康委员会（合成）");
        qualification.put("validFrom", TODAY.minusYears(1).toString());
        qualification.put("validTo", TODAY.plusYears(5).toString());
        body.put("qualifications", List.of(qualification));
        return body;
    }

    private static Map<String, Object> grant(int version, String level, String asaMax,
            boolean dual) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "GRANT");
        body.put("version", version);
        body.put("level", level);
        body.put("surgeryScopes", List.of("GENERAL"));
        body.put("ageMin", 18);
        body.put("ageMax", 90);
        body.put("asaMax", asaMax);
        body.put("validFrom", TODAY.minusDays(1).toString());
        body.put("validTo", TODAY.plusDays(365).toString());
        body.put("approvedBy", APPROVER);
        if (dual) {
            body.put("secondApproverId", SECOND_APPROVER);
            body.put("secondApproverReason", "第二审批人复核（SYNTHETIC）");
        }
        body.put("changeReason", "年度授权复评（SYNTHETIC）");
        return body;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(json(Map.of("username", username, "password", password,
                                "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).get("accessToken").asText();
    }
}
