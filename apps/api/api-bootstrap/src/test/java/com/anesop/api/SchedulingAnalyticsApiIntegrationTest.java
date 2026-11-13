package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anesop.api.support.AbstractApiIntegrationTest;
import com.anesop.api.support.SyntheticAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
 * 排程分析（M2-M6-01）端到端用例：MockMvc + 嵌入式 PostgreSQL 16。
 *
 * <p>固定 SYNTHETIC 数据集覆盖：利用率/准点率/取消率分子分母、Asia/Shanghai 跨日与 23:59
 * 边界、DEPT/ROOM/未绑定 403、参数校验、CSV 脱敏列头/行数、审计、空数据集 0。
 * 期望值均按固定数据集手算，不使用实现复制。
 */
class SchedulingAnalyticsApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String FROM = "2026-12-01";
    private static final String TO = "2026-12-02";
    private static final String OR_01 = "f2000000-0000-4000-8000-0000000000c1";
    private static final String OR_02 = "f2000000-0000-4000-8000-0000000000c2";
    private static final String OR_05 = "f2000000-0000-4000-8000-0000000000c5";
    private static final String ANES_DEPT = SyntheticAccounts.DEMO_ANES_ORG_ID;
    private static final String OR_DEPT = SyntheticAccounts.DEMO_OR_ORG_ID;
    private static final String PATIENT_ID = "53000000-0000-4000-8000-000000000011";
    private static final String QC_USER_ID = "e0000000-0000-4000-8000-0000000000e3";
    private static final String QC_USERNAME = "test.qc";
    private static final String QC_PASSWORD = "Synth@2026";
    private static final String ANALYTICS_PERMISSION = "b0000000-0000-4000-8000-000000000030";

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
        SyntheticAccounts.ensureUnboundUser(jdbcTemplate);
        SyntheticAccounts.ensureRoomUser(jdbcTemplate);
        grantRoomAnalyticsPermission();
        ensureQcUser();
        cleanBusinessTables();
        insertPatient();
        for (int i = 1; i <= 8; i++) {
            insertApplication(String.format("63000000-0000-4000-8000-00000000000%d", i),
                    "SYNTH-AN-2026-%04d".formatted(i));
        }
        // OR-01 12-01 首台：计划 08:00，实际(updated_at) 08:10 → 准点。
        insertSchedule("83000000-0000-4000-8000-000000000001", 1, OR_01,
                "2026-12-01T08:00:00+08:00", "2026-12-01T10:00:00+08:00", 1,
                "IN_PROGRESS", "2026-12-01T08:10:00+08:00");
        // OR-01 12-01 第二台：90 分钟，非首台。
        insertSchedule("83000000-0000-4000-8000-000000000002", 2, OR_01,
                "2026-12-01T10:30:00+08:00", "2026-12-01T12:00:00+08:00", 2,
                "COMPLETED", "2026-12-01T10:45:00+08:00");
        // OR-02 12-01 首台：计划 08:30，实际 09:00 → 延误 30 分钟。
        insertSchedule("83000000-0000-4000-8000-000000000003", 3, OR_02,
                "2026-12-01T08:30:00+08:00", "2026-12-01T09:30:00+08:00", 1,
                "IN_PROGRESS", "2026-12-01T09:00:00+08:00");
        // OR-05 12-01 取消（患者原因）。
        insertSchedule("83000000-0000-4000-8000-000000000004", 4, OR_05,
                "2026-12-01T09:00:00+08:00", "2026-12-01T10:00:00+08:00", 1,
                "CANCELLED", "2026-12-01T09:00:00+08:00");
        insertStopChange("93000000-0000-4000-8000-000000000004", 4,
                "83000000-0000-4000-8000-000000000004", "PATIENT_CONDITION",
                "2026-12-01T08:55:00+08:00");
        // OR-01 12-02 未开始：不计入准点分母，但计入利用率与取消率分母。
        insertSchedule("83000000-0000-4000-8000-000000000005", 5, OR_01,
                "2026-12-02T08:00:00+08:00", "2026-12-02T09:00:00+08:00", 1,
                "PLANNED", "2026-12-02T08:00:00+08:00");
        // OR-02 12-02 23:30 跨日台次：区间内重叠 30 分钟；计划 23:30，实际 23:40 → 准点。
        insertSchedule("83000000-0000-4000-8000-000000000006", 6, OR_02,
                "2026-12-02T23:30:00+08:00", "2026-12-03T00:30:00+08:00", 1,
                "IN_PROGRESS", "2026-12-02T23:40:00+08:00");
        // OR-05 11-30 23:00 跨入 12-01：只统计区间重叠 60 分钟，不进入 12-01 首台。
        insertSchedule("83000000-0000-4000-8000-000000000007", 7, OR_05,
                "2026-11-30T23:00:00+08:00", "2026-12-01T01:00:00+08:00", 1,
                "COMPLETED", "2026-11-30T23:10:00+08:00");
        // OR-05 12-02 取消（急诊抢占）。
        insertSchedule("83000000-0000-4000-8000-000000000008", 8, OR_05,
                "2026-12-02T09:00:00+08:00", "2026-12-02T10:00:00+08:00", 1,
                "CANCELLED", "2026-12-02T08:55:00+08:00");
        insertStopChange("93000000-0000-4000-8000-000000000008", 8,
                "83000000-0000-4000-8000-000000000008", "EMERGENCY_PREEMPT",
                "2026-12-02T08:50:00+08:00");
    }

    @AfterEach
    void cleanUp() {
        cleanBusinessTables();
    }

    @Test
    void utilizationMatchesHandCalculatedValuesIncludingCrossDayOverlap() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        JsonNode body = getJson("/v1/scheduling/analytics/utilization", admin, FROM, TO, null);

        // 手算：s1 120 + s2 90 + s3 60 + s5 60 + s6 30(跨日裁剪) + s7 60(跨日裁剪) = 420；
        // 5 术间 × 2 天 × 600 分钟 = 6000；取消台次 s4/s8 不计入分子。
        assertThat(body.at("/summary/totalSurgeryMinutes").asInt()).isEqualTo(420);
        assertThat(body.at("/summary/availableMinutes").asInt()).isEqualTo(6000);
        assertThat(body.at("/summary/caseCount").asInt()).isEqualTo(6);
        assertThat(body.at("/summary/utilizationRate").asDouble()).isCloseTo(0.07,
                org.assertj.core.data.Offset.offset(0.0001));
        assertThat(body.at("/days").asInt()).isEqualTo(2);
        assertThat(body.at("/roomCount").asInt()).isEqualTo(5);
        assertThat(room(body, "OR-01").at("/totalSurgeryMinutes").asInt()).isEqualTo(270);
        assertThat(room(body, "OR-01").at("/utilizationRate").asDouble())
                .isCloseTo(0.225, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(room(body, "OR-02").at("/totalSurgeryMinutes").asInt()).isEqualTo(90);
        assertThat(room(body, "OR-05").at("/totalSurgeryMinutes").asInt()).isEqualTo(60);
        assertThat(room(body, "OR-03").at("/totalSurgeryMinutes").asInt()).isZero();
        System.out.printf("[M2-M6-01][utilization] total=%d available=%d rate=%.4f rooms=%d%n",
                body.at("/summary/totalSurgeryMinutes").asInt(),
                body.at("/summary/availableMinutes").asInt(),
                body.at("/summary/utilizationRate").asDouble(), body.at("/rooms").size());
    }

    @Test
    void onTimeRateUsesPlannedFirstCaseAndExcludesCancelledOrNotStartedDays() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        JsonNode body = getJson("/v1/scheduling/analytics/on-time", admin, FROM, TO, null);

        // Q-2 手算（计划首台口径，不看状态）：
        // OR-01 12-01 s1 已评估准点；OR-01 12-02 s5 未开始 → 排除；
        // OR-02 12-01 s3 已评估延误；OR-02 12-02 s6 已评估准点；
        // OR-05 12-01 s4 取消 → 排除；OR-05 12-02 s8 取消 → 排除；
        // s7 计划开始于 11-30，不属于区间内任何本地日。
        // firstCaseCount = 6 = evaluated 3 + excludedDays 3。
        assertThat(body.at("/thresholdMinutes").asInt()).isEqualTo(15);
        assertThat(body.at("/actualStartSource").asText()).isEqualTo("SCHEDULE_UPDATED_AT");
        assertThat(body.at("/summary/firstCaseCount").asInt()).isEqualTo(6);
        assertThat(body.at("/summary/evaluatedCaseCount").asInt()).isEqualTo(3);
        assertThat(body.at("/summary/excludedDays").asInt()).isEqualTo(3);
        assertThat(body.at("/summary/onTimeCount").asInt()).isEqualTo(2);
        assertThat(body.at("/summary/delayedCount").asInt()).isEqualTo(1);
        assertThat(body.at("/summary/onTimeRate").asDouble())
                .isCloseTo(0.6667, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(room(body, "OR-01").at("/firstCaseCount").asInt()).isEqualTo(2);
        assertThat(room(body, "OR-01").at("/evaluatedCaseCount").asInt()).isEqualTo(1);
        assertThat(room(body, "OR-01").at("/excludedDays").asInt()).isEqualTo(1);
        assertThat(room(body, "OR-01").at("/onTimeRate").asDouble())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(room(body, "OR-02").at("/onTimeCount").asInt()).isEqualTo(1);
        assertThat(room(body, "OR-02").at("/evaluatedCaseCount").asInt()).isEqualTo(2);
        assertThat(room(body, "OR-02").at("/excludedDays").asInt()).isZero();
        assertThat(room(body, "OR-02").at("/onTimeRate").asDouble())
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(room(body, "OR-05").at("/evaluatedCaseCount").asInt()).isZero();
        assertThat(room(body, "OR-05").at("/excludedDays").asInt()).isEqualTo(2);
        assertThat(room(body, "OR-05").at("/onTimeRate").asDouble()).isZero();
        System.out.printf("[M2-M6-01][on-time] first=%d evaluated=%d excluded=%d onTime=%d "
                        + "rate=%.4f；取消/未开始计划首台按日排除%n",
                body.at("/summary/firstCaseCount").asInt(),
                body.at("/summary/evaluatedCaseCount").asInt(),
                body.at("/summary/excludedDays").asInt(),
                body.at("/summary/onTimeCount").asInt(),
                body.at("/summary/onTimeRate").asDouble());
    }

    @Test
    void cancellationRateGroupsByReasonCode() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        JsonNode body = getJson("/v1/scheduling/analytics/cancellations", admin, FROM, TO, null);

        // 手算：区间内开始台次 7（s1/s2/s3/s4/s5/s6/s8）；取消 2（s4/s8）；
        // s7 开始于 11-30 不计入取消率分母。
        assertThat(body.at("/summary/totalCases").asInt()).isEqualTo(7);
        assertThat(body.at("/summary/cancelledCases").asInt()).isEqualTo(2);
        assertThat(body.at("/summary/cancellationRate").asDouble())
                .isCloseTo(0.2857, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(body.at("/byReason").size()).isEqualTo(2);
        assertThat(reasonCount(body, "PATIENT_CONDITION")).isEqualTo(1);
        assertThat(reasonCount(body, "EMERGENCY_PREEMPT")).isEqualTo(1);
        assertThat(roomInByRoom(body, "OR-05").at("/totalCases").asInt()).isEqualTo(2);
        assertThat(roomInByRoom(body, "OR-05").at("/cancelledCases").asInt()).isEqualTo(2);
        assertThat(roomInByRoom(body, "OR-05").at("/cancellationRate").asDouble())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(roomInByRoom(body, "OR-01").at("/cancelledCases").asInt()).isZero();
        System.out.printf("[M2-M6-01][cancellations] total=%d cancelled=%d rate=%.4f reasons=%s%n",
                body.at("/summary/totalCases").asInt(),
                body.at("/summary/cancelledCases").asInt(),
                body.at("/summary/cancellationRate").asDouble(), body.at("/byReason"));
    }

    @Test
    void deptAndRoomScopesRestrictAnalyticsAndUnboundIsForbidden() throws Exception {
        String dept = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        JsonNode deptBody = getJson("/v1/scheduling/analytics/utilization", dept, FROM, TO, null);
        // DEPT 只可见麻醉科 4 间：270 + 90 = 360 分钟；4 × 2 × 600 = 4800。
        assertThat(deptBody.at("/roomCount").asInt()).isEqualTo(4);
        assertThat(deptBody.at("/summary/totalSurgeryMinutes").asInt()).isEqualTo(360);
        assertThat(deptBody.at("/summary/availableMinutes").asInt()).isEqualTo(4800);
        assertThat(rooms(body(deptBody))).doesNotContain("OR-05");
        JsonNode deptCancel = getJson("/v1/scheduling/analytics/cancellations", dept, FROM, TO, null);
        assertThat(deptCancel.at("/summary/totalCases").asInt()).isEqualTo(5);
        assertThat(deptCancel.at("/summary/cancelledCases").asInt()).isZero();

        // ROOM 只可见绑定术间 OR-01。
        String room = login(SyntheticAccounts.ROOM_USERNAME, SyntheticAccounts.ROOM_PASSWORD);
        JsonNode roomBody = getJson("/v1/scheduling/analytics/utilization", room, FROM, TO, null);
        assertThat(roomBody.at("/roomCount").asInt()).isEqualTo(1);
        assertThat(room(body(roomBody), "OR-01").at("/totalSurgeryMinutes").asInt()).isEqualTo(270);

        // DEPT 用户请求手术室科室（不在范围内）→ 403。
        mockMvc.perform(get("/v1/scheduling/analytics/utilization")
                        .header("Authorization", "Bearer " + dept)
                        .param("from", FROM).param("to", TO).param("deptId", OR_DEPT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        // 未绑定组织/术间的 SYS_OR_MANAGER（有权限但无数据范围）→ 403。
        String unbound = login(SyntheticAccounts.UNBOUND_USERNAME,
                SyntheticAccounts.UNBOUND_PASSWORD);
        mockMvc.perform(get("/v1/scheduling/analytics/on-time")
                        .header("Authorization", "Bearer " + unbound)
                        .param("from", FROM).param("to", TO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        // 无 m1:analytics:read 的外科医师 → 403（权限点而非数据范围）。
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/scheduling/analytics/cancellations")
                        .header("Authorization", "Bearer " + surgeon)
                        .param("from", FROM).param("to", TO))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
        System.out.printf("[M2-M6-01][scope] DEPT rooms=%d minutes=%d；ROOM rooms=%d；"
                        + "跨科室/未绑定/无权限均 403%n", deptBody.at("/roomCount").asInt(),
                deptBody.at("/summary/totalSurgeryMinutes").asInt(),
                roomBody.at("/roomCount").asInt());
    }

    @Test
    void qcManagerHasReadPermissionAndAllScope() throws Exception {
        String qc = login(QC_USERNAME, QC_PASSWORD);
        JsonNode body = getJson("/v1/scheduling/analytics/utilization", qc, FROM, TO, null);
        assertThat(body.at("/roomCount").asInt()).isEqualTo(5);
        assertThat(jdbcTemplate.queryForList("""
                SELECT r.code FROM m0_platform.sys_permission p
                JOIN m0_platform.sys_role_permission rp ON rp.permission_id = p.id
                JOIN m0_platform.sys_role r ON r.id = rp.role_id
                WHERE p.code = 'm1:analytics:read' ORDER BY r.code
                """, String.class)).contains("SYS_ADMIN", "SYS_OR_MANAGER", "SYS_QC_MANAGER");
        System.out.printf("[M2-M6-01][permission] m1:analytics:read → SYS_ADMIN/SYS_OR_MANAGER/"
                + "SYS_QC_MANAGER；QC 可见 %d 间%n", body.at("/roomCount").asInt());
    }

    @Test
    void invalidParametersReturn400() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        mockMvc.perform(get("/v1/scheduling/analytics/utilization")
                        .header("Authorization", "Bearer " + admin).param("to", TO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(get("/v1/scheduling/analytics/utilization")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", "2026-04-03"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(get("/v1/scheduling/analytics/on-time")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", TO).param("deptId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(get("/v1/scheduling/analytics/on-time")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", "2026-13-01").param("to", TO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(get("/v1/scheduling/analytics/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("metric", "unknown").param("from", FROM).param("to", TO)
                        .param("format", "csv"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(get("/v1/scheduling/analytics/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("metric", "utilization").param("from", FROM).param("to", TO)
                        .param("format", "xlsx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        mockMvc.perform(get("/v1/scheduling/analytics/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("from", FROM).param("to", TO).param("format", "csv"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_INVALID"));
        System.out.printf("[M2-M6-01][validation] 缺 from/to、跨度>92、非法 deptId/metric/format 均 400%n");
    }

    @Test
    void emptyDatasetReturnsZeroWithoutDivisionError() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        JsonNode utilization = getJson("/v1/scheduling/analytics/utilization", admin,
                "2030-01-01", "2030-01-02", null);
        assertThat(utilization.at("/summary/totalSurgeryMinutes").asInt()).isZero();
        assertThat(utilization.at("/summary/utilizationRate").asDouble()).isZero();
        assertThat(utilization.at("/summary/caseCount").asInt()).isZero();
        JsonNode onTime = getJson("/v1/scheduling/analytics/on-time", admin,
                "2030-01-01", "2030-01-02", null);
        assertThat(onTime.at("/summary/firstCaseCount").asInt()).isZero();
        assertThat(onTime.at("/summary/evaluatedCaseCount").asInt()).isZero();
        assertThat(onTime.at("/summary/excludedDays").asInt()).isZero();
        assertThat(onTime.at("/summary/onTimeRate").asDouble()).isZero();
        JsonNode cancellations = getJson("/v1/scheduling/analytics/cancellations", admin,
                "2030-01-01", "2030-01-02", null);
        assertThat(cancellations.at("/summary/totalCases").asInt()).isZero();
        assertThat(cancellations.at("/summary/cancellationRate").asDouble()).isZero();
        System.out.printf("[M2-M6-01][empty] utilizationRate=0 onTimeRate=0 cancellationRate=0%n");
    }

    @Test
    void timezoneBoundaryAt2359IsAttributedToLocalDate() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        insertApplication("63000000-0000-4000-8000-000000000009", "SYNTH-AN-2026-0009");
        // 23:59 开始、跨入次日 00:04 才更新：按 Asia/Shanghai 归入 12-10 首台，准点 5 分钟。
        insertSchedule("83000000-0000-4000-8000-000000000009", 9, "f2000000-0000-4000-8000-0000000000c3",
                "2026-12-10T23:59:00+08:00", "2026-12-11T00:59:00+08:00", 1,
                "IN_PROGRESS", "2026-12-11T00:04:00+08:00");

        JsonNode localDay = getJson("/v1/scheduling/analytics/on-time", admin,
                "2026-12-10", "2026-12-10", null);
        assertThat(localDay.at("/summary/firstCaseCount").asInt()).isEqualTo(1);
        assertThat(localDay.at("/summary/excludedDays").asInt()).isZero();
        assertThat(localDay.at("/summary/onTimeCount").asInt()).isEqualTo(1);
        assertThat(localDay.at("/summary/onTimeRate").asDouble()).isEqualTo(1.0);

        JsonNode nextDay = getJson("/v1/scheduling/analytics/on-time", admin,
                "2026-12-11", "2026-12-11", null);
        assertThat(nextDay.at("/summary/firstCaseCount").asInt()).isZero();
        assertThat(nextDay.at("/timezone").asText()).isEqualTo("Asia/Shanghai");
        System.out.printf("[M2-M6-01][timezone] 23:59 归本地日 12-10 首台，次日不重复；"
                + "timezone=Asia/Shanghai%n");
    }

    @Test
    void csvExportIsBomPrefixedAggregateOnlyAndHasNoPhi() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        MvcResult utilization = mockMvc.perform(get("/v1/scheduling/analytics/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("metric", "utilization").param("from", FROM).param("to", TO)
                        .param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();
        byte[] bytes = utilization.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        List<String> lines = csv.lines().toList();
        // 表头 + 汇总 + 5 术间 = 7 行。
        assertThat(lines).hasSize(7);
        assertThat(lines.get(0).split(",", -1)).hasSize(19);
        assertThat(lines.get(0)).contains("metric,from,to,scope,roomNo,deptName,reasonCode")
                .contains("totalSurgeryMinutes", "availableMinutes", "utilizationRate")
                .contains("cancellationRate", "shareOfCancelled");
        assertThat(lines.get(1)).contains("utilization,2026-12-01,2026-12-02,SUMMARY,ALL,ALL,")
                .contains(",420,6000,0.0700,");
        assertThat(csv).doesNotContain("演示患者", "SYNTH-PAT", "SYNTH-AN-2026",
                "patientName", "inpatientNo", "住院号", "诊断", "证件号");

        MvcResult cancellations = mockMvc.perform(get("/v1/scheduling/analytics/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("metric", "cancellations").param("from", FROM).param("to", TO)
                        .param("format", "csv"))
                .andExpect(status().isOk())
                .andReturn();
        String cancellationCsv = new String(cancellations.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        // 表头 + 汇总 + 5 术间 + 2 原因码 = 9 行。
        assertThat(cancellationCsv.lines().toList()).hasSize(9);
        assertThat(cancellationCsv).contains("REASON", "PATIENT_CONDITION", "EMERGENCY_PREEMPT")
                .doesNotContain("演示患者", "SYNTH-PAT", "SYNTH-AN-2026");

        MvcResult onTime = mockMvc.perform(get("/v1/scheduling/analytics/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("metric", "on-time").param("from", FROM).param("to", TO)
                        .param("format", "csv"))
                .andExpect(status().isOk())
                .andReturn();
        String onTimeCsv = new String(onTime.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(onTimeCsv.lines().toList()).hasSize(7);
        assertThat(onTimeCsv).contains("firstCaseCount", "evaluatedCaseCount", "onTimeRate")
                .doesNotContain("演示患者", "SYNTH-PAT");
        System.out.printf("[M2-M6-01][csv] BOM=EFBBBF utilizationRows=%d cancellationRows=%d "
                        + "onTimeRows=%d PHI=none%n", lines.size(),
                cancellationCsv.lines().count(), onTimeCsv.lines().count());
    }

    @Test
    void queryAndExportWriteAuditWithoutPhi() throws Exception {
        Instant startedAt = Instant.now();
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        getJson("/v1/scheduling/analytics/utilization", admin, FROM, TO, null);
        getJson("/v1/scheduling/analytics/on-time", admin, FROM, TO, null);
        getJson("/v1/scheduling/analytics/cancellations", admin, FROM, TO, null);
        mockMvc.perform(get("/v1/scheduling/analytics/export")
                        .header("Authorization", "Bearer " + admin)
                        .param("metric", "utilization").param("from", FROM).param("to", TO)
                        .param("format", "csv"))
                .andExpect(status().isOk());

        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, resource_id, detail::text AS detail
                FROM m0_platform.sys_audit_log
                WHERE action LIKE 'ANALYTICS_%' AND occurred_at >= ?
                ORDER BY occurred_at
                """, Timestamp.from(startedAt));
        List<String> actions = new ArrayList<>();
        for (Map<String, Object> audit : audits) {
            actions.add((String) audit.get("action"));
            String detail = (String) audit.get("detail");
            assertThat(detail).doesNotContain("演示患者", "SYNTH-PAT", "SYNTH-AN-2026",
                    "patientName", "inpatientNo", "住院号", "诊断", "证件号");
            assertThat(detail).contains("metric", "from", "to", "timezone", "roomCount");
        }
        assertThat(actions).contains("ANALYTICS_QUERY", "ANALYTICS_EXPORT");
        assertThat(actions.stream().filter("ANALYTICS_QUERY"::equals).count()).isGreaterThanOrEqualTo(3);
        System.out.printf("[M2-M6-01][audit] actions=%s detail=metric/from/to/timezone/roomCount，"
                + "无 PHI%n", actions);
    }

    @Test
    void ninetyTwoDayRangeWithThousandsOfCasesStaysUnderLocalBaseline() throws Exception {
        seedPerformanceDataset();
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);

        long startedAt = System.nanoTime();
        JsonNode utilization = getJson("/v1/scheduling/analytics/utilization", admin,
                "2027-01-01", "2027-04-02", null);
        JsonNode onTime = getJson("/v1/scheduling/analytics/on-time", admin,
                "2027-01-01", "2027-04-02", null);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(utilization.at("/days").asInt()).isEqualTo(92);
        assertThat(utilization.at("/summary/caseCount").asInt()).isEqualTo(3000);
        // 5 术间 × 92 日，每术间-日至少一台 → 计划首台 460 天，全部 COMPLETED 准点。
        assertThat(onTime.at("/summary/firstCaseCount").asInt()).isEqualTo(460);
        assertThat(onTime.at("/summary/evaluatedCaseCount").asInt()).isEqualTo(460);
        assertThat(onTime.at("/summary/excludedDays").asInt()).isZero();
        assertThat(onTime.at("/summary/onTimeRate").asDouble()).isEqualTo(1.0);
        // 本机基准（嵌入式 PG16 + MockMvc，含 JSON 序列化）：<10s；非生产容量承诺。
        assertThat(elapsedMs).isLessThan(10_000L);
        System.out.printf("[M2-M6-01][perf] 92 天 3000 台次 utilization+on-time=%d ms"
                + "（本机基线 <10s，非生产容量承诺）%n", elapsedMs);
    }

    // ---------------------------------------------------------------------
    // 固定 SYNTHETIC 夹具
    // ---------------------------------------------------------------------

    /**
     * 大区间性能夹具：3000 个 SYNTHETIC 申请与台次，覆盖 92 天 × 5 术间；全部 COMPLETED
     * 且 updated_at=start+10 分钟。仅用于本机性能回归，不含任何真实患者数据。
     */
    private void seedPerformanceDataset() {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, urgency,
                     planned_duration_min, dept_name, dept_id, site_id, surgeon_name,
                     preop_diagnosis, status, submitted_at, source, created_at, updated_at,
                     version, deleted)
                SELECT md5('perf-app-' || i)::uuid, 'SYNTH-PERF-' || lpad(i::text, 4, '0'),
                       ?::uuid, '演示术式（合成）', 'ELECTIVE', 60, '麻醉科（合成）', ?::uuid,
                       'f1000000-0000-4000-8000-000000000001'::uuid, '演示术者（合成）',
                       '演示诊断（合成）', 'SCHEDULED', now(), 'MANUAL', now(), now(), 1, false
                FROM generate_series(1, 3000) AS i
                """, PATIENT_ID, ANES_DEPT);
        jdbcTemplate.update("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority,
                     status, created_at, created_by, updated_at, updated_by, version, deleted)
                SELECT md5('perf-sch-' || i)::uuid, md5('perf-app-' || i)::uuid,
                       (ARRAY[?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid])[(i % 5) + 1],
                       TIMESTAMPTZ '2027-01-01 08:00:00+08'
                           + ((i % 92) * INTERVAL '1 day') + ((i % 6) * INTERVAL '1 hour'),
                       TIMESTAMPTZ '2027-01-01 08:00:00+08'
                           + ((i % 92) * INTERVAL '1 day') + ((i % 6) * INTERVAL '1 hour')
                           + INTERVAL '45 minutes',
                       (i % 6) + 1, 0, 'COMPLETED', now(), ?::uuid,
                       TIMESTAMPTZ '2027-01-01 08:00:00+08'
                           + ((i % 92) * INTERVAL '1 day') + ((i % 6) * INTERVAL '1 hour')
                           + INTERVAL '10 minutes',
                       ?::uuid, 1, false
                FROM generate_series(1, 3000) AS i
                """, OR_01, OR_02, OR_05,
                "f2000000-0000-4000-8000-0000000000c3",
                "f2000000-0000-4000-8000-0000000000c4",
                SyntheticAccounts.DEMO_ADMIN_ID, SyntheticAccounts.DEMO_ADMIN_ID);
    }

    private void cleanBusinessTables() {
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
    }

    private void insertPatient() {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name, dept_id, site_id, ward, bed_no,
                     created_at, updated_at, version, deleted)
                VALUES (?::uuid, 'SYNTH-PAT-0001', '演示患者甲（合成）', 'FEMALE',
                        DATE '1978-03-15', 48, 'SYNT****9101', 'hash-synth-9101', '麻醉科（合成）',
                        ?::uuid, 'f1000000-0000-4000-8000-000000000001'::uuid, '演示病区', '12',
                        now(), now(), 1, false)
                """, PATIENT_ID, ANES_DEPT);
    }

    private void insertApplication(String applicationId, String hisApplicationNo) {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, urgency,
                     planned_duration_min, dept_name, dept_id, site_id, surgeon_name,
                     preop_diagnosis, status, submitted_at, source, created_at, updated_at,
                     version, deleted)
                VALUES (?::uuid, ?, ?::uuid, '演示术式（合成）', 'ELECTIVE', 60,
                        '麻醉科（合成）', ?::uuid, 'f1000000-0000-4000-8000-000000000001'::uuid,
                        '演示术者（合成）', '演示诊断（合成）', 'SCHEDULED', now(), 'MANUAL',
                        now(), now(), 1, false)
                """, applicationId, hisApplicationNo, PATIENT_ID, ANES_DEPT);
    }

    private void insertSchedule(String scheduleId, int applicationNo, String roomId,
            String startAt, String endAt, int sequence, String status, String updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.schedule
                    (id, application_id, room_id, start_at, end_at, sequence, priority,
                     status, reason, created_at, created_by, updated_at, updated_by, version,
                     deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?::timestamptz, ?::timestamptz, ?, 0,
                        ?, NULL, ?::timestamptz, ?::uuid, ?::timestamptz, ?::uuid, 1, false)
                """, scheduleId,
                String.format("63000000-0000-4000-8000-00000000000%d", applicationNo),
                roomId, startAt, endAt, sequence, status, startAt,
                SyntheticAccounts.DEMO_ADMIN_ID, updatedAt, SyntheticAccounts.DEMO_ADMIN_ID);
    }

    private void insertStopChange(String changeId, int applicationNo, String scheduleId,
            String reasonCode, String requestedAt) {
        jdbcTemplate.update("""
                INSERT INTO m1_sched.schedule_change_request
                    (id, schedule_id, application_id, change_type, reason_code, reason,
                     requested_by, requested_at, approved_by, approved_at, status,
                     preempt_schedule_id, created_at, created_by, updated_at, updated_by,
                     version, deleted)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'STOP', ?, '演示停台（合成）',
                        ?::uuid, ?::timestamptz, ?::uuid, ?::timestamptz, 'APPROVED',
                        NULL, ?::timestamptz, ?::uuid, ?::timestamptz, ?::uuid, 1, false)
                """, changeId, scheduleId,
                String.format("63000000-0000-4000-8000-00000000000%d", applicationNo),
                reasonCode, SyntheticAccounts.DEMO_ADMIN_ID, requestedAt,
                SyntheticAccounts.DEMO_ADMIN_ID, requestedAt, requestedAt,
                SyntheticAccounts.DEMO_ADMIN_ID, requestedAt, SyntheticAccounts.DEMO_ADMIN_ID);
    }

    private void grantRoomAnalyticsPermission() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, SyntheticAccounts.ROOM_ROLE_ID, ANALYTICS_PERMISSION);
    }

    private void ensureQcUser() {
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试质控专员（合成）', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, QC_USER_ID, QC_USERNAME, new BCryptPasswordEncoder().encode(QC_PASSWORD));
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, 'a0000000-0000-4000-8000-000000000006') ON CONFLICT DO NOTHING
                """, QC_USER_ID);
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

    private JsonNode getJson(String path, String token, String from, String to, String deptId)
            throws Exception {
        var request = get(path).header("Authorization", "Bearer " + token)
                .param("from", from).param("to", to);
        if (deptId != null) {
            request = request.param("deptId", deptId);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode body(JsonNode report) {
        return report;
    }

    private static List<String> rooms(JsonNode report) {
        List<String> roomNos = new ArrayList<>();
        for (JsonNode room : report.at("/rooms")) {
            roomNos.add(room.at("/roomNo").asText());
        }
        return roomNos;
    }

    private static JsonNode room(JsonNode report, String roomNo) {
        for (JsonNode room : report.path("rooms")) {
            if (roomNo.equals(room.path("roomNo").asText())) {
                return room;
            }
        }
        return report.path("byRoom");
    }

    private static JsonNode roomInByRoom(JsonNode report, String roomNo) {
        for (JsonNode room : report.path("byRoom")) {
            if (roomNo.equals(room.path("roomNo").asText())) {
                return room;
            }
        }
        return report.path("byRoom");
    }

    private static int reasonCount(JsonNode report, String reasonCode) {
        for (JsonNode reason : report.path("byReason")) {
            if (reasonCode.equals(reason.path("reasonCode").asText())) {
                return reason.path("cancelledCases").asInt();
            }
        }
        return 0;
    }
}
