package com.anesop.m4.impl.qc;

import com.anesop.contracts.qc.model.AdverseEvent;
import com.anesop.contracts.qc.model.AdverseEventList;
import com.anesop.contracts.qc.model.AdverseRca;
import com.anesop.contracts.qc.model.ChartReview;
import com.anesop.contracts.qc.model.ChartReviewItem;
import com.anesop.contracts.qc.model.ChartReviewList;
import com.anesop.contracts.qc.model.CloseAdverseRcaRequest;
import com.anesop.contracts.qc.model.CloseRectificationRequest;
import com.anesop.contracts.qc.model.CreateAdverseEventRequest;
import com.anesop.contracts.qc.model.CreateAdverseRcaRequest;
import com.anesop.contracts.qc.model.CreateChartReviewRequest;
import com.anesop.contracts.qc.model.CreateRectificationRequest;
import com.anesop.contracts.qc.model.PageMeta;
import com.anesop.contracts.qc.model.QcIndicator;
import com.anesop.contracts.qc.model.QcIndicatorList;
import com.anesop.contracts.qc.model.QcIndicatorResult;
import com.anesop.contracts.qc.model.QcIndicatorResultList;
import com.anesop.contracts.qc.model.RcaTimelineItem;
import com.anesop.contracts.qc.model.Rectification;
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
import com.anesop.m4.api.qc.QcIndicatorFilter;
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
import com.anesop.m4.impl.support.M4Values;
import com.anesop.m4.impl.support.QcAccessGuard;
import com.anesop.m4.impl.support.QcAccessGuard.QcVisibility;
import com.anesop.m4.impl.support.QcJson;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 质控指标、不良事件 RCA、病历质量抽查与导出实现（M4-M17-01，契约 qc.yaml 0.1.0）。
 *
 * <p>口径（qc-1.0.0）：
 * <ul>
 *   <li>指标按 {@code code + version} 版本化；结果按 周期 + 科室 + 版本 留存快照（重算覆盖
 *       同一快照）；分子/分母由 {@link QcIndicatorSource} 从声明的数据源计数，分母 0 时
 *       {@code value=0} 且不判达标；指标清单/阈值【待确认】（ADR-0022）；</li>
 *   <li>不良事件状态机 {@code REPORTED→RCA→RECTIFYING→CLOSED}，越级/重复 409；
 *       主体与 RCA append-only（数据库守卫 55000），RCA 复核/闭环字段一次性写入且须独立复核人；</li>
 *   <li>病历抽查评分项白名单四项，总分/等级/缺陷由服务端计算；整改闭环须独立复核人；</li>
 *   <li>导出 UTF-8 BOM + 防公式注入，不含患者标识与自由文本原文；上报格式【待确认】（EXT 占位）。</li>
 * </ul>
 *
 * <p>数据范围 fail-closed：ALL 全院、SITE/DEPT 按科室；ROOM/SELF/无范围 403；{@code deptId}
 * 越界 403；单对象越界/不存在 404。审计 detail 只含标识/类型/状态/周期/计数，不含 PHI。
 */
