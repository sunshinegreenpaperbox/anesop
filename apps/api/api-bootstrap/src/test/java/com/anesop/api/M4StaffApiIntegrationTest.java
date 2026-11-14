package com.anesop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
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
import java.time.ZoneId;
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
 * 人员档案、分级授权、排班与培训（M4-M13-01）端到端用例：MockMvc + 真实嵌入式 PostgreSQL 16。
 *
 * <p>覆盖：档案工号唯一（409）/乐观锁（409）/权限 403/数据范围（DEPT 本科室、SELF 本人只读、
 * 越界 404）；分级授权 append-only 版本链（授予/复评/撤销）与授权校验矩阵（允许、术式超范围、
 * 年龄超范围、ASA 超上限、未生效、过期、已撤销、离职）；排班同人员/同术间冲突（409 + details）
 * 与取消后释放名额；培训到期提醒与状态；审计（STAFF、AUTH、SCHEDULE、TRAINING 系列动作）
 * 不含姓名与证书号；V21 表/视图/权限/触发器。全部数据为 SYNTHETIC。
 */
class M4StaffApiIntegrationTest extends AbstractApiIntegrationTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String DEPT = SyntheticAccounts.DEMO_ANES_ORG_ID;
    private static final String OTHER_DEPT = SyntheticAccounts.DEMO_OR_ORG_ID;
    private static final String APPROVER = SyntheticAccounts.APPROVER_1_ID;
    private static final String SECOND_APPROVER = SyntheticAccounts.APPROVER_2_ID;
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Shanghai"));

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
        SyntheticAccounts.ensureStaffGovernanceAccounts(jdbcTemplate);
        SyntheticAccounts.ensureRoomUser(jdbcTemplate);
    }

    @Test
    void staffProfileCrudPermissionsAndDataScope() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String staffNo = "STAFF-ANES-T" + suffix;
        String staffId = createStaff(admin, staffBody(staffNo, DEPT, null));
        String selfStaffId = ensureStaff(admin, "STAFF-SELF-T" + suffix, DEPT,
                SyntheticAccounts.DEMO_SELF_ID);
        String orStaffId = ensureStaff(admin, "STAFF-OR-T" + suffix, OTHER_DEPT, null);

        // 详情、工号重复 409、乐观锁更新与过期 409。
        mockMvc.perform(get("/v1/staff/" + staffId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffNo").value(staffNo))
                .andExpect(jsonPath("$.departmentName").value("麻醉科（合成）"))
                .andExpect(jsonPath("$.employmentStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(staffBody(staffNo, DEPT, null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("name", "演示麻醉医师甲（合成）");
        update.put("departmentId", DEPT);
        update.put("title", "副主任医师");
        update.put("jobGrade", "副高");
        update.put("employmentStatus", "ACTIVE");
        update.put("specialties", List.of("心血管麻醉"));
        update.put("version", 1);
        mockMvc.perform(put("/v1/staff/" + staffId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("副主任医师"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(put("/v1/staff/" + staffId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isConflict());

        // 无权限角色 403（外科医师无 m4:staff:*）。
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/staff").header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        // DEPT 范围：只看本科室；越界单对象 404；SYS_AUTH_MANAGER 可写本科室、跨科室 403。
        String deptUser = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        mockMvc.perform(get("/v1/staff")
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].staffId", hasItem(staffId)))
                .andExpect(jsonPath("$.items[*].staffId",
                        org.hamcrest.Matchers.not(hasItem(orStaffId))));
        mockMvc.perform(get("/v1/staff/" + orStaffId)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/staff")
                        .param("departmentId", OTHER_DEPT)
                        .header("Authorization", "Bearer " + deptUser))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + deptUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                staffBody("STAFF-DEPT-OK-T" + suffix, DEPT, null))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + deptUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                staffBody("STAFF-DENY-T" + suffix, OTHER_DEPT, null))))
                .andExpect(status().isForbidden());

        // SELF 范围：仅本人档案；写操作 403。
        String selfUser = login(SyntheticAccounts.DEMO_SELF_USERNAME,
                SyntheticAccounts.DEMO_SELF_PASSWORD);
        mockMvc.perform(get("/v1/staff").header("Authorization", "Bearer " + selfUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].staffId", containsInAnyOrder(selfStaffId)));
        mockMvc.perform(get("/v1/staff/" + selfStaffId)
                        .header("Authorization", "Bearer " + selfUser))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/staff/" + staffId)
                        .header("Authorization", "Bearer " + selfUser))
                .andExpect(status().isNotFound());
        Map<String, Object> selfUpdate = new LinkedHashMap<>(update);
        selfUpdate.put("version", 1);
        mockMvc.perform(put("/v1/staff/" + selfStaffId)
                        .header("Authorization", "Bearer " + selfUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(selfUpdate)))
                .andExpect(status().isForbidden());

        // 科室不存在/非科室 422。
        Map<String, Object> badDept = staffBody("STAFF-BAD-T" + suffix,
                UUID.randomUUID().toString(), null);
        mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(badDept)))
                .andExpect(status().isUnprocessableEntity());

        // 审计：STAFF_CREATE/STAFF_UPDATE 存在且不含姓名。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'STAFF' AND resource_id = ? AND result = 1
                 ORDER BY occurred_at
                """, staffId);
        assertThat(audits).extracting(row -> row.get("action"))
                .contains("STAFF_CREATE", "STAFF_UPDATE");
        assertThat(audits).allMatch(row ->
                !String.valueOf(row.get("detail")).contains("演示麻醉医师甲"));
    }

    @Test
    void authorizationLifecycleIsAppendOnlyAndCheckMatrix() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String staffId = createStaff(admin, staffBody("STAFF-AUTH-T" + suffix, DEPT, null));

        // 首次授予（version 0 → 1）。
        mockMvc.perform(put("/v1/staff/" + staffId + "/authorizations")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(grant(0, "R3",
                                List.of("GENERAL"), 18, 60, "ASA2",
                                TODAY.minusDays(1).toString(), TODAY.plusDays(365).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.version").value(1))
                .andExpect(jsonPath("$.current.status").value("ACTIVE"))
                .andExpect(jsonPath("$.history", org.hamcrest.Matchers.hasSize(1)));

        // 允许：术式在范围内、年龄与 ASA 在上限内。
        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(check(staffId, "GENERAL", 40,
                                "ASA2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reasons", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.level").value("R3"));

        // 拒绝：术式超范围 + 年龄超范围 + ASA 超上限，多原因一次返回。
        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(check(staffId, "CARDIAC", 72,
                                "ASA4"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reasons[*].code", containsInAnyOrder(
                        "SURGERY_SCOPE_MISSING", "AGE_OUT_OF_RANGE", "ASA_EXCEEDS_LIMIT")));

        // 复评：改成已过期有效期（version 1 → 2），校验返回 EXPIRED。
        mockMvc.perform(put("/v1/staff/" + staffId + "/authorizations")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(grant(1, "R2",
                                List.of("*"), 18, 90, "ASA3",
                                TODAY.minusDays(365).toString(),
                                TODAY.minusDays(1).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.version").value(2));
        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(check(staffId, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reasons[*].code", hasItem("EXPIRED")));

        // 撤销（version 2 → 3）：新版本 REVOKED，历史保留 3 条。
        Map<String, Object> revoke = new LinkedHashMap<>();
        revoke.put("action", "REVOKE");
        revoke.put("version", 2);
        revoke.put("approvedBy", APPROVER);
        revoke.put("secondApproverId", SECOND_APPROVER);
        revoke.put("secondApproverReason", "第二审批人复核撤销（SYNTHETIC）");
        revoke.put("changeReason", "年度复评未通过（SYNTHETIC）");
        mockMvc.perform(put("/v1/staff/" + staffId + "/authorizations")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(revoke)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.version").value(3))
                .andExpect(jsonPath("$.current.status").value("REVOKED"))
                .andExpect(jsonPath("$.history", org.hamcrest.Matchers.hasSize(3)));
        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(check(staffId, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasons[*].code", hasItem("AUTHORIZATION_REVOKED")));

        // 版本过期 409；重复撤销 409。
        mockMvc.perform(put("/v1/staff/" + staffId + "/authorizations")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(grant(1, "R3",
                                List.of("*"), 18, 90, "ASA3", TODAY.toString(),
                                TODAY.plusDays(30).toString()))))
                .andExpect(status().isConflict());
        revoke.put("version", 3);
        mockMvc.perform(put("/v1/staff/" + staffId + "/authorizations")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(revoke)))
                .andExpect(status().isConflict());

        // append-only：UPDATE/DELETE/TRUNCATE 一律 55000。
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "UPDATE m4_staff.staff_authorization SET level = 'R1' WHERE staff_id = ?::uuid",
                staffId)), "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.update(
                "DELETE FROM m4_staff.staff_authorization WHERE staff_id = ?::uuid", staffId)),
                "append-only");
        assertSqlState55000(catchThrowable(() -> jdbcTemplate.execute(
                "TRUNCATE m4_staff.staff_authorization")), "append-only");

        // 离职人员校验：STAFF_INACTIVE 且授权拒绝。
        java.util.Map<String, Object> deactivate = new LinkedHashMap<>();
        deactivate.put("name", "演示麻醉医师甲（合成）");
        deactivate.put("departmentId", DEPT);
        deactivate.put("title", "副主任医师");
        deactivate.put("employmentStatus", "INACTIVE");
        deactivate.put("version", 1);
        mockMvc.perform(put("/v1/staff/" + staffId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(deactivate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employmentStatus").value("INACTIVE"));
        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(check(staffId, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reasons[*].code", hasItem("STAFF_INACTIVE")));

        // 审计：AUTH_GRANT/AUTH_REVOKE 存在且不含姓名。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'STAFF_AUTHORIZATION' AND result = 1
                 ORDER BY occurred_at
                """);
        assertThat(audits).extracting(row -> row.get("action"))
                .contains("AUTH_GRANT", "AUTH_REVOKE");
        assertThat(audits).allMatch(row ->
                !String.valueOf(row.get("detail")).contains("演示麻醉医师甲"));
    }

    @Test
    void scheduleConflictsCancellationAndPermissions() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String staffA = createStaff(admin, staffBody("STAFF-SCH-A" + suffix, DEPT, null));
        String staffB = createStaff(admin, staffBody("STAFF-SCH-B" + suffix, DEPT, null));
        String room = UUID.randomUUID().toString();
        LocalDate date = TODAY.plusDays(20);

        String scheduleId = createSchedule(admin, staffA, date.toString(), "DAY", room);

        // 同人员同日同班次冲突（换术间）：details field=staffId。
        mockMvc.perform(post("/v1/staff/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(schedule(staffA,
                                date.toString(), "DAY", UUID.randomUUID().toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.details[0].field").value("staffId"))
                .andExpect(jsonPath("$.details[0].reason")
                        .value(org.hamcrest.Matchers.containsString("已有排班")));

        // 同术间同日同班次冲突（换人员）：details field=roomId。
        mockMvc.perform(post("/v1/staff/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(schedule(staffB,
                                date.toString(), "DAY", room))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0].field").value("roomId"));

        // 不同班次不冲突。
        createSchedule(admin, staffA, date.toString(), "NIGHT", null);

        // 更新为冲突班次 409；乐观锁过期 409。
        String otherScheduleId = createSchedule(admin, staffB, date.plusDays(1).toString(), "DAY",
                null);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("staffId", staffA);
        update.put("scheduleDate", date.toString());
        // 该人员当日已有夜班，再把白班改为夜班 → 人员同日同班次冲突（排除自身仍命中另一条）。
        update.put("shift", "NIGHT");
        update.put("departmentId", DEPT);
        update.put("roomId", UUID.randomUUID().toString());
        update.put("version", 1);
        mockMvc.perform(put("/v1/staff/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details[0].field").value("staffId"));
        // 把另一人员的排班改到该人员已有的白班 → 同样冲突。
        update.put("shift", "DAY");
        mockMvc.perform(put("/v1/staff/schedules/" + otherScheduleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isConflict());

        // 取消：状态 CANCELLED，重复取消 409，取消后释放同一班次名额。
        Map<String, Object> cancel = Map.of("reason", "人员临时调整（SYNTHETIC）", "version", 1);
        mockMvc.perform(post("/v1/staff/schedules/" + scheduleId + "/cancel")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(cancel)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("人员临时调整（SYNTHETIC）"))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/v1/staff/schedules/" + scheduleId + "/cancel")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(cancel)))
                .andExpect(status().isConflict());
        createSchedule(admin, staffA, date.toString(), "DAY", room);

        // 权限：SYS_OR_MANAGER（DEPT）可排班；麻醉医师无 schedule:manage 403；外科医师 403。
        String deptUser = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        createSchedule(deptUser, staffA, date.plusDays(5).toString(), "ON_CALL", null);
        String selfUser = login(SyntheticAccounts.DEMO_SELF_USERNAME,
                SyntheticAccounts.DEMO_SELF_PASSWORD);
        mockMvc.perform(post("/v1/staff/schedules")
                        .header("Authorization", "Bearer " + selfUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(schedule(staffA,
                                date.plusDays(6).toString(), "DAY", null))))
                .andExpect(status().isForbidden());
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/staff/schedules")
                        .header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden());

        // 查询按日期区间过滤。
        mockMvc.perform(get("/v1/staff/schedules")
                        .header("Authorization", "Bearer " + admin)
                        .param("staffId", staffA)
                        .param("from", date.toString())
                        .param("to", date.plusDays(6).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(greaterThanOrEqualTo(2))));

        // 审计：SCHEDULE_CREATE/SCHEDULE_CANCEL 存在且不含姓名。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT action, detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'STAFF_SCHEDULE' AND result = 1
                 ORDER BY occurred_at
                """);
        assertThat(audits).extracting(row -> row.get("action"))
                .contains("SCHEDULE_CREATE", "SCHEDULE_CANCEL");
        assertThat(audits).allMatch(row ->
                !String.valueOf(row.get("detail")).contains("演示麻醉医师甲"));
    }

    @Test
    void trainingExpiryReminderAndAudit() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String staffId = createStaff(admin, staffBody("STAFF-TRN-T" + suffix, DEPT, null));

        String expiredId = createTraining(admin, training(staffId, "TRN-EXP-" + suffix,
                TODAY.minusYears(2).toString(), TODAY.minusDays(10).toString()));
        createTraining(admin, training(staffId, "TRN-EXPIRING-" + suffix,
                TODAY.minusMonths(6).toString(), TODAY.plusDays(10).toString()));
        createTraining(admin, training(staffId, "TRN-VALID-" + suffix,
                TODAY.minusMonths(1).toString(), TODAY.plusDays(200).toString()));
        createTraining(admin, training(staffId, "TRN-NOEXP-" + suffix,
                TODAY.minusMonths(1).toString(), null));

        mockMvc.perform(get("/v1/staff/trainings")
                        .header("Authorization", "Bearer " + admin)
                        .param("staffId", staffId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(4)))
                .andExpect(jsonPath("$.items[*].status", containsInAnyOrder(
                        "EXPIRED", "EXPIRING", "VALID", "NO_EXPIRY")));

        // 到期提醒：含已过期与 30 天内到期，不含 200 天后到期；天数与基准日回传。
        mockMvc.perform(get("/v1/staff/trainings/expiring")
                        .header("Authorization", "Bearer " + admin)
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.asOf").value(TODAY.toString()))
                .andExpect(jsonPath("$.items[*].trainingId", hasItem(expiredId)))
                .andExpect(jsonPath("$.items[*].status", hasItem("EXPIRED")))
                .andExpect(jsonPath("$.items[*].status", hasItem("EXPIRING")))
                .andExpect(jsonPath("$.items[*].courseCode",
                        org.hamcrest.Matchers.not(hasItem("TRN-VALID-" + suffix))));

        // 同一人员同一课程编码重复 409；完成日期晚于今天 400。
        mockMvc.perform(post("/v1/staff/trainings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(training(staffId,
                                "TRN-EXP-" + suffix, TODAY.minusDays(5).toString(), null))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/v1/staff/trainings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(training(staffId,
                                "TRN-FUTURE-" + suffix, TODAY.plusDays(1).toString(), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("completedOn"));

        // 权限：麻醉医师可登记（training:manage），外科医师 403。
        String selfUser = login(SyntheticAccounts.DEMO_SELF_USERNAME,
                SyntheticAccounts.DEMO_SELF_PASSWORD);
        mockMvc.perform(post("/v1/staff/trainings")
                        .header("Authorization", "Bearer " + selfUser)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(training(staffId,
                                "TRN-DENY-" + suffix, TODAY.minusDays(5).toString(), null))))
                .andExpect(status().isForbidden());
        String surgeon = login(SyntheticAccounts.SURGEON_USERNAME,
                SyntheticAccounts.SURGEON_PASSWORD);
        mockMvc.perform(get("/v1/staff/trainings").header("Authorization", "Bearer " + surgeon))
                .andExpect(status().isForbidden());

        // 审计：TRAINING_RECORD 存在且不含证书号原文。
        List<Map<String, Object>> audits = jdbcTemplate.queryForList("""
                SELECT detail::text AS detail FROM m0_platform.sys_audit_log
                 WHERE resource_type = 'STAFF_TRAINING' AND result = 1
                """);
        assertThat(audits).isNotEmpty();
        assertThat(audits).allMatch(row ->
                !String.valueOf(row.get("detail")).contains("SYNTH-CERT-"));
    }

    @Test
    void authorizationApprovalGovernanceRejectsSelfFakeAndMissingSecondApprover() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String staffId = createStaff(admin, staffBody("STAFF-GOV-" + suffix, DEPT, null));
        String path = "/v1/staff/" + staffId + "/authorizations";

        // 伪造/不存在审批人 → 400。
        Map<String, Object> body = grant(0, "R3", List.of("GENERAL"), 18, 60, "ASA2",
                TODAY.minusDays(1).toString(), TODAY.plusDays(365).toString());
        body.put("approvedBy", UUID.randomUUID().toString());
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("approvedBy"));

        // 审批人 = 操作者本人 → 403。
        body.put("approvedBy", SyntheticAccounts.DEMO_ADMIN_ID);
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        // 审批人缺 m4:staff:authorize（麻醉医师只读）→ 403。
        body.put("approvedBy", SyntheticAccounts.DEMO_SELF_ID);
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());

        // R2 必须双人审批：缺第二审批人 400，与第一审批人相同 400。
        Map<String, Object> dual = grant(0, "R2", List.of("*"), 18, 90, "ASA3",
                TODAY.minusDays(1).toString(), TODAY.plusDays(365).toString());
        dual.remove("secondApproverId");
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(dual)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("secondApproverId"));
        dual.put("secondApproverId", APPROVER);
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(dual)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("secondApproverId"));

        // 双人审批完成：两名审批人不同，响应记录双方。
        dual.put("secondApproverId", SECOND_APPROVER);
        dual.put("secondApproverReason", "第二审批人复核（SYNTHETIC）");
        dual.put("approvedBy", APPROVER);
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(dual)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.version").value(1))
                .andExpect(jsonPath("$.current.approvedBy").value(APPROVER))
                .andExpect(jsonPath("$.current.secondApproverId").value(SECOND_APPROVER))
                .andExpect(jsonPath("$.current.secondApprovedAt").isNotEmpty());

        // 普通 R3（非 *、ASA3）单人审批成功。
        Map<String, Object> single = grant(1, "R3", List.of("GENERAL"), 18, 90, "ASA3",
                TODAY.minusDays(1).toString(), TODAY.plusDays(365).toString());
        single.remove("secondApproverId");
        single.remove("secondApproverReason");
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(single)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.version").value(2))
                .andExpect(jsonPath("$.current.secondApproverId")
                        .value(org.hamcrest.Matchers.nullValue()));

        // 禁止自授：被授权人员 userId = 操作者 → 403（即使审批人合法）。
        String selfStaffId = createStaff(admin, staffBody("STAFF-SELFGRANT-" + suffix, DEPT,
                SyntheticAccounts.DEMO_ADMIN_ID));
        Map<String, Object> selfGrant = grant(0, "R3", List.of("GENERAL"), 18, 90, "ASA3",
                TODAY.minusDays(1).toString(), TODAY.plusDays(365).toString());
        mockMvc.perform(put("/v1/staff/" + selfStaffId + "/authorizations")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(selfGrant)))
                .andExpect(status().isForbidden());
    }

    @Test
    void authorizationCheckRequiresExplicitInputAndRetrospectiveVersion() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String staffId = createStaff(admin, staffBody("STAFF-RETRO-" + suffix, DEPT, null));

        // 直接用 SQL 写入带历史审批时间的版本（API 写入的 approved_at 都是“现在”，
        // 无法构造“过去时点”的版本链；append-only 触发器允许 INSERT）。
        java.time.Instant now = java.time.Instant.now();
        insertAuthorization(staffId, 1, "R3", "[\"GENERAL\"]", "ASA3",
                TODAY.minusDays(10), TODAY.plusDays(365), "ACTIVE",
                now.minusSeconds(10 * 24 * 3600L), null);
        insertAuthorization(staffId, 2, "R4", "[\"CARDIAC\"]", "ASA3",
                TODAY.minusDays(1), TODAY.plusDays(365), "ACTIVE",
                now.minusSeconds(24 * 3600L), null);

        // 缺失 age/asa/surgeryTypeId 且未声明不适用 → INPUT_INCOMPLETE。
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("staffId", staffId);
        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(missing)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reasons[*].code", hasItem("INPUT_INCOMPLETE")));

        // 显式声明三个维度不适用 → 允许（证明不是简单放宽必填）。
        Map<String, Object> declared = new LinkedHashMap<>(missing);
        declared.put("notApplicable", List.of("SURGERY_TYPE", "AGE", "ASA"));
        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(declared)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));

        // 扩大为 R4 + CARDIAC 后查过去时点：仍按当时生效的 v1（R3 + GENERAL）。
        String beforeExpansion = TODAY.minusDays(5)
                .atStartOfDay(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
        Map<String, Object> pastCheck = check(staffId, "GENERAL", 50, "ASA2");
        pastCheck.put("at", beforeExpansion);
        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(pastCheck)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.level").value("R3"));

        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(check(staffId, "GENERAL", 50,
                                "ASA2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reasons[*].code", hasItem("SURGERY_SCOPE_MISSING")));

        // 撤销 v2（revoked_at=now-1s）：过去时点仍按当时 v2（撤销不追溯），当前返回 REVOKED。
        insertAuthorization(staffId, 3, "R4", "[\"CARDIAC\"]", "ASA3",
                TODAY.minusDays(1), TODAY.plusDays(365), "REVOKED",
                now.minusSeconds(1), now.minusSeconds(1));

        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(pastCheck)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
        mockMvc.perform(post("/v1/staff/authorization-checks")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(check(staffId, "GENERAL", 50,
                                "ASA2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reasons[*].code", hasItem("AUTHORIZATION_REVOKED")));
    }

    @Test
    void authorizationManagerWritesWithinDepartmentAndAnesthesiologistIsReadOnly() throws Exception {
        String manager = login(SyntheticAccounts.DEMO_DEPT_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String staffId = createStaff(manager, staffBody("STAFF-MGR-" + suffix, DEPT, null));

        Map<String, Object> single = grant(0, "R3", List.of("GENERAL"), 18, 90, "ASA3",
                TODAY.minusDays(1).toString(), TODAY.plusDays(365).toString());
        single.remove("secondApproverId");
        single.remove("secondApproverReason");
        mockMvc.perform(put("/v1/staff/" + staffId + "/authorizations")
                        .header("Authorization", "Bearer " + manager)
                        .contentType(JSON).content(objectMapper.writeValueAsString(single)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/staff").header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].staffId", hasItem(staffId)));

        // DEPT 范围不能建其他科室人员（403）。
        mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + manager)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                staffBody("STAFF-MGR-OTHER-" + suffix, OTHER_DEPT, null))))
                .andExpect(status().isForbidden());

        // 麻醉医师只读：可读，不能写档案/授权/培训。
        String anes = login(SyntheticAccounts.DEMO_SELF_USERNAME,
                SyntheticAccounts.DEMO_SELF_PASSWORD);
        mockMvc.perform(get("/v1/staff").header("Authorization", "Bearer " + anes))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + anes)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                staffBody("STAFF-ANES-DENY-" + suffix, DEPT, null))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/v1/staff/" + staffId + "/authorizations")
                        .header("Authorization", "Bearer " + anes)
                        .contentType(JSON).content(objectMapper.writeValueAsString(single)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/staff/trainings")
                        .header("Authorization", "Bearer " + anes)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(training(staffId,
                                "TRN-DENY-" + suffix, TODAY.minusDays(1).toString(), null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void inactiveStaffCanStillBeRevokedAndDataScopeDepartmentsFailClosed() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        String staffId = createStaff(admin, staffBody("STAFF-INACT-" + suffix, DEPT, null));
        String path = "/v1/staff/" + staffId + "/authorizations";
        Map<String, Object> single = grant(0, "R3", List.of("GENERAL"), 18, 90, "ASA3",
                TODAY.minusDays(1).toString(), TODAY.plusDays(365).toString());
        single.remove("secondApproverId");
        single.remove("secondApproverReason");
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(single)))
                .andExpect(status().isOk());

        Map<String, Object> deactivate = new LinkedHashMap<>();
        deactivate.put("name", "演示麻醉医师甲（合成）");
        deactivate.put("departmentId", DEPT);
        deactivate.put("title", "副主任医师");
        deactivate.put("employmentStatus", "INACTIVE");
        deactivate.put("version", 1);
        mockMvc.perform(put("/v1/staff/" + staffId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(deactivate)))
                .andExpect(status().isOk());

        // 离职后不能再授予；但允许撤销（S-2）。授权当前版本为 1，避免先命中版本冲突。
        Map<String, Object> grantAfterLeave = new LinkedHashMap<>(single);
        grantAfterLeave.put("version", 1);
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(grantAfterLeave)))
                .andExpect(status().isUnprocessableEntity());
        Map<String, Object> revoke = new LinkedHashMap<>();
        revoke.put("action", "REVOKE");
        revoke.put("version", 1);
        revoke.put("approvedBy", APPROVER);
        revoke.put("changeReason", "离职清理（SYNTHETIC）");
        mockMvc.perform(put(path).header("Authorization", "Bearer " + admin)
                        .contentType(JSON).content(objectMapper.writeValueAsString(revoke)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.status").value("REVOKED"));

        // ROOM 数据范围访问人员科室选项必须 403（fail-closed，S-4）。
        String room = login(SyntheticAccounts.ROOM_USERNAME, SyntheticAccounts.ROOM_PASSWORD);
        mockMvc.perform(get("/v1/staff/departments").header("Authorization", "Bearer " + room))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/staff").header("Authorization", "Bearer " + room))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCreateRejectsUnknownOrDisabledUserId() throws Exception {
        String admin = login(SyntheticAccounts.DEMO_ADMIN_USERNAME,
                SyntheticAccounts.DEMO_ADMIN_PASSWORD);
        String suffix = String.valueOf(SEQ.incrementAndGet());
        mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(staffBody(
                                "STAFF-USER-" + suffix, DEPT, UUID.randomUUID().toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("userId"));

        jdbcTemplate.update("UPDATE m0_platform.sys_user SET status = 0 WHERE id = ?::uuid",
                SyntheticAccounts.DEMO_SELF_ID);
        mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(staffBody(
                                "STAFF-USER2-" + suffix, DEPT,
                                SyntheticAccounts.DEMO_SELF_ID))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("userId"));
        jdbcTemplate.update("UPDATE m0_platform.sys_user SET status = 1 WHERE id = ?::uuid",
                SyntheticAccounts.DEMO_SELF_ID);

        // siteId：格式非法 → 400；合法院区 → 201（院区存在性/同院区校验依赖 m0 组织查询，Q-7）。
        Map<String, Object> badSite = staffBody("STAFF-SITE-BAD-" + suffix, DEPT, null);
        badSite.put("siteId", "not-a-uuid");
        mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(badSite)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("siteId"));

        Map<String, Object> goodSite = staffBody("STAFF-SITE-OK-" + suffix, DEPT, null);
        goodSite.put("siteId", "f1000000-0000-4000-8000-000000000001");
        mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(goodSite)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.siteId")
                        .value("f1000000-0000-4000-8000-000000000001"));
    }

    @Test
    void v21AndV22SchemaPermissionsRolesAndTriggersAreInstalled() {
        Long tables = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'm4_staff' AND table_type = 'BASE TABLE'
                """, Long.class);
        assertThat(tables).isEqualTo(5L);
        Long view = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.views"
                        + " WHERE table_schema = 'm4_staff'", Long.class);
        assertThat(view).isEqualTo(1L);
        Long permissions = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_permission
                 WHERE code IN ('m4:staff:read', 'm4:staff:write', 'm4:schedule:manage',
                                'm4:training:manage', 'm4:staff:authorize')
                """, Long.class);
        assertThat(permissions).isEqualTo(5L);
        Long governanceColumns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = 'm4_staff' AND table_name = 'staff_authorization'
                   AND column_name IN ('second_approver_id', 'second_approved_at',
                                       'second_approver_reason', 'revoked_at', 'revoked_by')
                """, Long.class);
        assertThat(governanceColumns).isEqualTo(5L);
        Long authManagerRole = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m0_platform.sys_role
                 WHERE code = 'SYS_AUTH_MANAGER' AND data_scope = 'DEPT'
                """, Long.class);
        assertThat(authManagerRole).isEqualTo(1L);
        Long anesWrites = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM m0_platform.sys_role_permission rp
                  JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
                 WHERE rp.role_id = 'a0000000-0000-4000-8000-000000000002'
                   AND sp.code IN ('m4:staff:write', 'm4:training:manage')
                """, Long.class);
        assertThat(anesWrites).isZero();
        Long grants = jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM m0_platform.sys_role_permission rp
                  JOIN m0_platform.sys_permission sp ON sp.id = rp.permission_id
                 WHERE sp.code IN ('m4:staff:read', 'm4:staff:write', 'm4:schedule:manage',
                                   'm4:training:manage')
                """, Long.class);
        assertThat(grants).isGreaterThanOrEqualTo(9L);
        Long triggers = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_trigger t
                  JOIN pg_class c ON c.oid = t.tgrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'm4_staff'
                   AND NOT t.tgisinternal
                   AND t.tgname LIKE 'trg_staff_authorization_no_%'
                """, Long.class);
        assertThat(triggers).isEqualTo(3L);
        Long currentView = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM m4_staff.staff_authorization_current
                """, Long.class);
        assertThat(currentView).isNotNull();
    }

    private void insertAuthorization(String staffId, int version, String level, String scopes,
            String asaMax, LocalDate validFrom, LocalDate validTo, String status,
            java.time.Instant approvedAt, java.time.Instant revokedAt) {
        jdbcTemplate.update("""
                INSERT INTO m4_staff.staff_authorization
                    (id, staff_id, version, level, surgery_scopes, age_min, age_max, asa_max,
                     valid_from, valid_to, status, approved_by, approved_at, revoked_at,
                     revoked_by, change_reason, created_at, created_by)
                VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, 18, 90, ?, ?, ?, ?, ?::uuid,
                        ?, ?, ?::uuid, 'SYNTHETIC 回溯用例', ?, ?::uuid)
                """, UUID.randomUUID().toString(), staffId, version, level, scopes, asaMax,
                validFrom, validTo, status, APPROVER, java.sql.Timestamp.from(approvedAt),
                revokedAt == null ? null : java.sql.Timestamp.from(revokedAt),
                revokedAt == null ? null : APPROVER,
                java.sql.Timestamp.from(approvedAt), APPROVER);
    }

    // ------------------------------------------------------------------ 辅助 --

    private Map<String, Object> staffBody(String staffNo, String departmentId, String userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (userId != null) {
            body.put("userId", userId);
        }
        body.put("staffNo", staffNo);
        body.put("name", "演示麻醉医师甲（合成）");
        body.put("departmentId", departmentId);
        body.put("title", "主任医师");
        body.put("jobGrade", "正高");
        body.put("employmentStatus", "ACTIVE");
        body.put("specialties", List.of("心血管麻醉"));
        Map<String, Object> qualification = new LinkedHashMap<>();
        qualification.put("qualificationType", "PRACTICE_LICENSE");
        qualification.put("certificateNo", "SYNTH-CERT-" + staffNo);
        qualification.put("issuingAuthority", "省卫生健康委员会（合成）");
        qualification.put("validFrom", TODAY.minusYears(1).toString());
        qualification.put("validTo", TODAY.plusYears(5).toString());
        body.put("qualifications", List.of(qualification));
        return body;
    }

    private Map<String, Object> grant(int version, String level, List<String> scopes, int ageMin,
            int ageMax, String asaMax, String validFrom, String validTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "GRANT");
        body.put("version", version);
        body.put("level", level);
        body.put("surgeryScopes", scopes);
        body.put("ageMin", ageMin);
        body.put("ageMax", ageMax);
        body.put("asaMax", asaMax);
        body.put("validFrom", validFrom);
        body.put("validTo", validTo);
        body.put("approvedBy", APPROVER);
        if (requiresDualApproval(level, scopes, asaMax)) {
            body.put("secondApproverId", SECOND_APPROVER);
            body.put("secondApproverReason", "第二审批人复核（SYNTHETIC）");
        }
        body.put("changeReason", "年度授权复评（SYNTHETIC）");
        return body;
    }

    private static boolean requiresDualApproval(String level, List<String> scopes, String asaMax) {
        return "R1".equals(level) || "R2".equals(level) || scopes.contains("*")
                || "ASA4".equals(asaMax) || "ASA5".equals(asaMax);
    }

    private Map<String, Object> check(String staffId, String surgeryTypeId, Integer age,
            String asa) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("staffId", staffId);
        if (surgeryTypeId != null) {
            body.put("surgeryTypeId", surgeryTypeId);
        }
        if (age != null) {
            body.put("age", age);
        }
        if (asa != null) {
            body.put("asa", asa);
        }
        return body;
    }

    private Map<String, Object> schedule(String staffId, String date, String shift, String roomId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("staffId", staffId);
        body.put("scheduleDate", date);
        body.put("shift", shift);
        if (roomId != null) {
            body.put("roomId", roomId);
        }
        body.put("departmentId", DEPT);
        body.put("note", "SYNTHETIC 演示排班");
        return body;
    }

    private Map<String, Object> training(String staffId, String courseCode, String completedOn,
            String expiresOn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("staffId", staffId);
        body.put("courseCode", courseCode);
        body.put("courseName", "困难气道处理（SYNTHETIC）");
        body.put("completedOn", completedOn);
        if (expiresOn != null) {
            body.put("expiresOn", expiresOn);
        }
        body.put("certificateNo", "SYNTH-CERT-" + courseCode);
        return body;
    }

    private String createStaff(String token, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/staff")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("staffId").asText();
    }

    /** 演示种子用户幂等档案：已存在则复用（避免与其它用例的唯一约束冲突）。 */
    private String ensureStaff(String token, String staffNo, String departmentId, String userId)
            throws Exception {
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT id::text FROM m4_staff.staff_profile WHERE staff_no = ?",
                String.class, staffNo);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return createStaff(token, staffBody(staffNo, departmentId, userId));
    }

    private String createSchedule(String token, String staffId, String date, String shift,
            String roomId) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/staff/schedules")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(
                                schedule(staffId, date, shift, roomId))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("scheduleId").asText();
    }

    private String createTraining(String token, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/staff/trainings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("trainingId").asText();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", password,
                                "authType", "LOCAL"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("accessToken").asText();
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
