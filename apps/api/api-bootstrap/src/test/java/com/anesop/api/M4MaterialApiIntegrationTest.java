package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 耗材主数据、出入库、UDI 追溯与 SPD 适配器（M4-M14-01）端到端用例：
 * MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：主数据唯一/搜索/乐观锁、出入库（数量不足 409、效期/批次校验、ADJUST 原因）、
 * 高值耗材一物一码（重复登记/重复使用 409、绑定记录校验、追溯链）、SPD（EXT-03）模拟同步与
 * 审计、权限 403 与科室数据范围（DEPT/ROOM）、append-only 55000、V23 结构/权限/触发器。
 * 全部数据为 SYNTHETIC。
 */
class M4MaterialApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String DEPT = SyntheticAccounts.DEMO_ANES_ORG_ID;
    private static final String OTHER_DEPT = SyntheticAccounts.DEMO_OR_ORG_ID;
    private static final String PATIENT_ANES = "54000000-0000-4000-8000-0000000000d1";
    private static final String HIS_ANES = "SYNTH-M4-MAT-P-0001";
    private static final String OR_01 = "f2000000-0000-4000-8000-0000000000c1";
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
        SyntheticAccounts.ensureRoomUser(jdbcTemplate);
        grantMaterialReadToRoomRole();
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, '演示患者戊', 'MALE', DATE '1972-03-05', 54, 'SYNT****M401', ?,
                        '耗材合成科室', ?::uuid, 'f1000000-0000-4000-8000-000000000001'::uuid,
                        '演示病区', '05', now(), now(), 1, false)
                ON CONFLICT (id) DO NOTHING
                """, PATIENT_ANES, HIS_ANES, "hash-" + HIS_ANES, DEPT);
    }

    @AfterEach
    void cleanUpClinicalFixture() {
        // 本类通过真实排程/记录单验证 UDI 绑定，必须在类内清理记录单，避免其他测试类
        // 清理 m1_sched.schedule 时被 fk_anesthesia_record_schedule 阻塞（临床表 append-only，
        // 测试夹具临时停用触发器后立即恢复，与 AnesthesiaRecordApiIntegrationTest 一致）。
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

    @Test
    void materialCatalogUniquenessSearchOptimisticLockAndPermissions() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 100);
        String code = "MAT-T4-" + suffix;
        String name = "T4 测试耗材（" + suffix + "）";
        String udiDi = "0693700" + suffix + "01";

        Map<String, Object> create = materialBody(code, name, udiDi, true, true);
        MvcResult created = mockMvc.perform(post("/v1/materials")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.highValue").value(true))
                .andExpect(jsonPath("$.expiryManaged").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        String materialId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("materialId").asText();

        // 编码/名称/UDI-DI 重复 409。
        mockMvc.perform(post("/v1/materials")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        Map<String, Object> duplicateName = materialBody(code + "-X", name, null, true, true);
        mockMvc.perform(post("/v1/materials")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(duplicateName)))
                .andExpect(status().isConflict());
        Map<String, Object> duplicateDi = materialBody(code + "-Y", name + "-Y", udiDi, true, true);
        mockMvc.perform(post("/v1/materials")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(duplicateDi)))
                .andExpect(status().isConflict());

        // 详情、扫码检索、按高值过滤。
        mockMvc.perform(get("/v1/materials/" + materialId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.udiDi").value(udiDi));
        mockMvc.perform(get("/v1/materials/search")
                        .param("q", udiDi)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].materialId").value(materialId));
        mockMvc.perform(get("/v1/materials")
                        .param("keyword", suffix)
                        .param("highValue", "true")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        // 乐观锁更新：版本过期 409，正常更新版本 +1。
        Map<String, Object> update = updateBody(name, udiDi, true, true, 1);
        mockMvc.perform(put("/v1/materials/" + materialId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(put("/v1/materials/" + materialId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/v1/materials/4a000000-0000-4000-8000-0000000000ff")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());

        // 权限：术者无 m4:material:read/write → 403；缺少 highValue 字段 → 400。
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/materials").header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        Map<String, Object> missingFlag = materialBody("MAT-BAD-" + suffix, "缺标志（" + suffix + "）",
                null, false, true);
        missingFlag.remove("highValue");
        mockMvc.perform(post("/v1/materials")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(missingFlag)))
                .andExpect(status().isBadRequest());

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'MATERIAL' AND resource_id = ? AND result = 1
                 ORDER BY occurred_at, action
                """, materialId);
        assertThat(audits).extracting(row -> row.get("action"))
                .contains("MATERIAL_CREATE", "MATERIAL_UPDATE");
        assertThat(audits).allMatch(row -> !String.valueOf(row.get("detail")).contains("演示备注"));
        assertThat(audits).noneMatch(row -> String.valueOf(row.get("detail")).contains("演示患者"));
    }

    @Test
    void stockMovementRulesInsufficientBatchExpiryAndAppendOnly() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 200);
        String materialId = createMaterial(admin, "MAT-STOCK-" + suffix, "出库测试耗材（" + suffix + "）",
                null, false, true);
        LocalDate future = LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1);
        String batch = "BATCH-T4-" + suffix;

        // 入库 10，出库 4，余额 6。
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("IN", materialId, batch,
                                future, 10, "中心库房（合成）", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movementType").value("IN"))
                .andExpect(jsonPath("$.balanceAfter").value(10));
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("OUT", materialId, batch,
                                future, 4, "手术室（合成）", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(6));

        // 数量不足 409，details[] 给出可用量与请求量。
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("OUT", materialId, batch,
                                future, 100, "手术室（合成）", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.details[0].field").value("quantity"))
                .andExpect(jsonPath("$.details[0].reason").value(
                        org.hamcrest.Matchers.containsString("可用 6")));

        // 同批次效期不一致 409；过期/缺效期 422；ADJUST 缺原因 400。
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("IN", materialId, batch,
                                future.plusDays(1), 1, "中心库房（合成）", null))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("IN", materialId, batch,
                                LocalDate.now(ZoneOffset.ofHours(8)).minusDays(1), 1,
                                "中心库房（合成）", null))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("IN", materialId, batch,
                                null, 1, "中心库房（合成）", null))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("ADJUST", materialId,
                                batch, future, -2, "手术室（合成）", null))))
                .andExpect(status().isBadRequest());

        // 盘点调整需原因，成功后余额 4。
        Map<String, Object> adjust = stockBody("ADJUST", materialId, batch, future, -2,
                "手术室（合成）", null);
        adjust.put("reason", "盘亏复核（合成）");
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(adjust)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balanceAfter").value(4));

        // 高值耗材禁止裸数量入库。
        String highValueId = createMaterial(admin, "MAT-HV-" + suffix, "高值测试耗材（" + suffix + "）",
                "0693701" + suffix + "01", true, true);
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("IN", highValueId, batch,
                                future, 1, "中心库房（合成）", null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].reason")
                        .value(org.hamcrest.Matchers.containsString("高值")));

        // 库存/流水查询。
        mockMvc.perform(get("/v1/materials/stock")
                        .param("materialId", materialId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(4));
        mockMvc.perform(get("/v1/materials/stock-movements")
                        .param("materialId", materialId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)));

        // 审计 STOCK_MOVE 存在且不含患者信息。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE action = 'STOCK_MOVE' AND result = 1 AND resource_type = 'STOCK_MOVEMENT'
                 ORDER BY occurred_at DESC LIMIT 5
                """);
        assertThat(audits).isNotEmpty();
        assertThat(audits).noneMatch(row -> String.valueOf(row.get("detail")).contains("演示患者"));

        // append-only：UPDATE/TRUNCATE 流水被触发器拒绝（55000）。
        String movementId = jdbcTemplate.queryForObject("""
                SELECT id::text FROM m4_material.stock_movement
                 WHERE material_id = ?::uuid ORDER BY occurred_at LIMIT 1
                """, String.class, materialId);
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m4_material.stock_movement SET counterparty = '改写' WHERE id = ?::uuid",
                movementId)), "append-only");
        assertSqlState55000(catchThrowable(
                () -> jdbcTemplate.execute("TRUNCATE m4_material.stock_movement")), "append-only");
    }

    @Test
    void udiLifecycleTraceDuplicateUseAndRecordBinding() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 300);
        String udiDi = "0693702" + suffix + "01";
        String materialId = createMaterial(admin, "MAT-UDI-" + suffix,
                "UDI 测试耗材（" + suffix + "）", udiDi, true, true);
        String udi = udiDi + "^SN-T4-" + suffix;
        String batch = "BATCH-UDI-" + suffix;
        LocalDate future = LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1);

        // 登记 = 入库：UDI 实例 IN_STOCK + 批次库存 1。
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(udiBody(materialId, udiDi,
                                "SN-T4-" + suffix, batch, future))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.udi").value(udi))
                .andExpect(jsonPath("$.status").value("IN_STOCK"));
        mockMvc.perform(get("/v1/materials/stock")
                        .param("materialId", materialId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(1));

        // 重复登记 409；UDI-DI 与耗材不一致 422。
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(udiBody(materialId, udiDi,
                                "SN-T4-" + suffix, batch, future))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(udiBody(materialId,
                                "0693702999" + suffix, "SN-OTHER-" + suffix, batch, future))))
                .andExpect(status().isUnprocessableEntity());

        // 绑定不存在的记录 422，且未消耗库存。
        mockMvc.perform(post("/v1/materials/udi-instances/" + udiPath(udi) + "/use")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "recordId", "7a000000-0000-4000-8000-0000000000ff"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("recordId"));

        // 真实记录（SYNTHETIC 排程 + 记录单）后使用：IN_STOCK → USED，库存 0。
        String recordId = createRecord(admin, "SYNTH-M4-MAT-" + suffix);
        String scheduleId = jdbcTemplate.queryForObject(
                "SELECT schedule_id::text FROM m2_anes.anesthesia_record WHERE id = ?::uuid",
                String.class, recordId);
        mockMvc.perform(post("/v1/materials/udi-instances/" + udiPath(udi) + "/use")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "recordId", recordId, "scheduleId", scheduleId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.recordId").value(recordId))
                .andExpect(jsonPath("$.scheduleId").value(scheduleId));
        mockMvc.perform(get("/v1/materials/stock")
                        .param("materialId", materialId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(0));

        // 一物一码：重复使用 409。
        mockMvc.perform(post("/v1/materials/udi-instances/" + udiPath(udi) + "/use")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("recordId", recordId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details").isEmpty());

        // 追溯链：material → UDI → record，流水 IN/OUT 各一条。
        mockMvc.perform(get("/v1/materials/udi-instances/" + udiPath(udi))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.status").value("USED"))
                .andExpect(jsonPath("$.material.code").value("MAT-UDI-" + suffix))
                .andExpect(jsonPath("$.movements", hasSize(2)))
                .andExpect(jsonPath("$.record.recordId").value(recordId))
                .andExpect(jsonPath("$.record.scheduleId").value(scheduleId));

        // 第二件登记后退回：状态 RETURNED，重复退回 409。
        String udi2 = udiDi + "^SN-T4B-" + suffix;
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(udiBody(materialId, udiDi,
                                "SN-T4B-" + suffix, batch, future))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("RETURN", materialId,
                                batch, future, 1, "中心库房（合成）", udi2))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/v1/materials/udi-instances/" + udiPath(udi2))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.status").value("RETURNED"));
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("RETURN", materialId,
                                batch, future, 1, "中心库房（合成）", udi2))))
                .andExpect(status().isConflict());

        // 审计 UDI_USE 只含 UDI-DI 与记录/台次，不含患者信息。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE action = 'UDI_USE' AND result = 1 ORDER BY occurred_at DESC LIMIT 5
                """);
        assertThat(audits).isNotEmpty();
        assertThat(audits).allMatch(row -> String.valueOf(row.get("detail")).contains("udiDi"));
        assertThat(audits).noneMatch(row -> String.valueOf(row.get("detail")).contains("演示患者"));
    }

    @Test
    void dataScopeAndPermissionBoundaries() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                "Anesop@2026");
        String suffix = String.valueOf(SEQ.incrementAndGet() + 400);
        String otherDi = "0693703" + suffix + "01";
        String otherMaterial = createMaterial(admin, "MAT-OTHER-" + suffix,
                "手术室耗材（" + suffix + "）", otherDi, true, true);
        String otherUdi = otherDi + "^SN-OTHER-" + suffix;
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(udiBody(otherMaterial, otherDi,
                                "SN-OTHER-" + suffix, "BATCH-OTHER-" + suffix,
                                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1),
                                OTHER_DEPT))))
                .andExpect(status().isCreated());

        // 目录全院可读（DEPT 用户可读主数据）；科室选项按数据范围收敛。
        mockMvc.perform(get("/v1/materials").header("Authorization", "Bearer " + dept))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/materials/departments").header("Authorization", "Bearer " + dept))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].departmentId").value(DEPT));
        // 库存按科室隔离：显式查询其他科室 403；列表不含其他科室。
        mockMvc.perform(get("/v1/materials/stock")
                        .param("departmentId", OTHER_DEPT)
                        .header("Authorization", "Bearer " + dept))
                .andExpect(status().isForbidden());
        MvcResult deptStock = mockMvc.perform(get("/v1/materials/stock")
                        .header("Authorization", "Bearer " + dept))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode stockItems = objectMapper.readTree(deptStock.getResponse().getContentAsString())
                .get("items");
        for (JsonNode item : stockItems) {
            assertThat(item.get("departmentId").asText()).isEqualTo(DEPT);
        }
        // 其他科室 UDI 追溯 404（不泄露存在性）。
        mockMvc.perform(get("/v1/materials/udi-instances/" + udiPath(otherUdi))
                        .header("Authorization", "Bearer " + dept))
                .andExpect(status().isNotFound());

        // ROOM 数据范围 fail-closed 403（即使持有 m4:material:read）。
        String room = login(SyntheticAccounts.ROOM_USERNAME, SyntheticAccounts.ROOM_PASSWORD);
        mockMvc.perform(get("/v1/materials/stock").header("Authorization", "Bearer " + room))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/materials/departments").header("Authorization", "Bearer " + room))
                .andExpect(status().isForbidden());
        // SPD 状态与库存同为科室数据范围：ROOM 即使持有 m4:material:read 也 fail-closed。
        mockMvc.perform(get("/v1/materials/spd-adapter/status")
                        .header("Authorization", "Bearer " + room))
                .andExpect(status().isForbidden());

        // 无耗材权限的麻醉医师账号 403。
        String self = login(SyntheticAccounts.DEMO_SELF_USERNAME, SyntheticAccounts.DEMO_SELF_PASSWORD);
        mockMvc.perform(get("/v1/materials").header("Authorization", "Bearer " + self))
                .andExpect(status().isForbidden());
    }

    @Test
    void spdAdapterStatusSyncAndAudit() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        long before = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m4_material.spd_sync_log", Long.class);

        mockMvc.perform(post("/v1/materials/spd-adapter/sync")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adapterCode").value("EXT-03"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.degraded").value(false));
        mockMvc.perform(get("/v1/materials/spd-adapter/status")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SIMULATED"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.degraded").value(false));

        long after = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m4_material.spd_sync_log", Long.class);
        assertThat(after).isEqualTo(before + 1);
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT result, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE action = 'SPD_SYNC' ORDER BY occurred_at DESC LIMIT 3
                """);
        assertThat(audits).isNotEmpty();
        assertThat(audits).allMatch(row -> String.valueOf(row.get("detail")).contains("EXT-03"));
        assertThat(audits).noneMatch(row -> String.valueOf(row.get("detail")).contains("SYS_ADMIN"));

        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(post("/v1/materials/spd-adapter/sync")
                        .header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden());
    }

    @Test
    void v23SchemaPermissionsAndGuardsAreInstalled() throws Exception {
        Integer tables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'm4_material'
                """, Integer.class);
        assertThat(tables).isEqualTo(5);

        Integer permissions = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_permission
                 WHERE code IN ('m4:material:read', 'm4:material:write', 'm4:udi:manage')
                """, Integer.class);
        assertThat(permissions).isEqualTo(3);

        List<String> grants = jdbcTemplate.queryForList("""
                SELECT r.code || ':' || p.code
                  FROM m0_platform.sys_role_permission rp
                  JOIN m0_platform.sys_role r ON r.id = rp.role_id
                  JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE p.code LIKE 'm4:material:%' OR p.code = 'm4:udi:manage'
                 ORDER BY 1
                """, String.class);
        assertThat(grants).contains(
                "SYS_ADMIN:m4:material:read", "SYS_ADMIN:m4:material:write",
                "SYS_ADMIN:m4:udi:manage",
                "SYS_OR_MANAGER:m4:material:read", "SYS_OR_MANAGER:m4:material:write",
                "SYS_OR_MANAGER:m4:udi:manage",
                "SYS_NURSE_ANESTHETIST:m4:material:read",
                "SYS_NURSE_ANESTHETIST:m4:udi:manage");

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = 'm4_material' ORDER BY indexname
                """, String.class);
        assertThat(indexes).contains("uk_material_code", "uk_material_name", "uk_material_udi_di",
                "uk_material_stock_batch", "uk_udi_instance_di_pi", "uk_udi_instance_udi_code");

        List<String> triggers = jdbcTemplate.queryForList("""
                SELECT tgname FROM pg_trigger
                 WHERE tgrelid = 'm4_material.stock_movement'::regclass AND NOT tgisinternal
                 ORDER BY tgname
                """, String.class);
        assertThat(triggers).contains("trg_stock_movement_no_update",
                "trg_stock_movement_no_delete", "trg_stock_movement_no_truncate");

        // 库存 CHECK >= 0：直接写负数被拒绝（23514）。
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String materialId = createMaterial(admin, "MAT-CHECK-" + SEQ.incrementAndGet(),
                "库存约束测试（合成）", null, false, true);
        assertThatThrownBySqlState(() -> jdbcTemplate.update("""
                INSERT INTO m4_material.material_stock
                    (material_id, material_code, material_name, batch_no, department_id, quantity,
                     unit)
                VALUES (?::uuid, 'X', 'X', 'B', ?::uuid, -1, '个')
                """, materialId, DEPT), "23514");

        // material 守卫：version 不增 / 编码改写 → 55000。
        String guardedMaterial = createMaterial(admin, "MAT-GUARD-" + SEQ.incrementAndGet(),
                "主数据守卫测试（合成）", null, false, true);
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                UPDATE m4_material.material SET name = name, version = version WHERE id = ?::uuid
                """, guardedMaterial)), "version must increase");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                UPDATE m4_material.material SET code = 'HACK-CODE', version = version + 1
                 WHERE id = ?::uuid
                """, guardedMaterial)), "identity");

        // udi_instance 守卫：身份列不可改、终态不可再改、非法状态迁移 → 55000。
        String guardSuffix = String.valueOf(SEQ.incrementAndGet() + 800);
        String guardDi = "0693707" + guardSuffix + "01";
        String guardMaterial = createMaterial(admin, "MAT-UDI-GUARD-" + guardSuffix,
                "UDI 守卫测试耗材（" + guardSuffix + "）", guardDi, true, true);
        String guardBatch = "BATCH-GUARD-" + guardSuffix;
        String guardUdi = guardDi + "^SN-GUARD-" + guardSuffix;
        LocalDate guardFuture = LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1);
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(udiBody(guardMaterial, guardDi,
                                "SN-GUARD-" + guardSuffix, guardBatch, guardFuture))))
                .andExpect(status().isCreated());
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                UPDATE m4_material.udi_instance SET batch_no = 'BATCH-HACK', version = version + 1
                 WHERE udi_code = ?
                """, guardUdi)), "identity");

        // 退回成为终态后，任何绑定信息修改都必须被拒绝。
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("RETURN",
                                guardMaterial, guardBatch, guardFuture, 1, "中心库房（合成）",
                                guardUdi))))
                .andExpect(status().isCreated());
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                UPDATE m4_material.udi_instance SET used_by_name = '改写', version = version + 1
                 WHERE udi_code = ?
                """, guardUdi)), "terminal");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update("""
                UPDATE m4_material.udi_instance
                   SET status = 'USED', record_id = gen_random_uuid(),
                       schedule_id = gen_random_uuid(), used_at = now(),
                       used_by = gen_random_uuid(), version = version + 1
                 WHERE udi_code = ?
                """, guardUdi)), "illegal status transition");
    }


    @Test
    void stockBatchExpiryQuantityScaleAndExpiredOutboundGuards() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 500);
        String name = "效期边界耗材（" + suffix + "）";
        String materialId = createMaterial(admin, "MAT-EXP-" + suffix, name, null, false, true);
        String batch = "BATCH-EXP-" + suffix;
        LocalDate past = LocalDate.now(ZoneOffset.ofHours(8)).minusDays(30);
        LocalDate future = LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1);

        // 过期批次来自历史库存（入库接口拒绝过期效期，这里直接落一条 SYNTHETIC 历史批次）。
        jdbcTemplate.update("""
                INSERT INTO m4_material.material_stock
                    (material_id, material_code, material_name, batch_no, department_id,
                     quantity, unit, expiry_date)
                VALUES (?::uuid, ?, ?, ?, ?::uuid, 5, '套', ?)
                """, materialId, "MAT-EXP-" + suffix, name, batch, DEPT,
                java.sql.Date.valueOf(past));

        // 未来效期对过期批次出库：请求效期与库存行不一致 → 409（不按请求效期“洗白”过期批次）。
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("OUT", materialId, batch,
                                future, 1, "手术室（合成）", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0].field").value("expiryDate"));

        // 效期与库存行一致的过期批次出库 → 422。
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("OUT", materialId, batch,
                                past, 1, "手术室（合成）", null))))
                .andExpect(status().isUnprocessableEntity());

        // 数量最多 2 位小数：1.001 → 400，且库存不变。
        Map<String, Object> fractional = stockBody("OUT", materialId, batch, future, 1,
                "手术室（合成）", null);
        fractional.put("quantity", new BigDecimal("1.001"));
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(fractional)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("quantity"));

        Integer quantity = jdbcTemplate.queryForObject("""
                SELECT quantity::int FROM m4_material.material_stock
                 WHERE material_id = ?::uuid AND batch_no = ?
                """, Integer.class, materialId, batch);
        assertThat(quantity).isEqualTo(5);
    }

    @Test
    void returnUdiBatchMismatchUdiDiChangeAndStatusEndpoint() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 600);
        String udiDi = "0693705" + suffix + "01";
        String materialId = createMaterial(admin, "MAT-RET-" + suffix,
                "退回批次测试耗材（" + suffix + "）", udiDi, true, true);
        String batch = "BATCH-RET-" + suffix;
        String udi = udiDi + "^SN-RET-" + suffix;
        LocalDate future = LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1);

        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(udiBody(materialId, udiDi,
                                "SN-RET-" + suffix, batch, future))))
                .andExpect(status().isCreated());

        // RETURN 携带 UDI 但批次错位 → 422，且不得扣减任何批次库存（UDI 仍 IN_STOCK）。
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("RETURN", materialId,
                                batch + "-X", future, 1, "中心库房（合成）", udi))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details[0].field").value("batchNo"));
        mockMvc.perform(get("/v1/materials/udi-instances/" + udiPath(udi))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance.status").value("IN_STOCK"));
        Integer quantity = jdbcTemplate.queryForObject("""
                SELECT quantity::int FROM m4_material.material_stock
                 WHERE material_id = ?::uuid AND batch_no = ?
                """, Integer.class, materialId, batch);
        assertThat(quantity).isEqualTo(1);

        // 失败审计可定位：含 UDI-DI 摘要与批次，不含 UDI 全串（PI/序列号）与患者信息。
        List<Map<String, Object>> failures = jdbcTemplate.queryForList("""
                SELECT detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE action = 'STOCK_MOVE' AND result = 0 AND resource_id = ?
                 ORDER BY occurred_at DESC
                """, materialId);
        assertThat(failures).isNotEmpty();
        String failureDetail = String.valueOf(failures.get(0).get("detail"));
        assertThat(failureDetail).contains(udiDi).contains(batch);
        assertThat(failureDetail).doesNotContain("SN-RET-" + suffix).doesNotContain("演示患者");

        // 存在 UDI 实例时修改/清空 UDI-DI → 409。
        mockMvc.perform(put("/v1/materials/" + materialId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(updateBody(
                                "退回批次测试耗材（" + suffix + "）",
                                "0693705" + suffix + "99", true, true, 1))))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/v1/materials/" + materialId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(updateBody(
                                "退回批次测试耗材（" + suffix + "）", null, true, true, 1))))
                .andExpect(status().isConflict());

        // 状态端点：停用后 version +1；重复停用幂等；停用后禁止新 UDI 登记。
        mockMvc.perform(patch("/v1/materials/" + materialId + "/status")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(patch("/v1/materials/" + materialId + "/status")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INACTIVE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(udiBody(materialId, udiDi,
                                "SN-RET2-" + suffix, batch, future))))
                .andExpect(status().isUnprocessableEntity());

        // 无写权限账号不能启停（403）。
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(patch("/v1/materials/" + materialId + "/status")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "ACTIVE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void udiCombinedLengthBoundaryIsEnforced() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 700);
        String longDi = String.format("%-64s", "LEN" + suffix).replace(' ', '0');
        String materialId = createMaterial(admin, "MAT-LEN-" + suffix,
                "UDI 长度边界耗材（" + suffix + "）", longDi, true, true);
        String batch = "BATCH-LEN-" + suffix;
        LocalDate future = LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1);

        // 64 + 1 + 95 = 160：允许。
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(udiBody(materialId, longDi,
                                "P".repeat(95), batch, future))))
                .andExpect(status().isCreated());
        // 64 + 1 + 96 = 161：400（不得落库）。
        mockMvc.perform(post("/v1/materials/udi-instances")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(udiBody(materialId, longDi,
                                "P".repeat(96), batch, future))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("udiPi"));
    }

    // ------------------------------------------------------------------
    // 库存预警与阈值维护（M8-M4-02，V28 / material.yaml 0.2.0）
    // ------------------------------------------------------------------

    @Test
    void stockAlertsCoverThresholdZeroStockNearExpiryAndDataScope() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet() + 500);
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(8));
        String lowId = createMaterial(admin, "MAT-ALR-LOW-" + suffix,
                "预警低库存耗材（" + suffix + "）", null, false, true);
        String zeroId = createMaterial(admin, "MAT-ALR-ZERO-" + suffix,
                "预警零库存耗材（" + suffix + "）", null, false, true);
        String nearId = createMaterial(admin, "MAT-ALR-NEAR-" + suffix,
                "预警临期耗材（" + suffix + "）", null, false, true);

        // 阈值：低库存/零库存耗材 = 10；临期耗材不配置（= 不按阈值预警）。
        putThreshold(admin, lowId, 10, 1);
        putThreshold(admin, zeroId, 10, 1);
        mockMvc.perform(get("/v1/materials/" + lowId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStockThreshold").value(10));

        // 入库：低库存 9（低于 10）；零库存先入 3 再出 3；临期 2（效期 +30 天）。
        LocalDate farExpiry = today.plusDays(200);
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("IN", lowId,
                                "BATCH-ALR-LOW-" + suffix, farExpiry, 9, "中心库房（合成）",
                                null))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("IN", zeroId,
                                "BATCH-ALR-ZERO-" + suffix, farExpiry, 3, "中心库房（合成）",
                                null))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("OUT", zeroId,
                                "BATCH-ALR-ZERO-" + suffix, farExpiry, 3, "麻醉科（合成）",
                                null))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/materials/stock-movements")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content(objectMapper.writeValueAsString(stockBody("IN", nearId,
                                "BATCH-ALR-NEAR-" + suffix, today.plusDays(30), 2,
                                "中心库房（合成）", null))))
                .andExpect(status().isCreated());

        JsonNode all = alerts(admin, DEPT, null);
        JsonNode low = alertOf(all, lowId);
        assertThat(low).isNotNull();
        assertThat(types(low)).containsExactly("LOW_STOCK");
        assertThat(low.get("availableQuantity").decimalValue()).isEqualByComparingTo("9");
        assertThat(low.get("lowStockThreshold").decimalValue()).isEqualByComparingTo("10");
        assertThat(low.get("suggestedQuantity").decimalValue()).isEqualByComparingTo("1");
        assertThat(low.get("departmentName").asText()).isNotBlank();
        assertThat(low.get("daysToExpiry").asInt()).isGreaterThan(90);

        JsonNode zero = alertOf(all, zeroId);
        assertThat(zero).isNotNull();
        assertThat(types(zero)).containsExactly("LOW_STOCK", "ZERO_STOCK");
        assertThat(zero.get("availableQuantity").decimalValue()).isEqualByComparingTo("0");
        assertThat(zero.get("suggestedQuantity").decimalValue()).isEqualByComparingTo("10");

        JsonNode near = alertOf(all, nearId);
        assertThat(near).isNotNull();
        assertThat(types(near)).containsExactly("NEAR_EXPIRY");
        assertThat(near.get("daysToExpiry").asInt()).isEqualTo(30);
        assertThat(near.get("nearExpiryBatchCount").asInt()).isEqualTo(1);
        assertThat(near.hasNonNull("suggestedQuantity")).isFalse();

        // 类型过滤：NEAR_EXPIRY 只返回临期耗材（低库存/零库存不出现）。
        JsonNode nearOnly = alerts(admin, DEPT, "NEAR_EXPIRY");
        assertThat(alertOf(nearOnly, nearId)).isNotNull();
        assertThat(alertOf(nearOnly, lowId)).isNull();
        assertThat(alertOf(nearOnly, zeroId)).isNull();

        // 阈值边界：可用量 = 阈值（9 = 9）不再低库存；阈值仅决定 LOW_STOCK，不影响零库存/临期。
        putThreshold(admin, lowId, 9, 2);
        JsonNode lowOnly = alerts(admin, DEPT, "LOW_STOCK");
        assertThat(alertOf(lowOnly, lowId)).isNull();
        assertThat(alertOf(alerts(admin, DEPT, null), lowId)).isNull();

        // 阈值维护校验：负数 400、版本过期 409、缺权限 403、科室数据范围（DEPT）可维护。
        mockMvc.perform(put("/v1/materials/" + lowId + "/low-stock-threshold")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content("{\"threshold\":-1,\"version\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("threshold"));
        mockMvc.perform(put("/v1/materials/" + lowId + "/low-stock-threshold")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content("{\"threshold\":12,\"version\":1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/v1/materials/" + lowId + "/low-stock-threshold")
                        .header("Authorization", "Bearer " + admin).contentType(JSON)
                        .content("{\"version\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStockThreshold").doesNotExist());

        String roomToken = login(SyntheticAccounts.ROOM_USERNAME,
                SyntheticAccounts.ROOM_PASSWORD);
        mockMvc.perform(get("/v1/materials/stock-alerts")
                        .header("Authorization", "Bearer " + roomToken)
                        .param("departmentId", DEPT))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/v1/materials/" + lowId + "/low-stock-threshold")
                        .header("Authorization", "Bearer " + roomToken).contentType(JSON)
                        .content("{\"threshold\":5,\"version\":4}"))
                .andExpect(status().isForbidden());

        String deptToken = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(put("/v1/materials/" + lowId + "/low-stock-threshold")
                        .header("Authorization", "Bearer " + deptToken).contentType(JSON)
                        .content("{\"threshold\":5,\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStockThreshold").value(5));

        // 审计：每次成功维护都有 MATERIAL_THRESHOLD_UPDATE（含前后阈值，不含患者信息）。
        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_audit_log "
                        + "WHERE action = 'MATERIAL_THRESHOLD_UPDATE' AND resource_id = ?",
                Integer.class, lowId);
        assertThat(audits).isGreaterThanOrEqualTo(4);
        String detail = jdbcTemplate.queryForObject(
                "SELECT detail::text FROM m0_platform.sys_audit_log "
                        + "WHERE action = 'MATERIAL_THRESHOLD_UPDATE' AND resource_id = ? "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                String.class, lowId);
        assertThat(detail).contains("threshold").contains("previousThreshold")
                .doesNotContain("患者");
    }

    private void putThreshold(String token, String materialId, int threshold, int version)
            throws Exception {
        mockMvc.perform(put("/v1/materials/" + materialId + "/low-stock-threshold")
                        .header("Authorization", "Bearer " + token).contentType(JSON)
                        .content("{\"threshold\":" + threshold + ",\"version\":" + version
                                + "}"))
                .andExpect(status().isOk());
    }

    private JsonNode alerts(String token, String departmentId, String alertType) throws Exception {
        var request = get("/v1/materials/stock-alerts")
                .header("Authorization", "Bearer " + token)
                .param("departmentId", departmentId)
                .param("limit", "200");
        if (alertType != null) {
            request.param("alertType", alertType);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode alertOf(JsonNode list, String materialId) {
        for (JsonNode item : list.get("items")) {
            if (materialId.equals(item.get("materialId").asText())) {
                return item;
            }
        }
        return null;
    }

    private static List<String> types(JsonNode alert) {
        List<String> types = new java.util.ArrayList<>();
        for (JsonNode type : alert.get("alertTypes")) {
            types.add(type.asText());
        }
        return types;
    }

    // ------------------------------------------------------------------ 辅助 --

    private static Map<String, Object> materialBody(String code, String name, String udiDi,
            boolean highValue, boolean expiryManaged) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("specification", "演示规格");
        body.put("unit", "套");
        body.put("category", "CATHETER");
        if (udiDi != null) {
            body.put("udiDi", udiDi);
        }
        body.put("highValue", highValue);
        body.put("expiryManaged", expiryManaged);
        body.put("remark", "SYNTHETIC 演示备注");
        return body;
    }

    private static Map<String, Object> updateBody(String name, String udiDi, boolean highValue,
            boolean expiryManaged, int version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("specification", "演示规格（更新）");
        body.put("unit", "套");
        body.put("category", "CATHETER");
        if (udiDi != null) {
            body.put("udiDi", udiDi);
        }
        body.put("highValue", highValue);
        body.put("expiryManaged", expiryManaged);
        body.put("version", version);
        return body;
    }

    private static Map<String, Object> stockBody(String type, String materialId, String batch,
            LocalDate expiry, int quantity, String counterparty, String udi) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("movementType", type);
        body.put("materialId", materialId);
        body.put("batchNo", batch);
        if (expiry != null) {
            body.put("expiryDate", expiry.toString());
        }
        body.put("quantity", quantity);
        body.put("departmentId", DEPT);
        body.put("counterparty", counterparty);
        if (udi != null) {
            body.put("udi", udi);
        }
        return body;
    }

    private static Map<String, Object> udiBody(String materialId, String udiDi, String udiPi,
            String batch, LocalDate expiry) {
        return udiBody(materialId, udiDi, udiPi, batch, expiry, DEPT);
    }

    private static Map<String, Object> udiBody(String materialId, String udiDi, String udiPi,
            String batch, LocalDate expiry, String departmentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("materialId", materialId);
        body.put("udiDi", udiDi);
        body.put("udiPi", udiPi);
        body.put("serialNumber", udiPi);
        body.put("batchNo", batch);
        if (expiry != null) {
            body.put("expiryDate", expiry.toString());
        }
        body.put("departmentId", departmentId);
        body.put("counterparty", "中心库房（合成）");
        return body;
    }

    private String createMaterial(String token, String code, String name, String udiDi,
            boolean highValue, boolean expiryManaged) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/materials")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                materialBody(code, name, udiDi, highValue, expiryManaged))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("materialId").asText();
    }

    private String createRecord(String token, String hisApplicationNo) throws Exception {
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
        schedule.put("plannedStart", "2028-03-15T08:00:00+08:00");
        schedule.put("plannedEnd", "2028-03-15T10:00:00+08:00");
        schedule.put("sequenceNo", 1);
        schedule.put("priority", "ELECTIVE");
        MvcResult scheduled = mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(schedule)))
                .andExpect(status().isCreated())
                .andReturn();
        String scheduleId = objectMapper.readTree(scheduled.getResponse().getContentAsString())
                .get("scheduleId").asText();

        MvcResult record = mockMvc.perform(post("/v1/anesthesia/records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduleId", scheduleId, "anesthesiaType", "GENERAL"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(record.getResponse().getContentAsString())
                .get("recordId").asText();
    }

    private void grantMaterialReadToRoomRole() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                SELECT ?::uuid, id FROM m0_platform.sys_permission WHERE code = 'm4:material:read'
                ON CONFLICT DO NOTHING
                """, SyntheticAccounts.ROOM_ROLE_ID);
    }

    private static String udiPath(String udi) {
        return udi;
    }

    private static void assertSqlState55000(Throwable throwable, String reason) {
        assertThat(throwable).isNotNull();
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.sql.SQLException sql
                    && "55000".equals(sql.getSQLState())) {
                assertThat(String.valueOf(sql.getMessage())).contains(reason);
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("期望 SQLSTATE 55000（" + reason + "），实际：" + throwable.getMessage());
    }

    private static void assertThatThrownBySqlState(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
            String expectedState) {
        Throwable throwable = catchThrowable(callable);
        assertThat(throwable).isNotNull();
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.sql.SQLException sql
                    && expectedState.equals(sql.getSQLState())) {
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("期望 SQLSTATE " + expectedState + "，实际：" + throwable.getMessage());
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
