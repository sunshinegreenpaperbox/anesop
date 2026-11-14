package com.anesop.m4.impl.qc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.qc.model.AdverseEvent;
import com.anesop.contracts.qc.model.AdverseRca;
import com.anesop.contracts.qc.model.ChartReview;
import com.anesop.contracts.qc.model.ChartReviewItem;
import com.anesop.contracts.qc.model.CloseAdverseRcaRequest;
import com.anesop.contracts.qc.model.CloseRectificationRequest;
import com.anesop.contracts.qc.model.CreateAdverseEventRequest;
import com.anesop.contracts.qc.model.CreateAdverseRcaRequest;
import com.anesop.contracts.qc.model.CreateChartReviewRequest;
import com.anesop.contracts.qc.model.CreateRectificationRequest;
import com.anesop.contracts.qc.model.QcIndicatorResult;
import com.anesop.contracts.qc.model.QcIndicatorResultList;
import com.anesop.contracts.qc.model.RcaTimelineItem;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.qc.AdverseEventQuery;
import com.anesop.m4.api.qc.ChartReviewQuery;
import com.anesop.m4.api.qc.QcCaseReferenceSource;
import com.anesop.m4.api.qc.QcExportQuery;
import com.anesop.m4.api.qc.QcIndicatorFacts;
import com.anesop.m4.api.qc.QcIndicatorResultQuery;
import com.anesop.m4.api.qc.QcIndicatorSource;
import com.anesop.m4.api.qc.QcService;
import com.anesop.m4.impl.persistence.entity.AdverseEventEntity;
import com.anesop.m4.impl.persistence.entity.AdverseRcaEntity;
import com.anesop.m4.impl.persistence.entity.ChartReviewEntity;
import com.anesop.m4.impl.persistence.entity.QcIndicatorEntity;
import com.anesop.m4.impl.persistence.entity.QcIndicatorResultEntity;
import com.anesop.m4.impl.persistence.entity.RectificationEntity;
import com.anesop.m4.impl.persistence.mapper.AdverseEventMapper;
import com.anesop.m4.impl.persistence.mapper.AdverseRcaMapper;
import com.anesop.m4.impl.persistence.mapper.ChartReviewMapper;
import com.anesop.m4.impl.persistence.mapper.QcIndicatorMapper;
import com.anesop.m4.impl.persistence.mapper.QcIndicatorResultMapper;
import com.anesop.m4.impl.persistence.mapper.RectificationMapper;
import com.anesop.m4.impl.support.QcAccessGuard;
import com.anesop.m4.impl.support.QcAccessGuard.QcVisibility;
import com.anesop.m4.impl.support.QcJson;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * 质控服务单测（M4-M17-01）：指标口径手算（分子/分母/换算/达标/分母 0）、不良事件状态机
 * （越级/重复/阶段 2 确认/独立复核人）、病历抽查评分与缺陷校验、整改期限、导出 BOM/防注入/
 * 无自由文本原文与审计动作。
 *
 * <p>固定数据（SYNTHETIC）：科室 {@code f1000000-...-0002}；指标 7 条参考口径；时钟固定
 * 2031-03-10T00:00:00Z（Asia/Shanghai 2031-03-10 08:00），避免用例随系统时间漂移。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QcServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID DEPT = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final String DEPT_ID = DEPT.toString();
    private static final Instant NOW = Instant.parse("2031-03-10T00:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_qc", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-10001");
    private static final String FROM = "2031-03-01";
    private static final String TO = "2031-03-31";

    @Mock
    private QcIndicatorMapper indicatorMapper;
    @Mock
    private QcIndicatorResultMapper resultMapper;
    @Mock
    private AdverseEventMapper adverseEventMapper;
    @Mock
    private AdverseRcaMapper adverseRcaMapper;
    @Mock
    private ChartReviewMapper chartReviewMapper;
    @Mock
    private RectificationMapper rectificationMapper;
    @Mock
    private QcIndicatorSource indicatorSource;
    @Mock
    private QcCaseReferenceSource caseReferenceSource;
    @Mock
    private QcAccessGuard accessGuard;
    @Mock
    private QcJson qcJson;
    @Mock
    private AuditWriter auditWriter;

    private QcServiceImpl service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));
        service = new QcServiceImpl(indicatorMapper, resultMapper, adverseEventMapper,
                adverseRcaMapper, chartReviewMapper, rectificationMapper, indicatorSource,
                caseReferenceSource, accessGuard, qcJson, auditWriter, clock);
        lenient().when(accessGuard.visibility(any()))
                .thenReturn(new QcVisibility(true, List.of()));
        lenient().when(accessGuard.requireDepartment(any(), anyString(), anyString()))
                .thenReturn(new OrgDepartment(DEPT_ID, "ANES", "麻醉科（合成）"));
        lenient().when(caseReferenceSource.scheduleDepartment(any()))
                .thenReturn(Optional.of(DEPT));
        lenient().when(caseReferenceSource.recordDepartment(any()))
                .thenReturn(Optional.of(DEPT));
        lenient().when(qcJson.writeItems(any())).thenReturn("[]");
        lenient().when(qcJson.readItems(any())).thenReturn(List.of());
        lenient().when(qcJson.writeTimeline(any())).thenReturn("[]");
        lenient().when(qcJson.readTimeline(any())).thenReturn(List.of());
        lenient().when(resultMapper.upsert(any())).thenReturn(1);
        lenient().when(adverseEventMapper.insertEvent(any())).thenReturn(1);
        lenient().when(adverseRcaMapper.insertRca(any())).thenReturn(1);
        lenient().when(chartReviewMapper.insertReview(any())).thenReturn(1);
        lenient().when(rectificationMapper.insertRectification(any())).thenReturn(1);
        lenient().when(adverseEventMapper.updateStatus(any(), anyString(), anyString(), anyInt()))
                .thenReturn(1);
        lenient().when(adverseRcaMapper.markReviewed(any(), any(), anyString(), any(), anyString()))
                .thenReturn(1);
        lenient().when(adverseRcaMapper.markClosed(any(), any())).thenReturn(1);
        lenient().when(chartReviewMapper.updateStatus(any(), anyString(), anyString(), anyInt(),
                any(), any())).thenReturn(1);
        lenient().when(rectificationMapper.markClosed(any(), any(), anyString(), any(),
                anyString())).thenReturn(1);
    }

    // ------------------------------------------------------------------
    // 指标口径
    // ------------------------------------------------------------------

    @Test
    void countsForMapsEveryReferenceIndicatorToItsDataSource() {
        QcIndicatorFacts facts = new QcIndicatorFacts(10, 9, 8, 7, 6, 5, 4, 3, 2, 11, 12);
        assertThat(QcServiceImpl.countsFor("QC-SYN-STRUCT-001", facts))
                .isEqualTo(new QcServiceImpl.Counts(9, 10));
        assertThat(QcServiceImpl.countsFor("QC-SYN-PROC-002", facts))
                .isEqualTo(new QcServiceImpl.Counts(8, 10));
        assertThat(QcServiceImpl.countsFor("QC-SYN-PROC-003", facts))
                .isEqualTo(new QcServiceImpl.Counts(7, 10));
        assertThat(QcServiceImpl.countsFor("QC-SYN-PROC-004", facts))
                .isEqualTo(new QcServiceImpl.Counts(5, 6));
        assertThat(QcServiceImpl.countsFor("QC-SYN-OUT-005", facts))
                .isEqualTo(new QcServiceImpl.Counts(4, 10));
        assertThat(QcServiceImpl.countsFor("QC-SYN-PROC-006", facts))
                .isEqualTo(new QcServiceImpl.Counts(2, 3));
        assertThat(QcServiceImpl.countsFor("QC-SYN-STRUCT-007", facts))
                .isEqualTo(new QcServiceImpl.Counts(12, 11));
        // 未登记编码按 0/0 呈现“无数据”，不静默丢弃。
        assertThat(QcServiceImpl.countsFor("QC-FUTURE-999", facts))
                .isEqualTo(new QcServiceImpl.Counts(0, 0));
        assertThat(QcServiceImpl.countsFor(null, facts))
                .isEqualTo(new QcServiceImpl.Counts(0, 0));
    }

    @Test
    void valueOfUsesPercentAndPerThousandWithTwoDecimalsAndZeroDenominator() {
        assertThat(QcServiceImpl.valueOf(new QcServiceImpl.Counts(1, 3), "PERCENT"))
                .isEqualTo(new BigDecimal("33.33"));
        assertThat(QcServiceImpl.valueOf(new QcServiceImpl.Counts(1, 3), "PER_1000_CASES"))
                .isEqualTo(new BigDecimal("333.33"));
        assertThat(QcServiceImpl.valueOf(new QcServiceImpl.Counts(0, 0), "PERCENT"))
                .isEqualTo(new BigDecimal("0.00"));
        assertThat(QcServiceImpl.valueOf(new QcServiceImpl.Counts(2, 2), "PERCENT"))
                .isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void indicatorResultsComputeTargetsAndPersistSnapshots() {
        QcIndicatorEntity record = indicator("QC-SYN-STRUCT-001", "STRUCTURE", "PERCENT", "GTE",
                "95");
        QcIndicatorEntity adverse = indicator("QC-SYN-OUT-005", "OUTCOME", "PER_1000_CASES",
                "GTE", "3");
        when(indicatorMapper.selectActive(any(), any())).thenReturn(List.of(record, adverse));
        when(indicatorSource.collect(any())).thenReturn(
                new QcIndicatorFacts(4, 3, 0, 0, 0, 0, 1, 0, 0, 0, 0));

        QcIndicatorResultList list = service.indicatorResults(
                new QcIndicatorResultQuery(FROM, TO, null), CALLER, CONTEXT);

        assertThat(list.getFrom()).isEqualTo(LocalDate.of(2031, 3, 1));
        assertThat(list.getTo()).isEqualTo(LocalDate.of(2031, 3, 31));
        assertThat(list.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(list.getItems()).hasSize(2);
        QcIndicatorResult first = list.getItems().get(0);
        assertThat(first.getCode()).isEqualTo("QC-SYN-STRUCT-001");
        assertThat(first.getNumerator()).isEqualTo(3);
        assertThat(first.getDenominator()).isEqualTo(4);
        assertThat(first.getValue()).isEqualByComparingTo("75.00");
        assertThat(first.getTargetMet()).isFalse();
        assertThat(first.getDeptId()).isNull();
        QcIndicatorResult second = list.getItems().get(1);
        // 1 例不良事件 / 4 台次 × 1000‰ = 250.00‰，≥3 → 达标。
        assertThat(second.getValue()).isEqualByComparingTo("250.00");
        assertThat(second.getTargetMet()).isTrue();
        verify(resultMapper, org.mockito.Mockito.times(2)).upsert(any());
        verify(auditWriter).record(eq("QC_INDICATOR_COMPUTE"), eq("QC_INDICATOR_RESULT"),
                eq(null), eq(true), any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
    }

    @Test
    void indicatorResultsWithDepartmentFilterPersistDepartmentSnapshot() {
        when(indicatorMapper.selectActive(any(), any())).thenReturn(List.of(
                indicator("QC-SYN-PROC-002", "PROCESS", "PERCENT", "LTE", "5")));
        when(indicatorSource.collect(any())).thenReturn(
                new QcIndicatorFacts(2, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0));

        QcIndicatorResultList list = service.indicatorResults(
                new QcIndicatorResultQuery(FROM, TO, DEPT_ID), CALLER, CONTEXT);

        assertThat(list.getItems()).hasSize(1);
        QcIndicatorResult item = list.getItems().get(0);
        assertThat(item.getDeptId()).isEqualTo(DEPT_ID);
        assertThat(item.getValue()).isEqualByComparingTo("100.00");
        assertThat(item.getTargetMet()).isFalse();
        ArgumentCaptor<QcIndicatorResultEntity> captor =
                ArgumentCaptor.forClass(QcIndicatorResultEntity.class);
        verify(resultMapper).upsert(captor.capture());
        assertThat(captor.getValue().getDeptId()).isEqualTo(DEPT);
    }

    @Test
    void indicatorResultsSelectVersionsByEffectivePeriodAndSplitCrossVersionWindow() {
        QcIndicatorEntity first = indicator("QC-SYN-STRUCT-001", "STRUCTURE", "PERCENT", "GTE",
                "95");
        first.setId(UUID.fromString("7c000000-0000-4000-8000-0000000000a1"));
        first.setVersion("1.0.0");
        first.setEffectiveFrom(LocalDate.of(2027, 1, 1));
        first.setEffectiveTo(LocalDate.of(2027, 1, 31));
        QcIndicatorEntity second = indicator("QC-SYN-STRUCT-001", "STRUCTURE", "PERCENT", "GTE",
                "95");
        second.setId(UUID.fromString("7c000000-0000-4000-8000-0000000000a2"));
        second.setVersion("2.0.0");
        second.setEffectiveFrom(LocalDate.of(2027, 2, 1));
        second.setEffectiveTo(null);
        when(indicatorMapper.selectActive(any(), any())).thenReturn(List.of(first, second));
        when(indicatorSource.collect(any())).thenReturn(
                new QcIndicatorFacts(4, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                new QcIndicatorFacts(2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0));

        QcIndicatorResultList list = service.indicatorResults(
                new QcIndicatorResultQuery("2027-01-10", "2027-02-20", null), CALLER, CONTEXT);

        assertThat(list.getItems()).hasSize(2);
        QcIndicatorResult older = list.getItems().get(0);
        assertThat(older.getIndicatorVersion()).isEqualTo("1.0.0");
        assertThat(older.getPeriodFrom()).isEqualTo(LocalDate.of(2027, 1, 10));
        assertThat(older.getPeriodTo()).isEqualTo(LocalDate.of(2027, 1, 31));
        assertThat(older.getNumerator()).isEqualTo(3);
        assertThat(older.getDenominator()).isEqualTo(4);
        assertThat(older.getValue()).isEqualByComparingTo("75.00");
        QcIndicatorResult newer = list.getItems().get(1);
        assertThat(newer.getIndicatorVersion()).isEqualTo("2.0.0");
        assertThat(newer.getPeriodFrom()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(newer.getPeriodTo()).isEqualTo(LocalDate.of(2027, 2, 20));
        assertThat(newer.getNumerator()).isEqualTo(1);
        assertThat(newer.getDenominator()).isEqualTo(2);
        assertThat(newer.getValue()).isEqualByComparingTo("50.00");
        verify(indicatorSource, org.mockito.Mockito.times(2)).collect(any());

        // 只查旧版本有效期：新版本被裁掉，不参与计算。
        when(indicatorSource.collect(any())).thenReturn(
                new QcIndicatorFacts(3, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        QcIndicatorResultList oldOnly = service.indicatorResults(
                new QcIndicatorResultQuery("2027-01-10", "2027-01-20", null), CALLER, CONTEXT);
        assertThat(oldOnly.getItems()).hasSize(1);
        assertThat(oldOnly.getItems().get(0).getIndicatorVersion()).isEqualTo("1.0.0");
        assertThat(oldOnly.getItems().get(0).getNumerator()).isEqualTo(2);
    }

    @Test
    void indicatorResultsRejectOverlappingEffectiveVersionsInPeriod() {
        QcIndicatorEntity first = indicator("QC-SYN-STRUCT-001", "STRUCTURE", "PERCENT", "GTE",
                "95");
        first.setVersion("1.0.0");
        first.setEffectiveFrom(LocalDate.of(2027, 3, 1));
        first.setEffectiveTo(LocalDate.of(2027, 3, 31));
        QcIndicatorEntity second = indicator("QC-SYN-STRUCT-001", "STRUCTURE", "PERCENT", "GTE",
                "95");
        second.setVersion("2.0.0");
        second.setEffectiveFrom(LocalDate.of(2027, 3, 15));
        second.setEffectiveTo(null);
        when(indicatorMapper.selectActive(any(), any())).thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service.indicatorResults(
                new QcIndicatorResultQuery("2027-03-01", "2027-03-31", null), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("重叠");
        verify(indicatorSource, never()).collect(any());
    }

    @Test
    void indicatorWithTargetStatisticsDisabledNeverMeetsThreshold() {
        QcIndicatorEntity entity = indicator("QC-SYN-PROC-004", "STRUCTURE", "PERCENT", "GTE",
                "100");
        entity.setInTargetStatistics(false);
        when(indicatorMapper.selectActive(any(), any())).thenReturn(List.of(entity));
        when(indicatorSource.collect(any())).thenReturn(
                new QcIndicatorFacts(0, 0, 0, 0, 10, 10, 0, 0, 0, 0, 0));

        QcIndicatorResultList list = service.indicatorResults(
                new QcIndicatorResultQuery(FROM, TO, null), CALLER, CONTEXT);

        QcIndicatorResult item = list.getItems().get(0);
        assertThat(item.getValue()).isEqualByComparingTo("100.00");
        assertThat(item.getInTargetStatistics()).isFalse();
        assertThat(item.getTargetMet()).isFalse();
    }

    @Test
    void indicatorResultsRejectInvalidRangeAndLongSpan() {
        assertThatThrownBy(() -> service.indicatorResults(
                new QcIndicatorResultQuery("2031-03-31", "2031-03-01", null), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("结束日期");
        assertThatThrownBy(() -> service.indicatorResults(
                new QcIndicatorResultQuery("2030-01-01", "2031-03-01", null), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("366");
        assertThatThrownBy(() -> service.indicatorResults(
                new QcIndicatorResultQuery("31-03-2031", TO, null), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("YYYY-MM-DD");
    }

    // ------------------------------------------------------------------
    // 不良事件
    // ------------------------------------------------------------------

    @Test
    void createAdverseEventRejectsPhiAndFutureOccurrence() {
        CreateAdverseEventRequest phi = new CreateAdverseEventRequest();
        phi.setEventType(CreateAdverseEventRequest.EventTypeEnum.MEDICATION);
        phi.setSeverity(CreateAdverseEventRequest.SeverityEnum.II);
        phi.setOccurredAt(OffsetDateTime.of(2031, 3, 2, 10, 30, 0, 0, ZoneOffset.ofHours(8)));
        phi.setDeptId(DEPT_ID);
        phi.setDescription("患者住院号 12345678901 诱导后血压下降");
        assertThatThrownBy(() -> service.createAdverseEvent(phi, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("脱敏");
        verify(adverseEventMapper, never()).insertEvent(any());

        CreateAdverseEventRequest future = new CreateAdverseEventRequest();
        future.setEventType(CreateAdverseEventRequest.EventTypeEnum.MEDICATION);
        future.setSeverity(CreateAdverseEventRequest.SeverityEnum.II);
        future.setOccurredAt(OffsetDateTime.of(2031, 3, 12, 10, 30, 0, 0, ZoneOffset.ofHours(8)));
        future.setDeptId(DEPT_ID);
        future.setDescription("诱导后一过性血压下降（合成）");
        assertThatThrownBy(() -> service.createAdverseEvent(future, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("不得晚于当前时间");
    }

    @Test
    void createAdverseEventMasksAnonymousReporterButKeepsTraceIdentity() {
        CreateAdverseEventRequest request = new CreateAdverseEventRequest();
        request.setEventType(CreateAdverseEventRequest.EventTypeEnum.MEDICATION);
        request.setSeverity(CreateAdverseEventRequest.SeverityEnum.II);
        request.setOccurredAt(OffsetDateTime.of(2031, 3, 2, 10, 30, 0, 0, ZoneOffset.ofHours(8)));
        request.setDeptId(DEPT_ID);
        request.setDescription("诱导后一过性血压下降（合成，已脱敏）");
        request.setAnonymous(true);

        AdverseEvent event = service.createAdverseEvent(request, CALLER, CONTEXT);

        assertThat(event.getStatus()).isEqualTo(AdverseEvent.StatusEnum.REPORTED);
        assertThat(event.getReporterName()).isEqualTo("匿名上报");
        assertThat(event.getAnonymous()).isTrue();
        assertThat(event.getEventNo()).startsWith("AE-20310302-");
        ArgumentCaptor<AdverseEventEntity> captor =
                ArgumentCaptor.forClass(AdverseEventEntity.class);
        verify(adverseEventMapper).insertEvent(captor.capture());
        assertThat(captor.getValue().getReporterName()).isEqualTo("synth_qc");
        assertThat(captor.getValue().getReporterId()).isEqualTo(ACTOR);
        verify(auditWriter).record(eq("ADVERSE_EVENT_CREATE"), eq("ADVERSE_EVENT"),
                anyString(), eq(true), any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
    }

    @Test
    void createAdverseEventRejectsMissingOrCrossDepartmentSchedule() {
        CreateAdverseEventRequest request = new CreateAdverseEventRequest();
        request.setEventType(CreateAdverseEventRequest.EventTypeEnum.MEDICATION);
        request.setSeverity(CreateAdverseEventRequest.SeverityEnum.II);
        request.setOccurredAt(OffsetDateTime.of(2031, 3, 2, 10, 30, 0, 0, ZoneOffset.ofHours(8)));
        request.setDeptId(DEPT_ID);
        request.setDescription("诱导后一过性血压下降（合成，已脱敏）");
        request.setScheduleId(UUID.randomUUID().toString());

        when(caseReferenceSource.scheduleDepartment(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createAdverseEvent(request, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("关联台次不存在");
        verify(adverseEventMapper, never()).insertEvent(any());

        UUID otherDept = UUID.fromString("f1000000-0000-4000-8000-000000000003");
        when(caseReferenceSource.scheduleDepartment(any())).thenReturn(Optional.of(otherDept));
        assertThatThrownBy(() -> service.createAdverseEvent(request, CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("跨科室");
        verify(adverseEventMapper, never()).insertEvent(any());
    }

    @Test
    void createChartReviewRejectsMissingOrCrossDepartmentRecord() {
        CreateChartReviewRequest request = reviewRequest();
        request.setRecordId(UUID.randomUUID().toString());

        when(caseReferenceSource.recordDepartment(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createChartReview(request, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("关联麻醉记录单不存在");
        verify(chartReviewMapper, never()).insertReview(any());

        UUID otherDept = UUID.fromString("f1000000-0000-4000-8000-000000000003");
        when(caseReferenceSource.recordDepartment(any())).thenReturn(Optional.of(otherDept));
        assertThatThrownBy(() -> service.createChartReview(request, CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("跨科室");
        verify(chartReviewMapper, never()).insertReview(any());
    }

    @Test
    void createRcaRejectsWrongStatusAndDuplicate() {
        AdverseEventEntity event = event("RCA", 2);
        when(adverseEventMapper.selectEventById(any())).thenReturn(event);
        assertThatThrownBy(() -> service.createRca(event.getId().toString(), rcaRequest(), CALLER,
                CONTEXT)).isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("不允许提交 RCA");

        AdverseEventEntity reported = event("REPORTED", 1);
        when(adverseEventMapper.selectEventById(any())).thenReturn(reported);
        when(adverseRcaMapper.selectByEventId(any())).thenReturn(new AdverseRcaEntity());
        assertThatThrownBy(() -> service.createRca(reported.getId().toString(), rcaRequest(),
                CALLER, CONTEXT)).isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("已有 RCA");
    }

    @Test
    void createRcaMovesEventToRcaAndRejectsDueDateBeforeOccurrence() {
        AdverseEventEntity event = event("REPORTED", 1);
        when(adverseEventMapper.selectEventById(any())).thenReturn(event);
        CreateAdverseRcaRequest request = rcaRequest();
        request.setDueDate(LocalDate.of(2031, 3, 1));
        assertThatThrownBy(() -> service.createRca(event.getId().toString(), request, CALLER,
                CONTEXT)).isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("不得早于事件发生");

        request.setDueDate(LocalDate.of(2031, 3, 20));
        when(adverseRcaMapper.selectRcaById(any())).thenReturn(rcaEntity(event.getId(), null, null,
                "OPEN"));
        AdverseRca rca = service.createRca(event.getId().toString(), request, CALLER, CONTEXT);
        assertThat(rca.getEventStatus()).isEqualTo(AdverseRca.EventStatusEnum.RCA);
        verify(adverseEventMapper).updateStatus(event.getId(), "REPORTED", "RCA", 1);
        verify(auditWriter).record(eq("RCA_CREATE"), eq("ADVERSE_RCA"), anyString(), eq(true),
                any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
    }

    @Test
    void closeRcaFirstStageReviewsThenMovesToRectifying() {
        AdverseEventEntity event = event("RCA", 2);
        AdverseRcaEntity rca = rcaEntity(event.getId(), null, null, "OPEN");
        when(adverseRcaMapper.selectRcaById(any())).thenReturn(rca);
        when(adverseEventMapper.selectEventById(any())).thenReturn(event);
        AdverseRcaEntity reviewed = rcaEntity(event.getId(), NOW, null, "OPEN");
        when(adverseRcaMapper.selectRcaById(any())).thenReturn(rca, reviewed);

        CloseAdverseRcaRequest request = new CloseAdverseRcaRequest();
        request.setReviewerName("演示复核人（合成）");
        request.setConclusion("根因与整改措施认可（合成）");
        request.setRectificationCompleted(false);

        AdverseRca result = service.closeRca(rca.getId().toString(), request, CALLER, CONTEXT);

        assertThat(result.getEventStatus()).isEqualTo(AdverseRca.EventStatusEnum.RECTIFYING);
        verify(adverseRcaMapper).markReviewed(eq(rca.getId()), eq(ACTOR),
                eq("演示复核人（合成）"), eq(NOW), eq("根因与整改措施认可（合成）"));
        verify(adverseEventMapper).updateStatus(event.getId(), "RCA", "RECTIFYING", 2);
        verify(auditWriter).record(eq("RCA_REVIEW"), eq("ADVERSE_RCA"), anyString(), eq(true),
                any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
    }

    @Test
    void closeRcaSecondStageRequiresCompletionAndClosesEvent() {
        AdverseEventEntity event = event("RECTIFYING", 3);
        AdverseRcaEntity reviewed = rcaEntity(event.getId(), NOW, null, "OPEN");
        when(adverseRcaMapper.selectRcaById(any())).thenReturn(reviewed);
        when(adverseEventMapper.selectEventById(any())).thenReturn(event);
        CloseAdverseRcaRequest request = new CloseAdverseRcaRequest();
        request.setReviewerName("演示复核人（合成）");
        request.setConclusion("整改完成（合成）");
        request.setRectificationCompleted(false);
        assertThatThrownBy(() -> service.closeRca(reviewed.getId().toString(), request, CALLER,
                CONTEXT)).isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("整改完成确认");

        request.setRectificationCompleted(true);
        AdverseRcaEntity closed = rcaEntity(event.getId(), NOW, NOW, "CLOSED");
        when(adverseRcaMapper.selectRcaById(any())).thenReturn(reviewed, closed);
        AdverseRca result = service.closeRca(reviewed.getId().toString(), request, CALLER,
                CONTEXT);
        assertThat(result.getEventStatus()).isEqualTo(AdverseRca.EventStatusEnum.CLOSED);
        verify(adverseRcaMapper).markClosed(reviewed.getId(), NOW);
        verify(adverseEventMapper).updateStatus(event.getId(), "RECTIFYING", "CLOSED", 3);
        verify(auditWriter).record(eq("RCA_CLOSE"), eq("ADVERSE_RCA"), anyString(), eq(true),
                any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
    }

    @Test
    void closeRcaRejectsReviewerEqualToReporterOrResponsible() {
        AdverseEventEntity event = event("RCA", 2);
        AdverseRcaEntity rca = rcaEntity(event.getId(), null, null, "OPEN");
        when(adverseRcaMapper.selectRcaById(any())).thenReturn(rca);
        when(adverseEventMapper.selectEventById(any())).thenReturn(event);
        CloseAdverseRcaRequest request = new CloseAdverseRcaRequest();
        request.setReviewerName(rca.getResponsibleName());
        request.setConclusion("复核（合成）");
        assertThatThrownBy(() -> service.closeRca(rca.getId().toString(), request, CALLER,
                CONTEXT)).isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("复核人不得为上报人或责任人");
    }

    // ------------------------------------------------------------------
    // 病历抽查 / 整改
    // ------------------------------------------------------------------

    @Test
    void createChartReviewComputesScoreGradeAndDefects() {
        CreateChartReviewRequest request = reviewRequest();
        ChartReview review = service.createChartReview(request, CALLER, CONTEXT);

        assertThat(review.getTotalScore()).isEqualTo(88);
        assertThat(review.getMaxScore()).isEqualTo(100);
        assertThat(review.getGrade()).isEqualTo(ChartReview.GradeEnum.C);
        assertThat(review.getDefectCount()).isEqualTo(2);
        assertThat(review.getStatus()).isEqualTo(ChartReview.StatusEnum.OPEN);
        assertThat(review.getReviewNo()).startsWith("CR-20310306-");
        assertThat(review.getReviewerName()).isEqualTo("synth_qc");
        verify(chartReviewMapper).insertReview(any());
        verify(auditWriter).record(eq("CHART_REVIEW_CREATE"), eq("CHART_REVIEW"), anyString(),
                eq(true), any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
    }

    @Test
    void createChartReviewRejectsDuplicateItemsAndMissingDefectNote() {
        CreateChartReviewRequest request = reviewRequest();
        request.getItems().add(reviewRequest().getItems().get(0));
        assertThatThrownBy(() -> service.createChartReview(request, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("不得重复");

        CreateChartReviewRequest missingNote = reviewRequest();
        missingNote.getItems().get(0).setDefectNote(null);
        assertThatThrownBy(() -> service.createChartReview(missingNote, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("缺陷说明");
    }

    @Test
    void createRectificationRejectsDueDateBeforeReviewAndMovesReview() {
        ChartReviewEntity review = reviewEntity("OPEN", 1);
        when(chartReviewMapper.selectReviewById(any())).thenReturn(review);
        CreateRectificationRequest request = new CreateRectificationRequest();
        request.setCorrectiveAction("补录术后随访（合成）");
        request.setResponsibleName("演示责任人（合成）");
        request.setDueDate(LocalDate.of(2031, 3, 1));
        assertThatThrownBy(() -> service.createRectification(review.getId().toString(), request,
                CALLER, CONTEXT)).isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("不得早于抽查日期");

        request.setDueDate(LocalDate.of(2031, 3, 20));
        when(rectificationMapper.selectByReviewId(any())).thenReturn(null);
        when(rectificationMapper.selectRectificationById(any()))
                .thenReturn(rectificationEntity(review.getId(), "OPEN", null));
        var created = service.createRectification(review.getId().toString(), request, CALLER,
                CONTEXT);
        assertThat(created.getReviewStatus())
                .isEqualTo(com.anesop.contracts.qc.model.Rectification.ReviewStatusEnum.RECTIFYING);
        verify(chartReviewMapper).updateStatus(eq(review.getId()), eq("OPEN"), eq("RECTIFYING"),
                eq(1), eq(NOW), eq(ACTOR));
        verify(auditWriter).record(eq("RECTIFICATION_CREATE"), eq("RECTIFICATION"), anyString(),
                eq(true), any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
    }

    @Test
    void closeRectificationRequiresOpenRectificationAndIndependentReviewer() {
        ChartReviewEntity review = reviewEntity("RECTIFYING", 2);
        RectificationEntity rectification = rectificationEntity(review.getId(), "OPEN", null);
        when(rectificationMapper.selectRectificationById(any())).thenReturn(rectification);
        when(chartReviewMapper.selectReviewById(any())).thenReturn(review);
        CloseRectificationRequest request = new CloseRectificationRequest();
        request.setReviewerName(rectification.getResponsibleName());
        request.setConclusion("闭环（合成）");
        assertThatThrownBy(() -> service.closeRectification(rectification.getId().toString(),
                request, CALLER, CONTEXT)).isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("复核人不得为上报人或责任人");

        request.setReviewerName("演示复核人（合成）");
        when(rectificationMapper.selectRectificationById(any()))
                .thenReturn(rectification, rectificationEntity(review.getId(), "CLOSED", NOW));
        var closed = service.closeRectification(rectification.getId().toString(), request, CALLER,
                CONTEXT);
        assertThat(closed.getReviewStatus())
                .isEqualTo(com.anesop.contracts.qc.model.Rectification.ReviewStatusEnum.CLOSED);
        verify(rectificationMapper).markClosed(eq(rectification.getId()), eq(ACTOR),
                eq("演示复核人（合成）"), eq(NOW), eq("闭环（合成）"));
        verify(chartReviewMapper).updateStatus(eq(review.getId()), eq("RECTIFYING"), eq("CLOSED"),
                eq(2), eq(NOW), eq(ACTOR));
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    @Test
    void exportAdverseCsvWritesBomNeutralizesFormulaAndOmitsFreeText() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_no", "=SUM(1,2)");
        row.put("event_type", "MEDICATION");
        row.put("severity", "LEVEL_II");
        row.put("occurred_at", "2031-03-02 10:30");
        row.put("dept_id", DEPT_ID);
        row.put("dept_name", "麻醉科（合成）");
        row.put("status", "CLOSED");
        row.put("rca_status", "CLOSED");
        row.put("responsible_name", "演示责任人（合成）");
        row.put("due_date", "2031-03-20");
        row.put("reviewer_name", "演示复核人（合成）");
        row.put("closed_at", "2031-03-08 10:00");
        row.put("anonymous", true);
        when(adverseEventMapper.selectExportRows(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row));

        QcService.ExportPlan plan = service.prepareExport(
                new QcExportQuery("adverse", "csv", FROM, TO, null), CALLER, CONTEXT);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        plan.writer().accept(output);
        byte[] bytes = output.toByteArray();

        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        String csv = output.toString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFtype,eventNo,eventType");
        assertThat(csv).contains("'=SUM(1,2)");
        // 导出只含状态/计数/标识，不含事件描述与根因等自由文本列。
        assertThat(csv).doesNotContain("description").doesNotContain("rootCause");
        assertThat(plan.fileName()).isEqualTo("qc-adverse-2031-03-01-2031-03-31.csv");
        assertThat(plan.dataRows()).isEqualTo(1);
        verify(auditWriter).record(eq("QC_EXPORT"), eq("QC_EXPORT"), eq("adverse"), eq(true),
                any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
    }

    @Test
    void exportRejectsOverLimitAndPersistsTruncatedFailureAudit() {
        List<Map<String, Object>> rows = new ArrayList<>(QcServiceImpl.MAX_EXPORT_ROWS + 1);
        Map<String, Object> row = Map.of("event_no", "AE-BULK-SYNTH");
        for (int index = 0; index <= QcServiceImpl.MAX_EXPORT_ROWS; index++) {
            rows.add(row);
        }
        when(adverseEventMapper.selectExportRows(any(), any(), any(), any(),
                eq(QcServiceImpl.MAX_EXPORT_ROWS + 1))).thenReturn(rows);
        when(adverseEventMapper.countPage(any(), any(), any(), any(), any())).thenReturn(60_000L);

        assertThatThrownBy(() -> service.prepareExport(
                new QcExportQuery("adverse", "csv", FROM, TO, null), CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("50000");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("QC_EXPORT"), eq("QC_EXPORT"), eq("adverse"), eq(false),
                detailCaptor.capture(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
        assertThat(detailCaptor.getValue())
                .containsEntry("dataRows", 60_000L)
                .containsEntry("truncated", true)
                .containsEntry("limit", QcServiceImpl.MAX_EXPORT_ROWS);
    }

    @Test
    void exportRejectsInvalidTypeFormatAndSpan() {
        assertThatThrownBy(() -> service.prepareExport(
                new QcExportQuery("unknown", "csv", FROM, TO, null), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> service.prepareExport(
                new QcExportQuery("indicator", "xlsx", FROM, TO, null), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("csv");
        assertThatThrownBy(() -> service.prepareExport(
                new QcExportQuery("indicator", "csv", "2030-01-01", TO, null), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("366");
    }

    @Test
    void exportIndicatorsUsesComputedSnapshotRows() throws Exception {
        when(indicatorMapper.selectActive(any(), any())).thenReturn(List.of(
                indicator("QC-SYN-STRUCT-001", "STRUCTURE", "PERCENT", "GTE", "95")));
        when(indicatorSource.collect(any())).thenReturn(
                new QcIndicatorFacts(4, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0));

        QcService.ExportPlan plan = service.prepareExport(
                new QcExportQuery("indicator", "csv", FROM, TO, null), CALLER, CONTEXT);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        plan.writer().accept(output);
        String csv = output.toString(StandardCharsets.UTF_8);

        assertThat(csv).contains("qc-indicator,QC-SYN-STRUCT-001");
        assertThat(csv).contains(
                ",3,4,75.00,PERCENT,GTE,95,false,true,true,Asia/Shanghai");
    }

    // ------------------------------------------------------------------
    // 列表参数
    // ------------------------------------------------------------------

    @Test
    void listQueriesRejectInvalidStatusPageAndPartialRange() {
        assertThatThrownBy(() -> service.listAdverseEvents(
                new AdverseEventQuery("UNKNOWN", null, null, null, 1, 20), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("status");
        assertThatThrownBy(() -> service.listAdverseEvents(
                new AdverseEventQuery(null, FROM, null, null, 1, 20), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("同时");
        assertThatThrownBy(() -> service.listChartReviews(
                new ChartReviewQuery(null, null, null, null, 0, 20), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("page");
        assertThatThrownBy(() -> service.listChartReviews(
                new ChartReviewQuery(null, null, null, null, 1, 500), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("size");
    }

    @Test
    void indicatorsAreAuditedWithoutDataScopeLeak() {
        QcIndicatorEntity entity = indicator("QC-SYN-STRUCT-001", "STRUCTURE", "PERCENT", "GTE",
                "95");
        when(indicatorMapper.selectAllActive()).thenReturn(List.of(entity));
        var list = service.indicators(CALLER, CONTEXT);
        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().get(0).getProvisional()).isTrue();
        verify(auditWriter).record(eq("QC_INDICATOR_QUERY"), eq("QC_INDICATOR"), eq(null),
                eq(true), any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
    }

    @Test
    void createChartReviewRejectsEmptyInvalidAndFutureItems() {
        CreateChartReviewRequest empty = reviewRequest();
        empty.setItems(List.of());
        assertThatThrownBy(() -> service.createChartReview(empty, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("至少 1 项");

        CreateChartReviewRequest overScore = reviewRequest();
        overScore.getItems().get(0).setScore(31);
        assertThatThrownBy(() -> service.createChartReview(overScore, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("不得大于满分");

        CreateChartReviewRequest outOfRange = reviewRequest();
        outOfRange.getItems().get(0).setMaxScore(101);
        assertThatThrownBy(() -> service.createChartReview(outOfRange, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("越界");

        CreateChartReviewRequest future = reviewRequest();
        future.setReviewedAt(OffsetDateTime.of(2031, 3, 20, 9, 0, 0, 0, ZoneOffset.ofHours(8)));
        assertThatThrownBy(() -> service.createChartReview(future, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("不得晚于当前时间");
    }

    @Test
    void createRcaRejectsInvalidTimelineAndMissingDueDate() {
        AdverseEventEntity event = event("REPORTED", 1);
        when(adverseEventMapper.selectEventById(any())).thenReturn(event);

        RcaTimelineItem validNode = rcaRequest().getTimeline().get(0);
        CreateAdverseRcaRequest empty = rcaRequest();
        empty.setTimeline(List.of());
        assertThatThrownBy(() -> service.createRca(event.getId().toString(), empty, CALLER,
                CONTEXT)).isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("至少 1 条");

        CreateAdverseRcaRequest tooMany = rcaRequest();
        List<RcaTimelineItem> many = new ArrayList<>();
        for (int index = 0; index < 21; index++) {
            many.add(validNode);
        }
        tooMany.setTimeline(many);
        assertThatThrownBy(() -> service.createRca(event.getId().toString(), tooMany, CALLER,
                CONTEXT)).isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("不能超过 20");

        CreateAdverseRcaRequest future = rcaRequest();
        RcaTimelineItem invalid = new RcaTimelineItem();
        invalid.setOccurredAt(OffsetDateTime.of(2031, 3, 20, 9, 0, 0, 0, ZoneOffset.ofHours(8)));
        invalid.setDescription("未来节点（合成）");
        future.setTimeline(List.of(invalid));
        assertThatThrownBy(() -> service.createRca(event.getId().toString(), future, CALLER,
                CONTEXT)).isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("不得晚于当前时间");

        CreateAdverseRcaRequest noDue = rcaRequest();
        noDue.setDueDate(null);
        assertThatThrownBy(() -> service.createRca(event.getId().toString(), noDue, CALLER,
                CONTEXT)).isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("dueDate");
    }

    @Test
    void closeRcaRejectsIllegalStageCombinations() {
        AdverseEventEntity rectifying = event("RECTIFYING", 3);
        AdverseRcaEntity notReviewed = rcaEntity(rectifying.getId(), null, null, "OPEN");
        when(adverseRcaMapper.selectRcaById(any())).thenReturn(notReviewed);
        when(adverseEventMapper.selectEventById(any())).thenReturn(rectifying);
        CloseAdverseRcaRequest request = new CloseAdverseRcaRequest();
        request.setReviewerName("演示复核人（合成）");
        request.setConclusion("闭环（合成）");
        request.setRectificationCompleted(true);
        assertThatThrownBy(() -> service.closeRca(notReviewed.getId().toString(), request, CALLER,
                CONTEXT)).isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("不允许闭环");

        AdverseEventEntity reported = event("REPORTED", 1);
        AdverseRcaEntity rca = rcaEntity(reported.getId(), null, null, "OPEN");
        when(adverseRcaMapper.selectRcaById(any())).thenReturn(rca);
        when(adverseEventMapper.selectEventById(any())).thenReturn(reported);
        assertThatThrownBy(() -> service.closeRca(rca.getId().toString(), request, CALLER,
                CONTEXT)).isInstanceOf(M4ConflictException.class);
    }

    @Test
    void listQueriesMapPagedRowsAndAudit() {
        AdverseEventEntity event = event("REPORTED", 1);
        when(adverseEventMapper.selectPage(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(event));
        when(adverseEventMapper.countPage(any(), any(), any(), any(), any())).thenReturn(1L);
        var adverse = service.listAdverseEvents(
                new AdverseEventQuery(null, null, null, null, 1, 20), CALLER, CONTEXT);
        assertThat(adverse.getItems()).hasSize(1);
        assertThat(adverse.getMeta().getTotal()).isEqualTo(1);
        assertThat(adverse.getMeta().getTotalPages()).isEqualTo(1);
        verify(auditWriter).record(eq("ADVERSE_EVENT_QUERY"), eq("ADVERSE_EVENT"), eq(null),
                eq(true), any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));

        ChartReviewEntity review = reviewEntity("OPEN", 1);
        when(chartReviewMapper.selectPage(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(review));
        when(chartReviewMapper.countPage(any(), any(), any(), any(), any())).thenReturn(0L);
        var reviews = service.listChartReviews(
                new ChartReviewQuery("OPEN", "2031-03-01", "2031-03-31", null, 1, 20), CALLER,
                CONTEXT);
        assertThat(reviews.getItems()).hasSize(1);
        assertThat(reviews.getMeta().getTotal()).isZero();
        assertThat(reviews.getMeta().getTotalPages()).isZero();
        verify(auditWriter).record(eq("CHART_REVIEW_QUERY"), eq("CHART_REVIEW"), eq(null),
                eq(true), any(), eq(ACTOR), eq("synth_qc"), eq(CONTEXT));
    }

    @Test
    void exportReviewCsvKeepsOnlyAggregateColumns() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("review_no", "CR-20310306-5e6f7a8b");
        row.put("reviewed_at", "2031-03-06 09:00");
        row.put("dept_id", DEPT_ID);
        row.put("dept_name", "麻醉科（合成）");
        row.put("total_score", 88);
        row.put("max_score", 100);
        row.put("grade", "C");
        row.put("defect_count", 2);
        row.put("status", "CLOSED");
        row.put("rectification_status", "CLOSED");
        row.put("responsible_name", "演示责任人（合成）");
        row.put("due_date", "2031-03-20");
        row.put("closed_at", "2031-03-08 10:00");
        when(chartReviewMapper.selectExportRows(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row));
        QcService.ExportPlan plan = service.prepareExport(
                new QcExportQuery("review", "csv", FROM, TO, null), CALLER, CONTEXT);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        plan.writer().accept(output);
        String csv = output.toString(StandardCharsets.UTF_8);
        assertThat(csv).contains("qc-review,CR-20310306-5e6f7a8b")
                .contains(",88,100,C,2,CLOSED,CLOSED,");
        assertThat(csv).doesNotContain("defectNote").doesNotContain("缺术后随访");
        assertThat(plan.fileName()).isEqualTo("qc-review-2031-03-01-2031-03-31.csv");
    }

    @Test
    void indicatorResultsTolerateMissingFactsAndUnknownCodes() {
        when(indicatorMapper.selectActive(any(), any())).thenReturn(List.of(
                indicator("QC-FUTURE-999", "PROCESS", "PERCENT", "LTE", "5")));
        when(indicatorSource.collect(any())).thenReturn(null);
        QcIndicatorResultList list = service.indicatorResults(
                new QcIndicatorResultQuery(FROM, TO, null), CALLER, CONTEXT);
        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().get(0).getNumerator()).isZero();
        assertThat(list.getItems().get(0).getDenominator()).isZero();
        assertThat(list.getItems().get(0).getValue()).isEqualByComparingTo("0.00");
        assertThat(list.getItems().get(0).getTargetMet()).isFalse();
    }

    @Test
    void closeRcaStageTwoRejectsDifferentReviewer() {
        AdverseEventEntity event = event("RECTIFYING", 3);
        AdverseRcaEntity reviewed = rcaEntity(event.getId(), NOW, null, "OPEN");
        reviewed.setReviewerName("演示复核人甲（合成）");
        when(adverseRcaMapper.selectRcaById(any())).thenReturn(reviewed);
        when(adverseEventMapper.selectEventById(any())).thenReturn(event);
        CloseAdverseRcaRequest request = new CloseAdverseRcaRequest();
        request.setReviewerName("演示复核人乙（合成）");
        request.setConclusion("闭环（合成）");
        request.setRectificationCompleted(true);
        assertThatThrownBy(() -> service.closeRca(reviewed.getId().toString(), request, CALLER,
                CONTEXT)).isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("必须与复核阶段一致");
    }

    @Test
    void rectificationRejectsNonOpenReviewAndClosedRectification() {
        ChartReviewEntity rectifying = reviewEntity("RECTIFYING", 2);
        when(chartReviewMapper.selectReviewById(any())).thenReturn(rectifying);
        CreateRectificationRequest create = new CreateRectificationRequest();
        create.setCorrectiveAction("整改（合成）");
        create.setResponsibleName("演示责任人（合成）");
        create.setDueDate(LocalDate.of(2031, 3, 20));
        assertThatThrownBy(() -> service.createRectification(rectifying.getId().toString(), create,
                CALLER, CONTEXT)).isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("不允许提交整改");

        RectificationEntity closed = rectificationEntity(rectifying.getId(), "CLOSED", NOW);
        when(rectificationMapper.selectRectificationById(any())).thenReturn(closed);
        CloseRectificationRequest close = new CloseRectificationRequest();
        close.setReviewerName("演示复核人（合成）");
        close.setConclusion("重复闭环（合成）");
        assertThatThrownBy(() -> service.closeRectification(closed.getId().toString(), close,
                CALLER, CONTEXT)).isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("不允许闭环");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static QcIndicatorEntity indicator(String code, String category, String unit,
            String operator, String threshold) {
        QcIndicatorEntity entity = new QcIndicatorEntity();
        entity.setId(UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)));
        entity.setCode(code);
        entity.setName(code + "（合成）");
        entity.setCategory(category);
        entity.setDefinition("口径（合成，待确认）");
        entity.setDataSource("M2/M3/M4（合成）");
        entity.setNumeratorLabel("分子");
        entity.setDenominatorLabel("分母");
        entity.setUnit(unit);
        entity.setThresholdOperator(operator);
        entity.setThresholdValue(new BigDecimal(threshold));
        entity.setVersion("1.0.0");
        entity.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        entity.setStatus("ACTIVE");
        entity.setProvisional(true);
        entity.setInTargetStatistics(true);
        entity.setCreatedAt(NOW);
        return entity;
    }

    private static AdverseEventEntity event(String status, int version) {
        AdverseEventEntity entity = new AdverseEventEntity();
        entity.setId(UUID.fromString("7d000000-0000-4000-8000-000000000001"));
        entity.setEventNo("AE-20310302-1a2b3c4d");
        entity.setEventType("MEDICATION");
        entity.setSeverity("LEVEL_II");
        entity.setOccurredAt(Instant.parse("2031-03-02T02:30:00Z"));
        entity.setDeptId(DEPT);
        entity.setDeptName("麻醉科（合成）");
        entity.setDescription("诱导后一过性血压下降（合成，已脱敏）");
        entity.setReporterId(ACTOR);
        entity.setReporterName("synth_qc");
        entity.setAnonymous(false);
        entity.setStatus(status);
        entity.setVersion(version);
        entity.setCreatedAt(NOW);
        return entity;
    }

    private static AdverseRcaEntity rcaEntity(UUID eventId, Instant reviewedAt, Instant closedAt,
            String status) {
        AdverseRcaEntity entity = new AdverseRcaEntity();
        entity.setId(UUID.fromString("7e000000-0000-4000-8000-000000000001"));
        entity.setEventId(eventId);
        entity.setEventNo("AE-20310302-1a2b3c4d");
        entity.setTimeline("[]");
        entity.setRootCause("诱导期容量评估不足（合成）");
        entity.setCorrectiveAction("修订容量评估清单（合成）");
        entity.setResponsibleName("演示责任人（合成）");
        entity.setDueDate(LocalDate.of(2031, 3, 20));
        entity.setStatus(status);
        entity.setReviewerName(reviewedAt == null ? null : "演示复核人（合成）");
        entity.setReviewedAt(reviewedAt);
        entity.setClosedAt(closedAt);
        entity.setCreatedAt(NOW);
        return entity;
    }

    private static CreateAdverseRcaRequest rcaRequest() {
        RcaTimelineItem first = new RcaTimelineItem();
        first.setOccurredAt(OffsetDateTime.of(2031, 3, 2, 10, 30, 0, 0, ZoneOffset.ofHours(8)));
        first.setDescription("诱导后 3 分钟血压下降（合成）");
        CreateAdverseRcaRequest request = new CreateAdverseRcaRequest();
        request.setTimeline(List.of(first));
        request.setRootCause("诱导期容量评估不足（合成）");
        request.setCorrectiveAction("修订诱导期容量评估清单（合成）");
        request.setResponsibleName("演示责任人（合成）");
        request.setDueDate(LocalDate.of(2031, 3, 20));
        return request;
    }

    private static ChartReviewEntity reviewEntity(String status, int version) {
        ChartReviewEntity entity = new ChartReviewEntity();
        entity.setId(UUID.fromString("7f000000-0000-4000-8000-000000000001"));
        entity.setReviewNo("CR-20310306-5e6f7a8b");
        entity.setDeptId(DEPT);
        entity.setDeptName("麻醉科（合成）");
        entity.setReviewerName("synth_qc");
        entity.setReviewedAt(Instant.parse("2031-03-06T01:00:00Z"));
        entity.setItems("[]");
        entity.setTotalScore(88);
        entity.setMaxScore(100);
        entity.setGrade("C");
        entity.setDefectCount(2);
        entity.setStatus(status);
        entity.setVersion(version);
        entity.setCreatedAt(NOW);
        return entity;
    }

    private static RectificationEntity rectificationEntity(UUID reviewId, String status,
            Instant closedAt) {
        RectificationEntity entity = new RectificationEntity();
        entity.setId(UUID.fromString("7a000000-0000-4000-8000-000000000001"));
        entity.setReviewId(reviewId);
        entity.setReviewNo("CR-20310306-5e6f7a8b");
        entity.setCorrectiveAction("补录术后随访（合成）");
        entity.setResponsibleName("演示责任人（合成）");
        entity.setDueDate(LocalDate.of(2031, 3, 20));
        entity.setStatus(status);
        entity.setReviewerName(closedAt == null ? null : "演示复核人（合成）");
        entity.setReviewedAt(closedAt);
        entity.setClosedAt(closedAt);
        entity.setCreatedAt(NOW);
        return entity;
    }

    private static CreateChartReviewRequest reviewRequest() {
        List<ChartReviewItem> items = new ArrayList<>();
        items.add(item(ChartReviewItem.ItemCodeEnum.COMPLETENESS, 28, 30, "缺术后随访记录（合成）"));
        items.add(item(ChartReviewItem.ItemCodeEnum.TIMELINESS, 25, 25, null));
        items.add(item(ChartReviewItem.ItemCodeEnum.SIGNATURE, 20, 20, null));
        items.add(item(ChartReviewItem.ItemCodeEnum.CONSISTENCY, 15, 25, "出入量不一致（合成）"));
        CreateChartReviewRequest request = new CreateChartReviewRequest();
        request.setDeptId(DEPT_ID);
        request.setReviewedAt(OffsetDateTime.of(2031, 3, 6, 9, 0, 0, 0, ZoneOffset.ofHours(8)));
        request.setItems(items);
        return request;
    }

    private static ChartReviewItem item(ChartReviewItem.ItemCodeEnum code, int score, int maxScore,
            String defectNote) {
        ChartReviewItem item = new ChartReviewItem();
        item.setItemCode(code);
        item.setScore(score);
        item.setMaxScore(maxScore);
        item.setDefectNote(defectNote);
        return item;
    }
}
