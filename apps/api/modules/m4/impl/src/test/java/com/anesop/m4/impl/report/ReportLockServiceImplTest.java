package com.anesop.m4.impl.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.report.model.MultiReport;
import com.anesop.contracts.report.model.ReportLock;
import com.anesop.contracts.report.model.ReportLockList;
import com.anesop.contracts.report.model.ReportLockRequest;
import com.anesop.contracts.report.model.ReportTotals;
import com.anesop.contracts.report.model.WorkloadReport;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.report.MultiReportQuery;
import com.anesop.m4.api.report.ReportCaseFact;
import com.anesop.m4.api.report.ReportCaseSource;
import com.anesop.m4.api.report.ReportExportQuery;
import com.anesop.m4.api.report.ReportQuery;
import com.anesop.m4.api.report.ReportService;
import com.anesop.m4.impl.config.ReportProperties;
import com.anesop.m4.impl.persistence.entity.ReportSnapshotEntity;
import com.anesop.m4.impl.persistence.mapper.ReportSnapshotMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;

/**
 * 报表锁定与快照单测（M8-M4-02，report.yaml 0.2.0）。
 *
 * <p>覆盖：锁定生成快照（期间展开/月边界/params_hash/payload_hash/dataScope/rowCount）、
 * 同参数查询与导出读快照（手算值不变且不再查库）、不同参数/不同数据范围仍实时、
 * 重复锁定 409（预检 + 唯一键并发兜底）、参数与列表校验 400、详情 404、payload 损坏 fail-closed。
 *
 * <p>固定 SYNTHETIC 台次与 {@code ReportServiceImplTest} 一致：
 * 3 台次 / 215 分钟 / 3.5 加权 / 1 无时长；DEPT 维度 2 个桶。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportLockServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID DEPT_ANES = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final UUID DEPT_OR = UUID.fromString("f1000000-0000-4000-8000-000000000003");
    private static final UUID STAFF_ID = UUID.fromString("3a000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_report", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-6101");
    private static final String FROM = "2026-12-01";
    private static final String TO = "2026-12-31";
    private static final String ART = "SYNTH-ART-001";
    private static final String GAS = "SYNTH-GAS-002";

    @Mock
    private ReportCaseSource caseSource;
    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private ReportSnapshotMapper snapshotMapper;

    private ReportSnapshotCodec codec;
    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        ReportProperties properties = new ReportProperties();
        properties.setSurgeryWeights(Map.of(ART, new BigDecimal("1.5")));
        codec = new ReportSnapshotCodec(new ObjectMapper());
        service = new ReportServiceImpl(caseSource, dataScopeResolver, auditWriter, properties,
                snapshotMapper, codec);
        when(dataScopeResolver.resolve(any())).thenReturn(all());
        when(caseSource.findCases(any())).thenReturn(List.of(caseA(), caseB(), caseC()));
    }

    // ------------------------------------------------------------------
    // 锁定：快照元数据与期间
    // ------------------------------------------------------------------

    @Test
    void lockWorkloadStoresSnapshotMetadataAndAudits() {
        ReportSnapshotEntity stored = lockAndCapture(workloadRequest("2026-12"));

        assertThat(stored.getReport()).isEqualTo("workload");
        assertThat(stored.getPeriod()).isEqualTo("2026-12");
        assertThat(stored.getFromDate()).isEqualTo(LocalDate.parse("2026-12-01"));
        assertThat(stored.getToDate()).isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(stored.getParamsHash()).matches("[0-9a-f]{64}");
        assertThat(stored.getPayloadHash()).matches("[0-9a-f]{64}");
        assertThat(stored.getScopeHash()).matches("[0-9a-f]{64}");
        assertThat(stored.getRowCount()).isEqualTo(2);
        assertThat(stored.getDataScope()).isEqualTo("ALL");
        assertThat(stored.getLockedBy()).isEqualTo(ACTOR);
        assertThat(stored.getLockedByName()).isEqualTo("synth_report");
        assertThat(stored.getLockedAt()).isNotNull();
        assertThat(codec.readWorkload(stored.getPayload()).getCaseCount()).isEqualTo(3);
        assertThat(codec.readWorkload(stored.getPayload()).getAnesthesiaMinutes()).isEqualTo(215);

        verify(auditWriter).record(eq("REPORT_LOCK"), eq("REPORT"),
                eq(stored.getId().toString()), eq(true), anyMap(), eq(ACTOR),
                eq("synth_report"), eq(CONTEXT));
    }

    @Test
    void lockMonthPeriodExpandsToLastDayIncludingLeapFebruary() {
        ReportSnapshotEntity stored = lockAndCapture(workloadRequest("2024-02"));

        assertThat(stored.getFromDate()).isEqualTo(LocalDate.parse("2024-02-01"));
        assertThat(stored.getToDate()).isEqualTo(LocalDate.parse("2024-02-29"));
        assertThat(stored.getPeriod()).isEqualTo("2024-02");
    }

    @Test
    void lockFromToUsesRangeLabelAndLockParams() {
        ReportLockRequest request = workloadRequest(null);
        request.setFrom(LocalDate.parse(FROM));
        request.setTo(LocalDate.parse(TO));

        ReportSnapshotEntity stored = lockAndCapture(request);

        assertThat(stored.getPeriod()).isEqualTo(FROM + "~" + TO);
        assertThat(codec.readParams(stored.getParams()))
                .containsEntry("report", "workload")
                .containsEntry("from", FROM)
                .containsEntry("to", TO)
                .containsEntry("dimension", "DEPT")
                .containsEntry("scope", "ALL");
    }

    @Test
    void lockSelfScopeFingerprintIncludesCaller() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.SELF, List.of()))));

        ReportSnapshotEntity stored = lockAndCapture(workloadRequest("2026-12"));

        assertThat(stored.getDataScope()).isEqualTo("SELF");
        assertThat(String.valueOf(codec.readParams(stored.getParams()).get("scope")))
                .contains("SELF:" + ACTOR);
    }

    // ------------------------------------------------------------------
    // 锁定后查询/导出读快照（手算值不变）
    // ------------------------------------------------------------------

    @Test
    void queryUsesSnapshotAfterLockAndIgnoresChangedSource() {
        ReportSnapshotEntity stored = lockAndCapture(workloadRequest("2026-12"));
        when(snapshotMapper.selectByReportAndParamsHash("workload", stored.getParamsHash()))
                .thenReturn(stored);
        // 锁定后源数据变化：只剩 1 台次 60 分钟；快照必须仍返回 3 台次 / 215 分钟。
        when(caseSource.findCases(any())).thenReturn(List.of(singleCase()));
        clearInvocations(caseSource);

        WorkloadReport report = service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER,
                CONTEXT);

        assertThat(report.getSource()).isEqualTo(WorkloadReport.SourceEnum.SNAPSHOT);
        assertThat(report.getLockId()).isEqualTo(stored.getId().toString());
        assertThat(report.getLockedAt()).isNotNull();
        assertThat(report.getCaseCount()).isEqualTo(3);
        assertThat(report.getAnesthesiaMinutes()).isEqualTo(215);
        assertThat(report.getWeightedCaseCount()).isEqualByComparingTo("3.5");
        assertThat(report.getBuckets()).hasSize(2);
        verify(caseSource, never()).findCases(any());
    }

    @Test
    void queryStaysLiveForDifferentParametersOrDataScope() {
        ReportSnapshotEntity stored = lockAndCapture(workloadRequest("2026-12"));
        when(snapshotMapper.selectByReportAndParamsHash("workload", stored.getParamsHash()))
                .thenReturn(stored);

        // 不同维度：哈希不同 → 实时。
        WorkloadReport byStaff = service.workload(new ReportQuery(FROM, TO, "STAFF"), CALLER,
                CONTEXT);
        assertThat(byStaff.getSource()).isEqualTo(WorkloadReport.SourceEnum.LIVE);
        assertThat(byStaff.getLockId()).isNull();

        // 同一维度但数据范围收窄为 DEPT：哈希不同（范围指纹）→ 实时，避免跨范围读快照。
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT,
                        List.of(DEPT_ANES.toString())))));
        WorkloadReport byDeptScope = service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER,
                CONTEXT);
        assertThat(byDeptScope.getSource()).isEqualTo(WorkloadReport.SourceEnum.LIVE);
    }

    @Test
    void multiQueryFromSnapshotReplaysPaginationOnAllStoredRows() {
        ReportSnapshotEntity stored = lockAndCapture(multiRequest("2026-12"));
        when(snapshotMapper.selectByReportAndParamsHash("multi", stored.getParamsHash()))
                .thenReturn(stored);

        MultiReport report = service.multi(new MultiReportQuery(FROM, TO, "DEPT,SURGERY",
                "caseCount,anesthesiaMinutes", null, 2, 2), CALLER, CONTEXT);

        assertThat(report.getSource()).isEqualTo(MultiReport.SourceEnum.SNAPSHOT);
        assertThat(report.getTotal()).isEqualTo(3);
        assertThat(report.getTotalPages()).isEqualTo(2);
        assertThat(report.getPage()).isEqualTo(2);
        assertThat(report.getRows()).hasSize(1);
        assertThat(report.getTotals().getCaseCount()).isEqualTo(3);
        assertThat(report.getTotals().getGroupCount()).isEqualTo(3);
        assertThat(report.getLockId()).isEqualTo(stored.getId().toString());
    }

    @Test
    void exportUsesSnapshotRowsAndReportsSource() {
        ReportSnapshotEntity stored = lockAndCapture(workloadRequest("2026-12"));
        when(snapshotMapper.selectByReportAndParamsHash("workload", stored.getParamsHash()))
                .thenReturn(stored);
        when(caseSource.findCases(any())).thenReturn(List.of());
        clearInvocations(caseSource);

        ReportService.ExportPlan plan = service.prepareExport(new ReportExportQuery("workload",
                "csv", FROM, TO, "DEPT", null, null, null), CALLER, CONTEXT);

        assertThat(plan.source()).isEqualTo("SNAPSHOT");
        String csv = write(plan);
        assertThat(csv).contains("workload,TOTAL,DEPT,ALL,全部,3,215,3.5,1");
        verify(caseSource, never()).findCases(any());
    }

    @Test
    void exportMultiFromSnapshotKeepsAllGroupsAndSource() {
        ReportSnapshotEntity stored = lockAndCapture(multiRequest("2026-12"));
        when(snapshotMapper.selectByReportAndParamsHash("multi", stored.getParamsHash()))
                .thenReturn(stored);
        clearInvocations(caseSource);

        ReportService.ExportPlan plan = service.prepareExport(new ReportExportQuery("multi",
                "csv", FROM, TO, null, "DEPT,SURGERY", "caseCount,anesthesiaMinutes", null),
                CALLER, CONTEXT);

        assertThat(plan.source()).isEqualTo("SNAPSHOT");
        assertThat(plan.fileName()).startsWith("multi-DEPT+SURGERY-");
        String csv = write(plan);
        assertThat(csv).contains("multi,ROW,DEPT+SURGERY");
        assertThat(csv.split("\n")).hasSize(4); // 表头 + 3 个分组行
        verify(caseSource, never()).findCases(any());
    }

    @Test
    void exportWithoutLockStaysLive() {
        ReportService.ExportPlan plan = service.prepareExport(new ReportExportQuery("workload",
                "csv", FROM, TO, "DEPT", null, null, null), CALLER, CONTEXT);

        assertThat(plan.source()).isEqualTo("LIVE");
        assertThat(write(plan)).contains("workload,TOTAL,DEPT,ALL,全部,3,215");
    }

    // ------------------------------------------------------------------
    // 重复锁定 409
    // ------------------------------------------------------------------

    @Test
    void relockSameParametersConflicts() {
        ReportSnapshotEntity stored = lockAndCapture(workloadRequest("2026-12"));
        when(snapshotMapper.selectByReportAndParamsHash("workload", stored.getParamsHash()))
                .thenReturn(stored);

        assertThatThrownBy(() -> service.lock(workloadRequest("2026-12"), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("已锁定");
    }

    @Test
    void concurrentDuplicateInsertConflicts() {
        when(snapshotMapper.insertSnapshot(any()))
                .thenThrow(new DuplicateKeyException("uk_report_snapshot_params"));

        assertThatThrownBy(() -> service.lock(workloadRequest("2026-12"), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("已锁定");
    }

    // ------------------------------------------------------------------
    // 参数/范围/数据完整性
    // ------------------------------------------------------------------

    @Test
    void lockValidatesRequestParameters() {
        assertThatThrownBy(() -> service.lock(null, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("请求体不能为空");

        ReportLockRequest missingReport = workloadRequest("2026-12");
        missingReport.setReport(null);
        assertThatThrownBy(() -> service.lock(missingReport, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("report");

        ReportLockRequest bothForms = workloadRequest("2026-12");
        bothForms.setFrom(LocalDate.parse(FROM));
        assertThatThrownBy(() -> service.lock(bothForms, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("只能二选一");

        ReportLockRequest noPeriod = workloadRequest(null);
        assertThatThrownBy(() -> service.lock(noPeriod, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("必须提供 period 或 from+to");

        ReportLockRequest badPeriod = workloadRequest("2026-13");
        assertThatThrownBy(() -> service.lock(badPeriod, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("period");

        ReportLockRequest badSpan = workloadRequest(null);
        badSpan.setFrom(LocalDate.parse("2025-01-01"));
        badSpan.setTo(LocalDate.parse("2026-12-31"));
        assertThatThrownBy(() -> service.lock(badSpan, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("366");

        ReportLockRequest noDimension = workloadRequest("2026-12");
        noDimension.setDimension(null);
        assertThatThrownBy(() -> service.lock(noDimension, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("dimension");

        ReportLockRequest noMultiDimensions = multiRequest("2026-12");
        noMultiDimensions.setDimensions(List.of());
        assertThatThrownBy(() -> service.lock(noMultiDimensions, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("dimensions");
    }

    @Test
    void lockRejectsDeniedScope() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.denied());

        assertThatThrownBy(() -> service.lock(workloadRequest("2026-12"), CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void corruptSnapshotPayloadFailsClosed() {
        ReportSnapshotEntity corrupt = new ReportSnapshotEntity();
        corrupt.setId(UUID.randomUUID());
        corrupt.setReport("workload");
        corrupt.setPayload("{\"report\":\"workload\",\"workload\":null,\"rowCount\":0}");
        corrupt.setParamsHash("0".repeat(64));
        corrupt.setLockedAt(NOW);
        when(snapshotMapper.selectByReportAndParamsHash(eq("workload"), any()))
                .thenReturn(corrupt);

        assertThatThrownBy(() -> service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER,
                CONTEXT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void corruptMultiSnapshotDimensionsFailClosedOnExport() {
        // 导出按快照内维度编码重建列，非法/缺失编码视为 payload 损坏（500），不静默输出空列。
        when(snapshotMapper.selectByReportAndParamsHash(eq("multi"), any()))
                .thenReturn(multiSnapshotEntity(List.of("BAD")))
                .thenReturn(multiSnapshotEntity(null));

        assertThatThrownBy(() -> service.prepareExport(new ReportExportQuery("multi", "csv",
                FROM, TO, null, "DEPT,SURGERY", "caseCount", null), CALLER, CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度");
        assertThatThrownBy(() -> service.prepareExport(new ReportExportQuery("multi", "csv",
                FROM, TO, null, "DEPT,SURGERY", "caseCount", null), CALLER, CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度");
    }

    private ReportSnapshotEntity multiSnapshotEntity(List<String> dimensions) {
        ReportSnapshotEntity entity = new ReportSnapshotEntity();
        entity.setId(UUID.randomUUID());
        entity.setReport("multi");
        entity.setPayload(codec.writeMulti(new ReportSnapshotCodec.MultiSnapshot(
                LocalDate.parse(FROM), LocalDate.parse(TO), dimensions, List.of("caseCount"),
                List.of(), new ReportTotals(), 0)));
        entity.setParamsHash("0".repeat(64));
        entity.setLockedAt(NOW);
        return entity;
    }

    // ------------------------------------------------------------------
    // 列表/详情
    // ------------------------------------------------------------------

    @Test
    void listAndGetReturnLockMetadata() {
        ReportSnapshotEntity stored = lockAndCapture(workloadRequest("2026-12"));
        when(snapshotMapper.selectLocks("workload", 10)).thenReturn(List.of(stored));
        when(snapshotMapper.selectById(stored.getId())).thenReturn(stored);

        ReportLockList list = service.list("workload", 10, CALLER);
        assertThat(list.getTotal()).isEqualTo(1);
        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().get(0).getLockId()).isEqualTo(stored.getId().toString());
        assertThat(list.getItems().get(0).getPeriod()).isEqualTo("2026-12");
        assertThat(list.getItems().get(0).getRowCount()).isEqualTo(2);
        assertThat(list.getItems().get(0).getParams()).containsEntry("dimension", "DEPT");

        ReportLock detail = service.get(stored.getId().toString(), CALLER);
        assertThat(detail.getReport()).isEqualTo(ReportLock.ReportEnum.WORKLOAD);
        assertThat(detail.getFrom()).isEqualTo(LocalDate.parse(FROM));
        assertThat(detail.getDataScope()).isEqualTo("ALL");
        assertThat(detail.getLockedByName()).isEqualTo("synth_report");

        assertThatThrownBy(() -> service.get("not-a-uuid", CALLER))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID().toString(), CALLER))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.list("weekly", 10, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("report");
        assertThatThrownBy(() -> service.list(null, 0, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("1–200");
    }

    // ------------------------------------------------------------------
    // 编解码
    // ------------------------------------------------------------------

    @Test
    void canonicalParamsAreOrderIndependentAndHashIsStable() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("report", "workload");
        first.put("from", FROM);
        first.put("scope", "ALL");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("scope", "ALL");
        second.put("from", FROM);
        second.put("report", "workload");

        assertThat(codec.canonical(first)).isEqualTo(codec.canonical(second));
        assertThat(codec.hash(codec.canonical(first))).matches("[0-9a-f]{64}")
                .isEqualTo(codec.hash(codec.canonical(second)));
        assertThat(codec.readParams(codec.canonical(first))).containsEntry("report", "workload");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private ReportSnapshotEntity lockAndCapture(ReportLockRequest request) {
        ArgumentCaptor<ReportSnapshotEntity> captor =
                ArgumentCaptor.forClass(ReportSnapshotEntity.class);
        when(snapshotMapper.insertSnapshot(captor.capture())).thenReturn(1);
        ReportLock lock = service.lock(request, CALLER, CONTEXT);
        ReportSnapshotEntity stored = captor.getValue();
        assertThat(lock.getLockId()).isEqualTo(stored.getId().toString());
        return stored;
    }

    private static ReportLockRequest workloadRequest(String period) {
        ReportLockRequest request = new ReportLockRequest();
        request.setReport(ReportLockRequest.ReportEnum.WORKLOAD);
        request.setPeriod(period);
        request.setDimension(ReportLockRequest.DimensionEnum.DEPT);
        return request;
    }

    private static ReportLockRequest multiRequest(String period) {
        ReportLockRequest request = new ReportLockRequest();
        request.setReport(ReportLockRequest.ReportEnum.MULTI);
        request.setPeriod(period);
        request.setDimensions(List.of(ReportLockRequest.DimensionsEnum.DEPT,
                ReportLockRequest.DimensionsEnum.SURGERY));
        request.setMetrics(List.of(ReportLockRequest.MetricsEnum.CASE_COUNT,
                ReportLockRequest.MetricsEnum.ANESTHESIA_MINUTES));
        return request;
    }

    private static DataScopeAssignments all() {
        return DataScopeAssignments.of(List.of(new DataScopeAssignment(DataScope.ALL, List.of())));
    }

    private static ReportCaseFact caseA() {
        return new ReportCaseFact("s-a", "f2000000-0000-4000-8000-0000000000c1", "OR-01",
                DEPT_ANES.toString(), "麻醉科（合成）", ART, "演示关节置换术（合成）",
                STAFF_ID.toString(), "SYN-AN-001", "演示麻醉医师（合成）",
                Instant.parse("2026-12-02T00:00:00Z"), Instant.parse("2026-12-02T02:05:00Z"));
    }

    private static ReportCaseFact caseB() {
        return new ReportCaseFact("s-b", "f2000000-0000-4000-8000-0000000000c1", "OR-01",
                DEPT_ANES.toString(), "麻醉科（合成）", GAS, "演示胃肠手术（合成）", null, null,
                null, null, null);
    }

    private static ReportCaseFact caseC() {
        return new ReportCaseFact("s-c", "f2000000-0000-4000-8000-0000000000c5", "OR-05",
                DEPT_OR.toString(), "手术室（合成）", "SYNTH-GAS-002", "演示胃肠手术（合成）",
                null, null, null, Instant.parse("2026-12-04T00:00:00Z"),
                Instant.parse("2026-12-04T01:30:00Z"));
    }

    private static ReportCaseFact singleCase() {
        return new ReportCaseFact("s-x", "f2000000-0000-4000-8000-0000000000c1", "OR-01",
                DEPT_ANES.toString(), "麻醉科（合成）", ART, "演示关节置换术（合成）",
                STAFF_ID.toString(), "SYN-AN-001", "演示麻醉医师（合成）",
                Instant.parse("2026-12-02T00:00:00Z"), Instant.parse("2026-12-02T01:00:00Z"));
    }

    private static String write(ReportService.ExportPlan plan) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        plan.writer().accept(output);
        return output.toString(StandardCharsets.UTF_8);
    }
}
