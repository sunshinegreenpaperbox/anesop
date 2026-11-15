package com.anesop.m4.impl.report;

import com.anesop.contracts.report.model.MultiReport;
import com.anesop.contracts.report.model.MultiRow;
import com.anesop.contracts.report.model.ReportLock;
import com.anesop.contracts.report.model.ReportLockList;
import com.anesop.contracts.report.model.ReportLockRequest;
import com.anesop.contracts.report.model.ReportTotals;
import com.anesop.contracts.report.model.SurgeryDistribution;
import com.anesop.contracts.report.model.WorkloadBucket;
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
import com.anesop.m4.api.report.ReportCaseFilter;
import com.anesop.m4.api.report.ReportCaseSource;
import com.anesop.m4.api.report.ReportDimension;
import com.anesop.m4.api.report.ReportExportQuery;
import com.anesop.m4.api.report.ReportLockService;
import com.anesop.m4.api.report.ReportMetric;
import com.anesop.m4.api.report.ReportQuery;
import com.anesop.m4.api.report.ReportService;
import com.anesop.m4.impl.config.ReportProperties;
import com.anesop.m4.impl.persistence.entity.ReportSnapshotEntity;
import com.anesop.m4.impl.persistence.mapper.ReportSnapshotMapper;
import com.anesop.m4.impl.support.M4Values;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工作量统计、多维报表与导出服务实现（M4-M16-01，契约 report.yaml 0.1.0）。
 *
 * <p>口径（report-1.0.0）：
 * <ul>
 *   <li>时间：{@code from}/{@code to} 按 {@code Asia/Shanghai} 本地日期解释，闭区间，
 *       跨度（含首尾）≤ 366 天，超过 400；</li>
 *   <li>台次：M1 台次中未删除、状态非 {@code CANCELLED} 的记录，按计划开始时间的本地日期归集；
 *       跨日台次只计首日（与排程分析 analytics-1.0.0 的归属一致）；</li>
 *   <li>麻醉时长：记录单 {@code started_at}..{@code ended_at} 之差向下取整到分钟之和；
 *       无记录单/未结束计 0，并单列 {@code unrecordedCaseCount}（数据质量）；
 *       时长以记录事件时间为准、是否扣除准备/复苏时间【待确认】；</li>
 *   <li>加权台次：Σ 台次权重，默认 1.0，可按术式编码配置；绩效权重/难度系数【待确认】，
 *       系统只出数、不内置绩效公式（D-21）；</li>
 *   <li>维度/指标白名单：非法值 400；未匹配维度进入固定兜底桶（{@code UNASSIGNED}/
 *       {@code UNSPECIFIED}），排序固定最后，保证各维度台次之和与总计守恒；</li>
 *   <li>空数据：计数与时长均为 0、数组为空、{@code total=0}/{@code totalPages=0}，不做除零。</li>
 * </ul>
 *
 * <p>数据范围 fail-closed：ALL 全院；SITE/DEPT 按申请科室（或院区）；ROOM 按术间；
 * SELF 按本人创建的麻醉记录单；无可用范围、SELF 缺用户标识或科室越界 403。
 * 查询与导出分别写审计 {@code REPORT_QUERY}/{@code REPORT_EXPORT}，detail 不含患者标识。
 */
