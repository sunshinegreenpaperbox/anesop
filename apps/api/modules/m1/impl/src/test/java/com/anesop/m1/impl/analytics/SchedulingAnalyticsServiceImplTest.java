package com.anesop.m1.impl.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m1.api.analytics.AnalyticsExportQuery;
import com.anesop.m1.api.analytics.AnalyticsQuery;
import com.anesop.m1.api.analytics.SchedulingAnalyticsService.ExportPlan;
import com.anesop.m1.api.schedule.SchedulingValidationException;
import com.anesop.m1.impl.persistence.entity.OperatingRoomEntity;
import com.anesop.m1.impl.persistence.mapper.AnalyticsMapper;
import com.anesop.m1.impl.persistence.mapper.OperatingRoomMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * 排程分析服务单测（M2-M6-01）：固定数据手算公式、阈值边界、空数据集、数据范围与
 * CSV 脱敏。协作 Mapper 用 Mockito 替身；公式与聚合在服务内真实执行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulingAnalyticsServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_1 = UUID.fromString("f2000000-0000-4000-8000-0000000000c1");
    private static final UUID ROOM_2 = UUID.fromString("f2000000-0000-4000-8000-0000000000c2");
    private static final UUID ROOM_3 = UUID.fromString("f2000000-0000-4000-8000-0000000000c3");
    private static final UUID ROOM_4 = UUID.fromString("f2000000-0000-4000-8000-0000000000c4");
    private static final UUID ROOM_5 = UUID.fromString("f2000000-0000-4000-8000-0000000000c5");
    private static final UUID DEPT = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_DEPT = UUID.fromString("f1000000-0000-4000-8000-000000000003");
    private static final UUID SITE = UUID.fromString("f1000000-0000-4000-8000-000000000001");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "synth-test", "SYNTH-TRACE-AN-1");

    @Mock
    private AnalyticsMapper analyticsMapper;
    @Mock
    private OperatingRoomMapper roomMapper;
    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private AuditWriter auditWriter;

    private SchedulingAnalyticsServiceImpl service;
    private AuthPrincipal caller;

    @BeforeEach
    void setUp() {
        service = new SchedulingAnalyticsServiceImpl(analyticsMapper, roomMapper,
                dataScopeResolver, auditWriter, 15, 600, 92);
        caller = new AuthPrincipal(USER_ID, "demo_admin", "jti",
                Instant.parse("2026-11-13T02:15:00Z"), "sid");
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(allScope());
        when(roomMapper.selectList(any())).thenReturn(List.of(room(ROOM_1, "OR-01", DEPT),
                room(ROOM_2, "OR-02", DEPT)));
    }

    @Test
    void emptyDatasetReturnsZeroRates() {
        when(analyticsMapper.selectRoomUsage(any(), any(), any())).thenReturn(List.of());
        when(analyticsMapper.selectFirstCases(any(), any(), any())).thenReturn(List.of());
        when(analyticsMapper.selectCancellationRows(any(), any(), any())).thenReturn(List.of());

        var utilization = service.utilization(new AnalyticsQuery("2026-12-01", "2026-12-01"),
                caller, CONTEXT);
        assertThat(utilization.getSummary().getTotalSurgeryMinutes()).isZero();
        assertThat(utilization.getSummary().getAvailableMinutes()).isEqualTo(1200);
        assertThat(utilization.getSummary().getUtilizationRate()).isZero();
        assertThat(utilization.getRooms()).hasSize(2);

        var onTime = service.onTime(new AnalyticsQuery("2026-12-01", "2026-12-01"), caller,
                CONTEXT);
        assertThat(onTime.getSummary().getEvaluatedCaseCount()).isZero();
        assertThat(onTime.getSummary().getExcludedDays()).isZero();
        assertThat(onTime.getSummary().getOnTimeRate()).isZero();
        assertThat(onTime.getActualStartSource().getValue()).isEqualTo("SCHEDULE_UPDATED_AT");

        var cancellations = service.cancellations(
                new AnalyticsQuery("2026-12-01", "2026-12-01"), caller, CONTEXT);
        assertThat(cancellations.getSummary().getTotalCases()).isZero();
        assertThat(cancellations.getSummary().getCancellationRate()).isZero();
        assertThat(cancellations.getByReason()).isEmpty();
    }

    @Test
    void utilizationSumsMinutesAndRoundsRate() {
        RoomUsageRow usage = new RoomUsageRow();
        usage.setRoomId(ROOM_1);
        usage.setCaseCount(2);
        usage.setTotalMinutes(120.4);
        when(analyticsMapper.selectRoomUsage(any(), any(), any())).thenReturn(List.of(usage));

        var report = service.utilization(new AnalyticsQuery("2026-12-01", "2026-12-02"), caller,
                CONTEXT);
        // 2 天 × 600 = 1200/间；2 间 = 2400；120 / 2400 = 0.05。
        assertThat(report.getSummary().getTotalSurgeryMinutes()).isEqualTo(120);
        assertThat(report.getSummary().getAvailableMinutes()).isEqualTo(2400);
        assertThat(report.getSummary().getUtilizationRate()).isEqualTo(0.05);
        assertThat(report.getSummary().getCaseCount()).isEqualTo(2);
        assertThat(report.getRooms().get(0).getUtilizationRate()).isEqualTo(0.1);
        assertThat(report.getRooms().get(1).getUtilizationRate()).isZero();
    }

    @Test
    void onTimeUsesAbsoluteThresholdAndSkipsMissingActualStart() {
        when(analyticsMapper.selectFirstCases(any(), any(), any())).thenReturn(List.of(
                firstCase(ROOM_1, "2026-12-01T08:00:00Z", "2026-12-01T08:15:00Z"),
                firstCase(ROOM_2, "2026-12-01T08:00:00Z", "2026-12-01T08:16:00Z"),
                firstCase(ROOM_2, "2026-12-02T08:00:00Z", null)));

        var report = service.onTime(new AnalyticsQuery("2026-12-01", "2026-12-02"), caller,
                CONTEXT);
        // 首台 3；已评估 2（updated_at 缺失计入 excludedDays 并从分母排除）；
        // 准点 1（15 分钟含阈值）；延误 1。
        assertThat(report.getSummary().getFirstCaseCount()).isEqualTo(3);
        assertThat(report.getSummary().getEvaluatedCaseCount()).isEqualTo(2);
        assertThat(report.getSummary().getExcludedDays()).isEqualTo(1);
        assertThat(report.getSummary().getOnTimeCount()).isEqualTo(1);
        assertThat(report.getSummary().getDelayedCount()).isEqualTo(1);
        assertThat(report.getSummary().getOnTimeRate()).isEqualTo(0.5);
        assertThat(report.getThresholdMinutes()).isEqualTo(15);
    }

    @Test
    void onTimeExcludesDaysWhosePlannedFirstCaseIsCancelledOrNotStarted() {
        when(analyticsMapper.selectFirstCases(any(), any(), any())).thenReturn(List.of(
                firstCase(ROOM_1, "2026-12-01T08:00:00Z", "2026-12-01T08:10:00Z", "COMPLETED"),
                firstCase(ROOM_1, "2026-12-02T08:00:00Z", "2026-12-02T08:00:00Z", "CANCELLED"),
                firstCase(ROOM_2, "2026-12-01T08:00:00Z", "2026-12-01T08:00:00Z", "PLANNED"),
                firstCase(ROOM_2, "2026-12-02T08:00:00Z", "2026-12-02T08:20:00Z",
                        "IN_PROGRESS")));

        var report = service.onTime(new AnalyticsQuery("2026-12-01", "2026-12-02"), caller,
                CONTEXT);
        // 计划首台 4：取消 1 + 未开始 1 排除；已评估 2（准点 1、延误 1），准点率 0.5。
        assertThat(report.getSummary().getFirstCaseCount()).isEqualTo(4);
        assertThat(report.getSummary().getEvaluatedCaseCount()).isEqualTo(2);
        assertThat(report.getSummary().getExcludedDays()).isEqualTo(2);
        assertThat(report.getSummary().getOnTimeRate()).isEqualTo(0.5);
        assertThat(report.getRooms()).filteredOn(room -> ROOM_1.toString()
                .equals(room.getRoomId())).singleElement().satisfies(room -> {
                    assertThat(room.getEvaluatedCaseCount()).isEqualTo(1);
                    assertThat(room.getExcludedDays()).isEqualTo(1);
                });
        assertThat(report.getRooms()).filteredOn(room -> ROOM_2.toString()
                .equals(room.getRoomId())).singleElement().satisfies(room -> {
                    assertThat(room.getEvaluatedCaseCount()).isEqualTo(1);
                    assertThat(room.getExcludedDays()).isEqualTo(1);
                });
    }

    @Test
    void configuredMaxRangeDaysAbove92IsClampedWithWarning() {
        SchedulingAnalyticsServiceImpl clamped = new SchedulingAnalyticsServiceImpl(
                analyticsMapper, roomMapper, dataScopeResolver, auditWriter, 15, 600, 200);
        // S-2：配置 200 不拒绝启动，但夹紧为 92；非法下限仍 fail-closed。
        clamped.validateConfiguration();
        when(analyticsMapper.selectRoomUsage(any(), any(), any())).thenReturn(List.of());

        var report = clamped.utilization(new AnalyticsQuery("2026-01-01", "2026-04-02"), caller,
                CONTEXT);
        assertThat(report.getDays()).isEqualTo(92);
        assertThatThrownBy(() -> clamped.utilization(
                new AnalyticsQuery("2026-01-01", "2026-04-03"), caller, CONTEXT))
                .isInstanceOf(SchedulingValidationException.class);
    }

    @Test
    void csvExportNeutralizesFormulaInjectionInTextCells() {
        when(roomMapper.selectList(any())).thenReturn(List.of(
                room(ROOM_1, "=OR-01", DEPT, "=1+1"),
                room(ROOM_2, "+OR-02", DEPT, "-2+3"),
                room(ROOM_3, "@OR-03", DEPT, "\tTAB"),
                room(ROOM_5, "OR-05", DEPT, "=1+1,\"q\"")));
        when(analyticsMapper.selectRoomUsage(any(), any(), any())).thenReturn(List.of());

        List<String> roomLines = exportLines("utilization").stream()
                .filter(line -> line.contains(",ROOM,")).toList();
        assertThat(csvCell(roomLines, "=OR-01")).isEqualTo("'=OR-01");
        assertThat(csvCell(roomLines, "+OR-02")).isEqualTo("'+OR-02");
        assertThat(csvCell(roomLines, "@OR-03")).isEqualTo("'@OR-03");
        assertThat(csvCell(roomLines, "=1+1")).isEqualTo("'=1+1");
        assertThat(csvCell(roomLines, "-2+3")).isEqualTo("'-2+3");
        assertThat(csvCell(roomLines, "\tTAB")).isEqualTo("'\tTAB");
        // 逗号/引号仍按 CSV 规则转义：前置单引号后再包裹并双写引号。
        assertThat(csvCell(roomLines, "=1+1,\"q\"")).isEqualTo("'=1+1,\"q\"");

        // 回车开头的单元格：前置单引号后因含 CR 仍按 CSV 规则包裹（用原始文本断言，
        // 避免 String.lines() 按 CR 拆行掩盖真实输出）。
        when(roomMapper.selectList(any())).thenReturn(List.of(
                room(ROOM_4, "\rCR-04", DEPT, "\rFORMULA")));
        String raw = exportRaw("utilization");
        assertThat(raw).contains("\"'\rCR-04\"").contains("\"'\rFORMULA\"");
    }

    @Test
    void csvExportNeutralizesFormulaInjectionInReasonCode() {
        when(analyticsMapper.selectCancellationRows(any(), any(), any())).thenReturn(List.of(
                cancellation(ROOM_1, "CANCELLED", "=HYPERLINK(\"http://x\")"),
                cancellation(ROOM_1, "CANCELLED", "@SUM(A1)"),
                cancellation(ROOM_1, "CANCELLED", "-1+1")));

        List<String> reasonLines = exportLines("cancellations").stream()
                .filter(line -> line.contains(",REASON,")).toList();
        assertThat(reasonLines).hasSize(3);
        List<String> reasonCodes = reasonLines.stream().map(SchedulingAnalyticsServiceImplTest::csvFields)
                .map(fields -> fields.get(6)).toList();
        assertThat(reasonCodes).containsExactlyInAnyOrder("'=HYPERLINK(\"http://x\")",
                "'@SUM(A1)", "'-1+1");
    }

    @Test
    void cancellationGroupsByReasonAndFallsBackToUnspecified() {
        when(analyticsMapper.selectCancellationRows(any(), any(), any())).thenReturn(List.of(
                cancellation(ROOM_1, "CANCELLED", "PATIENT_CONDITION"),
                cancellation(ROOM_1, "CANCELLED", null),
                cancellation(ROOM_2, "IN_PROGRESS", null)));

        var report = service.cancellations(new AnalyticsQuery("2026-12-01", "2026-12-01"),
                caller, CONTEXT);
        assertThat(report.getSummary().getTotalCases()).isEqualTo(3);
        assertThat(report.getSummary().getCancelledCases()).isEqualTo(2);
        assertThat(report.getSummary().getCancellationRate()).isEqualTo(0.6667);
        assertThat(report.getByReason()).hasSize(2);
        assertThat(report.getByReason()).extracting("reasonCode")
                .containsExactly("PATIENT_CONDITION", "UNSPECIFIED");
        assertThat(report.getByReason().get(0).getShareOfCancelled()).isEqualTo(0.5);
        assertThat(report.getByRoom()).hasSize(2);
    }

    @Test
    void roomScopeOnlyQueriesBoundRooms() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.ROOM, List.of(ROOM_1.toString())))));
        when(roomMapper.selectList(any())).thenReturn(List.of(room(ROOM_1, "OR-01", DEPT)));
        when(analyticsMapper.selectRoomUsage(any(), any(), any())).thenReturn(List.of());

        service.utilization(new AnalyticsQuery("2026-12-01", "2026-12-01"), caller, CONTEXT);

        verify(analyticsMapper).selectRoomUsage(any(), any(), eq(List.of(ROOM_1)));
    }

    @Test
    void deniedOrOutOfScopeDataScopeIsForbidden() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> service.utilization(
                new AnalyticsQuery("2026-12-01", "2026-12-01"), caller, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);

        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of(DEPT.toString())))));
        when(roomMapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.utilization(
                new AnalyticsQuery("2026-12-01", "2026-12-01", OTHER_DEPT.toString()), caller,
                CONTEXT)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void selfScopeIsForbiddenBecauseNoRoomObjectScope() {
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.SELF, List.of()))));
        assertThatThrownBy(() -> service.onTime(
                new AnalyticsQuery("2026-12-01", "2026-12-01"), caller, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void invalidParametersAreRejected() {
        assertThatThrownBy(() -> service.utilization(new AnalyticsQuery(null, "2026-12-01"),
                caller, CONTEXT)).isInstanceOf(SchedulingValidationException.class);
        assertThatThrownBy(() -> service.utilization(new AnalyticsQuery("2026-12-01", null),
                caller, CONTEXT)).isInstanceOf(SchedulingValidationException.class);
        assertThatThrownBy(() -> service.utilization(new AnalyticsQuery("2026-13-01", "2026-12-02"),
                caller, CONTEXT)).isInstanceOf(SchedulingValidationException.class);
        assertThatThrownBy(() -> service.utilization(new AnalyticsQuery("2026-12-02", "2026-12-01"),
                caller, CONTEXT)).isInstanceOf(SchedulingValidationException.class);
        assertThatThrownBy(() -> service.utilization(
                new AnalyticsQuery("2026-01-01", "2026-04-03"), caller, CONTEXT))
                .isInstanceOf(SchedulingValidationException.class);
        assertThatThrownBy(() -> service.utilization(
                new AnalyticsQuery("2026-12-01", "2026-12-02", "bad-uuid"), caller, CONTEXT))
                .isInstanceOf(SchedulingValidationException.class);
        assertThatThrownBy(() -> service.prepareExport(
                new AnalyticsExportQuery("unknown", "2026-12-01", "2026-12-02", "csv", null),
                caller, CONTEXT)).isInstanceOf(SchedulingValidationException.class);
        assertThatThrownBy(() -> service.prepareExport(
                new AnalyticsExportQuery("utilization", "2026-12-01", "2026-12-02", "xlsx", null),
                caller, CONTEXT)).isInstanceOf(SchedulingValidationException.class);
    }

    @Test
    void invalidConfigurationFailsFast() {
        SchedulingAnalyticsServiceImpl badThreshold = new SchedulingAnalyticsServiceImpl(
                analyticsMapper, roomMapper, dataScopeResolver, auditWriter, -1, 600, 92);
        assertThatThrownBy(badThreshold::validateConfiguration)
                .isInstanceOf(IllegalArgumentException.class);
        SchedulingAnalyticsServiceImpl badAvailable = new SchedulingAnalyticsServiceImpl(
                analyticsMapper, roomMapper, dataScopeResolver, auditWriter, 15, 0, 92);
        assertThatThrownBy(badAvailable::validateConfiguration)
                .isInstanceOf(IllegalArgumentException.class);
        SchedulingAnalyticsServiceImpl badRange = new SchedulingAnalyticsServiceImpl(
                analyticsMapper, roomMapper, dataScopeResolver, auditWriter, 15, 600, 0);
        assertThatThrownBy(badRange::validateConfiguration)
                .isInstanceOf(IllegalArgumentException.class);
        SchedulingAnalyticsServiceImpl good = new SchedulingAnalyticsServiceImpl(
                analyticsMapper, roomMapper, dataScopeResolver, auditWriter, 15, 600, 92);
        good.validateConfiguration();
    }

    @Test
    void csvExportHasBomHeaderAndNoPatientIdentifiers() {
        when(roomMapper.selectList(any())).thenReturn(List.of(room(ROOM_1, "OR-01", DEPT)));
        RoomUsageRow usage = new RoomUsageRow();
        usage.setRoomId(ROOM_1);
        usage.setCaseCount(1);
        usage.setTotalMinutes(120.0);
        when(analyticsMapper.selectRoomUsage(any(), any(), any())).thenReturn(List.of(usage));

        ExportPlan plan = service.prepareExport(
                new AnalyticsExportQuery("utilization", "2026-12-01", "2026-12-02", "csv", null),
                caller, CONTEXT);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        plan.writer().accept(output);
        byte[] bytes = output.toByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        List<String> lines = csv.lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).split(",", -1)).hasSize(19);
        assertThat(lines.get(1)).contains("utilization,2026-12-01,2026-12-02,SUMMARY,ALL,ALL,");
        assertThat(csv).doesNotContain("演示患者", "SYNTH-PAT", "住院号", "诊断", "证件号");
        assertThat(plan.dataRows()).isEqualTo(2);
        assertThat(plan.fileName()).isEqualTo("scheduling-utilization-2026-12-01-2026-12-02.csv");
        verify(auditWriter).record(eq("ANALYTICS_EXPORT"), eq("ANALYTICS"), eq("utilization"),
                eq(true), any(), eq(USER_ID), eq("demo_admin"), eq(CONTEXT));
    }

    @Test
    void auditDetailContainsOnlyAggregates() {
        when(analyticsMapper.selectFirstCases(any(), any(), any())).thenReturn(List.of());
        service.onTime(new AnalyticsQuery("2026-12-01", "2026-12-01"), caller, CONTEXT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("ANALYTICS_QUERY"), eq("ANALYTICS"), eq("on-time"),
                eq(true), detail.capture(), eq(USER_ID), eq("demo_admin"), eq(CONTEXT));
        assertThat(detail.getValue()).containsKeys("metric", "from", "to", "timezone",
                "roomCount", "firstCaseCount", "onTimeCount");
        assertThat(detail.getValue().values().toString())
                .doesNotContain("演示患者", "SYNTH-PAT", "住院号", "诊断");
    }

    private static DataScopeAssignments allScope() {
        return DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.ALL, List.of())));
    }

    private static OperatingRoomEntity room(UUID id, String roomNo, UUID deptId) {
        return room(id, roomNo, deptId, "麻醉科（合成）");
    }

    private static OperatingRoomEntity room(UUID id, String roomNo, UUID deptId, String deptName) {
        OperatingRoomEntity room = new OperatingRoomEntity();
        room.setId(id);
        room.setRoomNo(roomNo);
        room.setName("演示手术间（合成）");
        room.setRoomType("GENERAL");
        room.setStatus("IDLE");
        room.setDeptName(deptName);
        room.setDeptId(deptId);
        room.setSiteId(SITE);
        room.setCreatedAt(Instant.parse("2026-11-13T00:00:00Z"));
        room.setUpdatedAt(Instant.parse("2026-11-13T00:00:00Z"));
        room.setVersion(1L);
        room.setDeleted(false);
        return room;
    }

    private List<String> exportLines(String metric) {
        return exportRaw(metric).lines().toList();
    }

    private String exportRaw(String metric) {
        ExportPlan plan = service.prepareExport(
                new AnalyticsExportQuery(metric, "2026-12-01", "2026-12-01", "csv", null),
                caller, CONTEXT);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        plan.writer().accept(output);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String csvCell(List<String> lines, String expectedRaw) {
        for (String line : lines) {
            for (String field : csvFields(line)) {
                if (expectedRaw.equals(field.replace("'", ""))) {
                    return field;
                }
            }
        }
        throw new AssertionError("未找到 CSV 单元格：" + expectedRaw);
    }

    private static List<String> csvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char value = line.charAt(i);
            if (quoted) {
                if (value == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(value);
                }
            } else if (value == '"') {
                quoted = true;
            } else if (value == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(value);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static FirstCaseRow firstCase(UUID roomId, String startAt, String updatedAt) {
        return firstCase(roomId, startAt, updatedAt, "COMPLETED");
    }

    private static FirstCaseRow firstCase(UUID roomId, String startAt, String updatedAt,
            String status) {
        FirstCaseRow row = new FirstCaseRow();
        row.setRoomId(roomId);
        row.setStartAt(startAt == null ? null : Instant.parse(startAt));
        row.setUpdatedAt(updatedAt == null ? null : Instant.parse(updatedAt));
        row.setStatus(status);
        return row;
    }

    private static CancellationRow cancellation(UUID roomId, String status, String reasonCode) {
        CancellationRow row = new CancellationRow();
        row.setRoomId(roomId);
        row.setStatus(status);
        row.setReasonCode(reasonCode);
        return row;
    }
}
