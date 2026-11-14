package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 药品目录与麻精五专（M4-M15-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：药品编码/名称唯一（409）、别名冲突（400）、乐观锁（409）、检索与权限 403；
 * 专册/交接/基数/日结（双人签字不同人 400、日结不平 409 + details、重复日结 409）、
 * append-only（55000）、药品未收录（422）、科室数据范围（403）；药房适配器（EXT-02）
 * 状态与降级留痕；审计 DRUG_CREATE/DRUG_UPDATE/NARCOTIC_* 与 ADAPTER_SYNC 且无 PHI；
 * V20 结构/权限/触发器。全部数据为 SYNTHETIC。
 */
class M4DrugApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String DEPT = SyntheticAccounts.DEMO_ANES_ORG_ID;
    private static final String OTHER_DEPT = SyntheticAccounts.DEMO_OR_ORG_ID;
    private static final String SUFENTANIL_ID = "2a000000-0000-4000-8000-000000000001";
    private static final String PROPOFOL_ID = "2a000000-0000-4000-8000-000000000002";
    private static final String SIGNER_A = "10000000-0000-4000-8000-000000000003";
    private static final String SIGNER_B = "10000000-0000-4000-8000-000000000004";
    private static final AtomicInteger SEQ = new AtomicInteger();

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
    }

    @Test
    void drugCatalogCrudUniquenessAndPermissions() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 100);
        String code = "DRUG-T4-" + suffix;
        String name = "T4 测试药品（" + suffix + "）";

        Map<String, Object> create = new LinkedHashMap<>();
        create.put("code", code);
        create.put("name", name);
        create.put("genericName", "测试通用名（" + suffix + "）");
        create.put("specification", "10mg/2ml");
        create.put("unit", "支");
        create.put("dosageForm", "INJECTION");
        create.put("category", "OPIOID");
        create.put("narcotic", true);
        create.put("highRisk", true);
        create.put("aliases", List.of("测试别名（" + suffix + "）"));
        create.put("remark", "SYNTHETIC 演示备注");
        MvcResult created = mockMvc.perform(post("/v1/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.narcotic").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.aliases", hasSize(1)))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        String drugId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("drugId").asText();

        // 编码重复 409、名称重复 409、别名与名称冲突 400。
        mockMvc.perform(post("/v1/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        Map<String, Object> duplicateName = new LinkedHashMap<>(create);
        duplicateName.put("code", code + "-X");
        duplicateName.put("aliases", List.of());
        mockMvc.perform(post("/v1/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(duplicateName)))
                .andExpect(status().isConflict());
        Map<String, Object> aliasConflict = new LinkedHashMap<>(create);
        aliasConflict.put("code", code + "-Y");
        aliasConflict.put("name", name + "-Y");
        aliasConflict.put("aliases", List.of("舒芬太尼（演示）"));
        mockMvc.perform(post("/v1/drugs")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(aliasConflict)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        // 详情、检索与乐观锁更新。
        mockMvc.perform(get("/v1/drugs/" + drugId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name));
        mockMvc.perform(get("/v1/drugs/search")
                        .param("q", "测试别名（" + suffix + "）")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].drugId").value(drugId));
        mockMvc.perform(get("/v1/drugs")
                        .param("keyword", suffix)
                        .param("status", "ACTIVE")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(1)));

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("name", name);
        update.put("specification", "20mg/2ml");
        update.put("unit", "支");
        update.put("dosageForm", "INJECTION");
        update.put("category", "OPIOID");
        update.put("narcotic", false);
        update.put("highRisk", true);
        update.put("aliases", List.of("测试别名二（" + suffix + "）"));
        update.put("version", 1);
        mockMvc.perform(put("/v1/drugs/" + drugId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.narcotic").value(false))
                .andExpect(jsonPath("$.version").value(2));
        update.put("version", 1);
        mockMvc.perform(put("/v1/drugs/" + drugId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isConflict());

        // 不存在 404；无 m4:drug:write 的术者 403（读也需要 m4:drug:read）。
        mockMvc.perform(get("/v1/drugs/2a000000-0000-4000-8000-0000000000ff")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/drugs").header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mockMvc.perform(post("/v1/drugs")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isForbidden());

        // 审计：DRUG_CREATE/DRUG_UPDATE 各一条，detail 不含备注原文与患者信息。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'DRUG' AND resource_id = ? AND result = 1
                 ORDER BY occurred_at, action
                """, drugId);
        assertThat(audits).extracting(row -> row.get("action"))
                .contains("DRUG_CREATE", "DRUG_UPDATE");
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail")).contains("演示备注"));
        assertThat(audits).noneMatch(row -> String.valueOf(row.get("detail")).contains("演示患者"));
    }

    @Test
    void narcoticFiveSpecialtiesFlowAndRules() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 200);
        String batch = "BATCH-SYNTH-" + suffix;
        String cabinet = "CAB-T4-" + suffix;

        // 双人签字同一人 400（药品未收录 422 单独用例）。
        Map<String, Object> register = registerBody("RX-SYNTH-" + suffix, batch);
        register.put("secondSignerId", SIGNER_A);
        mockMvc.perform(post("/v1/narcotic/registers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("secondSignerId"));

        Map<String, Object> unknownDrug = registerBody("RX-SYNTH-U-" + suffix, batch);
        unknownDrug.put("drugId", "2a000000-0000-4000-8000-0000000000ff");
        mockMvc.perform(post("/v1/narcotic/registers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(unknownDrug)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.details[0].field").value("drugId"));

        Map<String, Object> valid = registerBody("RX-SYNTH-" + suffix, batch);
        MvcResult created = mockMvc.perform(post("/v1/narcotic/registers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(valid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.drugName").value("舒芬太尼（演示）"))
                .andExpect(jsonPath("$.departmentId").value(DEPT))
                .andExpect(jsonPath("$.quantity").exists())
                .andReturn();
        String registerId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("registerId").asText();

        mockMvc.perform(get("/v1/narcotic/registers")
                        .param("batchNo", batch)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/narcotic/registers")
                        .param("departmentId", DEPT)
                        .param("drugId", SUFENTANIL_ID)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(greaterThanOrEqualTo(1))));

        // append-only：UPDATE/DELETE 55000。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m4_drug.narcotic_register SET unit = 'x' WHERE id = ?::uuid", registerId)),
                "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "DELETE FROM m4_drug.narcotic_register WHERE id = ?::uuid", registerId)),
                "append-only");

        Map<String, Object> handover = handoverBody(cabinet);
        handover.put("handoverToId", SIGNER_A);
        mockMvc.perform(post("/v1/narcotic/cabinet-handovers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(handover)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("handoverToId"));

        handover.put("handoverToId", SIGNER_B);
        mockMvc.perform(post("/v1/narcotic/cabinet-handovers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(handover)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shift").value("DAY"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].drugName").value("舒芬太尼（演示）"));
        mockMvc.perform(get("/v1/narcotic/cabinet-handovers")
                        .param("cabinetCode", cabinet)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));

        // 基数：新建 → 查询 → 版本过期 409。
        Map<String, Object> baselineItem = new LinkedHashMap<>();
        baselineItem.put("drugId", SUFENTANIL_ID);
        baselineItem.put("cabinetCode", cabinet);
        baselineItem.put("departmentId", DEPT);
        baselineItem.put("quantity", 10);
        mockMvc.perform(put("/v1/narcotic/baselines")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                baselineItem)))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/narcotic/baselines")
                        .param("cabinetCode", cabinet)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").exists())
                .andExpect(jsonPath("$.items[0].version").value(1));
        Map<String, Object> staleItem = new LinkedHashMap<>(baselineItem);
        staleItem.put("quantity", 12);
        staleItem.put("version", 1);
        Map<String, Object> updatedItem = new LinkedHashMap<>(baselineItem);
        updatedItem.put("quantity", 12);
        updatedItem.put("version", 1);
        mockMvc.perform(put("/v1/narcotic/baselines")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                updatedItem)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(2));
        mockMvc.perform(put("/v1/narcotic/baselines")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                staleItem)))))
                .andExpect(status().isConflict());

        // 日结：不平 409（details 给出期望值与实盘值）→ 平账 201 → 重复 409。
        LocalDate closeDate = LocalDate.now(ZoneOffset.ofHours(8)).minusDays(1);
        Map<String, Object> unbalanced = closeBody(closeDate, cabinet, 8);
        mockMvc.perform(post("/v1/narcotic/daily-closes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(unbalanced)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.details[0].field").value("closingQuantity"))
                .andExpect(jsonPath("$.details[0].reason").value(
                        org.hamcrest.Matchers.containsString("期望 10")));

        Map<String, Object> balanced = closeBody(closeDate, cabinet, 10);
        MvcResult closeCreated = mockMvc.perform(post("/v1/narcotic/daily-closes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(balanced)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.closingQuantity").exists())
                .andReturn();
        String closeId = objectMapper.readTree(closeCreated.getResponse().getContentAsString())
                .get("closeId").asText();
        mockMvc.perform(get("/v1/narcotic/daily-closes/" + closeId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closeId").value(closeId));
        mockMvc.perform(post("/v1/narcotic/daily-closes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(balanced)))
                .andExpect(status().isConflict());
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m4_drug.daily_close SET closing_quantity = 99 WHERE id = ?::uuid",
                closeId)), "append-only");

        // 审计：四类五专动作均有成功留痕，且不含处方号原文/人员姓名。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'NARCOTIC' AND result = 1
                 ORDER BY occurred_at, action
                """);
        assertThat(audits).extracting(row -> row.get("action")).contains("NARCOTIC_REGISTER",
                "NARCOTIC_HANDOVER", "NARCOTIC_BASELINE", "NARCOTIC_DAILY_CLOSE");
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail"))
                .contains("RX-SYNTH-" + suffix));
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail"))
                .contains("演示给药人"));

        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/narcotic/registers")
                        .header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden());
    }

    @Test
    void pharmacyAdapterStatusIsSimulatedAndLogged() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(get("/v1/drugs/pharmacy-adapter/status")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adapterCode").value("EXT-02"))
                .andExpect(jsonPath("$.mode").value("SIMULATED"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.lastSyncedAt").isNotEmpty());

        Long logs = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m4_drug.pharmacy_adapter_log WHERE adapter_code = 'EXT-02'
                """, Long.class);
        assertThat(logs).isGreaterThanOrEqualTo(1L);
        Long audits = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_audit_log
                 WHERE action = 'ADAPTER_SYNC' AND result = 1
                """, Long.class);
        assertThat(audits).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void v20SchemaPermissionsAndTriggersAreInstalled() {
        Long tables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'm4_drug'
                """, Long.class);
        assertThat(tables).isEqualTo(7L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname = 'ck_daily_close_balanced'
                   AND conrelid = 'm4_drug.daily_close'::regclass
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname = 'ck_narcotic_register_two_signers'
                   AND conrelid = 'm4_drug.narcotic_register'::regclass
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_permission
                 WHERE code IN ('m4:drug:read', 'm4:drug:write', 'm4:narcotic:manage')
                """, Long.class)).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                  JOIN m0_platform.sys_role r ON r.id = rp.role_id
                  JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE r.code IN ('SYS_ADMIN', 'SYS_PHARMACIST', 'SYS_ANESTHESIOLOGIST',
                                  'SYS_NURSE_ANESTHETIST')
                   AND p.code IN ('m4:drug:read', 'm4:drug:write', 'm4:narcotic:manage')
                """, Long.class)).isEqualTo(9L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_trigger t
                  JOIN pg_class c ON c.oid = t.tgrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'm4_drug' AND NOT t.tgisinternal
                   AND t.tgname IN ('trg_narcotic_register_no_update',
                       'trg_cabinet_handover_no_delete', 'trg_daily_close_no_truncate')
                """, Long.class)).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m4_drug.drug WHERE narcotic = true AND code LIKE 'DRUG-%'
                """, Long.class)).isGreaterThanOrEqualTo(2L);
        // B-1：名称/通用名/别名归一化（trim + lower）唯一；通用名部分唯一（NULL 除外）。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname = 'm4_drug' AND indexname = 'uk_drug_name'
                """, String.class)).contains("UNIQUE", "lower", "btrim");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname = 'm4_drug' AND indexname = 'uk_drug_generic_name'
                """, String.class)).contains("UNIQUE", "btrim", "WHERE");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes
                 WHERE schemaname = 'm4_drug' AND indexname = 'uk_drug_alias_alias'
                """, String.class)).contains("UNIQUE", "lower", "btrim");
        // B-2：基数科室不可改由数据库守卫兜底。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_functiondef('m4_drug.fn_drug_baseline_guard()'::regprocedure)
                """, String.class)).contains("department_id");
        // V101 演示种子：3 条 SYNTHETIC 药品（仅 test/dev 路径加载）。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m4_drug.drug WHERE id::text LIKE '2a000000-%'",
                Long.class)).isEqualTo(3L);
    }

    @Test
    void drugCatalogNormalizesUniquenessAndRejectsReverseConflicts() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 300);
        String baseName = "T4 Base " + suffix;
        String baseGeneric = "T4 Generic " + suffix;
        String baseAlias = "T4Alias-" + suffix;

        postDrug(admin, drugCreate("DRUG-BASE-" + suffix, baseName, baseGeneric,
                List.of(baseAlias))).andExpect(status().isCreated());

        // 名称归一化（trim + lower）唯一。
        postDrug(admin, drugCreate("DRUG-DUPN-" + suffix,
                "  " + baseName.toLowerCase(java.util.Locale.ROOT) + "  ", null, List.of()))
                .andExpect(status().isConflict());
        // 通用名归一化唯一（NULL 除外）。
        postDrug(admin, drugCreate("DRUG-DUPG-" + suffix, "T4 Other " + suffix,
                " t4 generic " + suffix + " ", List.of()))
                .andExpect(status().isConflict());
        // 新名称不得撞他人别名（反向校验 409）。
        postDrug(admin, drugCreate("DRUG-NAMEALIAS-" + suffix,
                baseAlias.toLowerCase(java.util.Locale.ROOT), null, List.of()))
                .andExpect(status().isConflict());
        // 新别名不得撞他人名称（反向校验 409）。
        postDrug(admin, drugCreate("DRUG-ALIASNAME-" + suffix, "T4 Alias Name " + suffix, null,
                List.of("  " + baseName.toUpperCase(java.util.Locale.ROOT) + "  ")))
                .andExpect(status().isConflict());

        // 更新同样反向校验：名称撞他人别名 → 409。
        String otherAlias = "T4OtherAlias-" + suffix;
        postDrug(admin, drugCreate("DRUG-OTHER-" + suffix, "T4 Other Drug " + suffix, null,
                List.of(otherAlias))).andExpect(status().isCreated());
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("name", otherAlias.toLowerCase(java.util.Locale.ROOT));
        update.put("genericName", baseGeneric);
        update.put("specification", "1ml");
        update.put("unit", "支");
        update.put("dosageForm", "INJECTION");
        update.put("category", "OPIOID");
        update.put("narcotic", false);
        update.put("highRisk", false);
        update.put("aliases", List.of(baseAlias));
        update.put("version", 1);
        String baseId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM m4_drug.drug WHERE code = ?", String.class,
                "DRUG-BASE-" + suffix);
        mockMvc.perform(put("/v1/drugs/" + baseId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void departmentValidationCrossDepartmentAndSelfScope() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String self = login(SyntheticAccounts.DEMO_SELF_USERNAME,
                SyntheticAccounts.DEMO_SELF_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 400);

        // B-3：科室必须是存在、启用、org_type=DEPT 的组织（SITE/随机 UUID → 422）。
        Map<String, Object> siteDept = registerBody("RX-SITE-" + suffix, "BATCH-SITE-" + suffix);
        siteDept.put("departmentId", "f1000000-0000-4000-8000-000000000001");
        postRegister(admin, siteDept).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("departmentId"));
        Map<String, Object> unknownDept = registerBody("RX-UNK-" + suffix, "BATCH-UNK-" + suffix);
        unknownDept.put("departmentId", "f1000000-0000-4000-8000-0000000000ef");
        postRegister(admin, unknownDept).andExpect(status().isUnprocessableEntity());

        // S-5：SELF 按本人所属科室（麻醉科）推导；跨科室 403。
        Map<String, Object> selfOwn = registerBody("RX-SELF-" + suffix, "BATCH-SELF-" + suffix);
        postRegister(self, selfOwn).andExpect(status().isCreated())
                .andExpect(jsonPath("$.departmentId").value(DEPT));
        Map<String, Object> selfOther = registerBody("RX-SELF-O-" + suffix,
                "BATCH-SELF-O-" + suffix);
        selfOther.put("departmentId", OTHER_DEPT);
        postRegister(self, selfOther).andExpect(status().isForbidden());

        // 其他科室的专册对 SELF 不可见（列表按本人科室过滤）。
        Map<String, Object> other = registerBody("RX-OTHER-" + suffix, "BATCH-OTHER-" + suffix);
        other.put("departmentId", OTHER_DEPT);
        postRegister(admin, other).andExpect(status().isCreated());
        mockMvc.perform(get("/v1/narcotic/registers")
                        .header("Authorization", "Bearer " + self))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].departmentId",
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.is(DEPT))));

        // B-2：基数跨科改写在可见范围内 409，不可见科室 403；科室列不可改（DB 55000）。
        String cabinet = "CAB-X-" + suffix;
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("drugId", SUFENTANIL_ID);
        baseline.put("cabinetCode", cabinet);
        baseline.put("departmentId", OTHER_DEPT);
        baseline.put("quantity", 5);
        mockMvc.perform(put("/v1/narcotic/baselines")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items",
                                List.of(baseline)))))
                .andExpect(status().isOk());
        Map<String, Object> selfOtherBaseline = new LinkedHashMap<>(baseline);
        selfOtherBaseline.put("quantity", 6);
        selfOtherBaseline.put("version", 1);
        mockMvc.perform(put("/v1/narcotic/baselines")
                        .header("Authorization", "Bearer " + self)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items",
                                List.of(selfOtherBaseline)))))
                .andExpect(status().isForbidden());
        Map<String, Object> selfOwnBaseline = new LinkedHashMap<>(baseline);
        selfOwnBaseline.put("departmentId", DEPT);
        selfOwnBaseline.put("quantity", 6);
        selfOwnBaseline.put("version", 1);
        mockMvc.perform(put("/v1/narcotic/baselines")
                        .header("Authorization", "Bearer " + self)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items",
                                List.of(selfOwnBaseline)))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/v1/narcotic/baselines")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items",
                                List.of(selfOwnBaseline)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        String baselineId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM m4_drug.drug_baseline WHERE cabinet_code = ?",
                String.class, cabinet);
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m4_drug.drug_baseline SET department_id = ?::uuid WHERE id = ?::uuid",
                DEPT, baselineId)), "immutable");
    }

    @Test
    void failurePathsWriteResultZeroAuditAndTruncateIsRejected() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 500);

        // 400：编码格式非法（服务层校验，落失败审计）。
        postDrug(admin, drugCreate("编码 非 法", "T4 Bad " + suffix, null, List.of()))
                .andExpect(status().isBadRequest());
        // 409：别名撞名称。
        postDrug(admin, drugCreate("DRUG-FAIL-" + suffix, "T4 Fail " + suffix, null,
                List.of("舒芬太尼（演示）"))).andExpect(status().isConflict());
        // 422：药品未收录。
        Map<String, Object> unknownDrug = registerBody("RX-FAIL-" + suffix,
                "BATCH-FAIL-" + suffix);
        unknownDrug.put("drugId", "2a000000-0000-4000-8000-0000000000ff");
        postRegister(admin, unknownDrug).andExpect(status().isUnprocessableEntity());
        // 400：同一人双签。
        Map<String, Object> sameSigner = registerBody("RX-FAIL2-" + suffix,
                "BATCH-FAIL2-" + suffix);
        sameSigner.put("secondSignerId", SIGNER_A);
        postRegister(admin, sameSigner).andExpect(status().isBadRequest());
        // 409：日结不平。
        Map<String, Object> unbalanced = closeBody(
                LocalDate.now(ZoneOffset.ofHours(8)).minusDays(2), "CAB-FAIL-" + suffix, 99);
        mockMvc.perform(post("/v1/narcotic/daily-closes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(unbalanced)))
                .andExpect(status().isConflict());

        List<Map<String, Object>> failures = jdbcTemplate.queryForList(
                "SELECT action, detail::text AS detail FROM m0_platform.sys_audit_log "
                        + "WHERE result = 0 AND resource_type IN ('DRUG', 'NARCOTIC') "
                        + "ORDER BY occurred_at");
        assertThat(failures).extracting(row -> row.get("action"))
                .contains("DRUG_CREATE", "NARCOTIC_REGISTER", "NARCOTIC_DAILY_CLOSE");
        assertThat(failures).allMatch(row -> String.valueOf(row.get("detail"))
                .contains("rejection"));
        assertThat(failures).noneMatch(row -> String.valueOf(row.get("detail"))
                .contains("RX-FAIL"));
        assertThat(failures).noneMatch(row -> String.valueOf(row.get("detail"))
                .contains("演示给药人"));
        assertThat(failures).noneMatch(row -> String.valueOf(row.get("detail"))
                .contains("演示使用人"));

        assertSqlState55000(catchThrowable(
                () -> jdbcTemplate.execute("TRUNCATE m4_drug.narcotic_register")),
                "append-only");
        assertSqlState55000(catchThrowable(
                () -> jdbcTemplate.execute("TRUNCATE m4_drug.cabinet_handover")),
                "append-only");
    }

    // ------------------------------------------------------------------ 辅助 --

    private static Map<String, Object> registerBody(String prescriptionNo, String batch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drugId", SUFENTANIL_ID);
        body.put("batchNo", batch);
        body.put("quantity", 1);
        body.put("unit", "支");
        body.put("prescriptionNo", prescriptionNo);
        body.put("usedBy", "演示使用人（合成）");
        body.put("departmentId", DEPT);
        body.put("usageAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(10).toString());
        body.put("destination", "术中镇痛（演示）");
        body.put("firstSignerId", SIGNER_A);
        body.put("firstSignerName", "演示给药人（合成）");
        body.put("secondSignerId", SIGNER_B);
        body.put("secondSignerName", "演示核对人（合成）");
        return body;
    }

    private static Map<String, Object> handoverBody(String cabinet) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("drugId", SUFENTANIL_ID);
        item.put("quantity", 4);
        item.put("unit", "支");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cabinetCode", cabinet);
        body.put("departmentId", DEPT);
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

    private static Map<String, Object> closeBody(LocalDate closeDate, String cabinet,
            int closingQuantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("closeDate", closeDate.toString());
        body.put("shift", "DAY");
        body.put("cabinetCode", cabinet);
        body.put("departmentId", DEPT);
        body.put("openingQuantity", 10);
        body.put("inboundQuantity", 4);
        body.put("usedQuantity", 3);
        body.put("returnedQuantity", 1);
        body.put("closingQuantity", closingQuantity);
        return body;
    }

    private org.springframework.test.web.servlet.ResultActions postDrug(String token,
            Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/v1/drugs")
                .header("Authorization", "Bearer " + token)
                .contentType(JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions postRegister(String token,
            Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/v1/narcotic/registers")
                .header("Authorization", "Bearer " + token)
                .contentType(JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> drugCreate(String code, String name, String genericName,
            List<String> aliases) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        if (genericName != null) {
            body.put("genericName", genericName);
        }
        body.put("specification", "1ml");
        body.put("unit", "支");
        body.put("dosageForm", "INJECTION");
        body.put("category", "OPIOID");
        body.put("narcotic", false);
        body.put("highRisk", false);
        body.put("aliases", aliases);
        return body;
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

    private static void assertSqlState55000(Throwable throwable, String reason) {
        assertThat(throwable).isNotNull();
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException) root).getSQLState()).isEqualTo("55000");
        assertThat(root.getMessage()).contains(reason);
    }
}
