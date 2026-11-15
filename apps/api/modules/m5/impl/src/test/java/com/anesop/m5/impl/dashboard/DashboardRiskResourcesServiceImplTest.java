package com.anesop.m5.impl.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.dashboard.model.AsaClass;
import com.anesop.contracts.dashboard.model.DataQuality;
import com.anesop.contracts.dashboard.model.DataScopeType;
import com.anesop.contracts.dashboard.model.ResourceDashboard;
import com.anesop.contracts.dashboard.model.ResourceGapCode;
import com.anesop.contracts.dashboard.model.ResourceGapSeverity;
import com.anesop.contracts.dashboard.model.RiskDashboard;
import com.anesop.contracts.dashboard.model.RiskLevel;
import com.anesop.contracts.dashboard.model.Shift;
import com.anesop.contracts.dashboard.model.TrendDashboard;
import com.anesop.contracts.dashboard.model.TrendPoint;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m5.api.dashboard.DashboardAlertBucket;
import com.anesop.m5.api.dashboard.DashboardAlertSource;
import com.anesop.m5.api.dashboard.DashboardAlertStats;
import com.anesop.m5.api.dashboard.DashboardCaseFact;
import com.anesop.m5.api.dashboard.DashboardCaseSource;
import com.anesop.m5.api.dashboard.DashboardDeviceSource;
import com.anesop.m5.api.dashboard.DashboardDeviceStats;
import com.anesop.m5.api.dashboard.DashboardRiskBucket;
import com.anesop.m5.api.dashboard.DashboardRiskSource;
import com.anesop.m5.api.dashboard.DashboardRoomFact;
import com.anesop.m5.api.dashboard.DashboardRoomSource;
import com.anesop.m5.api.dashboard.DashboardScope;
import com.anesop.m5.api.dashboard.DashboardStaffShift;
import com.anesop.m5.api.dashboard.DashboardStaffSource;
import com.anesop.m5.api.dashboard.DashboardStaffStats;
import com.anesop.m5.api.error.M5ValidationException;
import com.anesop.m5.impl.config.DashboardProperties;
import com.anesop.m5.impl.support.DashboardAuditSampler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * 风险/资源/趋势聚合单元测试（M5-M18-02，契约 dashboard.yaml 0.2.0，口径 dashboard-1.1.0）。
 *
 * <p>固定时钟 2026-11-15T04:00:12Z = 本地 12:00:12；固定 SYNTHETIC 术间/台次/告警/设备/排班，
 * 全部期望值手算（注释给出算式），覆盖：风险分层与高危判定、超时台次宽限与并集去重、
 * 资源利用率/状态分布/设备在线率/当班人数/缺口提示、趋势小时桶（含首末桶裁剪）、
 * 趋势窗口边界（30–1440 分钟）、空数据 0/NO_DATA、数据范围 403 与下传、审计与响应无 PHI。
 */