@Service
public class ReportServiceImpl implements ReportService, ReportLockService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String TIMEZONE = "Asia/Shanghai";
    private static final String RESOURCE_TYPE = "REPORT";
    /** 查询跨度硬上限（契约固定 366 天，与方案书月度报表口径一致；不开放配置避免契约漂移）。 */
    public static final int MAX_RANGE_DAYS = 366;
    /** 导出数据行上限：超过则 400（避免一次导出拖垮应用与浏览器）。 */
    public static final int MAX_EXPORT_ROWS = 50_000;
    /** 单维度桶内术式分布最多返回条数（按台次倒序、编码升序）。 */
    public static final int MAX_SURGERY_DISTRIBUTION = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;
    private static final String REPORT_WORKLOAD = "workload";
    private static final String REPORT_MULTI = "multi";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final String SOURCE_LIVE = "LIVE";
    private static final String SOURCE_SNAPSHOT = "SNAPSHOT";
    private static final int MAX_LOCK_LIMIT = 200;
    private static final String LEVEL_TOTAL = "TOTAL";
    private static final String LEVEL_BUCKET = "BUCKET";
    private static final String LEVEL_SURGERY = "SURGERY";
    private static final String LEVEL_ROW = "ROW";
    private static final String TOTAL_KEY = "ALL";
    private static final String TOTAL_LABEL = "全部";
    private static final String FORMULA = "台次 = 统计边界内非取消台次（按计划开始时间归入 Asia/Shanghai "
            + "自然日，跨日只计首日）；麻醉时长 = Σ floor((ended_at - started_at) / 60s)，"
            + "无记录单或未结束计 0";
    private static final String WEIGHT_FORMULA = "加权台次 = Σ 台次权重（默认 1.0，可按术式编码配置；"
            + "绩效权重与难度系数由院方另定，系统只出数）";

    private final ReportCaseSource caseSource;
    private final DataScopeResolver dataScopeResolver;
    private final AuditWriter auditWriter;
    private final ReportProperties properties;
    private final ReportSnapshotMapper snapshotMapper;
    private final ReportSnapshotCodec codec;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的只读配置/服务/Mapper 由容器管理；服务只读权重配置，"
                    + "不修改其内部状态")
    public ReportServiceImpl(ReportCaseSource caseSource, DataScopeResolver dataScopeResolver,
            AuditWriter auditWriter, ReportProperties properties,
            ReportSnapshotMapper snapshotMapper, ReportSnapshotCodec codec) {
        this.caseSource = caseSource;
        this.dataScopeResolver = dataScopeResolver;
        this.auditWriter = auditWriter;
        this.properties = properties;
        this.snapshotMapper = snapshotMapper;
        this.codec = codec;
    }

    /**
     * 配置校验放在初始化阶段而非构造器：非法权重仍会拒绝启动（fail-closed），
     * 同时避免构造器抛异常导致“部分初始化对象”的风险（SpotBugs CT_CONSTRUCTOR_THROW）。
     */
    @PostConstruct
    void validateConfiguration() {
        requirePositiveWeight("default-weight", properties.getDefaultWeight());
        for (Map.Entry<String, BigDecimal> entry : properties.getSurgeryWeights().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("anesop.m4.report.surgery-weights 的术式编码不能为空");
            }
            requirePositiveWeight("surgery-weights." + entry.getKey().trim(), entry.getValue());
        }
    }

    @Override
    public WorkloadReport workload(ReportQuery query, AuthPrincipal caller,
            AuthRequestContext context) {
        if (query == null) {
            throw new M4ValidationException("from", "查询参数不能为空");
        }
        ReportDimension dimension = ReportDimension.parse(query.dimension());
        Window window = window(query.from(), query.to(), query.deptId(), caller);
        ReportSnapshotEntity snapshot = findSnapshot(REPORT_WORKLOAD,
                paramsHash(lockParams(REPORT_WORKLOAD, window, dimension.name(), null, null)));
        WorkloadReport report;
        if (snapshot != null) {
            report = codec.readWorkload(snapshot.getPayload());
            report.setSource(WorkloadReport.SourceEnum.SNAPSHOT);
            report.setLockId(snapshot.getId().toString());
            report.setLockedAt(snapshot.getLockedAt().atOffset(DISPLAY_OFFSET));
        } else {
            report = buildWorkload(window, loadCases(window), dimension);
            report.setSource(WorkloadReport.SourceEnum.LIVE);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("dimension", dimension.name());
        extra.put("bucketCount", report.getBuckets().size());
        extra.put("caseCount", report.getCaseCount());
        extra.put("source", report.getSource() == null ? SOURCE_LIVE : report.getSource().getValue());
        if (report.getLockId() != null) {
            extra.put("lockId", report.getLockId());
        }
        audit("REPORT_QUERY", REPORT_WORKLOAD, window, caller, context, extra);
        return report;
    }

    @Override
    public MultiReport multi(MultiReportQuery query, AuthPrincipal caller,
            AuthRequestContext context) {
        if (query == null) {
            throw new M4ValidationException("from", "查询参数不能为空");
        }
        List<ReportDimension> dimensions = parseDimensions(query.dimensions());
        List<ReportMetric> metrics = parseMetrics(query.metrics());
        int page = page(query.page());
        int size = size(query.size());
        Window window = window(query.from(), query.to(), query.deptId(), caller);
        ReportSnapshotEntity snapshot = findSnapshot(REPORT_MULTI, paramsHash(lockParams(
                REPORT_MULTI, window, null, dimensionCodes(dimensions), metricCodes(metrics))));
        MultiReport report;
        int groupCount;
        if (snapshot != null) {
            ReportSnapshotCodec.MultiSnapshot stored = codec.readMulti(snapshot.getPayload());
            report = toMultiReport(window, stored, page, size);
            report.setSource(MultiReport.SourceEnum.SNAPSHOT);
            report.setLockId(snapshot.getId().toString());
            report.setLockedAt(snapshot.getLockedAt().atOffset(DISPLAY_OFFSET));
            groupCount = stored.rows().size();
        } else {
            MultiAggregate aggregate = aggregateMulti(window, loadCases(window), dimensions);
            report = toMultiReport(window, dimensions, metrics, aggregate, page, size);
            report.setSource(MultiReport.SourceEnum.LIVE);
            groupCount = aggregate.totals().getGroupCount();
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("dimensions", dimensionCodes(dimensions));
        extra.put("metrics", metricCodes(metrics));
        extra.put("groupCount", groupCount);
        extra.put("caseCount", report.getTotals().getCaseCount());
        extra.put("source", report.getSource().getValue());
        if (report.getLockId() != null) {
            extra.put("lockId", report.getLockId());
        }
        audit("REPORT_QUERY", REPORT_MULTI, window, caller, context, extra);
        return report;
    }

    @Override
    public ExportPlan prepareExport(ReportExportQuery query, AuthPrincipal caller,
            AuthRequestContext context) {
        if (query == null) {
            throw new M4ValidationException("report", "查询参数不能为空");
        }
        String report = normalizeReport(query.report());
        String format = normalizeFormat(query.format());
        if (REPORT_WORKLOAD.equals(report)) {
            ReportDimension dimension = ReportDimension.parse(query.dimension());
            Window window = window(query.from(), query.to(), query.deptId(), caller);
            ReportSnapshotEntity snapshot = findSnapshot(REPORT_WORKLOAD,
                    paramsHash(lockParams(REPORT_WORKLOAD, window, dimension.name(), null, null)));
            WorkloadReport model;
            String source;
            if (snapshot != null) {
                // 锁定后导出默认读快照：源数据变化不影响已锁定值（手算对照不变）。
                model = codec.readWorkload(snapshot.getPayload());
                source = SOURCE_SNAPSHOT;
            } else {
                model = buildWorkload(window, loadCases(window), dimension);
                source = SOURCE_LIVE;
            }
            CsvRows rows = csvWorkload(model);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("dimension", dimension.name());
            extra.put("dataRows", rows.rows().size());
            extra.put("source", source);
            if (snapshot != null) {
                extra.put("lockId", snapshot.getId().toString());
            }
            audit("REPORT_EXPORT", REPORT_WORKLOAD, window, caller, context, extra);
            String fileName = "workload-" + dimension.name() + "-" + window.from() + "-"
                    + window.to() + "." + format;
            return new ExportPlan(fileName, rows.rows().size(),
                    output -> writeCsv(output, rows), source);
        }
        List<ReportDimension> dimensions = parseDimensions(query.dimensions());
        List<ReportMetric> metrics = parseMetrics(query.metrics());
        Window window = window(query.from(), query.to(), query.deptId(), caller);
        ReportSnapshotEntity snapshot = findSnapshot(REPORT_MULTI, paramsHash(lockParams(
                REPORT_MULTI, window, null, dimensionCodes(dimensions), metricCodes(metrics))));
        CsvRows rows;
        String source;
        int groupCount;
        String dimensionPart;
        if (snapshot != null) {
            ReportSnapshotCodec.MultiSnapshot stored = codec.readMulti(snapshot.getPayload());
            if (stored.rows().size() > MAX_EXPORT_ROWS) {
                throw new M4ValidationException("dimensions",
                        "导出分组数超过上限 " + MAX_EXPORT_ROWS + "，请缩小日期范围或增加维度");
            }
            List<ReportDimension> storedDimensions = parseDimensionCodes(stored.dimensions());
            MultiAggregate aggregate = new MultiAggregate(stored.rows(), stored.totals(),
                    stored.from().toString(), stored.to().toString());
            rows = csvMulti(storedDimensions, aggregate);
            source = SOURCE_SNAPSHOT;
            groupCount = stored.rows().size();
            dimensionPart = String.join("+", stored.dimensions());
        } else {
            MultiAggregate aggregate = aggregateMulti(window, loadCases(window), dimensions);
            if (aggregate.rows().size() > MAX_EXPORT_ROWS) {
                throw new M4ValidationException("dimensions",
                        "导出分组数超过上限 " + MAX_EXPORT_ROWS + "，请缩小日期范围或增加维度");
            }
            rows = csvMulti(dimensions, aggregate);
            source = SOURCE_LIVE;
            groupCount = aggregate.rows().size();
            dimensionPart = String.join("+", dimensionCodes(dimensions));
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("dimensions", dimensionCodes(dimensions));
        extra.put("metrics", metricCodes(metrics));
        extra.put("groupCount", groupCount);
        extra.put("dataRows", rows.rows().size());
        extra.put("source", source);
        if (snapshot != null) {
            extra.put("lockId", snapshot.getId().toString());
        }
        audit("REPORT_EXPORT", REPORT_MULTI, window, caller, context, extra);
        String fileName = "multi-" + dimensionPart + "-" + window.from() + "-"
                + window.to() + "." + format;
        return new ExportPlan(fileName, rows.rows().size(),
                output -> writeCsv(output, rows), source);
    }

    // ---------------------------------------------------------------------
    // 报表锁定（V28，report.yaml 0.2.0）
    // ---------------------------------------------------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportLock lock(ReportLockRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        String report = requireLockReport(request.getReport());
        Period period = resolvePeriod(request);
        String dimensionCode = null;
        List<ReportDimension> dimensions = null;
        List<ReportMetric> metrics = null;
        if (REPORT_WORKLOAD.equals(report)) {
            dimensionCode = ReportDimension.parse(request.getDimension() == null
                    ? null : request.getDimension().getValue()).name();
        } else {
            dimensions = parseDimensions(joinDimensionCodes(request.getDimensions()));
            metrics = parseMetrics(joinMetricCodes(request.getMetrics()));
        }
        Window window = window(period.from().toString(), period.to().toString(),
                request.getDeptId(), caller);
        Map<String, Object> params = lockParams(report, window, dimensionCode,
                dimensions == null ? null : dimensionCodes(dimensions),
                metrics == null ? null : metricCodes(metrics));
        String paramsHash = paramsHash(params);
        if (findSnapshot(report, paramsHash) != null) {
            throw duplicateLock(period);
        }

        String payload;
        int rowCount;
        if (REPORT_WORKLOAD.equals(report)) {
            WorkloadReport model = buildWorkload(window, loadCases(window),
                    ReportDimension.valueOf(dimensionCode));
            model.setSource(WorkloadReport.SourceEnum.LIVE);
            rowCount = model.getBuckets().size();
            payload = codec.writeWorkload(model, rowCount);
        } else {
            MultiAggregate aggregate = aggregateMulti(window, loadCases(window), dimensions);
            if (aggregate.rows().size() > MAX_EXPORT_ROWS) {
                throw new M4ValidationException("dimensions",
                        "锁定分组数超过上限 " + MAX_EXPORT_ROWS + "，请缩小日期范围或增加维度");
            }
            rowCount = aggregate.rows().size();
            payload = codec.writeMulti(new ReportSnapshotCodec.MultiSnapshot(window.from(),
                    window.to(), dimensionCodes(dimensions), metricCodes(metrics),
                    aggregate.rows(), aggregate.totals(), rowCount));
        }
        String payloadHash = codec.hash(payload);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ReportSnapshotEntity entity = new ReportSnapshotEntity();
        entity.setId(UUID.randomUUID());
        entity.setReport(report);
        entity.setPeriod(period.label());
        entity.setFromDate(window.from());
        entity.setToDate(window.to());
        entity.setParams(codec.canonical(params));
        entity.setParamsHash(paramsHash);
        entity.setPayload(payload);
        entity.setPayloadHash(payloadHash);
        entity.setDataScope(window.dataScopeSummary());
        entity.setScopeHash(codec.hash(window.scopeSignature()));
        entity.setRowCount(rowCount);
        entity.setLockedBy(caller == null ? null : caller.userId());
        entity.setLockedByName(lockerName(caller));
        entity.setLockedAt(now);
        try {
            snapshotMapper.insertSnapshot(entity);
        } catch (DuplicateKeyException duplicate) {
            throw duplicateLock(period);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "REPORT_LOCK");
        detail.put("lockId", entity.getId().toString());
        detail.put("report", report);
        detail.put("period", period.label());
        detail.put("from", window.from().toString());
        detail.put("to", window.to().toString());
        detail.put("paramsHash", paramsHash);
        detail.put("payloadHash", payloadHash);
        detail.put("rowCount", rowCount);
        detail.put("dataScope", window.dataScopeSummary());
        auditWriter.record("REPORT_LOCK", RESOURCE_TYPE, entity.getId().toString(), true, detail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
        return toLock(entity);
    }

    @Override
    public ReportLockList list(String report, int limit, AuthPrincipal caller) {
        if (limit < 1 || limit > MAX_LOCK_LIMIT) {
            throw new M4ValidationException("limit", "返回条数必须在 1–" + MAX_LOCK_LIMIT + " 之间");
        }
        String normalized = report == null || report.isBlank() ? null : normalizeReport(report);
        List<ReportSnapshotEntity> rows = snapshotMapper.selectLocks(normalized, limit);
        ReportLockList result = new ReportLockList();
        List<ReportLock> items = new ArrayList<>(rows.size());
        for (ReportSnapshotEntity row : rows) {
            items.add(toLock(row));
        }
        result.setItems(items);
        result.setTotal(items.size());
        return result;
    }

    @Override
    public ReportLock get(String lockId, AuthPrincipal caller) {
        UUID id = parseUuid(lockId);
        ReportSnapshotEntity entity = id == null ? null : snapshotMapper.selectById(id);
        if (entity == null) {
            throw new NoSuchElementException("报表锁定快照不存在或不可见");
        }
        return toLock(entity);
    }

    /** 锁定参数（固定键集合）：查询与锁定必须用同一构造方式，否则哈希不一致会读不到快照。 */
    private static Map<String, Object> lockParams(String report, Window window, String dimension,
            List<String> dimensions, List<String> metrics) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("report", report);
        params.put("from", window.from().toString());
        params.put("to", window.to().toString());
        params.put("deptId", window.deptId() == null ? null : window.deptId().toString());
        params.put("dimension", dimension);
        params.put("dimensions", dimensions);
        params.put("metrics", metrics);
        params.put("scope", window.scopeSignature());
        return params;
    }

    private String paramsHash(Map<String, Object> params) {
        return codec.hash(codec.canonical(params));
    }

    private ReportSnapshotEntity findSnapshot(String report, String paramsHash) {
        return snapshotMapper.selectByReportAndParamsHash(report, paramsHash);
    }

    private ReportLock toLock(ReportSnapshotEntity entity) {
        ReportLock lock = new ReportLock();
        lock.setLockId(entity.getId().toString());
        lock.setReport(ReportLock.ReportEnum.fromValue(entity.getReport()));
        lock.setPeriod(entity.getPeriod());
        lock.setFrom(entity.getFromDate());
        lock.setTo(entity.getToDate());
        lock.setParams(entity.getParams() == null ? Map.of() : codec.readParams(entity.getParams()));
        lock.setParamsHash(entity.getParamsHash());
        lock.setPayloadHash(entity.getPayloadHash());
        lock.setDataScope(entity.getDataScope());
        lock.setRowCount(entity.getRowCount());
        lock.setLockedBy(entity.getLockedBy() == null ? null : entity.getLockedBy().toString());
        lock.setLockedByName(entity.getLockedByName());
        lock.setLockedAt(entity.getLockedAt().atOffset(DISPLAY_OFFSET));
        return lock;
    }

    private static String requireLockReport(ReportLockRequest.ReportEnum report) {
        if (report == null) {
            throw new M4ValidationException("report", "report 必须为 workload 或 multi");
        }
        return normalizeReport(report.getValue());
    }

    private static String joinDimensionCodes(List<ReportLockRequest.DimensionsEnum> values) {
        if (values == null || values.isEmpty()) {
            throw new M4ValidationException("dimensions", "dimensions 不能为空");
        }
        return values.stream().map(ReportLockRequest.DimensionsEnum::getValue)
                .collect(Collectors.joining(","));
    }

    private static String joinMetricCodes(List<ReportLockRequest.MetricsEnum> values) {
        if (values == null || values.isEmpty()) {
            throw new M4ValidationException("metrics", "metrics 不能为空");
        }
        return values.stream().map(ReportLockRequest.MetricsEnum::getValue)
                .collect(Collectors.joining(","));
    }

    private static String lockerName(AuthPrincipal caller) {
        if (caller == null || caller.username() == null || caller.username().isBlank()) {
            return "UNKNOWN";
        }
        String name = caller.username().trim();
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    private static M4ConflictException duplicateLock(Period period) {
        return new M4ConflictException("该报表参数组合已锁定，请勿重复锁定",
                List.of(new M4ConflictException.Detail("period",
                        "同一 (report, period, params_hash) 已存在锁定快照："
                                + period.from() + "~" + period.to())));
    }

    /** 解析锁定期间：period（YYYY-MM 或 YYYY-MM-DD~YYYY-MM-DD）与 from+to 二选一。 */
    private static Period resolvePeriod(ReportLockRequest request) {
        String raw = request.getPeriod() == null ? null : request.getPeriod().trim();
        if (raw != null && raw.isEmpty()) {
            raw = null;
        }
        LocalDate requestedFrom = request.getFrom();
        LocalDate requestedTo = request.getTo();
        if (raw != null && (requestedFrom != null || requestedTo != null)) {
            throw new M4ValidationException("period", "period 与 from/to 只能二选一");
        }
        LocalDate start;
        LocalDate end;
        String label;
        if (raw != null) {
            if (raw.matches("\\d{4}-\\d{2}")) {
                int year;
                int month;
                try {
                    year = Integer.parseInt(raw.substring(0, 4));
                    month = Integer.parseInt(raw.substring(5, 7));
                } catch (NumberFormatException invalid) {
                    throw invalidPeriod();
                }
                if (month < 1 || month > 12) {
                    throw invalidPeriod();
                }
                start = LocalDate.of(year, month, 1);
                end = start.withDayOfMonth(start.lengthOfMonth());
                label = raw;
            } else if (raw.matches("\\d{4}-\\d{2}-\\d{2}~\\d{4}-\\d{2}-\\d{2}")) {
                String[] parts = raw.split("~", -1);
                start = parseDate(parts[0], "period");
                end = parseDate(parts[1], "period");
                label = start + "~" + end;
            } else {
                throw invalidPeriod();
            }
        } else {
            if (requestedFrom == null || requestedTo == null) {
                throw new M4ValidationException("period", "必须提供 period 或 from+to");
            }
            start = requestedFrom;
            end = requestedTo;
            label = start + "~" + end;
        }
        if (end.isBefore(start)) {
            throw new M4ValidationException("to", "结束日期不能早于起始日期");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new M4ValidationException("to", "查询跨度不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        return new Period(start, end, label);
    }

    private static M4ValidationException invalidPeriod() {
        return new M4ValidationException("period",
                "period 必须为 YYYY-MM 或 YYYY-MM-DD~YYYY-MM-DD");
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private record Period(LocalDate from, LocalDate to, String label) {
    }

    // ---------------------------------------------------------------------
    // 工作量
    // ---------------------------------------------------------------------

    private WorkloadReport buildWorkload(Window window, List<ReportCaseFact> cases,
            ReportDimension dimension) {
        Map<String, BucketAccumulator> buckets = new LinkedHashMap<>();
        Stat total = new Stat();
        for (ReportCaseFact fact : cases) {
            DimensionKey key = keyOf(dimension, fact);
            int minutes = minutes(fact);
            BigDecimal weight = weightOf(fact);
            boolean recorded = hasDuration(fact);
            buckets.computeIfAbsent(key.key(), ignored -> new BucketAccumulator(key))
                    .add(minutes, weight, recorded, fact.surgeryCode(), fact.surgeryName());
            total.add(minutes, weight, recorded);
        }
        List<BucketAccumulator> ordered = new ArrayList<>(buckets.values());
        ordered.sort(workloadOrder());

        List<WorkloadBucket> items = new ArrayList<>(ordered.size());
        for (BucketAccumulator bucket : ordered) {
            WorkloadBucket item = new WorkloadBucket();
            item.setKey(bucket.key.key());
            item.setLabel(bucketLabel(dimension, bucket.key));
            item.setCaseCount(bucket.stat.caseCount);
            item.setAnesthesiaMinutes((int) bucket.stat.anesthesiaMinutes);
            item.setWeightedCaseCount(scale(bucket.stat.weightedCaseCount));
            item.setUnrecordedCaseCount(bucket.stat.unrecordedCaseCount);
            item.setSurgeryDistribution(surgeryDistribution(bucket.surgeries));
            items.add(item);
        }

        WorkloadReport report = new WorkloadReport();
        report.setFrom(window.from());
        report.setTo(window.to());
        report.setTimezone(TIMEZONE);
        report.setDimension(WorkloadReport.DimensionEnum.valueOf(dimension.name()));
        report.setFormula(FORMULA);
        report.setWeightFormula(WEIGHT_FORMULA);
        report.setCaseCount(total.caseCount);
        report.setAnesthesiaMinutes((int) total.anesthesiaMinutes);
        report.setWeightedCaseCount(scale(total.weightedCaseCount));
        report.setUnrecordedCaseCount(total.unrecordedCaseCount);
        report.setBuckets(items);
        return report;
    }

    private static Comparator<BucketAccumulator> workloadOrder() {
        return Comparator.comparingInt((BucketAccumulator bucket) -> bucket.key.fallback() ? 1 : 0)
                .thenComparing(Comparator.comparingInt(
                        (BucketAccumulator bucket) -> bucket.stat.caseCount).reversed())
                .thenComparing(bucket -> bucket.key.key());
    }

    private static List<SurgeryDistribution> surgeryDistribution(
            Map<String, SurgeryAccumulator> surgeries) {
        List<SurgeryAccumulator> ordered = new ArrayList<>(surgeries.values());
        ordered.sort(Comparator.comparingInt((SurgeryAccumulator item) -> item.stat.caseCount)
                .reversed()
                .thenComparing(item -> item.code));
        List<SurgeryDistribution> items = new ArrayList<>();
        for (SurgeryAccumulator surgery : ordered) {
            if (items.size() >= MAX_SURGERY_DISTRIBUTION) {
                break;
            }
            SurgeryDistribution item = new SurgeryDistribution();
            item.setSurgeryCode(surgery.code);
            item.setSurgeryName(surgery.name == null ? surgery.code : surgery.name);
            item.setCaseCount(surgery.stat.caseCount);
            item.setAnesthesiaMinutes((int) surgery.stat.anesthesiaMinutes);
            items.add(item);
        }
        return items;
    }

    private static String bucketLabel(ReportDimension dimension, DimensionKey key) {
        if (key.fallback()) {
            return dimension.fallbackLabel();
        }
        return switch (dimension) {
            case DEPT, SURGERY, ROOM -> key.label() == null ? key.key() : key.label();
            case STAFF -> key.code() == null
                    ? (key.label() == null ? key.key() : key.label())
                    : key.code() + " " + (key.label() == null ? "" : key.label());
        };
    }

    // ---------------------------------------------------------------------
    // 多维报表
    // ---------------------------------------------------------------------

    private MultiAggregate aggregateMulti(Window window, List<ReportCaseFact> cases,
            List<ReportDimension> dimensions) {
        Map<List<String>, RowAccumulator> groups = new LinkedHashMap<>();
        Stat total = new Stat();
        for (ReportCaseFact fact : cases) {
            List<DimensionKey> keys = new ArrayList<>(dimensions.size());
            for (ReportDimension dimension : dimensions) {
                keys.add(keyOf(dimension, fact));
            }
            List<String> groupKey = keys.stream().map(DimensionKey::key).toList();
            int minutes = minutes(fact);
            BigDecimal weight = weightOf(fact);
            boolean recorded = hasDuration(fact);
            groups.computeIfAbsent(groupKey, ignored -> new RowAccumulator(keys))
                    .stat.add(minutes, weight, recorded);
            total.add(minutes, weight, recorded);
        }
        List<RowAccumulator> ordered = new ArrayList<>(groups.values());
        ordered.sort((left, right) -> compareKeys(left.keys, right.keys));

        List<MultiRow> rows = new ArrayList<>(ordered.size());
        for (RowAccumulator group : ordered) {
            rows.add(toMultiRow(group));
        }
        ReportTotals totals = totals(total, rows.size());
        return new MultiAggregate(rows, totals, window.from().toString(),
                window.to().toString());
    }

    private static int compareKeys(List<DimensionKey> left, List<DimensionKey> right) {
        for (int index = 0; index < left.size(); index++) {
            DimensionKey a = left.get(index);
            DimensionKey b = right.get(index);
            int fallback = Integer.compare(a.fallback() ? 1 : 0, b.fallback() ? 1 : 0);
            if (fallback != 0) {
                return fallback;
            }
            int byKey = a.key().compareTo(b.key());
            if (byKey != 0) {
                return byKey;
            }
        }
        return 0;
    }

    private static MultiRow toMultiRow(RowAccumulator group) {
        MultiRow row = new MultiRow();
        for (DimensionKey key : group.keys) {
            switch (key.dimension()) {
                case DEPT -> {
                    row.setDeptId(key.fallback() ? null : key.key());
                    row.setDeptName(key.fallback() ? null : key.label());
                }
                case STAFF -> {
                    row.setStaffId(key.fallback() ? null : key.key());
                    row.setStaffNo(key.fallback() ? null : key.code());
                    row.setStaffName(key.fallback() ? null : key.label());
                }
                case SURGERY -> {
                    row.setSurgeryCode(key.key());
                    // 兜底桶与工作量维度的中文兜底名保持一致（未登记术式），
                    // 避免多维报表把内部键 UNSPECIFIED 当作术式名展示。
                    row.setSurgeryName(key.fallback()
                            ? ReportDimension.SURGERY.fallbackLabel()
                            : (key.label() == null ? key.key() : key.label()));
                }
                case ROOM -> {
                    row.setRoomId(key.fallback() ? null : key.key());
                    row.setRoomNo(key.fallback() ? null : key.label());
                }
                default -> throw new IllegalStateException("未支持的报表维度：" + key.dimension());
            }
        }
        Stat stat = group.stat;
        row.setCaseCount(stat.caseCount);
        row.setAnesthesiaMinutes((int) stat.anesthesiaMinutes);
        row.setWeightedCaseCount(scale(stat.weightedCaseCount));
        row.setUnrecordedCaseCount(stat.unrecordedCaseCount);
        return row;
    }

    private static ReportTotals totals(Stat total, int groupCount) {
        ReportTotals totals = new ReportTotals();
        totals.setCaseCount(total.caseCount);
        totals.setAnesthesiaMinutes((int) total.anesthesiaMinutes);
        totals.setWeightedCaseCount(scale(total.weightedCaseCount));
        totals.setUnrecordedCaseCount(total.unrecordedCaseCount);
        totals.setGroupCount(groupCount);
        return totals;
    }

    private static MultiReport toMultiReport(Window window, List<ReportDimension> dimensions,
            List<ReportMetric> metrics, MultiAggregate aggregate, int page, int size) {
        List<MultiRow> all = aggregate.rows();
        int total = all.size();
        int totalPages = total == 0 ? 0 : (total + size - 1) / size;
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        MultiReport report = new MultiReport();
        report.setFrom(window.from());
        report.setTo(window.to());
        report.setTimezone(TIMEZONE);
        report.setDimensions(dimensionCodes(dimensions));
        report.setMetrics(metricCodes(metrics));
        report.setFormula(FORMULA);
        report.setWeightFormula(WEIGHT_FORMULA);
        report.setRows(new ArrayList<>(all.subList(fromIndex, toIndex)));
        report.setTotals(aggregate.totals());
        report.setPage(page);
        report.setSize(size);
        report.setTotal(total);
        report.setTotalPages(totalPages);
        return report;
    }

    /** 由锁定快照重建多维报表：分页重放在快照全量行上，合计取自快照。 */
    private static MultiReport toMultiReport(Window window,
            ReportSnapshotCodec.MultiSnapshot stored, int page, int size) {
        List<MultiRow> all = stored.rows() == null ? List.of() : stored.rows();
        int total = all.size();
        int totalPages = total == 0 ? 0 : (total + size - 1) / size;
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        MultiReport report = new MultiReport();
        report.setFrom(stored.from() == null ? window.from() : stored.from());
        report.setTo(stored.to() == null ? window.to() : stored.to());
        report.setTimezone(TIMEZONE);
        report.setDimensions(stored.dimensions() == null ? List.of() : stored.dimensions());
        report.setMetrics(stored.metrics() == null ? List.of() : stored.metrics());
        report.setFormula(FORMULA);
        report.setWeightFormula(WEIGHT_FORMULA);
        report.setRows(new ArrayList<>(all.subList(fromIndex, toIndex)));
        report.setTotals(stored.totals());
        report.setPage(page);
        report.setSize(size);
        report.setTotal(total);
        report.setTotalPages(totalPages);
        return report;
    }

    // ---------------------------------------------------------------------
    // 维度键、时长与权重
    // ---------------------------------------------------------------------

    /**
     * 台次 → 维度键：{@code key} 为标识/编码、{@code code} 为业务编码（人员工号、术间号）、
     * {@code label} 为展示名（人员维度为姓名）；未匹配进入固定兜底桶，保证台次之和守恒。
     */
    private static DimensionKey keyOf(ReportDimension dimension, ReportCaseFact fact) {
        return switch (dimension) {
            case DEPT -> blank(fact.deptId())
                    ? DimensionKey.fallback(ReportDimension.DEPT)
                    : new DimensionKey(ReportDimension.DEPT, fact.deptId(), null,
                            fact.deptName(), false);
            case STAFF -> blank(fact.staffId())
                    ? DimensionKey.fallback(ReportDimension.STAFF)
                    : new DimensionKey(ReportDimension.STAFF, fact.staffId(), fact.staffNo(),
                            fact.staffName(), false);
            case SURGERY -> blank(fact.surgeryCode())
                    ? DimensionKey.fallback(ReportDimension.SURGERY)
                    : new DimensionKey(ReportDimension.SURGERY, fact.surgeryCode().trim(),
                            fact.surgeryCode().trim(),
                            blank(fact.surgeryName()) ? null : fact.surgeryName(), false);
            case ROOM -> blank(fact.roomId())
                    ? DimensionKey.fallback(ReportDimension.ROOM)
                    : new DimensionKey(ReportDimension.ROOM, fact.roomId(), fact.roomNo(),
                            fact.roomNo(), false);
        };
    }

    /** 台次是否具备可用麻醉时长（有开始与结束且结束不早于开始）。 */
    static boolean hasDuration(ReportCaseFact fact) {
        return fact.startedAt() != null && fact.endedAt() != null
                && !fact.endedAt().isBefore(fact.startedAt());
    }

    /** 麻醉时长（分钟）：向下取整；无可用时长返回 0。 */
    static int minutes(ReportCaseFact fact) {
        if (!hasDuration(fact)) {
            return 0;
        }
        long seconds = Duration.between(fact.startedAt(), fact.endedAt()).getSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, seconds / 60);
    }

    private BigDecimal weightOf(ReportCaseFact fact) {
        BigDecimal fallback = properties.getDefaultWeight() == null
                ? BigDecimal.ONE : properties.getDefaultWeight();
        if (blank(fact.surgeryCode())) {
            return fallback;
        }
        BigDecimal configured = properties.getSurgeryWeights().get(fact.surgeryCode().trim());
        return configured == null ? fallback : configured;
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(4, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------------
    // 参数校验与数据范围
    // ---------------------------------------------------------------------

    /**
     * 解析并校验查询参数，解析数据范围并拉取统计单元。
     *
     * <p>失败语义：日期/跨度/标识/白名单非法 → 400（{@link M4ValidationException}）；
     * 无数据范围、SELF 缺用户标识、科室越界 → 403（{@link AccessDeniedException}）。
     */
    private Window window(String fromRaw, String toRaw, String deptIdRaw, AuthPrincipal caller) {
        LocalDate from = parseDate(fromRaw, "from");
        LocalDate to = parseDate(toRaw, "to");
        if (to.isBefore(from)) {
            throw new M4ValidationException("to", "结束日期不能早于起始日期");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new M4ValidationException("to", "查询跨度不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        UUID deptId = M4Values.optionalUuid(deptIdRaw, "deptId");
        DataScopeAssignments scope =
                dataScopeResolver.resolve(caller == null ? null : caller.userId());
        if (scope == null || scope.isDenied()) {
            throw new AccessDeniedException("当前用户无数据范围");
        }
        UUID selfUserId = caller == null ? null : caller.userId();
        boolean unrestricted = scope.isUnrestricted();
        boolean selfScoped = !unrestricted && hasSelf(scope);
        List<UUID> orgIds = unrestricted ? List.of() : parseUuids(scope.allowedOrgIds());
        List<UUID> roomIds = unrestricted ? List.of() : parseUuids(scope.allowedRoomIds());
        if (!unrestricted && orgIds.isEmpty() && roomIds.isEmpty() && !selfScoped) {
            throw new AccessDeniedException("当前用户无可用报表数据范围");
        }
        if (selfScoped && selfUserId == null) {
            throw new AccessDeniedException("当前用户无可用报表数据范围");
        }
        // deptId 只是收窄条件：只有“按组织过滤”的范围才校验越界，避免把 SELF/ROOM 的
        // 合法筛选误判为越权；反过来也绝不因 deptId 扩大可见范围。
        if (deptId != null && !unrestricted && !orgIds.isEmpty() && !orgIds.contains(deptId)) {
            throw new AccessDeniedException("科室不在当前用户数据范围内");
        }
        Instant fromInstant = from.atStartOfDay(ZONE).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        ReportCaseFilter filter = new ReportCaseFilter(fromInstant, toExclusive, deptId,
                unrestricted, orgIds, roomIds, selfUserId, selfScoped);
        return new Window(from, to, deptId, filter, scopeSignature(scope, caller),
                dataScopeSummary(scope));
    }

    /** 拉取统计单元（命中锁定快照时不调用，避免无谓查询与“快照后又变”的错觉）。 */
    private List<ReportCaseFact> loadCases(Window window) {
        List<ReportCaseFact> cases = caseSource.findCases(window.filter());
        return cases == null ? List.of() : cases;
    }

    /** 数据范围指纹：同一参数在不同范围下不共用快照（避免跨范围读数）。 */
    private static String scopeSignature(DataScopeAssignments scope, AuthPrincipal caller) {
        if (scope.isUnrestricted()) {
            return "ALL";
        }
        List<String> parts = new ArrayList<>();
        for (DataScopeAssignment assignment : scope.assignments()) {
            if (assignment == null || assignment.type() == null) {
                continue;
            }
            if (assignment.type() == DataScope.SELF) {
                parts.add("SELF:" + (caller == null || caller.userId() == null
                        ? "" : caller.userId()));
                continue;
            }
            List<String> ids = new ArrayList<>(assignment.scopeIds());
            ids.sort(Comparator.naturalOrder());
            parts.add(assignment.type().name() + ":" + String.join(",", ids));
        }
        parts.sort(Comparator.naturalOrder());
        return String.join(";", parts);
    }

    /** 数据范围摘要（存 report_snapshot.data_scope，≤32 字符）。 */
    private static String dataScopeSummary(DataScopeAssignments scope) {
        if (scope.isUnrestricted()) {
            return "ALL";
        }
        Set<String> types = new TreeSet<>();
        for (DataScopeAssignment assignment : scope.assignments()) {
            if (assignment != null && assignment.type() != null) {
                types.add(assignment.type().name());
            }
        }
        return types.isEmpty() ? "NONE" : String.join("+", types);
    }

    private static boolean hasSelf(DataScopeAssignments scope) {
        for (DataScopeAssignment assignment : scope.assignments()) {
            if (assignment != null && assignment.type() == DataScope.SELF) {
                return true;
            }
        }
        return false;
    }

    private static List<UUID> parseUuids(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<UUID> parsed = new LinkedHashSet<>();
        for (String value : values) {
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

    private static List<ReportDimension> parseDimensions(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new M4ValidationException("dimensions", "dimensions 不能为空");
        }
        Set<ReportDimension> dimensions = new LinkedHashSet<>();
        for (String token : raw.split(",", -1)) {
            dimensions.add(ReportDimension.parse(token, "dimensions"));
        }
        // 白名单全集只有 4 个维度，去重后不可能超过 4 个；不再保留不可达的数量上限分支。
        return List.copyOf(dimensions);
    }

    private static List<ReportMetric> parseMetrics(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new M4ValidationException("metrics", "metrics 不能为空");
        }
        Set<ReportMetric> metrics = new LinkedHashSet<>();
        for (String token : raw.split(",", -1)) {
            metrics.add(ReportMetric.parse(token));
        }
        // 白名单全集只有 3 个指标，去重后不可能超过 3 个；不再保留不可达的数量上限分支。
        return List.copyOf(metrics);
    }

    private static String normalizeReport(String report) {
        String value = report == null ? "" : report.trim().toLowerCase(Locale.ROOT);
        if (!REPORT_WORKLOAD.equals(value) && !REPORT_MULTI.equals(value)) {
            throw new M4ValidationException("report", "report 必须为 workload 或 multi");
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

    private static List<String> dimensionCodes(List<ReportDimension> dimensions) {
        return dimensions.stream().map(Enum::name).toList();
    }

    private static List<String> metricCodes(List<ReportMetric> metrics) {
        return metrics.stream().map(ReportMetric::code).toList();
    }

    /** 快照内维度编码 → 枚举（payload 由本服务写入，非法视为数据损坏 500）。 */
    private static List<ReportDimension> parseDimensionCodes(List<String> codes) {
        if (codes == null) {
            throw new IllegalStateException("报表快照维度编码缺失");
        }
        List<ReportDimension> dimensions = new ArrayList<>(codes.size());
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                throw new IllegalStateException("报表快照维度编码为空");
            }
            try {
                dimensions.add(ReportDimension.valueOf(code));
            } catch (IllegalArgumentException corrupt) {
                throw new IllegalStateException("报表快照维度编码损坏：" + code);
            }
        }
        return dimensions;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void requirePositiveWeight(String name, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "anesop.m4.report." + name + " 必须大于 0（当前：" + value + "）");
        }
    }

    private void audit(String action, String report, Window window, AuthPrincipal caller,
            AuthRequestContext context, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("report", report);
        detail.put("from", window.from().toString());
        detail.put("to", window.to().toString());
        detail.put("timezone", TIMEZONE);
        if (window.deptId() != null) {
            detail.put("deptId", window.deptId().toString());
        }
        detail.putAll(extra);
        auditWriter.record(action, RESOURCE_TYPE, report, true, detail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
    }

    // ---------------------------------------------------------------------
    // CSV：仅聚合值与维度标签；UTF-8 BOM；不含患者姓名/住院号/证件号/诊断/HIS 申请号。
    // ---------------------------------------------------------------------

    private static final List<String> CSV_HEADER = List.of(
            "report", "level", "dimension", "key", "label", "caseCount", "anesthesiaMinutes",
            "weightedCaseCount", "unrecordedCaseCount", "surgeryCode", "surgeryName", "from", "to",
            "timezone");

    /** Excel/Sheets 公式起始字符：命中后前置单引号，避免 CSV 被当作公式执行。 */
    private static final String FORMULA_PREFIXES = "=+-@\t\r";

    private record CsvRows(List<List<String>> rows) {
    }

    private static CsvRows csvWorkload(WorkloadReport report) {
        String from = report.getFrom().toString();
        String to = report.getTo().toString();
        String dimension = report.getDimension().getValue();
        List<List<String>> rows = new ArrayList<>();
        rows.add(row(REPORT_WORKLOAD, LEVEL_TOTAL, dimension, TOTAL_KEY, TOTAL_LABEL,
                report.getCaseCount(), report.getAnesthesiaMinutes(),
                plain(report.getWeightedCaseCount()), report.getUnrecordedCaseCount(), "", "",
                from, to, TIMEZONE));
        for (WorkloadBucket bucket : report.getBuckets()) {
            rows.add(row(REPORT_WORKLOAD, LEVEL_BUCKET, dimension, bucket.getKey(),
                    bucket.getLabel(), bucket.getCaseCount(), bucket.getAnesthesiaMinutes(),
                    plain(bucket.getWeightedCaseCount()), bucket.getUnrecordedCaseCount(), "", "",
                    from, to, TIMEZONE));
            // dimension=SURGERY 时桶本身即术式，分布行会完全重复，故不再输出。
            if ("SURGERY".equals(dimension)) {
                continue;
            }
            for (SurgeryDistribution surgery : bucket.getSurgeryDistribution()) {
                rows.add(row(REPORT_WORKLOAD, LEVEL_SURGERY, dimension, bucket.getKey(),
                        bucket.getLabel(), surgery.getCaseCount(), surgery.getAnesthesiaMinutes(),
                        "", "", surgery.getSurgeryCode(), surgery.getSurgeryName(), from, to,
                        TIMEZONE));
            }
        }
        return new CsvRows(rows);
    }

    private static CsvRows csvMulti(List<ReportDimension> dimensions, MultiAggregate aggregate) {
        String dimension = String.join("+", dimensionCodes(dimensions));
        List<List<String>> rows = new ArrayList<>();
        for (MultiRow item : aggregate.rows()) {
            List<String> keys = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (ReportDimension current : dimensions) {
                switch (current) {
                    case DEPT -> {
                        keys.add(item.getDeptId() == null ? ReportDimension.DEPT.fallbackKey()
                                : item.getDeptId());
                        labels.add(item.getDeptName() == null
                                ? ReportDimension.DEPT.fallbackLabel() : item.getDeptName());
                    }
                    case STAFF -> {
                        keys.add(item.getStaffNo() == null ? ReportDimension.STAFF.fallbackKey()
                                : item.getStaffNo());
                        labels.add(item.getStaffName() == null
                                ? ReportDimension.STAFF.fallbackLabel() : item.getStaffName());
                    }
                    case SURGERY -> {
                        keys.add(item.getSurgeryCode() == null
                                ? ReportDimension.SURGERY.fallbackKey() : item.getSurgeryCode());
                        labels.add(item.getSurgeryName() == null
                                ? ReportDimension.SURGERY.fallbackLabel() : item.getSurgeryName());
                    }
                    case ROOM -> {
                        keys.add(item.getRoomNo() == null ? ReportDimension.ROOM.fallbackKey()
                                : item.getRoomNo());
                        labels.add(item.getRoomNo() == null
                                ? ReportDimension.ROOM.fallbackLabel() : item.getRoomNo());
                    }
                    default -> throw new IllegalStateException("未支持的报表维度：" + current);
                }
            }
            rows.add(row(REPORT_MULTI, LEVEL_ROW, dimension, String.join("|", keys),
                    String.join(" | ", labels), item.getCaseCount(), item.getAnesthesiaMinutes(),
                    plain(item.getWeightedCaseCount()), item.getUnrecordedCaseCount(),
                    item.getSurgeryCode() == null ? "" : item.getSurgeryCode(),
                    item.getSurgeryName() == null ? "" : item.getSurgeryName(),
                    aggregate.from(), aggregate.to(), TIMEZONE));
        }
        return new CsvRows(rows);
    }

    private static List<String> row(Object... values) {
        List<String> cells = new ArrayList<>(values.length);
        for (Object value : values) {
            cells.add(value == null ? "" : value.toString());
        }
        return cells;
    }

    private static String plain(BigDecimal value) {
        BigDecimal normalized = (value == null ? BigDecimal.ZERO : value)
                .setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return normalized.scale() < 0 ? "0" : normalized.toPlainString();
    }

    private static void writeCsv(OutputStream output, CsvRows rows) {
        try {
            output.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writer.write(String.join(",", CSV_HEADER));
            writer.newLine();
            for (List<String> row : rows.rows()) {
                writer.write(row.stream().map(ReportServiceImpl::csvCell)
                        .collect(Collectors.joining(",")));
                writer.newLine();
            }
            writer.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("报表 CSV 导出失败", failure);
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
        // 公式注入防护：先前置单引号，再按 CSV 规则处理逗号/引号/换行。
        String cell = isFormulaRisk(value) ? "'" + value : value;
        boolean quote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0
                || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
        if (!quote) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    // ---------------------------------------------------------------------
    // 内部累加器
    // ---------------------------------------------------------------------

    /** 聚合计数：台次、麻醉时长、加权台次与无时长台次。 */
    private static final class Stat {

        private int caseCount;
        private long anesthesiaMinutes;
        private BigDecimal weightedCaseCount = BigDecimal.ZERO;
        private int unrecordedCaseCount;

        private void add(int minutes, BigDecimal weight, boolean recorded) {
            caseCount++;
            anesthesiaMinutes += Math.max(0, minutes);
            weightedCaseCount = weightedCaseCount.add(weight == null ? BigDecimal.ONE : weight);
            if (!recorded) {
                unrecordedCaseCount++;
            }
        }
    }

    /** 维度键：key 为标识/编码、code 为业务编码（工号/术间号）、label 为展示名。 */
    private record DimensionKey(ReportDimension dimension, String key, String code, String label,
            boolean fallback) {

        private static DimensionKey fallback(ReportDimension dimension) {
            return new DimensionKey(dimension, dimension.fallbackKey(), null, null, true);
        }
    }

    private static final class SurgeryAccumulator {

        private final String code;
        private final String name;
        private final Stat stat = new Stat();

        private SurgeryAccumulator(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    private static final class BucketAccumulator {

        private final DimensionKey key;
        private final Stat stat = new Stat();
        private final Map<String, SurgeryAccumulator> surgeries = new LinkedHashMap<>();

        private BucketAccumulator(DimensionKey key) {
            this.key = key;
        }

        private void add(int minutes, BigDecimal weight, boolean recorded, String surgeryCode,
                String surgeryName) {
            stat.add(minutes, weight, recorded);
            String code = blank(surgeryCode) ? ReportDimension.SURGERY.fallbackKey()
                    : surgeryCode.trim();
            SurgeryAccumulator surgery = surgeries.computeIfAbsent(code,
                    ignored -> new SurgeryAccumulator(code,
                            blank(surgeryName) ? null : surgeryName));
            surgery.stat.add(minutes, weight, recorded);
        }
    }

    private static final class RowAccumulator {

        private final List<DimensionKey> keys;
        private final Stat stat = new Stat();

        private RowAccumulator(List<DimensionKey> keys) {
            this.keys = List.copyOf(keys);
        }
    }

    private record MultiAggregate(List<MultiRow> rows, ReportTotals totals, String from, String to) {
    }

    private record Window(LocalDate from, LocalDate to, UUID deptId, ReportCaseFilter filter,
            String scopeSignature, String dataScopeSummary) {
    }
}
