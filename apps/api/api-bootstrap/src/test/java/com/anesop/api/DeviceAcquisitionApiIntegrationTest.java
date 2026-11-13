package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 设备采集接入（M3-M8-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16 + Flyway（V14）。
 *
 * <p>覆盖：V14 表/唯一键/权限种子；登记唯一与换台绑定；ingest 批次/行级双层幂等、
 * 网关令牌 401、质量标记与拒绝计数；vitals 窗口/质量过滤/分页；设备离线判定；
 * 权限 403；DEVICE_REGISTER/DEVICE_INGEST/DEVICE_QUERY 审计（detail 无 PHI）。
 * 全部数据为 SYNTHETIC（设备标识 SIM-*，术间为演示 UUID）。
 */
class DeviceAcquisitionApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String GATEWAY = "GW-IT-01";
    private static final String DEVICE = "SIM-IT-01";
    private static final String DEVICE_ALT = "SIM-IT-02";
    /** V8 演示术间：c1/c2 属麻醉科（demo_dept 的 DEPT 范围），c5 属手术室（范围外）。 */
    private static final String ROOM = "f2000000-0000-4000-8000-0000000000c1";
    private static final String ROOM_ALT = "f2000000-0000-4000-8000-0000000000c2";
    private static final String ROOM_OUTSIDE = "f2000000-0000-4000-8000-0000000000c5";
    private static final String DEMO_DEPT_USERNAME = "demo_dept";
    private static final String DEMO_DEPT_PASSWORD = "Anesop@2026";
    private static final String GATEWAY_TOKEN = "synth-test-gateway-token";
    private static final Instant BASE = Instant.parse("2026-11-14T01:00:00Z");

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
        jdbcTemplate.update("DELETE FROM m2_anes.device_ingest_log");
        jdbcTemplate.update("DELETE FROM m2_anes.device_registry");
        jdbcTemplate.update("DELETE FROM m2_anes.anesthesia_vital");
    }

    // ---------------------------------------------------------- V14 结构/权限 --

    @Test
    void v14CreatesTablesUniqueKeysAndDevicePermissions() {
        assertThat(constraintExists("uk_device_registry_gateway_device", "m2_anes.device_registry")).isTrue();
        assertThat(constraintExists("uk_device_ingest_log_gateway_batch", "m2_anes.device_ingest_log")).isTrue();
        assertThat(constraintExists("ck_device_registry_status", "m2_anes.device_registry")).isTrue();

        Long permissions = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_permission WHERE code IN (?, ?)",
                Long.class, "m2:device:read", "m2:device:write");
        assertThat(permissions).isEqualTo(2L);
        Long grants = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission rp
                  JOIN m0_platform.sys_permission p ON p.id = rp.permission_id
                 WHERE p.code IN ('m2:device:read', 'm2:device:write')
                """, Long.class);
        assertThat(grants).isEqualTo(4L);
        String comment = jdbcTemplate.queryForObject("""
                SELECT obj_description('m2_anes.device_ingest_log'::regclass, 'pg_class')
                """, String.class);
        assertThat(comment).contains("幂等");
    }

    // --------------------------------------------------------------- 设备登记 --

    @Test
    void registerIsUniqueAndRebindsRoom() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        long auditBefore = auditCount("DEVICE_REGISTER");

        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(registerBody(GATEWAY, DEVICE, ROOM, "MONITOR", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gatewayId").value(GATEWAY))
                .andExpect(jsonPath("$.deviceId").value(DEVICE))
                .andExpect(jsonPath("$.roomId").value(ROOM))
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.qualityStats.acceptedRows").value(0));

        // 同一设备重复登记：幂等 200，不新增行、不递增版本（登记唯一）。
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(registerBody(GATEWAY, DEVICE, ROOM, "MONITOR", "ONLINE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(deviceCount()).isEqualTo(1);

        // 换台绑定：200，roomId 变更、version 递增，仍只有一行。
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(registerBody(GATEWAY, DEVICE, ROOM_ALT, "ANESTHESIA_MACHINE", "MAINTENANCE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(ROOM_ALT))
                .andExpect(jsonPath("$.deviceType").value("ANESTHESIA_MACHINE"))
                .andExpect(jsonPath("$.version").value(2));
        assertThat(deviceCount()).isEqualTo(1);

        // 数据库唯一键兜底：绕过 API 直接插入同键必须失败。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO m2_anes.device_registry
                    (gateway_id, device_id, device_type, status)
                VALUES (?, ?, 'MONITOR', 'ONLINE')
                """, GATEWAY, DEVICE)).isInstanceOf(DataAccessException.class);

        assertThat(auditCount("DEVICE_REGISTER") - auditBefore).isEqualTo(2L);
        Map<String, Object> detail = latestAuditDetail("DEVICE_REGISTER");
        assertThat(detail).containsEntry("gatewayId", GATEWAY).containsEntry("deviceId", DEVICE);
        assertThat(detail.toString()).doesNotContainIgnoringCase("patient").doesNotContain("姓名");
    }

    // --------------------------------------------------------------- 网关上报 --

    @Test
    void ingestIsIdempotentAcrossBatchAndRowKeys() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        register(admin, GATEWAY, DEVICE, ROOM, "MONITOR", null);
        long auditBefore = auditCount("DEVICE_INGEST");

        String firstBatch = ingestBody("BATCH-0001", List.of(
                frame(1, BASE, 0, List.of()),
                frame(2, BASE.plusSeconds(1), 1, List.of("hr:RANGE")),
                invalidSeqFrame(3)));

        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .contentType(JSON)
                        .content(firstBatch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedRows").value(2))
                .andExpect(jsonPath("$.duplicateRows").value(0))
                .andExpect(jsonPath("$.rejectedRows").value(1))
                .andExpect(jsonPath("$.duplicateBatch").value(false))
                .andExpect(jsonPath("$.rejections[0].reason").value("SEQ_INVALID"));

        assertThat(vitalCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quality FROM m2_anes.anesthesia_vital WHERE seq = 2", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quality_flags FROM m2_anes.anesthesia_vital WHERE seq = 2", String.class))
                .isEqualTo("hr:RANGE");

        // 同一批次重放：整批判重，不新增行、不重复累加统计。
        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .contentType(JSON)
                        .content(firstBatch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedRows").value(0))
                .andExpect(jsonPath("$.duplicateRows").value(3))
                .andExpect(jsonPath("$.duplicateBatch").value(true));
        assertThat(vitalCount()).isEqualTo(2);

        // 新批次但帧相同：行级唯一键判重，仍不新增行。
        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .contentType(JSON)
                        .content(ingestBody("BATCH-0002", List.of(
                                frame(1, BASE, 0, List.of()),
                                frame(2, BASE.plusSeconds(1), 1, List.of("hr:RANGE")),
                                invalidSeqFrame(3)))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedRows").value(0))
                .andExpect(jsonPath("$.duplicateRows").value(2))
                .andExpect(jsonPath("$.rejectedRows").value(1))
                .andExpect(jsonPath("$.duplicateBatch").value(false));
        assertThat(vitalCount()).isEqualTo(2);

        Map<String, Object> stats = jdbcTemplate.queryForMap("""
                SELECT quality_stats->>'batches' AS batches,
                       quality_stats->>'acceptedRows' AS accepted,
                       quality_stats->>'duplicateRows' AS duplicates,
                       quality_stats->>'rejectedRows' AS rejected,
                       quality_stats->>'suspectRows' AS suspect,
                       last_seen_at
                  FROM m2_anes.device_registry
                 WHERE gateway_id = ? AND device_id = ?
                """, GATEWAY, DEVICE);
        assertThat(stats.get("batches")).isEqualTo("2");
        assertThat(stats.get("accepted")).isEqualTo("2");
        assertThat(stats.get("duplicates")).isEqualTo("2");
        assertThat(stats.get("rejected")).isEqualTo("2");
        assertThat(stats.get("suspect")).isEqualTo("1");
        assertThat(stats.get("last_seen_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m2_anes.device_ingest_log WHERE gateway_id = ?", Long.class, GATEWAY))
                .isEqualTo(2L);

        assertThat(auditCount("DEVICE_INGEST") - auditBefore).isEqualTo(3L);
        assertThat(latestAuditDetail("DEVICE_INGEST")).containsEntry("batchKey", "BATCH-0002")
                .containsEntry("duplicateBatch", false);
    }

    @Test
    void ingestRejectsInvalidOrMissingGatewayTokenWithoutAudit() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        register(admin, GATEWAY, DEVICE, ROOM, "MONITOR", null);
        long auditBefore = auditCount("DEVICE_INGEST");
        String body = ingestBody("BATCH-T1", List.of(frame(1, BASE, 0, List.of())));

        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", "wrong-token")
                        .contentType(JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"));
        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .contentType(JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertThat(vitalCount()).isZero();
        assertThat(auditCount("DEVICE_INGEST")).isEqualTo(auditBefore);
    }

    @Test
    void ingestUnknownDeviceReturns404() throws Exception {
        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/UNREGISTERED/ingest")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .contentType(JSON)
                        .content(ingestBody("BATCH-U1", List.of(frame(1, BASE, 0, List.of())))))
                .andExpect(status().isNotFound());
    }

    // --------------------------------------------------------------- 体征查询 --

    @Test
    void vitalsQueryFiltersByWindowQualityAndPaginates() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        register(admin, GATEWAY, DEVICE, ROOM, "MONITOR", null);
        long auditBefore = auditCount("DEVICE_QUERY");
        Instant t0 = BASE;
        insertVital(1, t0.minusSeconds(30), 76, 0, null);
        insertVital(2, t0.minusSeconds(20), 80, 1, "hr:RANGE");
        insertVital(3, t0.minusSeconds(10), 90, 0, null);
        insertVital(4, t0.minusSeconds(3600), 60, 0, null);

        String from = t0.minusSeconds(60).toString();
        String to = t0.plusSeconds(10).toString();

        mockMvc.perform(get("/v1/devices/" + GATEWAY + "/" + DEVICE + "/vitals")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", from).param("to", to)
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(3))
                .andExpect(jsonPath("$.meta.totalPages").value(2))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(2))
                .andExpect(jsonPath("$.items[0].metric").value("HR"))
                .andExpect(jsonPath("$.items[0].unit").value("bpm"))
                .andExpect(jsonPath("$.items[0].value").value(76))
                .andExpect(jsonPath("$.items[1].value").value(80));

        mockMvc.perform(get("/v1/devices/" + GATEWAY + "/" + DEVICE + "/vitals")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", from).param("to", to)
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].value").value(90));

        mockMvc.perform(get("/v1/devices/" + GATEWAY + "/" + DEVICE + "/vitals")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", from).param("to", to).param("quality", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.items[0].quality").value(1))
                .andExpect(jsonPath("$.items[0].qualityFlags[0]").value("hr:RANGE"));

        mockMvc.perform(get("/v1/devices/" + GATEWAY + "/" + DEVICE + "/vitals")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", t0.plusSeconds(60).toString()).param("to", t0.plusSeconds(120).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.meta.totalPages").value(0));

        assertThat(auditCount("DEVICE_QUERY") - auditBefore).isEqualTo(4L);
        Map<String, Object> detail = latestAuditDetail("DEVICE_QUERY");
        assertThat(detail).containsEntry("gatewayId", GATEWAY).containsEntry("deviceId", DEVICE)
                .containsKey("frames");
        assertThat(detail.toString()).doesNotContain("patient").doesNotContain("住院");
    }

    @Test
    void vitalsQueryRejectsBadWindowQualityAndUnknownDevice() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        register(admin, GATEWAY, DEVICE, ROOM, "MONITOR", null);

        mockMvc.perform(get("/v1/devices/" + GATEWAY + "/" + DEVICE + "/vitals")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", "not-a-time").param("to", BASE.toString()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/v1/devices/" + GATEWAY + "/" + DEVICE + "/vitals")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", BASE.minus(48, ChronoUnit.HOURS).toString())
                        .param("to", BASE.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        mockMvc.perform(get("/v1/devices/" + GATEWAY + "/" + DEVICE + "/vitals")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", BASE.minusSeconds(60).toString()).param("to", BASE.toString())
                        .param("quality", "9"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/v1/devices/" + GATEWAY + "/UNREGISTERED/vitals")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", BASE.minusSeconds(60).toString()).param("to", BASE.toString()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------- 列表/离线/健康摘要 --

    @Test
    void deviceListDerivesOfflineAndMaintenanceStatus() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        register(admin, GATEWAY, DEVICE, ROOM, "MONITOR", null);
        register(admin, GATEWAY, DEVICE_ALT, ROOM_ALT, "VENTILATOR", "MAINTENANCE");
        jdbcTemplate.update("""
                UPDATE m2_anes.device_registry SET last_seen_at = now() - interval '10 minutes'
                 WHERE gateway_id = ? AND device_id = ?
                """, GATEWAY, DEVICE);

        MvcResult result = mockMvc.perform(get("/v1/devices")
                        .header("Authorization", "Bearer " + admin)
                        .param("gatewayId", GATEWAY))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).path("items");
        assertThat(items).hasSize(2);
        assertThat(findDevice(items, DEVICE).path("status").asText()).isEqualTo("OFFLINE");
        assertThat(findDevice(items, DEVICE_ALT).path("status").asText()).isEqualTo("MAINTENANCE");

        mockMvc.perform(get("/v1/devices")
                        .header("Authorization", "Bearer " + admin)
                        .param("status", "OFFLINE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].deviceId").value(DEVICE));
    }

    @Test
    void healthSummarisesDevicesAndIngest() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        register(admin, GATEWAY, DEVICE, ROOM, "MONITOR", null);
        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .contentType(JSON)
                        .content(ingestBody("BATCH-H1", List.of(frame(1, BASE, 0, List.of())))))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/v1/devices/health")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offlineTimeoutSeconds").value(120))
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.devices.total").value(1))
                .andExpect(jsonPath("$.ingest.batches").value(1))
                .andExpect(jsonPath("$.ingest.acceptedRows").value(1))
                .andExpect(jsonPath("$.gateways[0].gatewayId").value(GATEWAY))
                .andExpect(jsonPath("$.gateways[0].status").value("ONLINE"));
    }

    // ----------------------------------------------------------- 数据范围（B-1）--

    @Test
    void deviceDataScopeFiltersByVisibleRoomsAndRejectsOutOfScopeTargets() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        register(admin, GATEWAY, DEVICE, ROOM, "MONITOR", null);
        register(admin, GATEWAY, DEVICE_ALT, ROOM_OUTSIDE, "VENTILATOR", null);

        // DEPT 账号（SYS_OR_MANAGER，绑定麻醉科）：只见本科室术间设备。
        String dept = login(DEMO_DEPT_USERNAME, DEMO_DEPT_PASSWORD);
        mockMvc.perform(get("/v1/devices").header("Authorization", "Bearer " + dept))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].deviceId").value(DEVICE));

        // 集合查询显式过滤范围外术间：403（不泄露该术间是否有设备）。
        mockMvc.perform(get("/v1/devices")
                        .header("Authorization", "Bearer " + dept)
                        .param("roomId", ROOM_OUTSIDE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        // 单设备越界：404（不存在与越界统一语义）。
        mockMvc.perform(get("/v1/devices/" + GATEWAY + "/" + DEVICE_ALT + "/vitals")
                        .header("Authorization", "Bearer " + dept)
                        .param("from", BASE.minusSeconds(60).toString())
                        .param("to", BASE.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 登记/换台目标术间必须可见；未绑定术间仅 ALL 可操作。
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + dept)
                        .contentType(JSON)
                        .content(registerBody(GATEWAY, "SIM-IT-03", ROOM_OUTSIDE, "MONITOR", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + dept)
                        .contentType(JSON)
                        .content(registerBody(GATEWAY, "SIM-IT-03", null, "MONITOR", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + dept)
                        .contentType(JSON)
                        .content(registerBody(GATEWAY, "SIM-IT-03", ROOM, "MONITOR", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(ROOM));

        // 换台到范围外术间同样 403，且不得改动既有绑定。
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + dept)
                        .contentType(JSON)
                        .content(registerBody(GATEWAY, DEVICE, ROOM_OUTSIDE, "MONITOR", null)))
                .andExpect(status().isForbidden());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT room_id::text FROM m2_anes.device_registry
                 WHERE gateway_id = ? AND device_id = ?
                """, String.class, GATEWAY, DEVICE)).isEqualTo(ROOM);

        System.out.println("[M3-M8-01][scope] deptList=1 roomFilterOutside=403 vitalsOutside=404 "
                + "registerOutside/unbound=403 registerVisible=201 rebindOutside=403");
    }

    // ------------------------------------------------------- 健康窗口/登记状态 --

    @Test
    void healthQualityRateUsesClosedWindowForNumeratorAndDenominator() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        register(admin, GATEWAY, DEVICE, ROOM, "MONITOR", null);
        Instant now = Instant.now();
        insertVital(1, now.minus(25, ChronoUnit.HOURS), 60, 1, "hr:RANGE");
        insertVital(2, now.minus(2, ChronoUnit.HOURS), 80, 1, "hr:RANGE");
        insertVital(3, now.minus(1, ChronoUnit.HOURS), 76, 0, null);
        insertVital(4, now.plus(1, ChronoUnit.HOURS), 70, 0, null);
        insertVital(5, now.plus(2, ChronoUnit.HOURS), 70, 0, null);

        mockMvc.perform(get("/v1/devices/health").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateways[0].gatewayId").value(GATEWAY))
                // 只计窗口内 2 帧（1 可疑 + 1 正常）→ 1/2；窗口外与未来帧不得稀释分母。
                .andExpect(jsonPath("$.gateways[0].qualityRate").value(0.5));

        System.out.println("[M3-M8-01][health-window] inside=2 outsidePast=1 future=2 "
                + "qualityRate=0.5 closedWindow=true");
    }

    @Test
    void lastSeenAdvancesOnlyWhenAcceptedRowsPositive() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        register(admin, GATEWAY, DEVICE, ROOM, "MONITOR", null);
        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .contentType(JSON)
                        .content(ingestBody("BATCH-L1", List.of(frame(1, BASE, 0, List.of())))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedRows").value(1));
        java.sql.Timestamp firstSeen = jdbcTemplate.queryForObject("""
                SELECT last_seen_at FROM m2_anes.device_registry
                 WHERE gateway_id = ? AND device_id = ?
                """, java.sql.Timestamp.class, GATEWAY, DEVICE);
        assertThat(firstSeen).isNotNull();

        // 新批次全部被拒绝：acceptedRows=0 → last_seen_at 不得推进，但统计照常累加。
        mockMvc.perform(post("/v1/devices/" + GATEWAY + "/" + DEVICE + "/ingest")
                        .header("X-Gateway-Token", GATEWAY_TOKEN)
                        .contentType(JSON)
                        .content(ingestBody("BATCH-L2", List.of(invalidSeqFrame(2)))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedRows").value(0))
                .andExpect(jsonPath("$.rejectedRows").value(1));

        java.sql.Timestamp secondSeen = jdbcTemplate.queryForObject("""
                SELECT last_seen_at FROM m2_anes.device_registry
                 WHERE gateway_id = ? AND device_id = ?
                """, java.sql.Timestamp.class, GATEWAY, DEVICE);
        assertThat(secondSeen).isEqualTo(firstSeen);
        Map<String, Object> stats = jdbcTemplate.queryForMap("""
                SELECT quality_stats->>'batches' AS batches,
                       quality_stats->>'acceptedRows' AS accepted,
                       quality_stats->>'rejectedRows' AS rejected
                  FROM m2_anes.device_registry WHERE gateway_id = ? AND device_id = ?
                """, GATEWAY, DEVICE);
        assertThat(stats.get("batches")).isEqualTo("2");
        assertThat(stats.get("accepted")).isEqualTo("1");
        assertThat(stats.get("rejected")).isEqualTo("1");
        System.out.printf("[M3-M8-01][last-seen] acceptedBatchAdvances=true allRejectedBatch=false "
                + "batches=2 accepted=1 rejected=1%n");
    }

    @Test
    void registerWithoutStatusKeepsExistingMaintenance() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String maintenance = registerBody(GATEWAY, DEVICE, ROOM, "MONITOR", "MAINTENANCE");
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(maintenance))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));

        // 换台但缺省 status：保留 MAINTENANCE，不被默认 ONLINE 覆盖。
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(registerBody(GATEWAY, DEVICE, ROOM_ALT, "MONITOR", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(ROOM_ALT))
                .andExpect(jsonPath("$.status").value("MAINTENANCE"))
                .andExpect(jsonPath("$.version").value(2));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM m2_anes.device_registry WHERE gateway_id = ? AND device_id = ?
                """, String.class, GATEWAY, DEVICE)).isEqualTo("MAINTENANCE");

        // 缺省 status + 内容不变 → 幂等 200，不递增版本。
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(registerBody(GATEWAY, DEVICE, ROOM_ALT, "MONITOR", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));

        System.out.println("[M3-M8-01][register] explicitMaintenance=true defaultKeepsStatus=true "
                + "idempotentNoVersionBump=true");
    }

    // --------------------------------------------------------------- 权限 403 --

    @Test
    void deviceEndpointsReturn403WithoutDevicePermission() throws Exception {
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME, SyntheticAccounts.SURGEON_PASSWORD);

        mockMvc.perform(get("/v1/devices").header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + surgeon)
                        .contentType(JSON)
                        .content(registerBody(GATEWAY, DEVICE, ROOM, "MONITOR", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/devices/" + GATEWAY + "/" + DEVICE + "/vitals")
                        .header("Authorization", "Bearer " + surgeon)
                        .param("from", BASE.minusSeconds(60).toString()).param("to", BASE.toString()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/devices/health").header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------- helpers --

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password, "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("accessToken").asText();
    }

    private void register(String token, String gateway, String device, String room, String type,
            String deviceStatus) throws Exception {
        mockMvc.perform(post("/v1/devices")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(registerBody(gateway, device, room, type, deviceStatus)))
                .andExpect(status().isCreated());
    }

    private String registerBody(String gateway, String device, String room, String type, String deviceStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gatewayId", gateway);
        body.put("deviceId", device);
        body.put("roomId", room);
        body.put("deviceType", type);
        if (deviceStatus != null) {
            body.put("status", deviceStatus);
        }
        return write(body);
    }

    private String ingestBody(String batchKey, List<Map<String, Object>> frames) {
        return write(Map.of("batchKey", batchKey, "frames", frames));
    }

    private static Map<String, Object> frame(long seq, Instant measuredAt, int quality, List<String> flags) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("seq", seq);
        frame.put("measuredAt", measuredAt.toString());
        frame.put("measuredAtCorrected", measuredAt.toString());
        frame.put("receivedAt", measuredAt.plusSeconds(1).toString());
        frame.put("hr", 76);
        frame.put("spo2", 98);
        frame.put("nibpSys", 120);
        frame.put("nibpDia", 80);
        frame.put("etco2", 35.0);
        frame.put("temp", 36.7);
        frame.put("quality", quality);
        frame.put("flags", flags);
        frame.put("source", "AUTO");
        return frame;
    }

    private static Map<String, Object> invalidSeqFrame(long seq) {
        Map<String, Object> frame = frame(seq, BASE, 0, List.of());
        frame.put("seq", -1);
        return frame;
    }

    private void insertVital(long seq, Instant measuredAt, int hr, int quality, String flags) {
        jdbcTemplate.update("""
                INSERT INTO m2_anes.anesthesia_vital
                    (gateway_id, device_id, seq, measured_at, measured_at_corrected, received_at,
                     hr, quality, quality_flags, source, synthetic)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'AUTO', true)
                """, GATEWAY, DEVICE, seq,
                java.time.OffsetDateTime.ofInstant(measuredAt, java.time.ZoneOffset.UTC),
                java.time.OffsetDateTime.ofInstant(measuredAt, java.time.ZoneOffset.UTC),
                java.time.OffsetDateTime.ofInstant(measuredAt.plusSeconds(1), java.time.ZoneOffset.UTC),
                hr, quality, flags);
    }

    private String write(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean constraintExists(String name, String table) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conname = ? AND conrelid = ?::regclass
                """, Long.class, name, table);
        return count != null && count > 0;
    }

    private long deviceCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM m2_anes.device_registry", Long.class);
        return count == null ? 0 : count;
    }

    private long vitalCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM m2_anes.anesthesia_vital", Long.class);
        return count == null ? 0 : count;
    }

    private long auditCount(String action) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_audit_log WHERE action = ?", Long.class, action);
        return count == null ? 0 : count;
    }

    private Map<String, Object> latestAuditDetail(String action) {
        String json = jdbcTemplate.queryForObject("""
                SELECT detail::text FROM m0_platform.sys_audit_log
                 WHERE action = ? ORDER BY occurred_at DESC LIMIT 1
                """, String.class, action);
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode findDevice(JsonNode items, String deviceId) {
        for (JsonNode item : items) {
            if (deviceId.equals(item.path("deviceId").asText())) {
                return item;
            }
        }
        throw new AssertionError("设备未出现在列表：" + deviceId);
    }
}
