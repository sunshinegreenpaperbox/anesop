package com.anesop.m5.impl.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.dashboard.model.CaseStatus;
import com.anesop.contracts.dashboard.model.DashboardKpi;
import com.anesop.contracts.dashboard.model.DataQuality;
import com.anesop.contracts.dashboard.model.DataScopeType;
import com.anesop.contracts.dashboard.model.RealtimeDashboard;
import com.anesop.contracts.dashboard.model.RoomStatus;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m5.api.dashboard.DashboardAlertSource;
import com.anesop.m5.api.dashboard.DashboardAlertStats;
import com.anesop.m5.api.dashboard.DashboardCaseFact;
import com.anesop.m5.api.dashboard.DashboardCaseSource;
import com.anesop.m5.api.dashboard.DashboardDeviceSource;
import com.anesop.m5.api.dashboard.DashboardDeviceStats;
import com.anesop.m5.api.dashboard.DashboardRoomFact;
import com.anesop.m5.api.dashboard.DashboardRiskSource;
import com.anesop.m5.api.dashboard.DashboardRoomSource;
import com.anesop.m5.api.dashboard.DashboardScope;
import com.anesop.m5.api.dashboard.DashboardStaffSource;
import com.anesop.m5.impl.config.DashboardProperties;
import com.anesop.m5.impl.support.DashboardAuditSampler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

/**
 * 看板聚合单元测试（M5-M18-01，口径 dashboard-1.0.0）。
 *
 * <p>固定时钟 2026-11-15T04:00:12Z = 本地 12:00:12；固定 SYNTHETIC 术间/台次/告警/设备，
 * 全部期望值手算（注释给出算式），覆盖：实时态势聚合、KPI 聚合、空数据 0/NO_DATA、
 * 延迟字段阈值、数据范围 403（拒绝/空范围/SELF 缺用户）、审计采样与无 PHI。
 */
class DashboardServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-15T04:00:12Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final String ROOM_01 = "f2000000-0000-4000-8000-0000000000c1";
    private static final String ROOM_02 = "f2000000-0000-4000-8000-0000000000c2";
    private static final String ROOM_05 = "f2000000-0000-4000-8000-0000000000c5";
    private static final String ROOM_09 = "f2000000-0000-4000-8000-0000000000c9";
    private static final String DEPT_ANES = "f1000000-0000-4000-8000-000000000002";
    private static final String DEPT_OR = "f1000000-0000-4000-8000-000000000003";
    private static final String SITE = "f1000000-0000-4000-8000-000000000001";
    private static final String USER_ID = "e0000000-0000-4000-8000-0000000000e1";
    private static final AuthPrincipal CALLER = new AuthPrincipal(UUID.fromString(USER_ID),
            "synth_dashboard", "jti-synth", NOW.plusSeconds(900), "sid-synth");

    private DashboardCaseSource caseSource;
    private DashboardRoomSource roomSource;
    private DashboardAlertSource alertSource;
    private DashboardDeviceSource deviceSource;
    private DashboardRiskSource riskSource;
    private DashboardStaffSource staffSource;
    private DataScopeResolver dataScopeResolver;
    private AuditWriter auditWriter;
    private DashboardProperties properties;

    @BeforeEach
    void setUp() {
        caseSource = mock(DashboardCaseSource.class);
        roomSource = mock(DashboardRoomSource.class);
        alertSource = mock(DashboardAlertSource.class);
        deviceSource = mock(DashboardDeviceSource.class);
        riskSource = mock(DashboardRiskSource.class);
        staffSource = mock(DashboardStaffSource.class);
        dataScopeResolver = mock(DataScopeResolver.class);
        auditWriter = mock(AuditWriter.class);
        properties = new DashboardProperties();
        grantAll();
        when(roomSource.findRooms(any(), any(), any())).thenReturn(syntheticRooms());
        when(caseSource.findCases(any(), any(), any())).thenReturn(syntheticCases());
        when(alertSource.countAlerts(any(), any(), any())).thenReturn(new DashboardAlertStats(
                2, 1, 1, 0, 1, 0, Instant.parse("2026-11-15T03:58:40Z")));
        when(deviceSource.countDevices(any(), any(), any(), any(), anyInt()))
                .thenReturn(new DashboardDeviceStats(4, 3, 1, 0,
                        Instant.parse("2026-11-15T04:00:00Z")));
    }

    // ------------------------------------------------------------------
    // 实时态势：手算聚合
    // ------------------------------------------------------------------

    @Test
    void realtimeAggregatesRoomsCasesAlertsAndDevicesByHand() {
        RealtimeDashboard model = service().realtime(CALLER, null);

        // 术间：4 间 → 手术中 1（OR-01 SURGERY）、空闲 1（OR-02 IDLE）、清洁 1（OR-05 CLEANING）、
        // 停用 1（OR-09 deleted），准备/复苏 0。
        assertThat(model.getRoomStatusCounts().getTotal()).isEqualTo(4);
        assertThat(model.getRoomStatusCounts().getInSurgery()).isEqualTo(1);
        assertThat(model.getRoomStatusCounts().getIdle()).isEqualTo(1);
        assertThat(model.getRoomStatusCounts().getCleaning()).isEqualTo(1);
        assertThat(model.getRoomStatusCounts().getPreparing()).isZero();
        assertThat(model.getRoomStatusCounts().getRecovery()).isZero();
        assertThat(model.getRoomStatusCounts().getOutOfService()).isEqualTo(1);
        assertThat(model.getRooms()).hasSize(4);
        // 稳定排序：院区 → 科室名 → 术间编号（“手术室”先于“麻醉科”，同科室按编号）。
        assertThat(model.getRooms())
                .extracting(room -> room.getRoomNo())
                .containsExactly("OR-05", "OR-09", "OR-01", "OR-02");
        com.anesop.contracts.dashboard.model.RoomSituation or01 = model.getRooms().stream()
                .filter(room -> "OR-01".equals(room.getRoomNo())).findFirst().orElseThrow();
        assertThat(or01.getStatus()).isEqualTo(RoomStatus.IN_SURGERY);
        assertThat(or01.getCaseInProgress()).isTrue();
        assertThat(or01.getPlannedCaseCount()).isEqualTo(2);
        assertThat(or01.getDeptName()).isEqualTo("麻醉科（合成）");
        com.anesop.contracts.dashboard.model.RoomSituation or09 = model.getRooms().stream()
                .filter(room -> "OR-09".equals(room.getRoomNo())).findFirst().orElseThrow();
        assertThat(or09.getStatus()).isEqualTo(RoomStatus.OUT_OF_SERVICE);
        assertThat(or09.getCaseInProgress()).isFalse();

        // 台次：6 台（含取消 1）、进行中 1；状态分布按固定顺序只输出计数 > 0 的状态。
        assertThat(model.getCaseProgress().getTotalCount()).isEqualTo(6);
        assertThat(model.getCaseProgress().getActiveCount()).isEqualTo(1);
        assertThat(model.getCaseProgress().getStatusCounts())
                .extracting(item -> item.getStatus(), item -> item.getCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(CaseStatus.PLANNED, 2),
                        org.assertj.core.groups.Tuple.tuple(CaseStatus.IN_PROGRESS, 1),
                        org.assertj.core.groups.Tuple.tuple(CaseStatus.COMPLETED, 2),
                        org.assertj.core.groups.Tuple.tuple(CaseStatus.CANCELLED, 1));

        // 告警：未确认 2 = 严重 1 + 警告 1 + 提示 0；已确认 1、已忽略 0。
        assertThat(model.getAlerts().getUnacknowledgedCount()).isEqualTo(2);
        assertThat(model.getAlerts().getCriticalCount()).isEqualTo(1);
        assertThat(model.getAlerts().getWarnCount()).isEqualTo(1);
        assertThat(model.getAlerts().getInfoCount()).isZero();
        assertThat(model.getAlerts().getAcknowledgedCount()).isEqualTo(1);
        assertThat(model.getAlerts().getIgnoredCount()).isZero();
        assertThat(model.getAlerts().getLatestTriggeredAt().toInstant())
                .isEqualTo(Instant.parse("2026-11-15T03:58:40Z"));

        // 设备：4 台 → 在线 3、离线 1、维护 0；阈值默认 120s。
        assertThat(model.getDevices().getTotal()).isEqualTo(4);
        assertThat(model.getDevices().getOnline()).isEqualTo(3);
        assertThat(model.getDevices().getOffline()).isEqualTo(1);
        assertThat(model.getDevices().getMaintenance()).isZero();
        assertThat(model.getDevices().getOfflineTimeoutSeconds()).isEqualTo(120);

        // 延迟：最新数据 = 设备 last_seen_at 12:00:00（本地）→ 12:00:12 − 12:00:00 = 12s。
        assertThat(model.getDataDelaySeconds()).isEqualTo(12);
        assertThat(model.getDataQuality()).isEqualTo(DataQuality.OK);
        assertThat(model.getUpdatedAt().toInstant()).isEqualTo(NOW);
        assertThat(model.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(model.getDataScope()).isEqualTo(DataScopeType.ALL);
    }

    // ------------------------------------------------------------------
    // KPI：手算聚合
    // ------------------------------------------------------------------

    @Test
    void kpiMatchesHandCalculatedAggregates() {
        DashboardKpi kpi = service().kpi("2026-11-15", CALLER, null);

        // 台次 6（含取消 1）→ 非取消 5；已开始 3（C1 IN_PROGRESS、C3/C6 有记录单）、已完成 2。
        assertThat(kpi.getDate().toString()).isEqualTo("2026-11-15");
        assertThat(kpi.getTotalCaseCount()).isEqualTo(6);
        assertThat(kpi.getCaseCount()).isEqualTo(5);
        assertThat(kpi.getCancelledCount()).isEqualTo(1);
        assertThat(kpi.getStartedCount()).isEqualTo(3);
        assertThat(kpi.getCompletedCount()).isEqualTo(2);

        // 准点：R1 首台 C1 08:00 计划 / 08:05 记录开始 → 准点；R2 首台 C3 09:00 / 09:40 → 延误；
        // R5 首台 C6 06:00 / 06:10 → 准点（C4 13:00 取消不是首台，不参与）。2/3 = 0.6667。
        assertThat(kpi.getOnTimeEvaluatedCount()).isEqualTo(3);
        assertThat(kpi.getOnTimeCount()).isEqualTo(2);
        assertThat(kpi.getOnTimeRate()).isEqualTo(0.6667);
        assertThat(kpi.getOnTimeThresholdMinutes()).isEqualTo(15);

        // 取消率 1/6 = 0.1667（分母含取消）。
        assertThat(kpi.getCancellationRate()).isEqualTo(0.1667);

        // 利用率：重叠分钟 120+90+120+60+60 = 450；可用 3 间 × 600 = 1800 → 450/1800 = 0.25。
        assertThat(kpi.getTotalSurgeryMinutes()).isEqualTo(450);
        assertThat(kpi.getAvailableMinutes()).isEqualTo(1800);
        assertThat(kpi.getRoomCount()).isEqualTo(3);
        assertThat(kpi.getUtilizationRate()).isEqualTo(0.25);

        // 平均麻醉时长：(25 + 90) / 2 = 57.5；无结束时间/无记录单的台次计入 unrecorded。
        assertThat(kpi.getRecordedCaseCount()).isEqualTo(2);
        assertThat(kpi.getAverageAnesthesiaMinutes()).isEqualTo(57.5);
        assertThat(kpi.getUnrecordedCaseCount()).isEqualTo(3);

        // 告警：当日触发 2，其中严重 1。
        assertThat(kpi.getAlertCount()).isEqualTo(2);
        assertThat(kpi.getCriticalAlertCount()).isEqualTo(1);
        assertThat(kpi.getFormula()).contains("准点率");
        assertThat(kpi.getPendingConfirmations()).isNotEmpty();
        assertThat(kpi.getPendingConfirmations().toString()).contains("待确认");
        assertThat(kpi.getDataDelaySeconds()).isEqualTo(12);
        assertThat(kpi.getDataQuality()).isEqualTo(DataQuality.OK);
    }

    // ------------------------------------------------------------------
    // 空数据与延迟字段
    // ------------------------------------------------------------------

    @Test
    void emptyDataReturnsZerosAndNoDataQuality() {
        when(roomSource.findRooms(any(), any(), any())).thenReturn(List.of());
        when(caseSource.findCases(any(), any(), any())).thenReturn(List.of());
        when(alertSource.countAlerts(any(), any(), any())).thenReturn(DashboardAlertStats.empty());
        when(deviceSource.countDevices(any(), any(), any(), any(), anyInt()))
                .thenReturn(DashboardDeviceStats.empty());

        RealtimeDashboard realtime = service().realtime(CALLER, null);
        assertThat(realtime.getRooms()).isEmpty();
        assertThat(realtime.getRoomStatusCounts().getTotal()).isZero();
        assertThat(realtime.getCaseProgress().getTotalCount()).isZero();
        assertThat(realtime.getCaseProgress().getStatusCounts()).isEmpty();
        assertThat(realtime.getAlerts().getUnacknowledgedCount()).isZero();
        assertThat(realtime.getDevices().getTotal()).isZero();
        assertThat(realtime.getDataDelaySeconds()).isZero();
        assertThat(realtime.getDataQuality()).isEqualTo(DataQuality.NO_DATA);

        DashboardKpi kpi = service().kpi(null, CALLER, null);
        assertThat(kpi.getCaseCount()).isZero();
        assertThat(kpi.getTotalCaseCount()).isZero();
        assertThat(kpi.getOnTimeEvaluatedCount()).isZero();
        assertThat(kpi.getOnTimeRate()).isZero();
        assertThat(kpi.getCancellationRate()).isZero();
        assertThat(kpi.getUtilizationRate()).isZero();
        assertThat(kpi.getAverageAnesthesiaMinutes()).isZero();
        assertThat(kpi.getDataQuality()).isEqualTo(DataQuality.NO_DATA);
        // date 缺省为服务端当日（本地 2026-11-15）。
        assertThat(kpi.getDate().toString()).isEqualTo("2026-11-15");
    }

    @Test
    void staleDataIsMarkedDegradedAndDelayIsCappedByServerClock() {
        // 所有数据时间都停在 09:00:00（本地）→ 延迟 10812s > 120s 阈值 → DEGRADED。
        Instant stale = Instant.parse("2026-11-15T01:00:00Z");
        when(deviceSource.countDevices(any(), any(), any(), any(), anyInt()))
                .thenReturn(new DashboardDeviceStats(1, 0, 1, 0, stale));
        when(alertSource.countAlerts(any(), any(), any())).thenReturn(DashboardAlertStats.empty());
        when(caseSource.findCases(any(), any(), any())).thenReturn(List.of(caseFact("C9", ROOM_01,
                "PLANNED", "2026-11-15T08:00:00+08:00", "2026-11-15T09:00:00+08:00",
                null, null, "2026-11-15T09:00:00+08:00")));

        RealtimeDashboard model = service().realtime(CALLER, null);
        assertThat(model.getDataDelaySeconds()).isEqualTo(10812);
        assertThat(model.getDataQuality()).isEqualTo(DataQuality.DEGRADED);
    }

    // ------------------------------------------------------------------
    // 数据范围（fail-closed）
    // ------------------------------------------------------------------

    @Test
    void deniedOrEmptyScopeIsForbidden() {
        when(dataScopeResolver.resolve(any()))
                .thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> service().realtime(CALLER, null))
                .isInstanceOf(AccessDeniedException.class);

        // 非全院且三个条件全空（既无组织/术间绑定也无 SELF）→ 403，绝不退化为全院。
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of()))));
        assertThatThrownBy(() -> service().kpi(null, CALLER, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void selfScopeWithoutUserIsForbiddenAndScopeIsPropagated() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.SELF, List.of()))));
        assertThatThrownBy(() -> service().realtime(null, null))
                .isInstanceOf(AccessDeniedException.class);

        // 有 SELF 用户时把 selfUserId 与 selfScoped 传给端口，scope 类型为 SELF。
        when(roomSource.findRooms(any(), any(), any())).thenReturn(List.of());
        when(caseSource.findCases(any(), any(), any())).thenReturn(List.of());
        when(alertSource.countAlerts(any(), any(), any())).thenReturn(DashboardAlertStats.empty());
        when(deviceSource.countDevices(any(), any(), any(), any(), anyInt()))
                .thenReturn(DashboardDeviceStats.empty());
        RealtimeDashboard model = service().realtime(CALLER, null);

        assertThat(model.getDataScope()).isEqualTo(DataScopeType.SELF);
        ArgumentCaptor<DashboardScope> captor = ArgumentCaptor.forClass(DashboardScope.class);
        verify(caseSource).findCases(any(), any(), captor.capture());
        assertThat(captor.getValue().selfScoped()).isTrue();
        assertThat(captor.getValue().selfUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().unrestricted()).isFalse();
    }

    @Test
    void deptScopeIsPropagatedAsOrgIdsAndReportedAsDept() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of(DEPT_ANES)))));
        when(roomSource.findRooms(any(), any(), any())).thenReturn(List.of());
        when(caseSource.findCases(any(), any(), any())).thenReturn(List.of());
        when(alertSource.countAlerts(any(), any(), any())).thenReturn(DashboardAlertStats.empty());
        when(deviceSource.countDevices(any(), any(), any(), any(), anyInt()))
                .thenReturn(DashboardDeviceStats.empty());

        DashboardKpi kpi = service().kpi(null, CALLER, null);
        assertThat(kpi.getDataScope()).isEqualTo(DataScopeType.DEPT);
        ArgumentCaptor<DashboardScope> captor = ArgumentCaptor.forClass(DashboardScope.class);
        verify(roomSource).findRooms(any(), any(), captor.capture());
        assertThat(captor.getValue().orgIds()).containsExactly(DEPT_ANES);
        assertThat(captor.getValue().roomIds()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 审计采样与无 PHI
    // ------------------------------------------------------------------

    @Test
    void auditIsSampledPerUserAndContainsNoPhi() {
        DashboardServiceImpl service = service();
        service.realtime(CALLER, null);
        service.realtime(CALLER, null);
        service.kpi(null, CALLER, null);
        // 采样窗口 300s：同一用户 realtime 只写 1 条、kpi 另写 1 条（看板类型不同）。
        verify(auditWriter, times(2)).record(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(true), any(), any(), any(), any());

        ArgumentCaptor<Map<String, Object>> detail = mapCaptor();
        verify(auditWriter, times(2)).record(org.mockito.ArgumentMatchers.eq("DASHBOARD_VIEW"),
                org.mockito.ArgumentMatchers.eq("DASHBOARD"), anyString(),
                org.mockito.ArgumentMatchers.eq(true), detail.capture(), any(), any(), any());
        Map<String, Object> combined = new LinkedHashMap<>();
        detail.getAllValues().forEach(combined::putAll);
        assertThat(combined).containsKeys("kind", "timezone", "dataScope", "roomCount",
                "caseCount", "alertCount", "dataDelaySeconds");
        assertThat(combined.toString()).doesNotContain("演示患者", "住院号", "patientName",
                "acknowledgeReason", "SYN-AN-001");

        // 采样间隔 0 → 每次请求都写审计。
        properties.setAuditSampleSeconds(0);
        DashboardServiceImpl always = service();
        always.realtime(CALLER, null);
        always.realtime(CALLER, null);
        verify(auditWriter, times(4)).record(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(true), any(), any(), any(), any());
    }

    @Test
    void responseJsonHasNoPatientIdentifiers() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String realtime = mapper.writeValueAsString(service().realtime(CALLER, null));
        String kpi = mapper.writeValueAsString(service().kpi(null, CALLER, null));

        for (String json : List.of(realtime, kpi)) {
            assertThat(json).doesNotContain("patient", "inpatient", "diagnosis", "idCard",
                    "phone", "recordId", "scheduleId", "acknowledgeReason");
        }
        // 术间卡片只有术间/科室/状态/计数。
        assertThat(realtime).contains("OR-01", "麻醉科（合成）", "IN_SURGERY", "plannedCaseCount");
    }

    @Test
    void invalidConfigurationIsRejectedAtStartup() {
        properties.setDeviceOfflineTimeoutSeconds(0);
        assertThatThrownBy(() -> service().validateConfiguration())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("device-offline-timeout-seconds");

        properties.setDeviceOfflineTimeoutSeconds(120);
        properties.setOnTimeThresholdMinutes(2000);
        assertThatThrownBy(() -> service().validateConfiguration())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("on-time-threshold-minutes");
    }

    @Test
    void invalidDateIsRejectedButTodayIsDefault() {
        assertThatThrownBy(() -> service().kpi("2026/11/15", CALLER, null))
                .hasMessageContaining("date");
        assertThatThrownBy(() -> service().kpi("2027-12-31", CALLER, null))
                .hasMessageContaining("date");
        assertThat(service().kpi("2026-11-14", CALLER, null).getDate().toString())
                .isEqualTo("2026-11-14");
    }

    // ---------------------------------------------------------------------
    // 固定 SYNTHETIC 数据
    // ---------------------------------------------------------------------

    private DashboardServiceImpl service() {
        DashboardServiceImpl service = new DashboardServiceImpl(caseSource, roomSource, alertSource,
                deviceSource, riskSource, staffSource, dataScopeResolver, auditWriter, properties,
                new DashboardAuditSampler(), CLOCK);
        service.validateConfiguration();
        return service;
    }

    private void grantAll() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.ALL, List.of()))));
    }

    private static List<DashboardRoomFact> syntheticRooms() {
        List<DashboardRoomFact> rooms = new ArrayList<>();
        rooms.add(new DashboardRoomFact(ROOM_09, "OR-09", "九号手术间（合成）", DEPT_OR,
                "手术室（合成）", SITE, "IDLE", true));
        rooms.add(new DashboardRoomFact(ROOM_05, "OR-05", "五号手术间（合成）", DEPT_OR,
                "手术室（合成）", SITE, "CLEANING", false));
        rooms.add(new DashboardRoomFact(ROOM_02, "OR-02", "二号手术间（合成）", DEPT_ANES,
                "麻醉科（合成）", SITE, "IDLE", false));
        rooms.add(new DashboardRoomFact(ROOM_01, "OR-01", "一号手术间（合成）", DEPT_ANES,
                "麻醉科（合成）", SITE, "SURGERY", false));
        return rooms;
    }

    private static List<DashboardCaseFact> syntheticCases() {
        List<DashboardCaseFact> cases = new ArrayList<>();
        cases.add(caseFact("C1", ROOM_01, "IN_PROGRESS", "2026-11-15T08:00:00+08:00",
                "2026-11-15T10:00:00+08:00", "2026-11-15T08:05:00+08:00", null,
                "2026-11-15T09:20:00+08:00"));
        cases.add(caseFact("C2", ROOM_01, "PLANNED", "2026-11-15T10:30:00+08:00",
                "2026-11-15T12:00:00+08:00", null, null, "2026-11-15T08:00:00+08:00"));
        cases.add(caseFact("C3", ROOM_02, "COMPLETED", "2026-11-15T09:00:00+08:00",
                "2026-11-15T11:00:00+08:00", "2026-11-15T09:40:00+08:00",
                "2026-11-15T10:05:00+08:00", "2026-11-15T09:25:00+08:00"));
        cases.add(caseFact("C4", ROOM_05, "CANCELLED", "2026-11-15T13:00:00+08:00",
                "2026-11-15T14:00:00+08:00", null, null, "2026-11-15T07:00:00+08:00"));
        cases.add(caseFact("C5", ROOM_02, "PLANNED", "2026-11-15T15:00:00+08:00",
                "2026-11-15T16:00:00+08:00", null, null, "2026-11-15T08:30:00+08:00"));
        cases.add(caseFact("C6", ROOM_05, "COMPLETED", "2026-11-15T06:00:00+08:00",
                "2026-11-15T07:00:00+08:00", "2026-11-15T06:10:00+08:00",
                "2026-11-15T07:40:00+08:00", "2026-11-15T09:26:00+08:00"));
        return cases;
    }

    private static DashboardCaseFact caseFact(String scheduleId, String roomId, String status,
            String startAt, String endAt, String recordStartedAt, String recordEndedAt,
            String updatedAt) {
        return new DashboardCaseFact(scheduleId, roomId, DEPT_ANES, SITE, status,
                instant(startAt), instant(endAt), instant(updatedAt),
                instant(recordStartedAt), instant(recordEndedAt), null);
    }

    private static Instant instant(String value) {
        return value == null ? null : java.time.OffsetDateTime.parse(value).toInstant();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
