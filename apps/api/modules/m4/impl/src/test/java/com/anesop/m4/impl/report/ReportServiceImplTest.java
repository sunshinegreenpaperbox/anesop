package com.anesop.m4.impl.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.report.model.MultiReport;
import com.anesop.contracts.report.model.WorkloadBucket;
import com.anesop.contracts.report.model.WorkloadReport;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.report.MultiReportQuery;
import com.anesop.m4.api.report.ReportCaseFact;
import com.anesop.m4.api.report.ReportCaseFilter;
import com.anesop.m4.api.report.ReportCaseSource;
import com.anesop.m4.api.report.ReportExportQuery;
import com.anesop.m4.api.report.ReportQuery;
import com.anesop.m4.api.report.ReportService;
import com.anesop.m4.impl.config.ReportProperties;
import com.anesop.m4.impl.persistence.mapper.ReportSnapshotMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
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
 * 报表服务单测（M4-M16-01）：工作量手算口径、多维分页与合计守恒、维度/指标白名单、
 * 366 天跨度、DataScope fail-closed、CSV BOM/列头/防公式注入、空数据 0 与权重配置校验。
 *
 * <p>固定数据（SYNTHETIC）：
 * <ul>
 *   <li>A：麻醉科 / SYNTH-ART-001 / 记录单 00:00→02:05Z（125 分钟）/ 本人(demo_self)；</li>
 *   <li>B：麻醉科 / SYNTH-GAS-002 / 无记录单（0 分钟，计 unrecorded）；</li>
 *   <li>C：手术室 / {@code =SUM(1,2)} / 记录单 00:00→01:30Z（90 分钟）/ 无人员档案。</li>
 * </ul>
 * 配置 SYNTH-ART-001 权重 1.5 ⇒ 加权台次 = 1.5 + 1 + 1 = 3.5；台次 3；时长 215。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID DEPT_ANES = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final UUID DEPT_OR = UUID.fromString("f1000000-0000-4000-8000-000000000003");
    private static final UUID STAFF_ID = UUID.fromString("3a000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_report", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-6001");
    private static final String FROM = "2026-12-01";
    private static final String TO = "2026-12-31";
    private static final String ART = "SYNTH-ART-001";
    private static final String GAS = "SYNTH-GAS-002";
    private static final String INJECTION = "=SUM(1,2)";

    @Mock
    private ReportCaseSource caseSource;
    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private ReportSnapshotMapper snapshotMapper;

    private ReportProperties properties;
    private ReportServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new ReportProperties();
        properties.setSurgeryWeights(Map.of(ART, new BigDecimal("1.5")));
        service = new ReportServiceImpl(caseSource, dataScopeResolver, auditWriter, properties,
                snapshotMapper, new ReportSnapshotCodec(new ObjectMapper()));
        when(dataScopeResolver.resolve(any())).thenReturn(all());
        when(caseSource.findCases(any())).thenReturn(List.of(caseA(), caseB(), caseC()));
    }

    // ------------------------------------------------------------------
    // 工作量：手算期望
    // ------------------------------------------------------------------

    @Test
    void workloadByDeptMatchesHandCalculatedTotals() {
        WorkloadReport report = service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER, CONTEXT);

        assertThat(report.getFrom().toString()).isEqualTo(FROM);
        assertThat(report.getTo().toString()).isEqualTo(TO);
        assertThat(report.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(report.getDimension().getValue()).isEqualTo("DEPT");
        assertThat(report.getCaseCount()).isEqualTo(3);
        assertThat(report.getAnesthesiaMinutes()).isEqualTo(125 + 0 + 90);
        assertThat(report.getWeightedCaseCount()).isEqualByComparingTo("3.5");
        assertThat(report.getUnrecordedCaseCount()).isEqualTo(1);
        assertThat(report.getFormula()).contains("started_at");
        assertThat(report.getWeightFormula()).contains("默认 1.0");

        assertThat(report.getBuckets()).hasSize(2);
        WorkloadBucket anes = report.getBuckets().get(0);
        assertThat(anes.getKey()).isEqualTo(DEPT_ANES.toString());
        assertThat(anes.getLabel()).isEqualTo("麻醉科（合成）");
        assertThat(anes.getCaseCount()).isEqualTo(2);
        assertThat(anes.getAnesthesiaMinutes()).isEqualTo(125);
        assertThat(anes.getWeightedCaseCount()).isEqualByComparingTo("2.5");
        assertThat(anes.getUnrecordedCaseCount()).isEqualTo(1);
        // 术式分布按台次倒序、编码升序：两条各 1 台 → 编码升序 ART 在前。
        assertThat(anes.getSurgeryDistribution()).hasSize(2);
        assertThat(anes.getSurgeryDistribution().get(0).getSurgeryCode()).isEqualTo(ART);
        assertThat(anes.getSurgeryDistribution().get(0).getAnesthesiaMinutes()).isEqualTo(125);
        assertThat(anes.getSurgeryDistribution().get(1).getSurgeryCode()).isEqualTo(GAS);
        assertThat(anes.getSurgeryDistribution().get(1).getAnesthesiaMinutes()).isZero();

        WorkloadBucket or = report.getBuckets().get(1);
        assertThat(or.getKey()).isEqualTo(DEPT_OR.toString());
        assertThat(or.getCaseCount()).isEqualTo(1);
        assertThat(or.getAnesthesiaMinutes()).isEqualTo(90);

        ArgumentCaptor<Map<String, Object>> detail = detailCaptor();
        verify(auditWriter).record(eq("REPORT_QUERY"), eq("REPORT"), eq("workload"), eq(true),
                detail.capture(), eq(ACTOR), eq("synth_report"), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("dimension", "DEPT")
                .containsEntry("caseCount", 3)
                .containsEntry("timezone", "Asia/Shanghai")
                .doesNotContainKeys("patientName", "inpatientNo", "diagnosis");
    }

    @Test
    void workloadByStaffUsesStaffNoNameAndFallbackBucketLast() {
        WorkloadReport report = service.workload(new ReportQuery(FROM, TO, "STAFF"), CALLER, CONTEXT);

        assertThat(report.getBuckets()).hasSize(2);
        WorkloadBucket assigned = report.getBuckets().get(0);
        assertThat(assigned.getLabel()).isEqualTo("SYN-AN-001 演示麻醉医师（合成）");
        assertThat(assigned.getCaseCount()).isEqualTo(1);
        WorkloadBucket fallback = report.getBuckets().get(1);
        assertThat(fallback.getKey()).isEqualTo("UNASSIGNED");
        assertThat(fallback.getLabel()).isEqualTo("未匹配医师");
        assertThat(fallback.getCaseCount()).isEqualTo(2);
        assertThat(fallback.getWeightedCaseCount()).isEqualByComparingTo("2.0");
    }

    @Test
    void aggregatesFiveThousandCasesWithinLocalBaseline() {
        // 性能基线（本机、无数据库）：366 天内 5000 台次的应用内聚合应远低于 5s，
        // 与方案书 §3.8.1“月度工作量报表 < 10s”留有余量；投影 SQL 由集成测试覆盖。
        List<ReportCaseFact> facts = new ArrayList<>();
        for (int index = 0; index < 5000; index++) {
            String dept = index % 2 == 0 ? DEPT_ANES.toString() : DEPT_OR.toString();
            facts.add(new ReportCaseFact("s-" + index, "room-" + (index % 2), "OR-01", dept,
                    "合成科室", index % 5 == 0 ? ART : GAS, "合成术式", null, null, null,
                    Instant.parse("2031-03-02T00:00:00Z"),
                    Instant.parse("2031-03-02T00:30:00Z")));
        }
        when(caseSource.findCases(any())).thenReturn(facts);

        long started = System.nanoTime();
        WorkloadReport report = service.workload(
                new ReportQuery("2031-01-01", "2031-12-31", "DEPT"), CALLER, CONTEXT);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(report.getCaseCount()).isEqualTo(5000);
        assertThat(report.getAnesthesiaMinutes()).isEqualTo(150_000);
        // 每 5 台一次 ART（权重 1.5）：1000×1.5 + 4000×1 = 5500。
        assertThat(report.getWeightedCaseCount()).isEqualByComparingTo("5500");
        assertThat(elapsedMs).isLessThan(5_000);
        System.out.printf("[M4-M16-01][benchmark] cases=5000 elapsedMs=%d baseline<5000ms%n",
                elapsedMs);
    }

    @Test
    void workloadEmptyDatasetReturnsZeroAndEmptyBuckets() {
        when(caseSource.findCases(any())).thenReturn(List.of());

        WorkloadReport report = service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER, CONTEXT);

        assertThat(report.getCaseCount()).isZero();
        assertThat(report.getAnesthesiaMinutes()).isZero();
        assertThat(report.getWeightedCaseCount()).isEqualByComparingTo("0");
        assertThat(report.getUnrecordedCaseCount()).isZero();
        assertThat(report.getBuckets()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 多维报表：组合维度、分页与合计守恒
    // ------------------------------------------------------------------

    @Test
    void multiPaginatesAndTotalsCoverAllGroups() {
        MultiReport first = service.multi(
                new MultiReportQuery(FROM, TO, "DEPT,SURGERY", "caseCount,anesthesiaMinutes", null,
                        1, 2), CALLER, CONTEXT);

        assertThat(first.getDimensions()).containsExactly("DEPT", "SURGERY");
        assertThat(first.getMetrics()).containsExactly("caseCount", "anesthesiaMinutes");
        assertThat(first.getTotal()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getRows()).hasSize(2);
        // 排序：麻醉科（id 较小）→ 科室内术式编码升序。
        assertThat(first.getRows().get(0).getDeptId()).isEqualTo(DEPT_ANES.toString());
        assertThat(first.getRows().get(0).getSurgeryCode()).isEqualTo(ART);
        assertThat(first.getRows().get(1).getSurgeryCode()).isEqualTo(GAS);
        // 合计覆盖全部分组，不受分页影响。
        assertThat(first.getTotals().getCaseCount()).isEqualTo(3);
        assertThat(first.getTotals().getAnesthesiaMinutes()).isEqualTo(215);
        assertThat(first.getTotals().getWeightedCaseCount()).isEqualByComparingTo("3.5");
        assertThat(first.getTotals().getGroupCount()).isEqualTo(3);

        MultiReport second = service.multi(
                new MultiReportQuery(FROM, TO, "DEPT,SURGERY", "caseCount", null, 2, 2), CALLER,
                CONTEXT);
        assertThat(second.getRows()).hasSize(1);
        assertThat(second.getRows().get(0).getDeptId()).isEqualTo(DEPT_OR.toString());
        assertThat(second.getRows().get(0).getSurgeryCode()).isEqualTo(INJECTION);
        assertThat(second.getTotals().getCaseCount()).isEqualTo(3);
    }

    @Test
    void multiEmptyDatasetHasZeroPages() {
        when(caseSource.findCases(any())).thenReturn(List.of());

        MultiReport report = service.multi(
                new MultiReportQuery(FROM, TO, "DEPT", "caseCount", null, 1, 20), CALLER, CONTEXT);

        assertThat(report.getRows()).isEmpty();
        assertThat(report.getTotal()).isZero();
        assertThat(report.getTotalPages()).isZero();
        assertThat(report.getTotals().getCaseCount()).isZero();
        assertThat(report.getTotals().getGroupCount()).isZero();
    }

    // ------------------------------------------------------------------
    // 白名单、跨度与参数校验（400）
    // ------------------------------------------------------------------

    @Test
    void rejectsNonWhitelistedDimensionAndMetrics() {
        assertThatThrownBy(() -> service.workload(new ReportQuery(FROM, TO, "PATIENT"), CALLER,
                CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("DEPT/STAFF/SURGERY/ROOM");
        assertThatThrownBy(() -> service.multi(
                new MultiReportQuery(FROM, TO, "DEPT,FOO", "caseCount", null, 1, 20), CALLER,
                CONTEXT))
                .isInstanceOfSatisfying(M4ValidationException.class,
                        failure -> assertThat(failure.field()).isEqualTo("dimensions"));
        assertThatThrownBy(() -> service.multi(
                new MultiReportQuery(FROM, TO, "DEPT", "revenue", null, 1, 20), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("caseCount/anesthesiaMinutes/weightedCaseCount");
        assertThatThrownBy(() -> service.multi(
                new MultiReportQuery(FROM, TO, "", "caseCount", null, 1, 20), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> service.multi(
                new MultiReportQuery(FROM, TO, "DEPT", "caseCount", null, 0, 20), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> service.multi(
                new MultiReportQuery(FROM, TO, "DEPT", "caseCount", null, 1, 201), CALLER,
                CONTEXT))
                .isInstanceOf(M4ValidationException.class);
    }

    @Test
    void rejectsRangeLongerThan366DaysAndInvalidDates() {
        assertThatThrownBy(() -> service.workload(
                new ReportQuery("2026-01-01", "2027-01-02", "DEPT"), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("366");
        // 正好 366 天（含首尾）允许。
        assertThatCode(() -> service.workload(
                new ReportQuery("2026-01-01", "2027-01-01", "DEPT"), CALLER, CONTEXT))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.workload(
                new ReportQuery("2026-12-31", "2026-12-01", "DEPT"), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("结束日期");
        assertThatThrownBy(() -> service.workload(new ReportQuery("2026/12/01", TO, "DEPT"),
                CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> service.workload(new ReportQuery(FROM, TO, "DEPT", "not-uuid"),
                CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("deptId");
    }

    // ------------------------------------------------------------------
    // 数据范围（fail-closed）
    // ------------------------------------------------------------------

    @Test
    void dataScopeDeniedOrEmptyOrgScopeIsForbidden() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER,
                CONTEXT))
                .isInstanceOf(AccessDeniedException.class);

        // DEPT 角色存在但没有任何科室绑定：不得回退为全院，必须 403。
        when(dataScopeResolver.resolve(any())).thenReturn(
                DataScopeAssignments.of(List.of(new DataScopeAssignment(DataScope.DEPT, List.of()))));
        assertThatThrownBy(() -> service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER,
                CONTEXT))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("无可用报表数据范围");
    }

    @Test
    void dataScopeDeptFiltersByOrgAndRejectsOutOfScopeDepartment() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of(DEPT_ANES.toString())))));

        service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER, CONTEXT);
        ArgumentCaptor<ReportCaseFilter> captor = ArgumentCaptor.forClass(ReportCaseFilter.class);
        verify(caseSource).findCases(captor.capture());
        assertThat(captor.getValue().unrestricted()).isFalse();
        assertThat(captor.getValue().orgIds()).containsExactly(DEPT_ANES);
        assertThat(captor.getValue().roomIds()).isEmpty();

        assertThatThrownBy(() -> service.workload(
                new ReportQuery(FROM, TO, "DEPT", DEPT_OR.toString()), CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("科室不在当前用户数据范围内");
    }

    @Test
    void dataScopeSelfFiltersByCurrentUser() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.SELF, List.of()))));

        service.workload(new ReportQuery(FROM, TO, "STAFF"), CALLER, CONTEXT);
        ArgumentCaptor<ReportCaseFilter> captor = ArgumentCaptor.forClass(ReportCaseFilter.class);
        verify(caseSource).findCases(captor.capture());
        assertThat(captor.getValue().selfScoped()).isTrue();
        assertThat(captor.getValue().selfUserId()).isEqualTo(ACTOR);
        assertThat(captor.getValue().unrestricted()).isFalse();
    }

    // ------------------------------------------------------------------
    // CSV 导出：BOM、列头、防注入、行数、无 PHI
    // ------------------------------------------------------------------

    @Test
    void csvWorkloadExportHasBomHeaderInjectionGuardAndRowCount() {
        ReportService.ExportPlan plan = service.prepareExport(
                new ReportExportQuery("workload", "csv", FROM, TO, "DEPT", null, null, null),
                CALLER, CONTEXT);
        String csv = write(plan);

        assertThat(csv).startsWith("\uFEFF");
        String[] lines = csv.substring(1).split("\n");
        assertThat(lines[0]).isEqualTo(
                "report,level,dimension,key,label,caseCount,anesthesiaMinutes,weightedCaseCount,"
                        + "unrecordedCaseCount,surgeryCode,surgeryName,from,to,timezone");
        // TOTAL + 2 BUCKET + 3 SURGERY（麻醉科 2 + 手术室 1）
        assertThat(lines).hasSize(1 + 6);
        assertThat(plan.dataRows()).isEqualTo(6);
        assertThat(plan.fileName()).isEqualTo("workload-DEPT-2026-12-01-2026-12-31.csv");
        assertThat(csv).contains("workload,TOTAL,DEPT,ALL,全部,3,215,3.5,1");
        // 防公式注入：术式名以 = 开头时前置单引号。
        assertThat(csv).contains("'=SUM(1,2)");
        // 无 PHI / 无患者标识列。
        assertThat(csv).doesNotContain("患者", "住院号", "身份证", "手机号", "诊断");
        verify(auditWriter).record(eq("REPORT_EXPORT"), eq("REPORT"), eq("workload"), eq(true),
                any(), eq(ACTOR), eq("synth_report"), eq(CONTEXT));
    }

    @Test
    void csvMultiExportContainsOneRowPerGroup() {
        ReportService.ExportPlan plan = service.prepareExport(
                new ReportExportQuery("multi", "csv", FROM, TO, null, "DEPT,STAFF",
                        "caseCount,anesthesiaMinutes", null), CALLER, CONTEXT);
        String csv = write(plan);

        assertThat(plan.dataRows()).isEqualTo(3);
        assertThat(plan.fileName()).isEqualTo("multi-DEPT+STAFF-2026-12-01-2026-12-31.csv");
        assertThat(csv).contains("multi,ROW,DEPT+STAFF,");
        assertThat(csv.lines().count()).isEqualTo(4);
    }

    @Test
    void csvExportRejectsUnknownReportOrFormat() {
        assertThatThrownBy(() -> service.prepareExport(
                new ReportExportQuery("quality", "csv", FROM, TO, "DEPT", null, null, null),
                CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("workload 或 multi");
        assertThatThrownBy(() -> service.prepareExport(
                new ReportExportQuery("workload", "xlsx", FROM, TO, "DEPT", null, null, null),
                CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("仅支持 csv");
        assertThatThrownBy(() -> service.prepareExport(
                new ReportExportQuery("workload", "csv", FROM, TO, null, null, null, null), CALLER,
                CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("dimension");
    }

    @Test
    void emptyDatasetCsvHasOnlyTotalRow() {
        when(caseSource.findCases(any())).thenReturn(List.of());

        ReportService.ExportPlan plan = service.prepareExport(
                new ReportExportQuery("workload", "csv", FROM, TO, "DEPT", null, null, null),
                CALLER, CONTEXT);

        assertThat(plan.dataRows()).isEqualTo(1);
        assertThat(write(plan)).contains("workload,TOTAL,DEPT,ALL,全部,0,0,0,0");
    }

    // ------------------------------------------------------------------
    // 权重配置与静态口径
    // ------------------------------------------------------------------

    @Test
    void rejectsNonPositiveWeightsAtStartup() {
        properties.setDefaultWeight(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.validateConfiguration())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-weight");

        properties.setDefaultWeight(BigDecimal.ONE);
        properties.setSurgeryWeights(Map.of(ART, new BigDecimal("-1")));
        assertThatThrownBy(() -> service.validateConfiguration())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surgery-weights");
    }

    @Test
    void minutesUsesFloorAndUnrecordedWhenTimeMissing() {
        assertThat(ReportServiceImpl.hasDuration(caseB())).isFalse();
        assertThat(ReportServiceImpl.minutes(caseB())).isZero();
        // 2 小时 5 分 59 秒 → 向下取整 125 分钟。
        ReportCaseFact almost = new ReportCaseFact("s", "r", "OR-01", null, null, null, null, null,
                null, null, Instant.parse("2026-12-02T00:00:00Z"),
                Instant.parse("2026-12-02T02:05:59Z"));
        assertThat(ReportServiceImpl.minutes(almost)).isEqualTo(125);
        // 结束早于开始（异常数据）按无时长处理，不倒扣。
        ReportCaseFact reversed = new ReportCaseFact("s", "r", "OR-01", null, null, null, null,
                null, null, null, Instant.parse("2026-12-02T02:00:00Z"),
                Instant.parse("2026-12-02T01:00:00Z"));
        assertThat(ReportServiceImpl.hasDuration(reversed)).isFalse();
        assertThat(ReportServiceImpl.minutes(reversed)).isZero();
    }

    @Test
    void formulaRiskOnlyMatchesExcelTriggerCharacters() {
        assertThat(ReportServiceImpl.isFormulaRisk("=SUM(1,2)")).isTrue();
        assertThat(ReportServiceImpl.isFormulaRisk("+86")).isTrue();
        assertThat(ReportServiceImpl.isFormulaRisk("-1")).isTrue();
        assertThat(ReportServiceImpl.isFormulaRisk("@cmd")).isTrue();
        assertThat(ReportServiceImpl.isFormulaRisk("麻醉科（合成）")).isFalse();
        assertThat(ReportServiceImpl.isFormulaRisk("")).isFalse();
        assertThat(ReportServiceImpl.isFormulaRisk(null)).isFalse();
    }

    @Test
    void workloadFallbackBucketsForMissingDeptRoomAndSurgery() {
        List<ReportCaseFact> facts = List.of(
                new ReportCaseFact("s-1", null, null, null, null, null, null, null, null, null,
                        null, null),
                new ReportCaseFact("s-2", "room-1", null, null, null, "CODE-1", null, null, null,
                        null, null, null));
        when(caseSource.findCases(any())).thenReturn(facts);

        WorkloadReport room = service.workload(new ReportQuery(FROM, TO, "ROOM"), CALLER,
                CONTEXT);
        assertThat(room.getCaseCount()).isEqualTo(2);
        assertThat(room.getBuckets()).hasSize(2);
        assertThat(room.getBuckets().get(0).getKey()).isEqualTo("room-1");
        // roomNo 缺失时展示回退为术间标识（不展示空标签）。
        assertThat(room.getBuckets().get(0).getLabel()).isEqualTo("room-1");
        assertThat(room.getBuckets().get(1).getKey()).isEqualTo("UNASSIGNED");
        assertThat(room.getBuckets().get(1).getLabel()).isEqualTo("未匹配术间");

        WorkloadReport surgery = service.workload(new ReportQuery(FROM, TO, "SURGERY"), CALLER,
                CONTEXT);
        assertThat(surgery.getBuckets()).hasSize(2);
        assertThat(surgery.getBuckets().get(0).getKey()).isEqualTo("CODE-1");
        assertThat(surgery.getBuckets().get(1).getKey()).isEqualTo("UNSPECIFIED");
        assertThat(surgery.getBuckets().get(1).getLabel()).isEqualTo("未登记术式");

        WorkloadReport dept = service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER, CONTEXT);
        assertThat(dept.getBuckets()).hasSize(1);
        assertThat(dept.getBuckets().get(0).getKey()).isEqualTo("UNASSIGNED");
        assertThat(dept.getBuckets().get(0).getLabel()).isEqualTo("未匹配科室");
    }

    @Test
    void multiAllDimensionsAndCsvCoverStaffAndRoomColumns() {
        List<ReportCaseFact> facts = List.of(
                new ReportCaseFact("s-1", "room-1", null, DEPT_ANES.toString(), null, "CODE-1",
                        null, STAFF_ID.toString(), null, null, null, null),
                new ReportCaseFact("s-2", null, null, null, null, null, null, null, null, null,
                        null, null));
        when(caseSource.findCases(any())).thenReturn(facts);

        MultiReport report = service.multi(new MultiReportQuery(FROM, TO, "DEPT,STAFF,SURGERY,ROOM",
                "weightedCaseCount", null, null, null), CALLER, CONTEXT);
        assertThat(report.getDimensions()).containsExactly("DEPT", "STAFF", "SURGERY", "ROOM");
        assertThat(report.getPage()).isEqualTo(1);
        assertThat(report.getSize()).isEqualTo(20);
        assertThat(report.getTotal()).isEqualTo(2);
        assertThat(report.getRows()).hasSize(2);
        // 兜底行排在最后，且未匹配维度字段为 null。
        assertThat(report.getRows().get(1).getDeptId()).isNull();
        assertThat(report.getRows().get(1).getStaffId()).isNull();
        assertThat(report.getRows().get(1).getRoomNo()).isNull();
        // 术式兜底名与 workload 维度桶统一为中文“未登记术式”，不暴露内部键。
        assertThat(report.getRows().get(1).getSurgeryCode()).isEqualTo("UNSPECIFIED");
        assertThat(report.getRows().get(1).getSurgeryName()).isEqualTo("未登记术式");

        ReportService.ExportPlan plan = service.prepareExport(new ReportExportQuery("multi", "csv",
                FROM, TO, null, "DEPT,STAFF,SURGERY,ROOM", "weightedCaseCount", null), CALLER,
                CONTEXT);
        String csv = write(plan);
        assertThat(plan.dataRows()).isEqualTo(2);
        assertThat(csv).contains("multi,ROW,DEPT+STAFF+SURGERY+ROOM,");
        assertThat(csv).contains("UNASSIGNED");
    }

    @Test
    void workloadSurgeryDimensionExportSkipsDuplicateDistributionRows() {
        ReportService.ExportPlan plan = service.prepareExport(new ReportExportQuery("workload",
                "csv", FROM, TO, "SURGERY", null, null, null), CALLER, CONTEXT);
        // TOTAL + 3 个术式桶（SURGERY 维度桶即术式，不再输出 SURGERY 分布行）。
        assertThat(plan.dataRows()).isEqualTo(4);
        String csv = write(plan);
        assertThat(csv.split("\n")).hasSize(5);
        assertThat(csv).doesNotContain(",SURGERY,SURGERY,");
    }

    @Test
    void csvEscapesQuoteAndCommaInSurgeryLabel() {
        ReportCaseFact tricky = new ReportCaseFact("s-t", "r-1", "OR-01", DEPT_ANES.toString(),
                "麻醉科（合成）", "C-1", "术式\"含逗号,", null, null, null, null, null);
        when(caseSource.findCases(any())).thenReturn(List.of(tricky));

        ReportService.ExportPlan plan = service.prepareExport(new ReportExportQuery("workload",
                "csv", FROM, TO, "DEPT", null, null, null), CALLER, CONTEXT);
        String csv = write(plan);

        // 含引号与逗号的术式名按 CSV 规则整体加引号并转义内部引号。
        assertThat(csv).contains("\"术式\"\"含逗号,\"");
    }

    @Test
    void multiPageBeyondLastReturnsEmptyRowsButKeepsTotals() {
        MultiReport report = service.multi(new MultiReportQuery(FROM, TO, "DEPT", "caseCount",
                null, 99, 2), CALLER, CONTEXT);

        assertThat(report.getRows()).isEmpty();
        assertThat(report.getPage()).isEqualTo(99);
        assertThat(report.getTotal()).isEqualTo(2);
        assertThat(report.getTotalPages()).isEqualTo(1);
        assertThat(report.getTotals().getCaseCount()).isEqualTo(3);
    }

    @Test
    void invalidScopeBindingsAreIgnoredAndFailClosedWhenAllInvalid() {
        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of("bad-uuid", " ")))));
        assertThatThrownBy(() -> service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER,
                CONTEXT))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("无可用报表数据范围");

        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT,
                        List.of(DEPT_ANES.toString(), "bad-uuid")))));
        service.workload(new ReportQuery(FROM, TO, "DEPT"), CALLER, CONTEXT);
        ArgumentCaptor<ReportCaseFilter> captor = ArgumentCaptor.forClass(ReportCaseFilter.class);
        verify(caseSource, org.mockito.Mockito.atLeastOnce()).findCases(captor.capture());
        assertThat(captor.getValue().orgIds()).containsExactly(DEPT_ANES);
    }

    @Test
    void minutesZeroWhenStartEqualsEndAndBlankParamsRejected() {
        ReportCaseFact sameInstant = new ReportCaseFact("s", "r", "OR-01", null, null, null, null,
                null, null, null, Instant.parse("2026-12-02T00:00:00Z"),
                Instant.parse("2026-12-02T00:00:00Z"));
        assertThat(ReportServiceImpl.hasDuration(sameInstant)).isTrue();
        assertThat(ReportServiceImpl.minutes(sameInstant)).isZero();

        assertThatThrownBy(() -> service.workload(new ReportQuery(FROM, TO, " "), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("dimension 不能为空");
        assertThatThrownBy(() -> service.multi(
                new MultiReportQuery(FROM, TO, "DEPT", " ", null, 1, 20), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("metrics 不能为空");
    }

    // ------------------------------------------------------------------
    // 固定数据
    // ------------------------------------------------------------------

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
                DEPT_ANES.toString(), "麻醉科（合成）", GAS, "演示胃肠手术（合成）", null, null, null,
                null, null);
    }

    private static ReportCaseFact caseC() {
        return new ReportCaseFact("s-c", "f2000000-0000-4000-8000-0000000000c5", "OR-05",
                DEPT_OR.toString(), "手术室（合成）", INJECTION, "=SUM(1,2)", null, null, null,
                Instant.parse("2026-12-04T00:00:00Z"), Instant.parse("2026-12-04T01:30:00Z"));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> detailCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private static String write(ReportService.ExportPlan plan) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        plan.writer().accept(output);
        return output.toString(StandardCharsets.UTF_8);
    }
}