class DashboardRiskResourcesServiceImplTest {

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
        when(alertSource.countAlertsByHour(any(), any(), any())).thenReturn(List.of(
                new DashboardAlertBucket(Instant.parse("2026-11-15T03:00:00Z"), 2, 1),
                // 09:00 本地在 2 小时窗口之外，不应进入任何桶。
                new DashboardAlertBucket(Instant.parse("2026-11-15T01:00:00Z"), 5, 0)));
        when(deviceSource.countDevices(any(), any(), any(), any(), anyInt()))
                .thenReturn(new DashboardDeviceStats(4, 3, 1, 0,
                        Instant.parse("2026-11-15T04:00:00Z")));
        when(riskSource.findRiskBuckets(any(), any(), any())).thenReturn(syntheticRiskBuckets());
        when(staffSource.countOnDuty(any(), any(), any(), any())).thenReturn(
                new DashboardStaffStats(5, List.of(
                        new DashboardStaffShift("DAY", 4),
                        new DashboardStaffShift("ON_CALL", 1))));
    }

    // ------------------------------------------------------------------
    // 风险分层：手算
    // ------------------------------------------------------------------

    @Test
    void riskLayersMatchHandCalculatedBuckets() {
        RiskDashboard risk = service().risk(CALLER, null);

        // 台次 6（含取消 1）→ 非取消 5；风险桶 I/LOW 1、II/LOW 1、II/HIGH 1、III/MODERATE 1、
        // 未评估 1 → 已评估 4、未评估 1、高危 = III（1）+ HIGH（1）= 2。
        assertThat(risk.getDate().toString()).isEqualTo("2026-11-15");
        assertThat(risk.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(risk.getTotalCaseCount()).isEqualTo(5);
        assertThat(risk.getAssessedCaseCount()).isEqualTo(4);
        assertThat(risk.getUnassessedCaseCount()).isEqualTo(1);
        assertThat(risk.getHighRiskCaseCount()).isEqualTo(2);
        assertThat(risk.getAsaClassCounts())
                .extracting(item -> item.getAsaClass(), item -> item.getCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(AsaClass.I, 1),
                        org.assertj.core.groups.Tuple.tuple(AsaClass.II, 2),
                        org.assertj.core.groups.Tuple.tuple(AsaClass.III, 1));
        assertThat(risk.getRiskLevelCounts())
                .extracting(item -> item.getRiskLevel(), item -> item.getCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(RiskLevel.LOW, 2),
                        org.assertj.core.groups.Tuple.tuple(RiskLevel.MODERATE, 1),
                        org.assertj.core.groups.Tuple.tuple(RiskLevel.HIGH, 1));

        // 未确认严重告警取告警端口 critical=1；超时（宽限 0）：C1 计划结束已过未完成、
        // C2 计划开始与结束均已过且未开始/未完成 → 延迟开始 1、超时未结束 2、并集 2。
        assertThat(risk.getSevereAlertCount()).isEqualTo(1);
        assertThat(risk.getDelayedStartCount()).isEqualTo(1);
        assertThat(risk.getOverrunCaseCount()).isEqualTo(2);
        assertThat(risk.getOverdueCaseCount()).isEqualTo(2);
        assertThat(risk.getOverdueThresholdMinutes()).isZero();
        assertThat(risk.getFormula()).contains("ASA");
        assertThat(risk.getPendingConfirmations().toString()).contains("待确认");
        assertThat(risk.getDataScope()).isEqualTo(DataScopeType.ALL);
        // 时效：最新数据 = 告警 03:58:40Z（晚于台次/记录单时间）→ 04:00:12 − 03:58:40 = 92s。
        assertThat(risk.getDataDelaySeconds()).isEqualTo(92);
        assertThat(risk.getDataQuality()).isEqualTo(DataQuality.OK);
    }

    @Test
    void riskOverdueRespectsGraceWindow() {
        // 宽限 60 分钟：C1 计划结束 10:00 + 60min = 11:00 < 12:00 → 仍超时未结束；
        // C2 计划结束 12:00 + 60min = 13:00 > 12:00 → 不再计超时结束（但延迟开始仍成立）。
        properties.setOverdueGraceMinutes(60);
        RiskDashboard risk = service().risk(CALLER, null);

        assertThat(risk.getOverdueThresholdMinutes()).isEqualTo(60);
        assertThat(risk.getDelayedStartCount()).isEqualTo(1);
        assertThat(risk.getOverrunCaseCount()).isEqualTo(1);
        assertThat(risk.getOverdueCaseCount()).isEqualTo(2);
    }

    @Test
    void riskWithEmptyDataReturnsZerosAndNoDataQuality() {
        when(riskSource.findRiskBuckets(any(), any(), any())).thenReturn(List.of());
        when(caseSource.findCases(any(), any(), any())).thenReturn(List.of());
        when(alertSource.countAlerts(any(), any(), any())).thenReturn(DashboardAlertStats.empty());

        RiskDashboard risk = service().risk(CALLER, null);
        assertThat(risk.getTotalCaseCount()).isZero();
        assertThat(risk.getAssessedCaseCount()).isZero();
        assertThat(risk.getUnassessedCaseCount()).isZero();
        assertThat(risk.getHighRiskCaseCount()).isZero();
        assertThat(risk.getAsaClassCounts()).isEmpty();
        assertThat(risk.getRiskLevelCounts()).isEmpty();
        assertThat(risk.getSevereAlertCount()).isZero();
        assertThat(risk.getOverdueCaseCount()).isZero();
        assertThat(risk.getDataDelaySeconds()).isZero();
        assertThat(risk.getDataQuality()).isEqualTo(DataQuality.NO_DATA);
    }

    // ------------------------------------------------------------------
    // 资源态势：手算
    // ------------------------------------------------------------------

    @Test
    void resourcesMatchHandCalculatedUtilizationDevicesAndStaff() {
        ResourceDashboard resources = service().resources(CALLER, null);

        // 术间 4（含软删除 1）→ 分母 3 间 × 600 = 1800 分钟；非取消台次重叠 450 分钟 →
        // 利用率 450/1800 = 0.25。状态：手术中 1、空闲 1、清洁 1、停用 1。
        assertThat(resources.getRoomUtilization().getRoomCount()).isEqualTo(3);
        assertThat(resources.getRoomUtilization().getSurgeryMinutes()).isEqualTo(450);
        assertThat(resources.getRoomUtilization().getAvailableMinutes()).isEqualTo(1800);
        assertThat(resources.getRoomUtilization().getUtilizationRate()).isEqualTo(0.25);
        assertThat(resources.getRoomStatusCounts().getTotal()).isEqualTo(4);
        assertThat(resources.getRoomStatusCounts().getInSurgery()).isEqualTo(1);
        assertThat(resources.getRoomStatusCounts().getIdle()).isEqualTo(1);
        assertThat(resources.getRoomStatusCounts().getCleaning()).isEqualTo(1);
        assertThat(resources.getRoomStatusCounts().getOutOfService()).isEqualTo(1);

        // 设备 4 台：在线 3（0.75）、离线 1、维护 0。
        assertThat(resources.getDevices().getTotal()).isEqualTo(4);
        assertThat(resources.getDevices().getOnline()).isEqualTo(3);
        assertThat(resources.getDevices().getOffline()).isEqualTo(1);
        assertThat(resources.getDeviceOnlineRate()).isEqualTo(0.75);

        // 当班：总 5（DAY 4 + ON_CALL 1），按 DAY→NIGHT→ON_CALL 顺序只输出 > 0 的班次。
        assertThat(resources.getStaffOnDuty().getTotal()).isEqualTo(5);
        assertThat(resources.getStaffOnDuty().getByShift())
                .extracting(item -> item.getShift(), item -> item.getCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Shift.DAY, 4),
                        org.assertj.core.groups.Tuple.tuple(Shift.ON_CALL, 1));

        // 缺口：设备离线（WARN 1）+ 停用术间（INFO 1）；利用率未超载、有当班、有术间。
        assertThat(resources.getResourceGaps())
                .extracting(item -> item.getCode(), item -> item.getSeverity(), item -> item.getCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ResourceGapCode.DEVICE_OFFLINE,
                                ResourceGapSeverity.WARN, 1),
                        org.assertj.core.groups.Tuple.tuple(ResourceGapCode.ROOM_OUT_OF_SERVICE,
                                ResourceGapSeverity.INFO, 1));
        assertThat(resources.getResourceGaps().toString()).doesNotContain("姓名", "工号");

        assertThat(resources.getDataScope()).isEqualTo(DataScopeType.ALL);
        // 时效：最新 = 设备 last_seen_at 12:00:00 本地 → 12s。
        assertThat(resources.getDataDelaySeconds()).isEqualTo(12);
        assertThat(resources.getDataQuality()).isEqualTo(DataQuality.OK);
    }

    @Test
    void resourceGapsCoverMaintenanceOverloadAndMissingStaff() {
        properties.setRoomDailyAvailableMinutes(600);
        when(roomSource.findRooms(any(), any(), any())).thenReturn(List.of(
                new DashboardRoomFact(ROOM_01, "OR-01", "一号手术间（合成）", DEPT_ANES,
                        "麻醉科（合成）", SITE, "SURGERY", false)));
        // 单台 08:00–20:00 = 720 分钟重叠（> 600）→ 利用率 1.2、超额 120 分钟 → 等效 1 间。
        when(caseSource.findCases(any(), any(), any())).thenReturn(List.of(caseFact("C7", ROOM_01,
                "IN_PROGRESS", "2026-11-15T08:00:00+08:00", "2026-11-15T20:00:00+08:00",
                "2026-11-15T08:05:00+08:00", null, "2026-11-15T09:00:00+08:00")));
        when(deviceSource.countDevices(any(), any(), any(), any(), anyInt()))
                .thenReturn(new DashboardDeviceStats(2, 0, 0, 2,
                        Instant.parse("2026-11-15T04:00:00Z")));
        when(staffSource.countOnDuty(any(), any(), any(), any()))
                .thenReturn(DashboardStaffStats.empty());

        ResourceDashboard resources = service().resources(CALLER, null);
        assertThat(resources.getRoomUtilization().getUtilizationRate()).isEqualTo(1.2D);
        assertThat(resources.getResourceGaps())
                .extracting(item -> item.getCode(), item -> item.getSeverity(), item -> item.getCount())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ResourceGapCode.DEVICE_MAINTENANCE,
                                ResourceGapSeverity.INFO, 2),
                        org.assertj.core.groups.Tuple.tuple(ResourceGapCode.ROOM_OVERLOADED,
                                ResourceGapSeverity.WARN, 1),
                        org.assertj.core.groups.Tuple.tuple(ResourceGapCode.STAFF_NOT_SCHEDULED,
                                ResourceGapSeverity.WARN, 0));
        assertThat(resources.getStaffOnDuty().getTotal()).isZero();
        assertThat(resources.getStaffOnDuty().getByShift()).isEmpty();
    }

    @Test
    void resourcesWithEmptyDataReturnZerosAndGapHints() {
        when(roomSource.findRooms(any(), any(), any())).thenReturn(List.of());
        when(caseSource.findCases(any(), any(), any())).thenReturn(List.of());
        when(deviceSource.countDevices(any(), any(), any(), any(), anyInt()))
                .thenReturn(DashboardDeviceStats.empty());
        when(staffSource.countOnDuty(any(), any(), any(), any()))
                .thenReturn(DashboardStaffStats.empty());

        ResourceDashboard resources = service().resources(CALLER, null);
        assertThat(resources.getRoomUtilization().getRoomCount()).isZero();
        assertThat(resources.getRoomUtilization().getUtilizationRate()).isZero();
        assertThat(resources.getRoomStatusCounts().getTotal()).isZero();
        assertThat(resources.getDevices().getTotal()).isZero();
        assertThat(resources.getDeviceOnlineRate()).isZero();
        assertThat(resources.getStaffOnDuty().getTotal()).isZero();
        assertThat(resources.getResourceGaps())
                .extracting(item -> item.getCode())
                .containsExactly(ResourceGapCode.STAFF_NOT_SCHEDULED,
                        ResourceGapCode.NO_VISIBLE_ROOM);
        assertThat(resources.getDataDelaySeconds()).isZero();
        assertThat(resources.getDataQuality()).isEqualTo(DataQuality.NO_DATA);
    }

    // ------------------------------------------------------------------
    // 趋势：小时桶手算与窗口边界
    // ------------------------------------------------------------------

    @Test
    void trendsBucketCasesAlertsAndUtilizationByHand() {
        // hours=2 → 窗口 [10:00:12, 12:00:12]；桶 10:00（交集 59 分钟）、11:00（60）、12:00（0）。
        TrendDashboard trends = service().trends(2.0D, CALLER, null);

        assertThat(trends.getWindowMinutes()).isEqualTo(120);
        assertThat(trends.getBucketMinutes()).isEqualTo(60);
        assertThat(trends.getFrom().toInstant()).isEqualTo(Instant.parse("2026-11-15T02:00:12Z"));
        assertThat(trends.getTo().toInstant()).isEqualTo(NOW);
        assertThat(trends.getRoomCount()).isEqualTo(3);
        assertThat(trends.getDataScope()).isEqualTo(DataScopeType.ALL);
        assertThat(trends.getPoints()).hasSize(3);

        TrendPoint first = trends.getPoints().get(0);
        assertThat(first.getStartsAt()).isEqualTo(OffsetDateTime.parse(
                "2026-11-15T10:00:00+08:00"));
        // C2 10:30 计划开始落入首桶；手术分钟 C2 30 + C3 59（10:00:12→11:00 截断）= 89；
        // 分母 3 间 × 59 分钟 = 177 → 89/177 = 0.5028。
        assertThat(first.getCaseCount()).isEqualTo(1);
        assertThat(first.getSurgeryMinutes()).isEqualTo(89);
        assertThat(first.getUtilizationRate()).isEqualTo(0.5028);
        assertThat(first.getAlertCount()).isZero();

        TrendPoint second = trends.getPoints().get(1);
        assertThat(second.getStartsAt()).isEqualTo(OffsetDateTime.parse(
                "2026-11-15T11:00:00+08:00"));
        // 11:00 桶：无计划开始台次，C2 重叠 60 分钟 → 60/180 = 0.3333；告警桶 11:00 命中 2 条。
        assertThat(second.getCaseCount()).isZero();
        assertThat(second.getSurgeryMinutes()).isEqualTo(60);
        assertThat(second.getUtilizationRate()).isEqualTo(0.3333);
        assertThat(second.getAlertCount()).isEqualTo(2);
        assertThat(second.getCriticalAlertCount()).isEqualTo(1);

        TrendPoint last = trends.getPoints().get(2);
        assertThat(last.getStartsAt()).isEqualTo(OffsetDateTime.parse("2026-11-15T12:00:00+08:00"));
        assertThat(last.getSurgeryMinutes()).isZero();
        assertThat(last.getUtilizationRate()).isZero();
        assertThat(trends.getFormula()).contains("桶");
        assertThat(trends.getPendingConfirmations().toString()).contains("待确认");
    }

    @Test
    void trendsWindowBoundsAreValidated() {
        assertThat(DashboardServiceImpl.trendWindowMinutes(null)).isEqualTo(1440);
        assertThat(DashboardServiceImpl.trendWindowMinutes(24.0D)).isEqualTo(1440);
        assertThat(DashboardServiceImpl.trendWindowMinutes(0.5D)).isEqualTo(30);
        assertThat(DashboardServiceImpl.trendWindowMinutes(2.0D)).isEqualTo(120);

        for (Double invalid : List.of(0.4D, 24.5D, -1.0D, Double.NaN,
                Double.POSITIVE_INFINITY)) {
            assertThatThrownBy(() -> DashboardServiceImpl.trendWindowMinutes(invalid))
                    .isInstanceOf(M5ValidationException.class)
                    .hasMessageContaining("hours");
        }
        assertThatThrownBy(() -> service().trends(30.0D, CALLER, null))
                .isInstanceOf(M5ValidationException.class);
    }

    @Test
    void trendsWithEmptyDataReturnZeroPoints() {
        when(roomSource.findRooms(any(), any(), any())).thenReturn(List.of());
        when(caseSource.findCases(any(), any(), any())).thenReturn(List.of());
        when(alertSource.countAlertsByHour(any(), any(), any())).thenReturn(List.of());

        // hours=0.5 → 窗口 [11:30:12, 12:00:12]：桶 11:00（交集 29 分钟）与 12:00（0 分钟）。
        TrendDashboard trends = service().trends(0.5D, CALLER, null);
        assertThat(trends.getWindowMinutes()).isEqualTo(30);
        assertThat(trends.getRoomCount()).isZero();
        assertThat(trends.getPoints()).hasSize(2);
        for (TrendPoint point : trends.getPoints()) {
            assertThat(point.getAlertCount()).isZero();
            assertThat(point.getCriticalAlertCount()).isZero();
            assertThat(point.getCaseCount()).isZero();
            assertThat(point.getSurgeryMinutes()).isZero();
            assertThat(point.getUtilizationRate()).isZero();
        }
    }

    // ------------------------------------------------------------------
    // 数据范围（fail-closed）与下传
    // ------------------------------------------------------------------

    @Test
    void newEndpointsFailClosedWithoutScope() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> service().risk(CALLER, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service().resources(CALLER, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service().trends(1.0D, CALLER, null))
                .isInstanceOf(AccessDeniedException.class);

        // 非全院且三个条件全空 → 403（不退化全院）。
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of()))));
        assertThatThrownBy(() -> service().risk(CALLER, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service().resources(CALLER, null))
                .isInstanceOf(AccessDeniedException.class);

        // SELF 缺用户标识 → 403。
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.SELF, List.of()))));
        assertThatThrownBy(() -> service().risk(null, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service().trends(1.0D, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void newEndpointsPropagateDeptScopeToPorts() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of(DEPT_ANES)))));

        RiskDashboard risk = service().risk(CALLER, null);
        assertThat(risk.getDataScope()).isEqualTo(DataScopeType.DEPT);
        ArgumentCaptor<DashboardScope> riskScope = ArgumentCaptor.forClass(DashboardScope.class);
        verify(riskSource).findRiskBuckets(any(), any(), riskScope.capture());
        assertThat(riskScope.getValue().orgIds()).containsExactly(DEPT_ANES);
        assertThat(riskScope.getValue().unrestricted()).isFalse();

        ResourceDashboard resources = service().resources(CALLER, null);
        assertThat(resources.getDataScope()).isEqualTo(DataScopeType.DEPT);
        ArgumentCaptor<DashboardScope> staffScope = ArgumentCaptor.forClass(DashboardScope.class);
        verify(staffSource).countOnDuty(any(), any(), any(), staffScope.capture());
        assertThat(staffScope.getValue().orgIds()).containsExactly(DEPT_ANES);

        TrendDashboard trends = service().trends(1.0D, CALLER, null);
        assertThat(trends.getDataScope()).isEqualTo(DataScopeType.DEPT);
        ArgumentCaptor<DashboardScope> alertScope = ArgumentCaptor.forClass(DashboardScope.class);
        verify(alertSource).countAlertsByHour(any(), any(), alertScope.capture());
        assertThat(alertScope.getValue().orgIds()).containsExactly(DEPT_ANES);
    }

    // ------------------------------------------------------------------
    // 审计与无 PHI
    // ------------------------------------------------------------------

    @Test
    void auditsUseRiskResourcesTrendsKindsWithoutPhi() {
        DashboardServiceImpl service = service();
        service.risk(CALLER, null);
        service.resources(CALLER, null);
        service.trends(2.0D, CALLER, null);

        ArgumentCaptor<String> resourceIds = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter, times(3)).record(eq("DASHBOARD_VIEW"), eq("DASHBOARD"),
                resourceIds.capture(), eq(true), details.capture(), any(), any(), any());

        assertThat(resourceIds.getAllValues()).containsExactly("risk", "resources", "trends");
        Map<String, Object> riskDetail = details.getAllValues().get(0);
        assertThat(riskDetail).containsKeys("kind", "date", "timezone", "dataScope",
                "caseCount", "assessedCaseCount", "highRiskCaseCount", "alertCount",
                "overdueCaseCount", "dataDelaySeconds");
        Map<String, Object> resourcesDetail = details.getAllValues().get(1);
        assertThat(resourcesDetail).containsKeys("kind", "date", "dataScope", "roomCount",
                "caseCount", "staffOnDutyTotal", "deviceOffline", "dataDelaySeconds");
        Map<String, Object> trendsDetail = details.getAllValues().get(2);
        assertThat(trendsDetail).containsKeys("kind", "dataScope", "windowMinutes", "roomCount",
                "pointCount", "caseCount", "alertCount");
        assertThat(trendsDetail).doesNotContainKey("dataDelaySeconds");

        Map<String, Object> combined = new LinkedHashMap<>();
        details.getAllValues().forEach(combined::putAll);
        assertThat(combined.toString()).doesNotContain("演示患者", "住院号", "patientName",
                "acknowledgeReason", "staffId", "deviceId");
    }

    @Test
    void responsesContainNoPatientOrStaffIdentifiers() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        List<String> bodies = List.of(
                mapper.writeValueAsString(service().risk(CALLER, null)),
                mapper.writeValueAsString(service().resources(CALLER, null)),
                mapper.writeValueAsString(service().trends(2.0D, CALLER, null)));

        for (String json : bodies) {
            assertThat(json).doesNotContain("patient", "inpatient", "diagnosis", "idCard",
                    "phone", "recordId", "scheduleId", "staffId", "staffName", "deviceId",
                    "acknowledgeReason", "roomId");
        }
        // 风险响应只含分级/等级计数，资源响应只含术间/设备/班组计数与比率。
        assertThat(bodies.get(0)).contains("asaClass", "riskLevel", "overdueCaseCount");
        assertThat(bodies.get(1)).contains("staffOnDuty", "deviceOnlineRate", "resourceGaps");
        assertThat(bodies.get(2)).contains("points", "utilizationRate", "windowMinutes");
    }

    // ---------------------------------------------------------------------
    // 固定 SYNTHETIC 数据与装配
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

    private static List<DashboardRiskBucket> syntheticRiskBuckets() {
        return List.of(
                new DashboardRiskBucket("I", "LOW", 1),
                new DashboardRiskBucket("II", "LOW", 1),
                new DashboardRiskBucket("II", "HIGH", 1),
                new DashboardRiskBucket("III", "MODERATE", 1),
                new DashboardRiskBucket(null, null, 1));
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
        return value == null ? null : OffsetDateTime.parse(value).toInstant();
    }
}