@Service
public class QcServiceImpl implements QcService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String TIMEZONE = "Asia/Shanghai";
    /** 查询/导出跨度硬上限（与报表口径一致；不开放配置避免契约漂移）。 */
    public static final int MAX_RANGE_DAYS = 366;
    /** 导出数据行上限：超过则 422（不静默截断，审计记录真实行数）。 */
    public static final int MAX_EXPORT_ROWS = 50_000;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_REVIEW_ITEMS = 12;
    private static final int MAX_TIMELINE_ITEMS = 20;
    /** 全院口径的科室哨兵（避免 NULL 在唯一键中重复）。 */
    private static final UUID ALL_DEPTS = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final String ANONYMOUS_REPORTER = "匿名上报";
    private static final BigDecimal PASS_RATIO = new BigDecimal("90");
    private static final Set<String> ADVERSE_STATUSES =
            Set.of("REPORTED", "RCA", "RECTIFYING", "CLOSED");
    private static final Set<String> REVIEW_STATUSES = Set.of("OPEN", "RECTIFYING", "CLOSED");
    private static final Map<String, String> ITEM_NAMES = Map.of(
            "COMPLETENESS", "完整性",
            "TIMELINESS", "时效性",
            "SIGNATURE", "签名",
            "CONSISTENCY", "逻辑一致性");
    private static final String INDICATOR_FORMULA =
            "value = round(numerator / denominator × 100, 2)（PERCENT）或 × 1000（PER_1000_CASES）；"
                    + "分母为 0 时 value = 0 且 targetMet = false";
    /**
     * 描述中的疑似患者标识规则：直接标识关键词，或连续 11–19 位数字（住院号/身份证号/手机号）。
     * 只做保守拦截并返回固定文案，不回合用户输入原文。
     */
    private static final Pattern PHI_PATTERN = Pattern.compile(
            "(身份证|住院号|门诊号|手机号|联系电话|患者姓名|姓名[:：])"
                    + "|(?<!\\d)\\d{11,19}(?!\\d)");
    /** Excel/Sheets 公式起始字符：命中后前置单引号，避免 CSV 被当作公式执行。 */
    private static final String FORMULA_PREFIXES = "=+-@\t\r";
    private static final List<String> INDICATOR_HEADER = List.of(
            "type", "code", "name", "category", "indicatorVersion", "periodFrom", "periodTo",
            "deptId", "deptName", "numerator", "denominator", "value", "unit",
            "thresholdOperator", "thresholdValue", "targetMet", "inTargetStatistics", "provisional",
            "timezone");
    private static final List<String> ADVERSE_HEADER = List.of(
            "type", "eventNo", "eventType", "severity", "occurredAt", "deptId", "deptName",
            "status", "rcaStatus", "responsibleName", "dueDate", "reviewerName", "closedAt",
            "anonymous", "timezone");
    private static final List<String> REVIEW_HEADER = List.of(
            "type", "reviewNo", "reviewedAt", "deptId", "deptName", "totalScore", "maxScore",
            "grade", "defectCount", "status", "rectificationStatus", "responsibleName", "dueDate",
            "closedAt", "timezone");

    private final QcIndicatorMapper indicatorMapper;
    private final QcIndicatorResultMapper resultMapper;
    private final AdverseEventMapper adverseEventMapper;
    private final AdverseRcaMapper adverseRcaMapper;
    private final ChartReviewMapper chartReviewMapper;
    private final RectificationMapper rectificationMapper;
    private final QcIndicatorSource indicatorSource;
    private final QcCaseReferenceSource caseReferenceSource;
    private final QcAccessGuard accessGuard;
    private final QcJson qcJson;
    private final AuditWriter auditWriter;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/审计/时钟由容器管理，服务不修改其内部状态")
    public QcServiceImpl(QcIndicatorMapper indicatorMapper,
            QcIndicatorResultMapper resultMapper,
            AdverseEventMapper adverseEventMapper,
            AdverseRcaMapper adverseRcaMapper,
            ChartReviewMapper chartReviewMapper,
            RectificationMapper rectificationMapper,
            QcIndicatorSource indicatorSource,
            QcCaseReferenceSource caseReferenceSource,
            QcAccessGuard accessGuard,
            QcJson qcJson,
            AuditWriter auditWriter,
            Clock clock) {
        this.indicatorMapper = indicatorMapper;
        this.resultMapper = resultMapper;
        this.adverseEventMapper = adverseEventMapper;
        this.adverseRcaMapper = adverseRcaMapper;
        this.chartReviewMapper = chartReviewMapper;
        this.rectificationMapper = rectificationMapper;
        this.indicatorSource = indicatorSource;
        this.caseReferenceSource = caseReferenceSource;
        this.accessGuard = accessGuard;
        this.qcJson = qcJson;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------
    // 指标定义与结果
    // ---------------------------------------------------------------------

    @Override
    public QcIndicatorList indicators(AuthPrincipal caller, AuthRequestContext context) {
        // 指标目录是全院配置，不按数据范围裁剪；读取权限由 Web 层 m4:qc:read 控制。
        accessGuard.visibility(caller);
        List<QcIndicator> items = new ArrayList<>();
        for (QcIndicatorEntity entity : indicatorMapper.selectAllActive()) {
            items.add(toIndicator(entity));
        }
        QcIndicatorList list = new QcIndicatorList();
        list.setItems(items);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("indicatorCount", items.size());
        detail.put("version", "qc-1.0.0");
        audit("QC_INDICATOR_QUERY", "QC_INDICATOR", null, caller, context, detail);
        return list;
    }

    @Override
    @Transactional
    public QcIndicatorResultList indicatorResults(QcIndicatorResultQuery query,
            AuthPrincipal caller, AuthRequestContext context) {
        if (query == null) {
            throw new M4ValidationException("from", "查询参数不能为空");
        }
        Window window = window(query.from(), query.to(), query.deptId(), caller);
        List<QcIndicatorResult> items = computeAndPersist(window);
        auditCompute(window, items, "query", caller, context);
        return resultList(window, items);
    }

    /**
     * 计算并留存查询周期内的生效指标版本（重算覆盖同一快照），返回 DTO 列表。
     *
     * <p>版本选择（评审 B-1）：按 {@code effective_from/effective_to} 与查询周期求交，
     * 同一 code 在周期内**只允许一个生效版本**，版本区间重叠直接 409（fail-closed）。
     * 查询区间跨版本时按有效期切分为多段，每段一个版本、独立计数与留存；
     * 结果条目的 {@code periodFrom/periodTo} 是切分后的真实统计区间（可能小于查询周期）。
     */
    private List<QcIndicatorResult> computeAndPersist(Window window) {
        List<IndicatorSegment> segments = effectiveSegments(window.from(), window.to());
        if (segments.isEmpty()) {
            return List.of();
        }
        List<UUID> scopedOrgIds = orgIds(window.visibility());
        Map<Period, List<IndicatorSegment>> byPeriod = new LinkedHashMap<>();
        for (IndicatorSegment segment : segments) {
            byPeriod.computeIfAbsent(new Period(segment.from(), segment.to()),
                    key -> new ArrayList<>()).add(segment);
        }
        List<QcIndicatorResult> items = new ArrayList<>(segments.size());
        for (Map.Entry<Period, List<IndicatorSegment>> entry : byPeriod.entrySet()) {
            Window segmentWindow = withPeriod(window, entry.getKey().from(), entry.getKey().to());
            QcIndicatorFilter filter = new QcIndicatorFilter(segmentWindow.fromInstant(),
                    segmentWindow.toExclusive(), segmentWindow.deptId(),
                    window.visibility().unrestricted(), scopedOrgIds);
            QcIndicatorFacts facts = indicatorSource.collect(filter);
            if (facts == null) {
                facts = QcIndicatorFacts.empty();
            }
            for (IndicatorSegment segment : entry.getValue()) {
                QcIndicatorEntity definition = segment.definition();
                Counts counts = countsFor(definition.getCode(), facts);
                BigDecimal value = valueOf(counts, definition.getUnit());
                boolean targetMet = counts.denominator() > 0
                        && Boolean.TRUE.equals(definition.getInTargetStatistics())
                        && meetsThreshold(value, definition);
                Instant computedAt = clock.instant();
                resultMapper.upsert(toResultEntity(definition, segmentWindow, counts, value,
                        targetMet, computedAt));
                items.add(toIndicatorResult(definition, segmentWindow, counts, value, targetMet,
                        computedAt));
            }
        }
        items.sort(Comparator.comparing(QcIndicatorResult::getCategory)
                .thenComparing(QcIndicatorResult::getCode)
                .thenComparing(QcIndicatorResult::getIndicatorVersion)
                .thenComparing(QcIndicatorResult::getPeriodFrom));
        return items;
    }

    /**
     * 查询周期内的指标版本分段：选择版本、校验同 code 唯一生效版本、按有效期裁剪。
     */
    private List<IndicatorSegment> effectiveSegments(LocalDate from, LocalDate to) {
        List<QcIndicatorEntity> definitions = indicatorMapper.selectActive(from, to);
        if (definitions == null || definitions.isEmpty()) {
            return List.of();
        }
        Map<String, List<QcIndicatorEntity>> byCode = new LinkedHashMap<>();
        for (QcIndicatorEntity definition : definitions) {
            if (definition == null || definition.getCode() == null) {
                continue;
            }
            byCode.computeIfAbsent(definition.getCode(), key -> new ArrayList<>())
                    .add(definition);
        }
        List<IndicatorSegment> segments = new ArrayList<>();
        for (Map.Entry<String, List<QcIndicatorEntity>> entry : byCode.entrySet()) {
            List<QcIndicatorEntity> versions = entry.getValue();
            versions.sort(Comparator.comparing(QcIndicatorEntity::getEffectiveFrom)
                    .thenComparing(QcIndicatorEntity::getVersion));
            for (int left = 0; left < versions.size(); left++) {
                for (int right = left + 1; right < versions.size(); right++) {
                    if (overlaps(versions.get(left), versions.get(right))) {
                        throw new M4ConflictException("指标口径版本在查询周期内重叠，拒绝计算",
                                List.of(new M4ConflictException.Detail("code",
                                        "指标 " + entry.getKey() + " 的版本 "
                                                + versions.get(left).getVersion() + " 与 "
                                                + versions.get(right).getVersion()
                                                + " 生效区间重叠，请先修复指标版本数据")));
                    }
                }
            }
            for (QcIndicatorEntity version : versions) {
                LocalDate segmentFrom = version.getEffectiveFrom() == null
                        || version.getEffectiveFrom().isBefore(from)
                        ? from : version.getEffectiveFrom();
                LocalDate segmentTo = version.getEffectiveTo() == null
                        || version.getEffectiveTo().isAfter(to)
                        ? to : version.getEffectiveTo();
                if (!segmentFrom.isAfter(segmentTo)) {
                    segments.add(new IndicatorSegment(version, segmentFrom, segmentTo));
                }
            }
        }
        return segments;
    }

    /** 两个版本的生效闭区间是否重叠（effective_to 为空视为长期有效）。 */
    private static boolean overlaps(QcIndicatorEntity left, QcIndicatorEntity right) {
        if (left.getEffectiveFrom() == null || right.getEffectiveFrom() == null) {
            return true;
        }
        boolean leftBeforeRightEnds = right.getEffectiveTo() == null
                || !left.getEffectiveFrom().isAfter(right.getEffectiveTo());
        boolean rightBeforeLeftEnds = left.getEffectiveTo() == null
                || !right.getEffectiveFrom().isAfter(left.getEffectiveTo());
        return leftBeforeRightEnds && rightBeforeLeftEnds;
    }

    private static Window withPeriod(Window base, LocalDate from, LocalDate to) {
        return new Window(from, to, base.deptId(), base.deptName(), base.visibility(),
                from.atStartOfDay(ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(ZONE).toInstant());
    }

    /**
     * 指标编码 → 分子/分母。未登记的编码（院方后续新增、计算器未实现）按 0/0 返回，
     * 在结果中显式呈现“无数据”，而不是静默丢弃整条指标。
     */
    static Counts countsFor(String code, QcIndicatorFacts facts) {
        if (code == null) {
            return new Counts(0, 0);
        }
        return switch (code) {
            case "QC-SYN-STRUCT-001" ->
                    new Counts(facts.recordCompletedCount(), facts.caseCount());
            case "QC-SYN-PROC-002" -> new Counts(facts.preopCount(), facts.caseCount());
            case "QC-SYN-PROC-003" -> new Counts(facts.threeWayCount(), facts.caseCount());
            case "QC-SYN-PROC-004" ->
                    new Counts(facts.narcoticDualSigned(), facts.narcoticTotal());
            case "QC-SYN-OUT-005" -> new Counts(facts.adverseEventCount(), facts.caseCount());
            case "QC-SYN-PROC-006" ->
                    new Counts(facts.chartReviewPass(), facts.chartReviewTotal());
            case "QC-SYN-STRUCT-007" ->
                    new Counts(facts.highValueUdiCount(), facts.highValueOutCount());
            default -> new Counts(0, 0);
        };
    }

    /** 指标值：分母 0 → 0；PERCENT ×100、PER_1000_CASES ×1000；保留 2 位小数。 */
    static BigDecimal valueOf(Counts counts, String unit) {
        if (counts == null || counts.denominator() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal numerator = BigDecimal.valueOf(Math.max(0, counts.numerator()));
        BigDecimal factor = "PER_1000_CASES".equals(unit)
                ? new BigDecimal("1000") : new BigDecimal("100");
        return numerator.multiply(factor)
                .divide(BigDecimal.valueOf(counts.denominator()), 2, RoundingMode.HALF_UP);
    }

    private static boolean meetsThreshold(BigDecimal value, QcIndicatorEntity definition) {
        BigDecimal threshold = definition.getThresholdValue() == null
                ? BigDecimal.ZERO : definition.getThresholdValue();
        if ("LTE".equals(definition.getThresholdOperator())) {
            return value.compareTo(threshold) <= 0;
        }
        return value.compareTo(threshold) >= 0;
    }

    private static QcIndicatorResultEntity toResultEntity(QcIndicatorEntity definition,
            Window window, Counts counts, BigDecimal value, boolean targetMet, Instant computedAt) {
        QcIndicatorResultEntity entity = new QcIndicatorResultEntity();
        entity.setId(UUID.randomUUID());
        entity.setIndicatorId(definition.getId());
        entity.setIndicatorCode(definition.getCode());
        entity.setIndicatorVersion(definition.getVersion());
        entity.setPeriodFrom(window.from());
        entity.setPeriodTo(window.to());
        entity.setDeptId(window.deptId() == null ? ALL_DEPTS : window.deptId());
        entity.setNumerator(counts.numerator());
        entity.setDenominator(counts.denominator());
        entity.setValue(value);
        entity.setTargetMet(targetMet);
        entity.setComputedAt(computedAt);
        return entity;
    }

    private QcIndicatorResult toIndicatorResult(QcIndicatorEntity definition, Window window,
            Counts counts, BigDecimal value, boolean targetMet, Instant computedAt) {
        QcIndicatorResult item = new QcIndicatorResult();
        item.setIndicatorId(definition.getId().toString());
        item.setCode(definition.getCode());
        item.setName(definition.getName());
        item.setCategory(QcIndicatorResult.CategoryEnum.fromValue(definition.getCategory()));
        item.setIndicatorVersion(definition.getVersion());
        item.setDefinition(definition.getDefinition());
        item.setUnit(QcIndicatorResult.UnitEnum.fromValue(definition.getUnit()));
        item.setPeriodFrom(window.from());
        item.setPeriodTo(window.to());
        item.setDeptId(window.deptId() == null ? null : window.deptId().toString());
        item.setNumerator(counts.numerator());
        item.setDenominator(counts.denominator());
        item.setValue(value);
        item.setThresholdOperator(QcIndicatorResult.ThresholdOperatorEnum
                .fromValue(definition.getThresholdOperator()));
        item.setThresholdValue(definition.getThresholdValue());
        item.setTargetMet(targetMet);
        item.setComputedAt(OffsetDateTime.ofInstant(computedAt, ZONE));
        item.setProvisional(Boolean.TRUE.equals(definition.getProvisional()));
        item.setInTargetStatistics(Boolean.TRUE.equals(definition.getInTargetStatistics()));
        return item;
    }

    private static QcIndicatorResultList resultList(Window window,
            List<QcIndicatorResult> items) {
        QcIndicatorResultList list = new QcIndicatorResultList();
        list.setFrom(window.from());
        list.setTo(window.to());
        list.setTimezone(TIMEZONE);
        list.setFormula(INDICATOR_FORMULA);
        list.setItems(new ArrayList<>(items));
        return list;
    }

    private static QcIndicator toIndicator(QcIndicatorEntity entity) {
        QcIndicator item = new QcIndicator();
        item.setId(entity.getId().toString());
        item.setCode(entity.getCode());
        item.setName(entity.getName());
        item.setCategory(QcIndicator.CategoryEnum.fromValue(entity.getCategory()));
        item.setDefinition(entity.getDefinition());
        item.setDataSource(entity.getDataSource());
        item.setNumeratorLabel(entity.getNumeratorLabel());
        item.setDenominatorLabel(entity.getDenominatorLabel());
        item.setUnit(QcIndicator.UnitEnum.fromValue(entity.getUnit()));
        item.setThresholdOperator(QcIndicator.ThresholdOperatorEnum
                .fromValue(entity.getThresholdOperator()));
        item.setThresholdValue(entity.getThresholdValue());
        item.setVersion(entity.getVersion());
        item.setEffectiveFrom(entity.getEffectiveFrom());
        item.setEffectiveTo(entity.getEffectiveTo());
        item.setStatus(QcIndicator.StatusEnum.fromValue(entity.getStatus()));
        item.setProvisional(Boolean.TRUE.equals(entity.getProvisional()));
        item.setInTargetStatistics(Boolean.TRUE.equals(entity.getInTargetStatistics()));
        return item;
    }

    // ---------------------------------------------------------------------
    // 不良事件
    // ---------------------------------------------------------------------

    @Override
    @Transactional
    public AdverseEvent createAdverseEvent(CreateAdverseEventRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("eventType", "请求体不能为空");
        }
        String eventType = requireEnumValue(request.getEventType() == null
                ? null : request.getEventType().getValue(), "eventType");
        String severity = requireEnumValue(request.getSeverity() == null
                ? null : request.getSeverity().getValue(), "severity");
        Instant occurredAt = requirePastInstant(request.getOccurredAt(), "occurredAt");
        String description = M4Values.requireText(request.getDescription(), "description", 500);
        if (PHI_PATTERN.matcher(description).find()) {
            throw new M4ValidationException("description",
                    "描述含疑似患者标识，请脱敏后重试（不得含姓名/住院号/身份证号/手机号）");
        }
        OrgDepartment department =
                accessGuard.requireDepartment(caller, request.getDeptId(), "deptId");
        UUID scheduleId = M4Values.optionalUuid(request.getScheduleId(), "scheduleId");
        requireScheduleDepartment(scheduleId, UUID.fromString(department.orgId()), "scheduleId");
        boolean anonymous = Boolean.TRUE.equals(request.getAnonymous());
        String reporterName = caller == null || caller.username() == null
                ? "系统" : caller.username();

        AdverseEventEntity entity = new AdverseEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setEventNo(nextNo("AE", occurredAt));
        entity.setEventType(eventType);
        entity.setSeverity(severity);
        entity.setOccurredAt(occurredAt);
        entity.setDeptId(UUID.fromString(department.orgId()));
        entity.setDeptName(department.name());
        entity.setScheduleId(scheduleId);
        entity.setDescription(description);
        entity.setReporterId(caller == null ? null : caller.userId());
        entity.setReporterName(reporterName);
        entity.setAnonymous(anonymous);
        entity.setStatus("REPORTED");
        entity.setStatusChangedAt(clock.instant());
        entity.setVersion(1);
        entity.setCreatedAt(clock.instant());
        entity.setCreatedBy(caller == null ? null : caller.userId());
        adverseEventMapper.insertEvent(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventNo", entity.getEventNo());
        detail.put("eventType", eventType);
        detail.put("severity", severity);
        detail.put("deptId", department.orgId());
        detail.put("anonymous", anonymous);
        audit("ADVERSE_EVENT_CREATE", "ADVERSE_EVENT", entity.getId().toString(), caller,
                context, detail);
        return toAdverseEvent(entity);
    }

    @Override
    public AdverseEventList listAdverseEvents(AdverseEventQuery query, AuthPrincipal caller,
            AuthRequestContext context) {
        if (query == null) {
            throw new M4ValidationException("status", "查询参数不能为空");
        }
        int page = page(query.page());
        int size = size(query.size());
        String status = optionalEnum(query.status(), ADVERSE_STATUSES, "status");
        Range range = optionalRange(query.from(), query.to());
        QcVisibility visibility = accessGuard.visibility(caller);
        String deptFilter = departmentFilter(caller, query.deptId(), visibility);
        List<AdverseEventEntity> rows = adverseEventMapper.selectPage(range.from(),
                range.toExclusive(), status, deptFilter, visibility.mapperFilter(), size,
                (page - 1) * size);
        long total = adverseEventMapper.countPage(range.from(), range.toExclusive(), status,
                deptFilter, visibility.mapperFilter());
        AdverseEventList list = new AdverseEventList();
        List<AdverseEvent> items = new ArrayList<>();
        for (AdverseEventEntity row : rows) {
            items.add(toAdverseEvent(row));
        }
        list.setItems(items);
        list.setMeta(meta(page, size, total));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", status);
        detail.put("deptId", deptFilter);
        detail.put("page", page);
        detail.put("size", size);
        detail.put("total", total);
        audit("ADVERSE_EVENT_QUERY", "ADVERSE_EVENT", null, caller, context, detail);
        return list;
    }

    @Override
    @Transactional
    public AdverseRca createRca(String eventId, CreateAdverseRcaRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("rootCause", "请求体不能为空");
        }
        UUID id = requireUuid(eventId, "id");
        AdverseEventEntity event = requireEvent(id);
        accessGuard.requireVisible(caller, event.getDeptId());
        if (!"REPORTED".equals(event.getStatus())) {
            throw new M4ConflictException("不良事件当前状态不允许提交 RCA",
                    List.of(new M4ConflictException.Detail("status",
                            "仅 REPORTED 状态可提交 RCA；当前状态：" + event.getStatus())));
        }
        if (adverseRcaMapper.selectByEventId(id) != null) {
            throw new M4ConflictException("该事件已有 RCA，不得重复提交",
                    List.of(new M4ConflictException.Detail("status", "一条事件只允许一条 RCA")));
        }
        List<RcaTimelineItem> timeline = validTimeline(request.getTimeline());
        String rootCause = M4Values.requireText(request.getRootCause(), "rootCause", 500);
        String correctiveAction =
                M4Values.requireText(request.getCorrectiveAction(), "correctiveAction", 500);
        String responsibleName =
                M4Values.requireText(request.getResponsibleName(), "responsibleName", 64);
        LocalDate dueDate = request.getDueDate();
        if (dueDate == null) {
            throw new M4ValidationException("dueDate", "dueDate 不能为空");
        }
        LocalDate occurredDate = event.getOccurredAt().atZone(ZONE).toLocalDate();
        if (dueDate.isBefore(occurredDate)) {
            throw new M4UnprocessableException("dueDate", "整改期限不得早于事件发生日期");
        }

        AdverseRcaEntity rca = new AdverseRcaEntity();
        rca.setId(UUID.randomUUID());
        rca.setEventId(event.getId());
        rca.setEventNo(event.getEventNo());
        rca.setTimeline(qcJson.writeTimeline(timeline));
        rca.setRootCause(rootCause);
        rca.setCorrectiveAction(correctiveAction);
        rca.setResponsibleName(responsibleName);
        rca.setDueDate(dueDate);
        rca.setStatus("OPEN");
        rca.setCreatedAt(clock.instant());
        rca.setCreatedBy(caller == null ? null : caller.userId());
        adverseRcaMapper.insertRca(rca);

        int moved = adverseEventMapper.updateStatus(event.getId(), "REPORTED", "RCA",
                event.getVersion());
        if (moved == 0) {
            throw new M4ConflictException("不良事件已被他人更新",
                    List.of(new M4ConflictException.Detail("status", "请刷新后重试")));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventNo", event.getEventNo());
        detail.put("rcaId", rca.getId().toString());
        detail.put("timelineCount", timeline.size());
        detail.put("dueDate", dueDate.toString());
        audit("RCA_CREATE", "ADVERSE_RCA", rca.getId().toString(), caller, context, detail);
        auditStatus(event, "RCA", caller, context);
        return toRca(rca, "RCA");
    }

    @Override
    @Transactional
    public AdverseRca closeRca(String rcaId, CloseAdverseRcaRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("reviewerName", "请求体不能为空");
        }
        UUID id = requireUuid(rcaId, "id");
        AdverseRcaEntity rca = adverseRcaMapper.selectRcaById(id);
        if (rca == null) {
            throw new NoSuchElementException("RCA 不存在或不可见");
        }
        AdverseEventEntity event = requireEvent(rca.getEventId());
        accessGuard.requireVisible(caller, event.getDeptId());
        String reviewerName =
                M4Values.requireText(request.getReviewerName(), "reviewerName", 64);
        String conclusion = M4Values.requireText(request.getConclusion(), "conclusion", 300);
        requireIndependentReviewer(reviewerName, event.getReporterName(),
                rca.getResponsibleName());
        boolean completed = Boolean.TRUE.equals(request.getRectificationCompleted());

        if ("RCA".equals(event.getStatus()) && rca.getReviewedAt() == null
                && rca.getClosedAt() == null) {
            if (completed) {
                throw new M4ConflictException("复核阶段不得声明整改完成",
                        List.of(new M4ConflictException.Detail("rectificationCompleted",
                                "阶段 1 只做 RCA 复核，整改完成确认属阶段 2")));
            }
            int reviewed = adverseRcaMapper.markReviewed(rca.getId(),
                    caller == null ? null : caller.userId(), reviewerName, clock.instant(),
                    conclusion);
            if (reviewed == 0) {
                throw new M4ConflictException("RCA 已被他人复核",
                        List.of(new M4ConflictException.Detail("status", "请刷新后重试")));
            }
            int moved = adverseEventMapper.updateStatus(event.getId(), "RCA", "RECTIFYING",
                    event.getVersion());
            if (moved == 0) {
                throw new M4ConflictException("不良事件已被他人更新",
                        List.of(new M4ConflictException.Detail("status", "请刷新后重试")));
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("eventNo", event.getEventNo());
            detail.put("stage", "REVIEW");
            detail.put("toStatus", "RECTIFYING");
            audit("RCA_REVIEW", "ADVERSE_RCA", rca.getId().toString(), caller, context, detail);
            auditStatus(event, "RECTIFYING", caller, context);
            return toRca(adverseRcaMapper.selectRcaById(rca.getId()), "RECTIFYING");
        }

        if ("RECTIFYING".equals(event.getStatus()) && rca.getReviewedAt() != null
                && rca.getClosedAt() == null) {
            if (!completed) {
                throw new M4ConflictException("整改完成确认缺失",
                        List.of(new M4ConflictException.Detail("rectificationCompleted",
                                "阶段 2 必须确认整改完成（rectificationCompleted=true）")));
            }
            if (rca.getReviewerName() != null && !reviewerName.equals(rca.getReviewerName())) {
                throw new M4UnprocessableException("reviewerName",
                        "闭环复核人必须与复核阶段一致（复核字段一次性写入）");
            }
            int closed = adverseRcaMapper.markClosed(rca.getId(), clock.instant());
            if (closed == 0) {
                throw new M4ConflictException("RCA 已闭环或状态不允许闭环",
                        List.of(new M4ConflictException.Detail("status", "请刷新后重试")));
            }
            int done = adverseEventMapper.updateStatus(event.getId(), "RECTIFYING", "CLOSED",
                    event.getVersion());
            if (done == 0) {
                throw new M4ConflictException("不良事件已被他人更新",
                        List.of(new M4ConflictException.Detail("status", "请刷新后重试")));
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("eventNo", event.getEventNo());
            detail.put("stage", "CLOSE");
            detail.put("toStatus", "CLOSED");
            audit("RCA_CLOSE", "ADVERSE_RCA", rca.getId().toString(), caller, context, detail);
            auditStatus(event, "CLOSED", caller, context);
            return toRca(adverseRcaMapper.selectRcaById(rca.getId()), "CLOSED");
        }

        throw new M4ConflictException("RCA 当前阶段不允许闭环",
                List.of(new M4ConflictException.Detail("status",
                        "事件状态 " + event.getStatus() + " 与 RCA 阶段不符，禁止越级/重复闭环")));
    }

    private AdverseEventEntity requireEvent(UUID id) {
        AdverseEventEntity event = adverseEventMapper.selectEventById(id);
        if (event == null) {
            throw new NoSuchElementException("不良事件不存在或不可见");
        }
        return event;
    }

    /**
     * 逻辑关联台次校验（评审 S-3）：不存在/已删除 422；存在但属他科 403。
     * 只在请求携带 {@code scheduleId} 时执行，不改变“可空”语义。
     */
    private void requireScheduleDepartment(UUID scheduleId, UUID deptId, String field) {
        if (scheduleId == null) {
            return;
        }
        UUID actual = caseReferenceSource.scheduleDepartment(scheduleId)
                .orElseThrow(() -> new M4UnprocessableException(field, "关联台次不存在或已删除"));
        requireSameDepartment(actual, deptId, field, "关联台次");
    }

    /** 逻辑关联麻醉记录单校验（评审 S-3）：不存在/已删除 422；存在但属他科 403。 */
    private void requireRecordDepartment(UUID recordId, UUID deptId, String field) {
        if (recordId == null) {
            return;
        }
        UUID actual = caseReferenceSource.recordDepartment(recordId)
                .orElseThrow(() -> new M4UnprocessableException(field,
                        "关联麻醉记录单不存在或已删除"));
        requireSameDepartment(actual, deptId, field, "关联麻醉记录单");
    }

    private static void requireSameDepartment(UUID actual, UUID expected, String field,
            String label) {
        if (!expected.equals(actual)) {
            throw new AccessDeniedException(label + "不属于提交科室，禁止跨科室关联");
        }
    }

    // ---------------------------------------------------------------------
    // 病历质量抽查与整改
    // ---------------------------------------------------------------------

    @Override
    @Transactional
    public ChartReview createChartReview(CreateChartReviewRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("items", "请求体不能为空");
        }
        List<ChartReviewItem> items = validItems(request.getItems());
        Instant reviewedAt = requirePastInstant(request.getReviewedAt(), "reviewedAt");
        OrgDepartment department =
                accessGuard.requireDepartment(caller, request.getDeptId(), "deptId");
        UUID scheduleId = M4Values.optionalUuid(request.getScheduleId(), "scheduleId");
        UUID recordId = M4Values.optionalUuid(request.getRecordId(), "recordId");
        UUID deptId = UUID.fromString(department.orgId());
        requireScheduleDepartment(scheduleId, deptId, "scheduleId");
        requireRecordDepartment(recordId, deptId, "recordId");
        int totalScore = 0;
        int maxScore = 0;
        int defectCount = 0;
        for (ChartReviewItem item : items) {
            totalScore += item.getScore();
            maxScore += item.getMaxScore();
            if (item.getScore() < item.getMaxScore()) {
                defectCount++;
            }
        }
        String reviewerName = caller == null || caller.username() == null
                ? "系统" : caller.username();

        ChartReviewEntity entity = new ChartReviewEntity();
        entity.setId(UUID.randomUUID());
        entity.setReviewNo(nextNo("CR", reviewedAt));
        entity.setScheduleId(scheduleId);
        entity.setRecordId(recordId);
        entity.setDeptId(UUID.fromString(department.orgId()));
        entity.setDeptName(department.name());
        entity.setReviewerId(caller == null ? null : caller.userId());
        entity.setReviewerName(reviewerName);
        entity.setReviewedAt(reviewedAt);
        entity.setItems(qcJson.writeItems(items));
        entity.setTotalScore(totalScore);
        entity.setMaxScore(maxScore);
        entity.setGrade(gradeOf(totalScore, maxScore));
        entity.setDefectCount(defectCount);
        entity.setStatus("OPEN");
        entity.setVersion(1);
        entity.setCreatedAt(clock.instant());
        entity.setUpdatedAt(clock.instant());
        entity.setCreatedBy(caller == null ? null : caller.userId());
        chartReviewMapper.insertReview(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reviewNo", entity.getReviewNo());
        detail.put("deptId", department.orgId());
        detail.put("totalScore", totalScore);
        detail.put("maxScore", maxScore);
        detail.put("defectCount", defectCount);
        audit("CHART_REVIEW_CREATE", "CHART_REVIEW", entity.getId().toString(), caller, context,
                detail);
        return toChartReview(entity);
    }

    @Override
    public ChartReviewList listChartReviews(ChartReviewQuery query, AuthPrincipal caller,
            AuthRequestContext context) {
        if (query == null) {
            throw new M4ValidationException("status", "查询参数不能为空");
        }
        int page = page(query.page());
        int size = size(query.size());
        String status = optionalEnum(query.status(), REVIEW_STATUSES, "status");
        Range range = optionalRange(query.from(), query.to());
        QcVisibility visibility = accessGuard.visibility(caller);
        String deptFilter = departmentFilter(caller, query.deptId(), visibility);
        List<ChartReviewEntity> rows = chartReviewMapper.selectPage(range.from(),
                range.toExclusive(), status, deptFilter, visibility.mapperFilter(), size,
                (page - 1) * size);
        long total = chartReviewMapper.countPage(range.from(), range.toExclusive(), status,
                deptFilter, visibility.mapperFilter());
        ChartReviewList list = new ChartReviewList();
        List<ChartReview> items = new ArrayList<>();
        for (ChartReviewEntity row : rows) {
            items.add(toChartReview(row));
        }
        list.setItems(items);
        list.setMeta(meta(page, size, total));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", status);
        detail.put("deptId", deptFilter);
        detail.put("page", page);
        detail.put("size", size);
        detail.put("total", total);
        audit("CHART_REVIEW_QUERY", "CHART_REVIEW", null, caller, context, detail);
        return list;
    }

    @Override
    @Transactional
    public Rectification createRectification(String reviewId, CreateRectificationRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("correctiveAction", "请求体不能为空");
        }
        UUID id = requireUuid(reviewId, "id");
        ChartReviewEntity review = requireReview(id);
        accessGuard.requireVisible(caller, review.getDeptId());
        if (!"OPEN".equals(review.getStatus())) {
            throw new M4ConflictException("抽查当前状态不允许提交整改",
                    List.of(new M4ConflictException.Detail("status",
                            "仅 OPEN 状态可提交整改；当前状态：" + review.getStatus())));
        }
        if (rectificationMapper.selectByReviewId(id) != null) {
            throw new M4ConflictException("该抽查已有整改记录",
                    List.of(new M4ConflictException.Detail("status", "一条抽查只允许一条整改")));
        }
        String correctiveAction =
                M4Values.requireText(request.getCorrectiveAction(), "correctiveAction", 500);
        String responsibleName =
                M4Values.requireText(request.getResponsibleName(), "responsibleName", 64);
        LocalDate dueDate = request.getDueDate();
        if (dueDate == null) {
            throw new M4ValidationException("dueDate", "dueDate 不能为空");
        }
        LocalDate reviewedDate = review.getReviewedAt().atZone(ZONE).toLocalDate();
        if (dueDate.isBefore(reviewedDate)) {
            throw new M4UnprocessableException("dueDate", "整改期限不得早于抽查日期");
        }

        RectificationEntity entity = new RectificationEntity();
        entity.setId(UUID.randomUUID());
        entity.setReviewId(review.getId());
        entity.setReviewNo(review.getReviewNo());
        entity.setCorrectiveAction(correctiveAction);
        entity.setResponsibleName(responsibleName);
        entity.setDueDate(dueDate);
        entity.setStatus("OPEN");
        entity.setCreatedAt(clock.instant());
        entity.setCreatedBy(caller == null ? null : caller.userId());
        rectificationMapper.insertRectification(entity);

        int moved = chartReviewMapper.updateStatus(review.getId(), "OPEN", "RECTIFYING",
                review.getVersion(), clock.instant(), caller == null ? null : caller.userId());
        if (moved == 0) {
            throw new M4ConflictException("抽查已被他人更新",
                    List.of(new M4ConflictException.Detail("status", "请刷新后重试")));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reviewNo", review.getReviewNo());
        detail.put("rectificationId", entity.getId().toString());
        detail.put("dueDate", dueDate.toString());
        audit("RECTIFICATION_CREATE", "RECTIFICATION", entity.getId().toString(), caller, context,
                detail);
        auditReviewStatus(review, "RECTIFYING", caller, context);
        return toRectification(entity, "RECTIFYING");
    }

    @Override
    @Transactional
    public Rectification closeRectification(String rectificationId,
            CloseRectificationRequest request, AuthPrincipal caller, AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("reviewerName", "请求体不能为空");
        }
        UUID id = requireUuid(rectificationId, "id");
        RectificationEntity entity = rectificationMapper.selectRectificationById(id);
        if (entity == null) {
            throw new NoSuchElementException("整改记录不存在或不可见");
        }
        ChartReviewEntity review = requireReview(entity.getReviewId());
        accessGuard.requireVisible(caller, review.getDeptId());
        if (!"OPEN".equals(entity.getStatus()) || !"RECTIFYING".equals(review.getStatus())) {
            throw new M4ConflictException("整改当前阶段不允许闭环",
                    List.of(new M4ConflictException.Detail("status",
                            "整改状态 " + entity.getStatus() + " / 抽查状态 "
                                    + review.getStatus() + " 不允许闭环")));
        }
        String reviewerName =
                M4Values.requireText(request.getReviewerName(), "reviewerName", 64);
        String conclusion = M4Values.requireText(request.getConclusion(), "conclusion", 300);
        requireIndependentReviewer(reviewerName, review.getReviewerName(),
                entity.getResponsibleName());
        Instant now = clock.instant();
        int closed = rectificationMapper.markClosed(entity.getId(),
                caller == null ? null : caller.userId(), reviewerName, now, conclusion);
        if (closed == 0) {
            throw new M4ConflictException("整改已被他人闭环",
                    List.of(new M4ConflictException.Detail("status", "请刷新后重试")));
        }
        int done = chartReviewMapper.updateStatus(review.getId(), "RECTIFYING", "CLOSED",
                review.getVersion(), now, caller == null ? null : caller.userId());
        if (done == 0) {
            throw new M4ConflictException("抽查已被他人更新",
                    List.of(new M4ConflictException.Detail("status", "请刷新后重试")));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reviewNo", review.getReviewNo());
        detail.put("rectificationId", entity.getId().toString());
        detail.put("toStatus", "CLOSED");
        audit("RECTIFICATION_CLOSE", "RECTIFICATION", entity.getId().toString(), caller, context,
                detail);
        auditReviewStatus(review, "CLOSED", caller, context);
        return toRectification(rectificationMapper.selectRectificationById(entity.getId()),
                "CLOSED");
    }

    private ChartReviewEntity requireReview(UUID id) {
        ChartReviewEntity review = chartReviewMapper.selectReviewById(id);
        if (review == null) {
            throw new NoSuchElementException("抽查记录不存在或不可见");
        }
        return review;
    }

    // ---------------------------------------------------------------------
    // 导出（脱敏、防注入、无自由文本原文）
    // ---------------------------------------------------------------------

    @Override
    @Transactional(noRollbackFor = ExportLimitExceededException.class)
    public ExportPlan prepareExport(QcExportQuery query, AuthPrincipal caller,
            AuthRequestContext context) {
        if (query == null) {
            throw new M4ValidationException("type", "查询参数不能为空");
        }
        String type = normalizeType(query.type());
        String format = normalizeFormat(query.format());
        Window window = window(query.from(), query.to(), query.deptId(), caller);
        List<List<String>> rows;
        List<String> header;
        if ("indicator".equals(type)) {
            header = INDICATOR_HEADER;
            List<QcIndicatorResult> results = computeAndPersist(window);
            rows = csvIndicators(results, window);
            auditCompute(window, results, "export", caller, context);
        } else if ("adverse".equals(type)) {
            // LIMIT MAX+1：命中第 MAX+1 行即判定超限，绝不静默截断为 MAX 行。
            List<Map<String, Object>> source = adverseEventMapper.selectExportRows(
                    window.fromInstant(), window.toExclusive(), deptFilter(window),
                    window.visibility().mapperFilter(), MAX_EXPORT_ROWS + 1);
            if (source.size() > MAX_EXPORT_ROWS) {
                long total = adverseEventMapper.countPage(window.fromInstant(),
                        window.toExclusive(), null, deptFilter(window),
                        window.visibility().mapperFilter());
                rejectOversizeExport(type, window, Math.max(total, source.size()), caller, context);
            }
            header = ADVERSE_HEADER;
            rows = csvAdverse(source, window);
        } else {
            header = REVIEW_HEADER;
            List<Map<String, Object>> source = chartReviewMapper.selectExportRows(
                    window.fromInstant(), window.toExclusive(), deptFilter(window),
                    window.visibility().mapperFilter(), MAX_EXPORT_ROWS + 1);
            if (source.size() > MAX_EXPORT_ROWS) {
                long total = chartReviewMapper.countPage(window.fromInstant(),
                        window.toExclusive(), null, deptFilter(window),
                        window.visibility().mapperFilter());
                rejectOversizeExport(type, window, Math.max(total, source.size()), caller, context);
            }
            rows = csvReview(source, window);
        }
        Map<String, Object> detail = exportDetail(type, window);
        detail.put("dataRows", rows.size());
        detail.put("truncated", false);
        audit("QC_EXPORT", "QC_EXPORT", type, caller, context, detail);
        String fileName = "qc-" + type + "-" + window.from() + "-" + window.to() + "." + format;
        return new ExportPlan(fileName, rows.size(),
                output -> writeCsv(output, header, rows));
    }

    /**
     * 导出超上限：写失败审计（真实总行数 + truncated=true）后抛 422；
     * 事务对 {@link ExportLimitExceededException} 不滚回，保证审计留存。
     */
    private void rejectOversizeExport(String type, Window window, long total,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = exportDetail(type, window);
        detail.put("dataRows", total);
        detail.put("truncated", true);
        detail.put("limit", MAX_EXPORT_ROWS);
        auditWriter.record("QC_EXPORT", "QC_EXPORT", type, false, detail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
        throw new ExportLimitExceededException("type",
                "导出数据行 " + total + " 超过上限 " + MAX_EXPORT_ROWS + "，请缩小日期范围");
    }

    private static Map<String, Object> exportDetail(String type, Window window) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("from", window.from().toString());
        detail.put("to", window.to().toString());
        detail.put("deptId", deptFilter(window));
        return detail;
    }

    private static List<List<String>> csvIndicators(List<QcIndicatorResult> items, Window window) {
        List<List<String>> rows = new ArrayList<>(items.size());
        for (QcIndicatorResult item : items) {
            List<String> row = new ArrayList<>(19);
            row.add("qc-indicator");
            row.add(item.getCode());
            row.add(item.getName());
            row.add(item.getCategory().getValue());
            row.add(item.getIndicatorVersion());
            row.add(item.getPeriodFrom().toString());
            row.add(item.getPeriodTo().toString());
            row.add(item.getDeptId() == null ? "" : item.getDeptId());
            row.add(window.deptName() == null ? "" : window.deptName());
            row.add(String.valueOf(item.getNumerator()));
            row.add(String.valueOf(item.getDenominator()));
            row.add(item.getValue().setScale(2, RoundingMode.HALF_UP).toPlainString());
            row.add(item.getUnit().getValue());
            row.add(item.getThresholdOperator().getValue());
            row.add(plain(item.getThresholdValue()));
            row.add(String.valueOf(Boolean.TRUE.equals(item.getTargetMet())));
            row.add(String.valueOf(Boolean.TRUE.equals(item.getInTargetStatistics())));
            row.add(String.valueOf(Boolean.TRUE.equals(item.getProvisional())));
            row.add(TIMEZONE);
            rows.add(row);
        }
        return rows;
    }

    private static List<List<String>> csvAdverse(List<Map<String, Object>> source, Window window) {
        List<List<String>> rows = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            rows.add(row("qc-adverse", text(item, "event_no"), text(item, "event_type"),
                    text(item, "severity"), text(item, "occurred_at"), text(item, "dept_id"),
                    text(item, "dept_name"), text(item, "status"), text(item, "rca_status"),
                    text(item, "responsible_name"), text(item, "due_date"),
                    text(item, "reviewer_name"), text(item, "closed_at"),
                    text(item, "anonymous"), TIMEZONE));
        }
        return rows;
    }

    private static List<List<String>> csvReview(List<Map<String, Object>> source, Window window) {
        List<List<String>> rows = new ArrayList<>(source.size());
        for (Map<String, Object> item : source) {
            rows.add(row("qc-review", text(item, "review_no"), text(item, "reviewed_at"),
                    text(item, "dept_id"), text(item, "dept_name"), text(item, "total_score"),
                    text(item, "max_score"), text(item, "grade"), text(item, "defect_count"),
                    text(item, "status"), text(item, "rectification_status"),
                    text(item, "responsible_name"), text(item, "due_date"),
                    text(item, "closed_at"), TIMEZONE));
        }
        return rows;
    }

    private static List<String> row(String... values) {
        List<String> cells = new ArrayList<>(values.length);
        for (String value : values) {
            cells.add(value == null ? "" : value);
        }
        return cells;
    }

    /** Map 值 → CSV 文本（null 为空串；时间已由 SQL 转为 Asia/Shanghai 文本）。 */
    private static String text(Map<String, Object> item, String key) {
        Object value = item == null ? null : item.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String plain(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? "0" : normalized.toPlainString();
    }

    private static void writeCsv(OutputStream output, List<String> header,
            List<List<String>> rows) {
        try {
            output.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writer.write(header.stream().map(QcServiceImpl::csvCell)
                    .collect(Collectors.joining(",")));
            writer.newLine();
            for (List<String> row : rows) {
                writer.write(row.stream().map(QcServiceImpl::csvCell)
                        .collect(Collectors.joining(",")));
                writer.newLine();
            }
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("质控 CSV 导出失败", failure);
        }
    }

    static boolean isFormulaRisk(String value) {
        return value != null && !value.isEmpty()
                && FORMULA_PREFIXES.indexOf(value.charAt(0)) >= 0;
    }

    private static String csvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String cell = isFormulaRisk(value) ? "'" + value : value;
        boolean quote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0
                || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
        if (!quote) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    // ---------------------------------------------------------------------
    // DTO 映射
    // ---------------------------------------------------------------------

    private AdverseEvent toAdverseEvent(AdverseEventEntity entity) {
        AdverseEvent item = new AdverseEvent();
        item.setId(entity.getId().toString());
        item.setEventNo(entity.getEventNo());
        item.setEventType(AdverseEvent.EventTypeEnum.fromValue(entity.getEventType()));
        item.setSeverity(AdverseEvent.SeverityEnum.fromValue(entity.getSeverity()));
        item.setOccurredAt(OffsetDateTime.ofInstant(entity.getOccurredAt(), ZONE));
        item.setDeptId(entity.getDeptId().toString());
        item.setDeptName(entity.getDeptName());
        item.setScheduleId(entity.getScheduleId() == null ? null
                : entity.getScheduleId().toString());
        item.setDescription(entity.getDescription());
        item.setReporterName(Boolean.TRUE.equals(entity.getAnonymous())
                ? ANONYMOUS_REPORTER : entity.getReporterName());
        item.setAnonymous(Boolean.TRUE.equals(entity.getAnonymous()));
        item.setStatus(AdverseEvent.StatusEnum.fromValue(entity.getStatus()));
        item.setRcaId(entity.getRcaId() == null ? null : entity.getRcaId().toString());
        item.setVersion(entity.getVersion());
        item.setCreatedAt(OffsetDateTime.ofInstant(entity.getCreatedAt(), ZONE));
        return item;
    }

    private AdverseRca toRca(AdverseRcaEntity entity, String eventStatus) {
        AdverseRca item = new AdverseRca();
        item.setId(entity.getId().toString());
        item.setEventId(entity.getEventId().toString());
        item.setEventNo(entity.getEventNo());
        item.setTimeline(qcJson.readTimeline(entity.getTimeline()));
        item.setRootCause(entity.getRootCause());
        item.setCorrectiveAction(entity.getCorrectiveAction());
        item.setResponsibleName(entity.getResponsibleName());
        item.setDueDate(entity.getDueDate());
        item.setStatus(AdverseRca.StatusEnum.fromValue(entity.getStatus()));
        item.setReviewerName(entity.getReviewerName());
        item.setReviewedAt(entity.getReviewedAt() == null ? null
                : OffsetDateTime.ofInstant(entity.getReviewedAt(), ZONE));
        item.setClosedAt(entity.getClosedAt() == null ? null
                : OffsetDateTime.ofInstant(entity.getClosedAt(), ZONE));
        item.setCreatedAt(OffsetDateTime.ofInstant(entity.getCreatedAt(), ZONE));
        item.setEventStatus(AdverseRca.EventStatusEnum.fromValue(eventStatus));
        return item;
    }

    private ChartReview toChartReview(ChartReviewEntity entity) {
        ChartReview item = new ChartReview();
        item.setId(entity.getId().toString());
        item.setReviewNo(entity.getReviewNo());
        item.setScheduleId(entity.getScheduleId() == null ? null
                : entity.getScheduleId().toString());
        item.setRecordId(entity.getRecordId() == null ? null : entity.getRecordId().toString());
        item.setRectificationId(entity.getRectificationId() == null ? null
                : entity.getRectificationId().toString());
        item.setDeptId(entity.getDeptId().toString());
        item.setDeptName(entity.getDeptName());
        item.setReviewerName(entity.getReviewerName());
        item.setReviewedAt(OffsetDateTime.ofInstant(entity.getReviewedAt(), ZONE));
        item.setItems(qcJson.readItems(entity.getItems()));
        item.setTotalScore(entity.getTotalScore());
        item.setMaxScore(entity.getMaxScore());
        item.setGrade(ChartReview.GradeEnum.fromValue(entity.getGrade()));
        item.setDefectCount(entity.getDefectCount());
        item.setStatus(ChartReview.StatusEnum.fromValue(entity.getStatus()));
        item.setCreatedAt(OffsetDateTime.ofInstant(entity.getCreatedAt(), ZONE));
        return item;
    }

    private Rectification toRectification(RectificationEntity entity, String reviewStatus) {
        Rectification item = new Rectification();
        item.setId(entity.getId().toString());
        item.setReviewId(entity.getReviewId().toString());
        item.setReviewNo(entity.getReviewNo());
        item.setCorrectiveAction(entity.getCorrectiveAction());
        item.setResponsibleName(entity.getResponsibleName());
        item.setDueDate(entity.getDueDate());
        item.setStatus(Rectification.StatusEnum.fromValue(entity.getStatus()));
        item.setReviewerName(entity.getReviewerName());
        item.setReviewedAt(entity.getReviewedAt() == null ? null
                : OffsetDateTime.ofInstant(entity.getReviewedAt(), ZONE));
        item.setClosedAt(entity.getClosedAt() == null ? null
                : OffsetDateTime.ofInstant(entity.getClosedAt(), ZONE));
        item.setCreatedAt(OffsetDateTime.ofInstant(entity.getCreatedAt(), ZONE));
        item.setReviewStatus(Rectification.ReviewStatusEnum.fromValue(reviewStatus));
        return item;
    }

    private static PageMeta meta(int page, int size, long total) {
        int safeTotal = (int) Math.min(total, Integer.MAX_VALUE);
        PageMeta meta = new PageMeta();
        meta.setPage(page);
        meta.setSize(size);
        meta.setTotal(safeTotal);
        meta.setTotalPages(safeTotal == 0 ? 0 : (safeTotal + size - 1) / size);
        return meta;
    }

    // ---------------------------------------------------------------------
    // 参数校验
    // ---------------------------------------------------------------------

    /**
     * 解析必填周期、数据范围与科室收窄条件。
     *
     * <p>失败语义：日期/跨度/标识非法 → 400；无范围/科室越界 → 403；科室不存在/停用 → 422。
     */
    private Window window(String fromRaw, String toRaw, String deptIdRaw, AuthPrincipal caller) {
        LocalDate from = parseDate(fromRaw, "from");
        LocalDate to = parseDate(toRaw, "to");
        validateSpan(from, to);
        QcVisibility visibility = accessGuard.visibility(caller);
        UUID deptId = null;
        String deptName = null;
        if (deptIdRaw != null && !deptIdRaw.isBlank()) {
            OrgDepartment department =
                    accessGuard.requireDepartment(caller, deptIdRaw, "deptId");
            deptId = UUID.fromString(department.orgId());
            deptName = department.name();
        }
        return new Window(from, to, deptId, deptName, visibility,
                from.atStartOfDay(ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(ZONE).toInstant());
    }

    private static void validateSpan(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new M4ValidationException("to", "结束日期不能早于起始日期");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new M4ValidationException("to", "查询跨度不能超过 " + MAX_RANGE_DAYS + " 天");
        }
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new M4ValidationException(field, "日期不能为空");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException invalid) {
            throw new M4ValidationException(field, "日期格式必须为 YYYY-MM-DD");
        }
    }

    /** 可选日期范围：两者都空 → 不过滤；只给一个 → 400；都给出 → 闭区间换算。 */
    private static Range optionalRange(String fromRaw, String toRaw) {
        boolean fromBlank = fromRaw == null || fromRaw.isBlank();
        boolean toBlank = toRaw == null || toRaw.isBlank();
        if (fromBlank && toBlank) {
            return new Range(null, null);
        }
        if (fromBlank || toBlank) {
            throw new M4ValidationException("from", "起止日期需同时提供或同时留空");
        }
        LocalDate from = parseDate(fromRaw, "from");
        LocalDate to = parseDate(toRaw, "to");
        validateSpan(from, to);
        return new Range(from.atStartOfDay(ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(ZONE).toInstant());
    }

    private String departmentFilter(AuthPrincipal caller, String deptIdRaw,
            QcVisibility visibility) {
        if (deptIdRaw == null || deptIdRaw.isBlank()) {
            return null;
        }
        OrgDepartment department = accessGuard.requireDepartment(caller, deptIdRaw, "deptId");
        return department.orgId();
    }

    private static String deptFilter(Window window) {
        return window.deptId() == null ? null : window.deptId().toString();
    }

    private static List<UUID> orgIds(QcVisibility visibility) {
        if (visibility.unrestricted()) {
            return List.of();
        }
        Set<UUID> parsed = new LinkedHashSet<>();
        for (String value : visibility.allowedDeptIds()) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                parsed.add(UUID.fromString(value.trim()));
            } catch (IllegalArgumentException ignored) {
                // 绑定数据异常按不可见处理（fail-closed），不扩大范围。
            }
        }
        return List.copyOf(parsed);
    }

    private static int page(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw new M4ValidationException("page", "page 必须从 1 开始");
        }
        return page;
    }

    private static int size(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new M4ValidationException("size", "size 必须在 1–" + MAX_PAGE_SIZE + " 之间");
        }
        return size;
    }

    private static String requireEnumValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        return value;
    }

    private static String optionalEnum(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new M4ValidationException(field, field + " 取值非法");
        }
        return normalized;
    }

    private static UUID requireUuid(String value, String field) {
        UUID parsed = M4Values.parseUuid(value);
        if (parsed == null) {
            // 路径标识缺失/非法按“资源不存在”处理（集合查询不会走到这里）。
            throw new NoSuchElementException(field + " 格式非法");
        }
        return parsed;
    }

    private Instant requirePastInstant(OffsetDateTime value, String field) {
        if (value == null) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        Instant instant = value.toInstant();
        if (instant.isAfter(clock.instant().plusSeconds(300))) {
            throw new M4ValidationException(field, field + " 不得晚于当前时间");
        }
        return instant;
    }

    private List<RcaTimelineItem> validTimeline(List<RcaTimelineItem> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            throw new M4ValidationException("timeline", "timeline 至少 1 条");
        }
        if (timeline.size() > MAX_TIMELINE_ITEMS) {
            throw new M4ValidationException("timeline",
                    "timeline 不能超过 " + MAX_TIMELINE_ITEMS + " 条");
        }
        Instant latest = clock.instant().plusSeconds(300);
        List<RcaTimelineItem> items = new ArrayList<>(timeline.size());
        for (RcaTimelineItem item : timeline) {
            if (item == null || item.getOccurredAt() == null) {
                throw new M4ValidationException("timeline", "timeline 条目时间不能为空");
            }
            if (item.getOccurredAt().toInstant().isAfter(latest)) {
                throw new M4ValidationException("timeline", "timeline 条目时间不得晚于当前时间");
            }
            String description =
                    M4Values.requireText(item.getDescription(), "timeline", 200);
            RcaTimelineItem normalized = new RcaTimelineItem();
            normalized.setOccurredAt(item.getOccurredAt());
            normalized.setDescription(description);
            items.add(normalized);
        }
        items.sort(Comparator.comparing(RcaTimelineItem::getOccurredAt));
        return items;
    }

    private static List<ChartReviewItem> validItems(List<ChartReviewItem> items) {
        if (items == null || items.isEmpty()) {
            throw new M4ValidationException("items", "评分项至少 1 项");
        }
        if (items.size() > MAX_REVIEW_ITEMS) {
            throw new M4ValidationException("items",
                    "评分项不能超过 " + MAX_REVIEW_ITEMS + " 项");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ChartReviewItem> normalized = new ArrayList<>(items.size());
        for (ChartReviewItem item : items) {
            if (item == null || item.getItemCode() == null) {
                throw new M4ValidationException("items", "评分项编码不能为空");
            }
            String code = item.getItemCode().getValue();
            if (!seen.add(code)) {
                throw new M4ValidationException("items", "评分项 itemCode 不得重复");
            }
            if (item.getScore() == null || item.getMaxScore() == null) {
                throw new M4ValidationException("items", "评分项得分与满分不能为空");
            }
            if (item.getScore() < 0 || item.getScore() > 100
                    || item.getMaxScore() < 1 || item.getMaxScore() > 100) {
                throw new M4ValidationException("items", "评分项得分/满分越界（0–100）");
            }
            if (item.getScore() > item.getMaxScore()) {
                throw new M4ValidationException("items", "评分项得分不得大于满分");
            }
            String defectNote = M4Values.optionalText(item.getDefectNote(), "items", 200);
            if (item.getScore() < item.getMaxScore() && defectNote == null) {
                throw new M4ValidationException("items", "存在缺陷项时必须填写缺陷说明");
            }
            if (defectNote != null && PHI_PATTERN.matcher(defectNote).find()) {
                throw new M4ValidationException("items",
                        "缺陷说明含疑似患者标识，请脱敏后重试");
            }
            ChartReviewItem copy = new ChartReviewItem();
            copy.setItemCode(item.getItemCode());
            copy.setItemName(item.getItemName() == null || item.getItemName().isBlank()
                    ? ITEM_NAMES.getOrDefault(code, code) : item.getItemName().trim());
            copy.setScore(item.getScore());
            copy.setMaxScore(item.getMaxScore());
            copy.setDefectNote(defectNote);
            normalized.add(copy);
        }
        return normalized;
    }

    private static String gradeOf(int totalScore, int maxScore) {
        if (maxScore <= 0) {
            return "D";
        }
        BigDecimal percent = BigDecimal.valueOf(totalScore)
                .multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(maxScore), 2, RoundingMode.HALF_UP);
        if (percent.compareTo(new BigDecimal("95")) >= 0) {
            return "A";
        }
        if (percent.compareTo(PASS_RATIO) >= 0) {
            return "B";
        }
        if (percent.compareTo(new BigDecimal("80")) >= 0) {
            return "C";
        }
        return "D";
    }

    private static void requireIndependentReviewer(String reviewerName, String reporterOrReviewer,
            String responsibleName) {
        if (Objects.equals(reviewerName, responsibleName)
                || Objects.equals(reviewerName, reporterOrReviewer)) {
            throw new M4UnprocessableException("reviewerName", "复核人不得为上报人或责任人");
        }
    }

    /**
     * 事件号/抽查号：{@code 前缀-yyyyMMdd-8 位十六进制}。日期取事件发生/抽查的本地日期，
     * 十六进制取随机 UUID 前 8 位（32 位空间 + 唯一约束兜底）。
     */
    private static String nextNo(String prefix, Instant instant) {
        LocalDate date = instant.atZone(ZONE).toLocalDate();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return prefix + "-" + date.toString().replace("-", "") + "-" + suffix;
    }

    private static String normalizeType(String type) {
        String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!"indicator".equals(value) && !"adverse".equals(value) && !"review".equals(value)) {
            throw new M4ValidationException("type", "type 必须为 indicator/adverse/review");
        }
        return value;
    }

    private static String normalizeFormat(String format) {
        String value = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(value)) {
            throw new M4ValidationException("format", "format 当前仅支持 csv");
        }
        return value;
    }

    // ---------------------------------------------------------------------
    // 审计
    // ---------------------------------------------------------------------

    private void audit(String action, String resourceType, String resourceId,
            AuthPrincipal caller, AuthRequestContext context, Map<String, Object> detail) {
        Map<String, Object> safeDetail =
                detail == null ? Map.of() : new LinkedHashMap<>(detail);
        auditWriter.record(action, resourceType, resourceId, true, safeDetail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
    }

    /**
     * 结果重算审计（评审 S-4）：查询与导出均写 {@code QC_INDICATOR_COMPUTE}，
     * 记录周期、指标条数、达标数与重算来源，detail 不含患者标识。
     */
    private void auditCompute(Window window, List<QcIndicatorResult> items, String source,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", window.from().toString());
        detail.put("to", window.to().toString());
        detail.put("indicatorCount", items.size());
        detail.put("targetMetCount", items.stream()
                .filter(item -> Boolean.TRUE.equals(item.getTargetMet())).count());
        detail.put("scoped", window.deptId() != null);
        detail.put("source", source);
        audit("QC_INDICATOR_COMPUTE", "QC_INDICATOR_RESULT", null, caller, context, detail);
    }

    private void auditStatus(AdverseEventEntity event, String toStatus, AuthPrincipal caller,
            AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("eventNo", event.getEventNo());
        detail.put("toStatus", toStatus);
        audit("ADVERSE_EVENT_STATUS", "ADVERSE_EVENT", event.getId().toString(), caller, context,
                detail);
    }

    private void auditReviewStatus(ChartReviewEntity review, String toStatus,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reviewNo", review.getReviewNo());
        detail.put("toStatus", toStatus);
        audit("CHART_REVIEW_STATUS", "CHART_REVIEW", review.getId().toString(), caller, context,
                detail);
    }

    // ---------------------------------------------------------------------
    // 内部类型
    // ---------------------------------------------------------------------

    /** 导出超上限（422）；事务对该异常不滚回，以保留 {@code QC_EXPORT} 失败审计。 */
    static final class ExportLimitExceededException extends M4UnprocessableException {

        ExportLimitExceededException(String field, String message) {
            super(field, message);
        }
    }

    /** 分子/分母计数。 */
    record Counts(int numerator, int denominator) {
    }

    /** 指标版本在查询周期内的有效统计区间（跨版本时按有效期切分）。 */
    record IndicatorSegment(QcIndicatorEntity definition, LocalDate from, LocalDate to) {
    }

    /** 统计区间（作为分段计数的分组键）。 */
    record Period(LocalDate from, LocalDate to) {
    }

    /** 周期 + 科室 + 数据范围（计算与导出共用）。 */
    record Window(LocalDate from, LocalDate to, UUID deptId, String deptName,
            QcVisibility visibility, Instant fromInstant, Instant toExclusive) {
    }

    /** 可选时间过滤区间（两端皆 null 表示不过滤）。 */
    record Range(Instant from, Instant toExclusive) {
    }
}
