package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.anesop.contracts.scheduling.model.CreateScheduleRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.schedule.SchedulingConflictException;
import com.anesop.m1.api.schedule.SchedulingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 排程工作台与冲突检测（M2-M2-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：术间列表（DEPT/ROOM 数据范围）、创建/日查询/详情/调整/取消、术间重叠/术者/
 * 患者/术间类型冲突（409 + conflicts[]）、并发同室同窗恰一成功、403、审计、
 * 申请状态不允许（409/422）与时间窗校验。全部数据为 SYNTHETIC。
 */
class SchedulingApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String DATE = "2026-12-02";

    private static final String PATIENT_A = "52000000-0000-4000-8000-000000000001";
    private static final String PATIENT_B = "52000000-0000-4000-8000-000000000002";
    private static final String PATIENT_C = "52000000-0000-4000-8000-000000000003";
    private static final String HIS_A = "SYNTH-HIS-P-9101";
    private static final String HIS_B = "SYNTH-HIS-P-9102";
    private static final String HIS_C = "SYNTH-HIS-P-9103";

    private static final String OR_01 = "f2000000-0000-4000-8000-0000000000c1";
    private static final String OR_02 = "f2000000-0000-4000-8000-0000000000c2";
    private static final String OR_03 = "f2000000-0000-4000-8000-0000000000c3";
    private static final String OR_05 = "f2000000-0000-4000-8000-0000000000c5";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SchedulingService schedulingService;

    @BeforeEach
    void prepareData() {
        SyntheticAccounts.resetDemoAccounts(jdbcTemplate);
        SyntheticAccounts.ensureSurgeon(jdbcTemplate);
        SyntheticAccounts.ensureRoomUser(jdbcTemplate);
        grantRoomUserSchedulePermissions();
        jdbcTemplate.update("DELETE FROM m1_sched.schedule");
        jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history DISABLE TRIGGER USER");
        try {
            jdbcTemplate.update("DELETE FROM m1_sched.application_status_history");
            jdbcTemplate.update("DELETE FROM m1_sched.surgery_application");
            jdbcTemplate.update("DELETE FROM m1_sched.patient_snapshot");
        } finally {
            jdbcTemplate.execute("ALTER TABLE m1_sched.application_status_history ENABLE TRIGGER USER");
        }
        insertPatient(PATIENT_A, HIS_A, "演示患者甲", "SYNT****9101", "hash-9101",
                SyntheticAccounts.DEMO_ANES_ORG_ID, "麻醉科（合成）");
        insertPatient(PATIENT_B, HIS_B, "演示患者乙", "SYNT****9102", "hash-9102",
                SyntheticAccounts.DEMO_ANES_ORG_ID, "麻醉科（合成）");
        // 手术室科室患者：用于 DEPT（麻醉科）用户持其他科室申请的负向用例。
        insertPatient(PATIENT_C, HIS_C, "演示患者丙", "SYNT****9103", "hash-9103",
                SyntheticAccounts.DEMO_OR_ORG_ID, "手术室（合成）");
    }

    @Test
    void roomListIncludesTypesAndIsScopedByDepartment() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/scheduling/rooms").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[0].roomNo").value("OR-01"))
                .andExpect(jsonPath("$.items[0].roomType").value("GENERAL"))
                .andExpect(jsonPath("$.items[1].roomType").value("HYBRID"))
                .andExpect(jsonPath("$.items[2].roomType").value("INTERVENTION"));

        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/scheduling/rooms").header("Authorization", "Bearer " + dept))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[?(@.roomNo=='OR-05')]").isEmpty());

        String room = login(SyntheticAccounts.ROOM_USERNAME, SyntheticAccounts.ROOM_PASSWORD);
        mockMvc.perform(get("/v1/scheduling/rooms").header("Authorization", "Bearer " + room))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].roomId").value(OR_01));
        System.out.printf("[M2-M2-01][rooms] admin=5 dept=4 room=1 types=GENERAL/HYBRID/INTERVENTION%n");
    }

    @Test
    void roomListFiltersBySiteAndStatusAndRejectsInvalidParams() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String siteId = "f1000000-0000-4000-8000-000000000001";
        mockMvc.perform(get("/v1/scheduling/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .param("siteId", siteId)
                        .param("status", "IDLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)));
        mockMvc.perform(get("/v1/scheduling/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .param("status", "SURGERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/v1/scheduling/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .param("siteId", "f1000000-0000-4000-8000-0000000000ff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(get("/v1/scheduling/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .param("siteId", "bad-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(get("/v1/scheduling/rooms")
                        .header("Authorization", "Bearer " + admin)
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        System.out.println("[M2-M2-01][rooms-filter] siteId+status=5 statusEmpty=0 unknownSite=0 "
                + "badSite=400 badStatus=400");
    }

    @Test
    void scheduleCreateListGetAdjustCancelAndAudit() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M2-APP-0001", PATIENT_A, "演示术者", null);

        MvcResult created = mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                applicationId, OR_01, "2026-12-02T08:00:00+08:00",
                                "2026-12-02T10:00:00+08:00", 1, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.roomNo").value("OR-01"))
                .andExpect(jsonPath("$.patientNo").value(HIS_A))
                .andExpect(jsonPath("$.priority").value("ELECTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        String scheduleId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("scheduleId").asText();
        Integer storedPriority = jdbcTemplate.queryForObject(
                "SELECT priority FROM m1_sched.schedule WHERE id = ?::uuid", Integer.class, scheduleId);
        assertThat(storedPriority).as("CreateScheduleRequest.priority 必须落库").isZero();
        assertThat(created.getResponse().getContentAsString())
                .as("排程响应不得包含患者标识以外的敏感字段")
                .doesNotContain("idCard", "\"phone", "inpatientNo");

        mockMvc.perform(get("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .param("date", DATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.items[0].scheduleId").value(scheduleId));
        mockMvc.perform(get("/v1/scheduling/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequenceNo").value(1));

        mockMvc.perform(put("/v1/scheduling/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                null, OR_02, "2026-12-02T10:30:00+08:00",
                                "2026-12-02T12:00:00+08:00", 2, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomNo").value("OR-02"))
                .andExpect(jsonPath("$.sequenceNo").value(2))
                .andExpect(jsonPath("$.changeReason").value("演示调整"))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(post("/v1/scheduling/schedules/" + scheduleId + "/cancel")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "PATIENT_CONDITION",
                                "reason", "演示停台原因（合成数据）",
                                "version", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(scheduleId));

        mockMvc.perform(get("/v1/scheduling/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.changeReason").value("演示停台原因（合成数据）"));

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, result FROM m0_platform.sys_audit_log
                WHERE resource_type = 'SCHEDULE' AND resource_id = ?
                ORDER BY occurred_at
                """, scheduleId);
        assertThat(audits).extracting(row -> row.get("action"))
                .containsExactly("SCHEDULE_CREATE", "SCHEDULE_UPDATE", "SCHEDULE_CANCEL");
        assertThat(audits).allMatch(row -> ((Number) row.get("result")).intValue() == 1);
        System.out.printf("[M2-M2-01][flow] schedule=%s create=201 list=1 update=v2 cancel=CANCELLED audit=3%n",
                scheduleId);
    }

    @Test
    void roomOverlapReturns409WithConflicts() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String firstApp = createApplication(admin, "SYNTH-M2-APP-0010", PATIENT_A, "演示术者", null);
        String secondApp = createApplication(admin, "SYNTH-M2-APP-0011", PATIENT_B, "其他术者", null);
        createSchedule(admin, firstApp, OR_01, "2026-12-02T08:00:00+08:00",
                "2026-12-02T10:00:00+08:00", 1);

        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                secondApp, OR_01, "2026-12-02T09:00:00+08:00",
                                "2026-12-02T11:00:00+08:00", 2, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEDULE_CONFLICT"))
                .andExpect(jsonPath("$.conflicts", hasSize(1)))
                .andExpect(jsonPath("$.conflicts[0].conflictType").value("ROOM_OVERLAP"))
                .andExpect(jsonPath("$.conflicts[0].severity").value("BLOCKING"))
                .andExpect(jsonPath("$.conflicts[0].message", containsString("时段重叠")))
                .andExpect(jsonPath("$.details[0].reason", containsString("时段重叠")));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM m1_sched.schedule", Integer.class);
        assertThat(rows).as("冲突不得落库").isEqualTo(1);

        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                secondApp, OR_01, "2026-12-02T10:00:00+08:00",
                                "2026-12-02T11:00:00+08:00", 2, null))))
                .andExpect(status().isCreated());
        System.out.println("[M2-M2-01][409] overlapping=409 ROOM_OVERLAP rows=1 adjacent=201");
    }

    @Test
    void surgeonPatientAndRoomTypeRulesAreEnforced() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String firstApp = createApplication(admin, "SYNTH-M2-APP-0020", PATIENT_A, "演示术者", null);
        createSchedule(admin, firstApp, OR_01, "2026-12-02T08:00:00+08:00",
                "2026-12-02T10:00:00+08:00", 1);

        String sameSurgeon = createApplication(admin, "SYNTH-M2-APP-0021", PATIENT_B, "演示术者", null);
        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                sameSurgeon, OR_02, "2026-12-02T09:00:00+08:00",
                                "2026-12-02T11:00:00+08:00", 2, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts[0].conflictType").value("STAFF_CONFLICT"));

        String samePatient = createApplication(admin, "SYNTH-M2-APP-0022", PATIENT_A, "其他术者", null);
        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                samePatient, OR_02, "2026-12-02T09:00:00+08:00",
                                "2026-12-02T11:00:00+08:00", 2, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts[0].conflictType").value("PATIENT_CONFLICT"));

        String hybridRequired = createApplication(admin, "SYNTH-M2-APP-0023", PATIENT_B, "其他术者",
                "HYBRID");
        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                hybridRequired, OR_01, "2026-12-02T13:00:00+08:00",
                                "2026-12-02T14:00:00+08:00", 3, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts[0].conflictType").value("ROOM_TYPE_MISMATCH"));

        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                hybridRequired, OR_02, "2026-12-02T13:00:00+08:00",
                                "2026-12-02T14:00:00+08:00", 3, null))))
                .andExpect(status().isCreated());
        System.out.println("[M2-M2-01][rules] surgeon=STAFF_CONFLICT patient=PATIENT_CONFLICT "
                + "type=ROOM_TYPE_MISMATCH hybridRoom=201");
    }

    @Test
    void conflictCheckEndpointReturnsStructuredResult() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M2-APP-0030", PATIENT_A, "演示术者", null);
        createSchedule(admin, applicationId, OR_01, "2026-12-02T08:00:00+08:00",
                "2026-12-02T10:00:00+08:00", 1);

        mockMvc.perform(post("/v1/scheduling/schedules/conflicts")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roomId", OR_01,
                                "applicationId", applicationId,
                                "plannedStart", "2026-12-02T09:00:00+08:00",
                                "plannedEnd", "2026-12-02T11:00:00+08:00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasConflict").value(true))
                .andExpect(jsonPath("$.conflicts[0].conflictType").value("ROOM_OVERLAP"));

        mockMvc.perform(post("/v1/scheduling/schedules/conflicts")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roomId", OR_03,
                                "plannedStart", "2026-12-02T15:00:00+08:00",
                                "plannedEnd", "2026-12-02T16:00:00+08:00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasConflict").value(false))
                .andExpect(jsonPath("$.conflicts", hasSize(0)));
        System.out.println("[M2-M2-01][precheck] overlap=true conflicts=ROOM_OVERLAP freeRoom=false");
    }

    @Test
    void deprecatedConflictCheckEndpointReturnsSameResponseAsNewEndpoint() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M2-APP-0031", PATIENT_A, "演示术者", null);
        createSchedule(admin, applicationId, OR_01, "2026-12-02T08:00:00+08:00",
                "2026-12-02T10:00:00+08:00", 1);
        String body = objectMapper.writeValueAsString(Map.of(
                "roomId", OR_01,
                "plannedStart", "2026-12-02T09:00:00+08:00",
                "plannedEnd", "2026-12-02T11:00:00+08:00"));

        MvcResult oldResult = mockMvc.perform(post("/v1/scheduling/conflicts/check")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasConflict").value(true))
                .andExpect(jsonPath("$.conflicts[0].conflictType").value("ROOM_OVERLAP"))
                .andReturn();
        MvcResult newResult = mockMvc.perform(post("/v1/scheduling/schedules/conflicts")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(oldResult.getResponse().getContentAsString()))
                .as("deprecated 端点必须与新端点响应一致")
                .isEqualTo(objectMapper.readTree(newResult.getResponse().getContentAsString()));
        System.out.println("[M2-M2-01][deprecated] POST /conflicts/check=200 sameBodyAsNew=true");
    }

    @Test
    void validationAndApplicationStateAreEnforced() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M2-APP-0040", PATIENT_A, "演示术者", null);

        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                applicationId, OR_01, "2026-12-02T10:00:00+08:00",
                                "2026-12-02T09:00:00+08:00", 1, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("plannedEnd"));

        jdbcTemplate.update("UPDATE m1_sched.surgery_application SET status = 'CANCELLED' WHERE id = ?::uuid",
                applicationId);
        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                applicationId, OR_01, "2026-12-02T08:00:00+08:00",
                                "2026-12-02T09:00:00+08:00", 1, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));

        jdbcTemplate.update("UPDATE m1_sched.surgery_application SET status = 'SCHEDULED' WHERE id = ?::uuid",
                applicationId);
        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                applicationId, OR_01, "2026-12-02T08:00:00+08:00",
                                "2026-12-02T09:00:00+08:00", 1, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts[0].conflictType")
                        .value("APPLICATION_ALREADY_SCHEDULED"));

        // S-9：停台原因码白名单正则，非法格式 400（不写状态、不落审计）。
        String cancelApp = createApplication(admin, "SYNTH-M2-APP-0041", PATIENT_B, "其他术者", null);
        MvcResult cancelCreated = mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                cancelApp, OR_01, "2026-12-02T15:00:00+08:00",
                                "2026-12-02T16:00:00+08:00", 1, null))))
                .andExpect(status().isCreated())
                .andReturn();
        String cancelScheduleId = objectMapper.readTree(
                        cancelCreated.getResponse().getContentAsString())
                .get("scheduleId").asText();
        mockMvc.perform(post("/v1/scheduling/schedules/" + cancelScheduleId + "/cancel")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reasonCode", "patient condition",
                                "reason", "演示停台原因（合成数据）",
                                "version", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"))
                .andExpect(jsonPath("$.details[0].field").value("reasonCode"));
        mockMvc.perform(get("/v1/scheduling/schedules/" + cancelScheduleId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLANNED"));

        mockMvc.perform(get("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .param("date", "2026/12/02"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        System.out.println("[M2-M2-01][validation] timeWindow=400 cancelled=422 "
                + "scheduled=APPLICATION_ALREADY_SCHEDULED badDate=400 reasonCode=400");
    }

    @Test
    void permissionsAndDataScopeAreEnforced() throws Exception {
        mockMvc.perform(get("/v1/scheduling/rooms"))
                .andExpect(status().isUnauthorized());
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        // S-7：V8 已授予 SYS_SURGEON m1:schedule:read（临床只读），但其数据范围为 SELF，
        // 无术间对象绑定 → 集合查询仍 fail-closed 403（授权 ≠ 可见性）。
        Integer surgeonGrant = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role_permission
                WHERE role_id = 'a0000000-0000-4000-8000-000000000004'
                  AND permission_id = 'b0000000-0000-4000-8000-000000000027'
                """, Integer.class);
        assertThat(surgeonGrant).as("SYS_SURGEON 应获 m1:schedule:read").isEqualTo(1);
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME, SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/scheduling/rooms").header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String deptApp = createApplication(dept, "SYNTH-M2-APP-0050", PATIENT_A, "演示术者", null);
        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + dept)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                deptApp, OR_05, "2026-12-02T08:00:00+08:00",
                                "2026-12-02T09:00:00+08:00", 1, null))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + dept)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                deptApp, OR_01, "2026-12-02T08:00:00+08:00",
                                "2026-12-02T09:00:00+08:00", 1, null))))
                .andExpect(status().isCreated());

        String room = login(SyntheticAccounts.ROOM_USERNAME, SyntheticAccounts.ROOM_PASSWORD);
        // 术间范围账号只有排程权限：申请由管理员创建，本用例只验证术间数据范围。
        String roomApp = createApplication(admin, "SYNTH-M2-APP-0051", PATIENT_B, "其他术者", null);
        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + room)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                roomApp, OR_02, "2026-12-02T10:00:00+08:00",
                                "2026-12-02T11:00:00+08:00", 1, null))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + room)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                roomApp, OR_01, "2026-12-02T11:00:00+08:00",
                                "2026-12-02T12:00:00+08:00", 2, null))))
                .andExpect(status().isCreated());
        System.out.println("[M2-M2-01][scope] anonymous=401 surgeon=403(no object scope) "
                + "deptRoom5=404 room2=404 boundRooms=201");
    }

    @Test
    void departmentUserCannotScheduleAnotherDepartmentApplication() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        // 患者丙属手术室（合成）科室；申请由 ALL 范围管理员创建。
        String otherDeptApp = createApplication(admin, "SYNTH-M2-APP-0052", PATIENT_C, "其他术者", null);
        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult result = mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + dept)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(
                                otherDeptApp, OR_01, "2026-12-02T13:00:00+08:00",
                                "2026-12-02T14:00:00+08:00", 1, null))))
                .andExpect(status().isNotFound())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).as("越界拒绝响应不得泄露患者姓名/住院号")
                .doesNotContain("演示患者丙").doesNotContain(HIS_C);

        // SELF 范围账号无术间绑定：集合查询 403（fail-closed）。
        String self = login(SyntheticAccounts.DEMO_SELF_USERNAME, SyntheticAccounts.DEMO_SELF_PASSWORD);
        mockMvc.perform(get("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + self)
                        .param("date", DATE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        System.out.println("[M2-M2-01][scope-cross-dept] deptOtherDept=404 noPhi=true selfList=403");
    }

    @Test
    void departmentUserCannotRescheduleAnotherDepartmentApplication() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String otherDeptApp = createApplication(admin, "SYNTH-M2-APP-0054", PATIENT_C, "其他术者", null);
        String scheduleId = createScheduleReturningId(admin, otherDeptApp, OR_01,
                "2026-12-02T15:00:00+08:00", "2026-12-02T16:00:00+08:00", 1);
        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        MvcResult result = mockMvc.perform(put("/v1/scheduling/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + dept)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(scheduleBody(otherDeptApp, OR_02,
                                "2026-12-02T17:00:00+08:00", "2026-12-02T18:00:00+08:00", 1, 1))))
                .andExpect(status().isNotFound())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).as("调整台次越界拒绝不得泄露患者姓名/住院号")
                .doesNotContain("演示患者丙").doesNotContain(HIS_C);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT room_id FROM m1_sched.schedule WHERE id = ?::uuid", String.class, scheduleId))
                .isEqualTo(OR_01);
        System.out.println("[M2-M2-01][scope-cross-dept-reschedule] 404 noPhi=true roomUnchanged=true");
    }

    @Test
    void departmentUserCannotCheckConflictsForAnotherDepartmentApplication() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String otherDeptApp = createApplication(admin, "SYNTH-M2-APP-0055", PATIENT_C, "其他术者", null);
        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("roomId", OR_01);
        request.put("applicationId", otherDeptApp);
        request.put("plannedStart", "2026-12-02T19:00:00+08:00");
        request.put("plannedEnd", "2026-12-02T20:00:00+08:00");
        MvcResult result = mockMvc.perform(post("/v1/scheduling/schedules/conflicts")
                        .header("Authorization", "Bearer " + dept)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).as("冲突预检越界拒绝不得泄露患者姓名/住院号")
                .doesNotContain("演示患者丙").doesNotContain(HIS_C);
        System.out.println("[M2-M2-01][scope-cross-dept-conflicts] 404 noPhi=true");
    }

    @Test
    void concurrentSchedulesOnSameRoomWindowOnlyOneSucceeds() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String firstApp = createApplication(adminToken, "SYNTH-M2-APP-0060", PATIENT_A, "演示术者", null);
        String secondApp = createApplication(adminToken, "SYNTH-M2-APP-0061", PATIENT_B, "其他术者", null);
        List<Boolean> results = raceInTwoThreads(firstApp, OR_01, secondApp, OR_01);
        assertThat(results).as("同室同窗并发排程恰有一个成功").containsExactlyInAnyOrder(true, false);
        assertThat(scheduleRows()).isEqualTo(1);
        System.out.printf("[M2-M2-01][concurrent-room] attempts=2 success=%d conflict=%d rows=%d%n",
                results.stream().filter(Boolean::booleanValue).count(),
                results.stream().filter(value -> !value).count(), scheduleRows());
    }

    @Test
    void concurrentSameSurgeonAcrossRoomsOnlyOneSucceeds() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String firstApp = createApplication(adminToken, "SYNTH-M2-APP-0062", PATIENT_A, "演示术者", null);
        String secondApp = createApplication(adminToken, "SYNTH-M2-APP-0063", PATIENT_B, "演示术者", null);
        List<Boolean> results = raceInTwoThreads(firstApp, OR_01, secondApp, OR_02);
        assertThat(results).as("跨术间同术者并发排程恰有一个成功").containsExactlyInAnyOrder(true, false);
        assertThat(scheduleRows()).isEqualTo(1);
        System.out.printf("[M2-M2-01][concurrent-surgeon] rooms=2 attempts=2 success=%d rows=%d%n",
                results.stream().filter(Boolean::booleanValue).count(), scheduleRows());
    }

    @Test
    void concurrentSamePatientAcrossRoomsOnlyOneSucceeds() throws Exception {
        String adminToken = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String firstApp = createApplication(adminToken, "SYNTH-M2-APP-0064", PATIENT_A, "演示术者", null);
        String secondApp = createApplication(adminToken, "SYNTH-M2-APP-0065", PATIENT_A, "其他术者", null);
        List<Boolean> results = raceInTwoThreads(firstApp, OR_01, secondApp, OR_02);
        assertThat(results).as("跨术间同患者并发排程恰有一个成功").containsExactlyInAnyOrder(true, false);
        assertThat(scheduleRows()).isEqualTo(1);
        System.out.printf("[M2-M2-01][concurrent-patient] rooms=2 attempts=2 success=%d rows=%d%n",
                results.stream().filter(Boolean::booleanValue).count(), scheduleRows());
    }

    /** CyclicBarrier 让两线程同时进入 create；服务层 READ_COMMITTED + advisory/行锁串行化。 */
    private List<Boolean> raceInTwoThreads(String firstApp, String firstRoom, String secondApp,
            String secondRoom) throws Exception {
        AuthPrincipal principal = new AuthPrincipal(UUID.fromString(SyntheticAccounts.DEMO_ADMIN_ID),
                "demo_admin", "jti", Instant.now().plusSeconds(900), "sid");
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Boolean> first = createCallable(principal, firstApp, firstRoom, barrier);
        Callable<Boolean> second = createCallable(principal, secondApp, secondRoom, barrier);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Boolean> results = new ArrayList<>();
        try {
            Future<Boolean> firstFuture = pool.submit(first);
            Future<Boolean> secondFuture = pool.submit(second);
            results.add(firstFuture.get(60, TimeUnit.SECONDS));
            results.add(secondFuture.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    private int scheduleRows() {
        Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM m1_sched.schedule",
                Integer.class);
        return rows == null ? 0 : rows;
    }

    private Callable<Boolean> createCallable(AuthPrincipal principal, String applicationId,
            String roomId, CyclicBarrier barrier) {
        return () -> {
            CreateScheduleRequest request = new CreateScheduleRequest();
            request.setApplicationId(applicationId);
            request.setRoomId(roomId);
            request.setPlannedStart(OffsetDateTime.parse("2026-12-02T08:00:00+08:00"));
            request.setPlannedEnd(OffsetDateTime.parse("2026-12-02T10:00:00+08:00"));
            request.setSequenceNo(1);
            request.setPriority(CreateScheduleRequest.PriorityEnum.ELECTIVE);
            barrier.await(30, TimeUnit.SECONDS);
            try {
                schedulingService.create(request, principal, AuthRequestContext.empty());
                return true;
            } catch (SchedulingConflictException e) {
                return false;
            }
        };
    }

    @Test
    void thousandScheduleQueryStaysUnderLocalBaseline() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME, SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String applicationId = createApplication(admin, "SYNTH-M2-APP-0070", PATIENT_A, "演示术者", null);
        List<Object[]> batch = new ArrayList<>();
        for (int index = 0; index < 1000; index++) {
            java.sql.Timestamp start = java.sql.Timestamp.from(
                    OffsetDateTime.parse("2026-12-02T08:00:00+08:00").toInstant()
                            .plusSeconds((index % 600) * 60L));
            batch.add(new Object[]{"80000000-0000-4000-8000-%012d".formatted(index),
                    applicationId, OR_01, start,
                    java.sql.Timestamp.from(start.toInstant().plusSeconds(1800)), index + 1, 0,
                    "CANCELLED", false});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority, status, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?)
                """, batch);

        long startedAt = System.nanoTime();
        mockMvc.perform(get("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .param("date", DATE)
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1000))
                .andExpect(jsonPath("$.items", hasSize(200)));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        assertThat(elapsedMs).as("1000 条排程查询本地基准 <2s（含 MockMvc/JSON 序列化）")
                .isLessThan(2000L);
        System.out.printf("[M2-M2-01][benchmark] rows=1000 pageSize=200 elapsedMs=%d baseline<2000ms%n",
                elapsedMs);
    }

    private Map<String, Object> scheduleBody(String applicationId, String roomId, String start,
            String end, int sequence, Integer version) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (applicationId != null) {
            body.put("applicationId", applicationId);
        }
        body.put("roomId", roomId);
        body.put("plannedStart", start);
        body.put("plannedEnd", end);
        body.put("sequenceNo", sequence);
        if (version != null) {
            body.put("version", version);
            body.put("reason", "演示调整");
        } else if (applicationId != null) {
            body.put("priority", "ELECTIVE");
        }
        return body;
    }

    private String createApplication(String token, String hisApplicationNo, String patientId,
            String surgeonName, String requiredRoomType) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("hisApplicationNo", hisApplicationNo);
        request.put("patientId", patientId);
        request.put("surgeryName", "演示术式（腹腔镜胆囊切除术）");
        request.put("urgency", "URGENT");
        request.put("plannedDurationMin", 120);
        request.put("surgeonName", surgeonName);
        if (requiredRoomType != null) {
            request.put("requiredRoomType", requiredRoomType);
        }
        MvcResult result = mockMvc.perform(post("/v1/scheduling/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("applicationId").asText();
    }

    private void createSchedule(String token, String applicationId, String roomId, String start,
            String end, int sequence) throws Exception {
        mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                scheduleBody(applicationId, roomId, start, end, sequence, null))))
                .andExpect(status().isCreated());
    }

    private String createScheduleReturningId(String token, String applicationId, String roomId,
            String start, String end, int sequence) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/scheduling/schedules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                scheduleBody(applicationId, roomId, start, end, sequence, null))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("scheduleId").asText();
    }

    private void grantRoomUserSchedulePermissions() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                VALUES (?::uuid, 'b0000000-0000-4000-8000-000000000027'),
                       (?::uuid, 'b0000000-0000-4000-8000-000000000028')
                ON CONFLICT DO NOTHING
                """, SyntheticAccounts.ROOM_ROLE_ID, SyntheticAccounts.ROOM_ROLE_ID);
    }

    private void insertPatient(String id, String hisPatientNo, String name, String masked, String hash,
            String deptId, String deptName) {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, ?, ?, 'FEMALE', DATE '1978-03-15', 48, ?, ?, ?, ?::uuid,
                        'f1000000-0000-4000-8000-000000000001'::uuid, '演示病区', '12',
                        now(), now(), 1, false)
                """, id, hisPatientNo, name, masked, hash, deptName, deptId);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password, "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @SuppressWarnings("unused")
    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
