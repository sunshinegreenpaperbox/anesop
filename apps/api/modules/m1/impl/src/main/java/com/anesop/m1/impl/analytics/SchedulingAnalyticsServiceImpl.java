package com.anesop.m1.impl.analytics;

import com.anesop.contracts.scheduling.model.CancellationReason;
import com.anesop.contracts.scheduling.model.CancellationReport;
import com.anesop.contracts.scheduling.model.CancellationRoom;
import com.anesop.contracts.scheduling.model.CancellationSummary;
import com.anesop.contracts.scheduling.model.OnTimeReport;
import com.anesop.contracts.scheduling.model.OnTimeRoom;
import com.anesop.contracts.scheduling.model.OnTimeSummary;
import com.anesop.contracts.scheduling.model.UtilizationReport;
import com.anesop.contracts.scheduling.model.UtilizationRoom;
import com.anesop.contracts.scheduling.model.UtilizationSummary;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m1.api.analytics.AnalyticsExportQuery;
import com.anesop.m1.api.analytics.AnalyticsQuery;
import com.anesop.m1.api.analytics.SchedulingAnalyticsService;
import com.anesop.m1.api.schedule.SchedulingValidationException;
import com.anesop.m1.impl.persistence.entity.OperatingRoomEntity;
import com.anesop.m1.impl.persistence.mapper.AnalyticsMapper;
import com.anesop.m1.impl.persistence.mapper.OperatingRoomMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * 排程分析只读服务实现（M2-M6-01，契约 scheduling.yaml 0.6.0）。
 *
 * <p>公式与时区：
 * <ul>
 *   <li>时间范围按 {@code Asia/Shanghai} 本地日期解释，闭区间，跨度 ≤ 92 天（默认 92，
 *       可配置但上限固定夹紧为 92）；</li>
 *   <li>利用率：非取消台次与区间重叠的计划分钟 /（术间数 × 天数 × 每间每日可用分钟）；</li>
 *   <li>准点率：每术间每本地日<strong>计划开始最早</strong>的台次；该台次取消/未开始（或
 *       缺实际开始时间）则该术间-日不纳入评估，计入 {@code excludedDays}；
 *       {@code |updated_at - start_at| ≤ 阈值}，MVP 以 updated_at 近似实际开台（【待确认】）；</li>
 *   <li>取消率：CANCELLED 台次 / 区间内总台次，按最近 STOP 原因码分组，无记录归 UNSPECIFIED。</li>
 * </ul>
 *
 * <p>数据范围 fail-closed：解析器拒绝/无可见术间 403；DEPT 看本科室术间、ROOM 看绑定术间。
 * 审计 {@code ANALYTICS_QUERY}/{@code ANALYTICS_EXPORT} 只含指标、日期、术间数、计数与命中数，
 * 不含患者姓名、住院号、诊断或申请标识。
 */
@Service
public class SchedulingAnalyticsServiceImpl implements SchedulingAnalyticsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String TIMEZONE = "Asia/Shanghai";
    private static final String RESOURCE_TYPE = "ANALYTICS";
    private static final String FORMULA_UTILIZATION = "总手术时长 / 可用时长";
    private static final String METRIC_UTILIZATION = "utilization";
    private static final String METRIC_ON_TIME = "on-time";
    private static final String METRIC_CANCELLATIONS = "cancellations";
    private static final Set<String> METRICS =
            Set.of(METRIC_UTILIZATION, METRIC_ON_TIME, METRIC_CANCELLATIONS);
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String UNSPECIFIED_REASON = "UNSPECIFIED";
    private static final int MAX_PAGE_ROOMS = 10_000;
    /** 查询跨度硬上限：配置可小于该值，超过则夹紧到 92 天并 WARN（契约上限 92）。 */
    public static final int MAX_RANGE_DAYS_LIMIT = 92;

    private static final Logger LOG =
            LoggerFactory.getLogger(SchedulingAnalyticsServiceImpl.class);

    private final AnalyticsMapper analyticsMapper;
    private final OperatingRoomMapper roomMapper;
    private final DataScopeResolver dataScopeResolver;
    private final AuditWriter auditWriter;
    private final int onTimeThresholdMinutes;
    private final int roomDailyAvailableMinutes;
    private final int configuredMaxRangeDays;
    private final int maxRangeDays;

    public SchedulingAnalyticsServiceImpl(AnalyticsMapper analyticsMapper,
            OperatingRoomMapper roomMapper,
            DataScopeResolver dataScopeResolver,
            AuditWriter auditWriter,
            @Value("${anesop.m1.analytics.on-time-threshold-minutes:15}") int onTimeThresholdMinutes,
            @Value("${anesop.m1.analytics.room-daily-available-minutes:600}")
            int roomDailyAvailableMinutes,
            @Value("${anesop.m1.analytics.max-range-days:92}") int maxRangeDays) {
        this.analyticsMapper = analyticsMapper;
        this.roomMapper = roomMapper;
        this.dataScopeResolver = dataScopeResolver;
        this.auditWriter = auditWriter;
        this.onTimeThresholdMinutes = onTimeThresholdMinutes;
        this.roomDailyAvailableMinutes = roomDailyAvailableMinutes;
        this.configuredMaxRangeDays = maxRangeDays;
        // S-2：配置可小于默认值，但永远不能突破契约上限 92 天。
        this.maxRangeDays = Math.min(maxRangeDays, MAX_RANGE_DAYS_LIMIT);
    }

    /**
     * 配置校验放在初始化阶段而非构造器：非法配置仍会拒绝启动（fail-closed），
     * 同时避免构造器抛出异常导致“部分初始化对象”的风险（SpotBugs CT_CONSTRUCTOR_THROW）。
     */
    @PostConstruct
    void validateConfiguration() {
        requireRange("on-time-threshold-minutes", onTimeThresholdMinutes, 0, 1440);
        requireRange("room-daily-available-minutes", roomDailyAvailableMinutes, 1, 1440);
        // 原始配置先做宽松合法性校验（1–366），再夹紧到契约上限 92 天并留 WARN 证据。
        requireRange("max-range-days", configuredMaxRangeDays, 1, 366);
        if (configuredMaxRangeDays > MAX_RANGE_DAYS_LIMIT) {
            LOG.warn("anesop.m1.analytics.max-range-days={} 超过上限 {}，已夹紧为 {} 天",
                    configuredMaxRangeDays, MAX_RANGE_DAYS_LIMIT, MAX_RANGE_DAYS_LIMIT);
        }
    }

    @Override
    public UtilizationReport utilization(AnalyticsQuery query, AuthPrincipal caller,
            AuthRequestContext context) {
        AnalyticsWindow window = window(query, caller);
        UtilizationReport report = buildUtilization(window);
        audit("ANALYTICS_QUERY", METRIC_UTILIZATION, window, caller, context,
                Map.of("caseCount", report.getSummary().getCaseCount(),
                        "roomCount", report.getRoomCount()));
        return report;
    }

    @Override
    public OnTimeReport onTime(AnalyticsQuery query, AuthPrincipal caller,
            AuthRequestContext context) {
        AnalyticsWindow window = window(query, caller);
        OnTimeReport report = buildOnTime(window);
        audit("ANALYTICS_QUERY", METRIC_ON_TIME, window, caller, context,
                Map.of("firstCaseCount", report.getSummary().getFirstCaseCount(),
                        "onTimeCount", report.getSummary().getOnTimeCount()));
        return report;
    }

    @Override
    public CancellationReport cancellations(AnalyticsQuery query, AuthPrincipal caller,
            AuthRequestContext context) {
        AnalyticsWindow window = window(query, caller);
        CancellationReport report = buildCancellations(window);
        audit("ANALYTICS_QUERY", METRIC_CANCELLATIONS, window, caller, context,
                Map.of("totalCases", report.getSummary().getTotalCases(),
                        "cancelledCases", report.getSummary().getCancelledCases()));
        return report;
    }

    @Override
    public ExportPlan prepareExport(AnalyticsExportQuery query, AuthPrincipal caller,
            AuthRequestContext context) {
        String metric = normalizeMetric(query.metric());
        String format = normalizeFormat(query.format());
        AnalyticsWindow window = window(query.range(), caller);
        CsvRows rows = switch (metric) {
            case METRIC_UTILIZATION -> csvUtilization(window, buildUtilization(window));
            case METRIC_ON_TIME -> csvOnTime(window, buildOnTime(window));
            default -> csvCancellations(window, buildCancellations(window));
        };
        String fileName = "scheduling-" + metric + "-" + window.from() + "-" + window.to() + "."
                + format;
        audit("ANALYTICS_EXPORT", metric, window, caller, context,
                Map.of("format", format, "dataRows", rows.rows().size()));
        return new ExportPlan(fileName, rows.rows().size(), output -> writeCsv(output, rows));
    }

    private UtilizationReport buildUtilization(AnalyticsWindow window) {
        List<UUID> roomIds = window.roomIds();
        List<RoomUsageRow> usageRows = roomIds.isEmpty()
                ? List.of() : analyticsMapper.selectRoomUsage(window.fromInstant(),
                        window.toExclusive(), roomIds);
        Map<UUID, RoomUsageRow> usageByRoom = new HashMap<>();
        for (RoomUsageRow row : usageRows) {
            usageByRoom.put(row.getRoomId(), row);
        }
        int perRoomAvailable = Math.multiplyExact(window.days(), roomDailyAvailableMinutes);
        int totalSurgeryMinutes = 0;
        int totalCases = 0;
        List<UtilizationRoom> rooms = new ArrayList<>();
        for (OperatingRoomEntity room : window.rooms()) {
            RoomUsageRow usage = usageByRoom.get(room.getId());
            int minutes = usage == null || usage.getTotalMinutes() == null
                    ? 0 : (int) Math.round(usage.getTotalMinutes());
            int cases = usage == null || usage.getCaseCount() == null ? 0 : usage.getCaseCount();
            totalSurgeryMinutes += minutes;
            totalCases += cases;
            UtilizationRoom item = new UtilizationRoom();
            item.setRoomId(room.getId().toString());
            item.setRoomNo(room.getRoomNo());
            item.setDeptId(room.getDeptId() == null ? null : room.getDeptId().toString());
            item.setDeptName(room.getDeptName());
            item.setTotalSurgeryMinutes(minutes);
            item.setAvailableMinutes(perRoomAvailable);
            item.setUtilizationRate(ratio(minutes, perRoomAvailable));
            item.setCaseCount(cases);
            rooms.add(item);
        }
        int availableMinutes = Math.multiplyExact(perRoomAvailable, rooms.size());
        UtilizationSummary summary = new UtilizationSummary();
        summary.setTotalSurgeryMinutes(totalSurgeryMinutes);
        summary.setAvailableMinutes(availableMinutes);
        summary.setUtilizationRate(ratio(totalSurgeryMinutes, availableMinutes));
        summary.setCaseCount(totalCases);
        summary.setRoomCount(rooms.size());
        summary.setDays(window.days());

        UtilizationReport report = new UtilizationReport();
        report.setFrom(window.from());
        report.setTo(window.to());
        report.setTimezone(TIMEZONE);
        report.setFormula(FORMULA_UTILIZATION);
        report.setAvailableMinutesPerRoomPerDay(roomDailyAvailableMinutes);
        report.setDays(window.days());
        report.setRoomCount(rooms.size());
        report.setSummary(summary);
        report.setRooms(rooms);
        return report;
    }

    private OnTimeReport buildOnTime(AnalyticsWindow window) {
        List<UUID> roomIds = window.roomIds();
        List<FirstCaseRow> rows = roomIds.isEmpty()
                ? List.of() : analyticsMapper.selectFirstCases(window.fromInstant(),
                        window.toExclusive(), roomIds);
        Map<UUID, List<FirstCaseRow>> rowsByRoom = rows.stream()
                .collect(Collectors.groupingBy(FirstCaseRow::getRoomId));
        int firstCaseCount = 0;
        int evaluated = 0;
        int onTime = 0;
        List<OnTimeRoom> rooms = new ArrayList<>();
        for (OperatingRoomEntity room : window.rooms()) {
            List<FirstCaseRow> roomRows = rowsByRoom.getOrDefault(room.getId(), List.of());
            int roomFirst = 0;
            int roomEvaluated = 0;
            int roomOnTime = 0;
            int roomExcluded = 0;
            for (FirstCaseRow row : roomRows) {
                // 每行是该术间-本地日“计划开始最早”的台次（不论状态）。
                roomFirst++;
                boolean started = STATUS_IN_PROGRESS.equals(row.getStatus())
                        || STATUS_COMPLETED.equals(row.getStatus());
                if (!started || row.getStartAt() == null || row.getUpdatedAt() == null) {
                    // 计划首台取消/未开始/缺实际开始 → 该日不纳入准点评估（分母排除）。
                    roomExcluded++;
                    continue;
                }
                long diffMillis = Math.abs(Duration.between(row.getStartAt(),
                        row.getUpdatedAt()).toMillis());
                roomEvaluated++;
                if (diffMillis <= onTimeThresholdMinutes * 60_000L) {
                    roomOnTime++;
                }
            }
            firstCaseCount += roomFirst;
            evaluated += roomEvaluated;
            onTime += roomOnTime;
            OnTimeRoom item = new OnTimeRoom();
            item.setRoomId(room.getId().toString());
            item.setRoomNo(room.getRoomNo());
            item.setDeptId(room.getDeptId() == null ? null : room.getDeptId().toString());
            item.setDeptName(room.getDeptName());
            item.setFirstCaseCount(roomFirst);
            item.setEvaluatedCaseCount(roomEvaluated);
            item.setOnTimeCount(roomOnTime);
            item.setDelayedCount(roomEvaluated - roomOnTime);
            item.setExcludedDays(roomExcluded);
            item.setOnTimeRate(ratio(roomOnTime, roomEvaluated));
            rooms.add(item);
        }
        OnTimeSummary summary = new OnTimeSummary();
        summary.setFirstCaseCount(firstCaseCount);
        summary.setEvaluatedCaseCount(evaluated);
        summary.setOnTimeCount(onTime);
        summary.setDelayedCount(evaluated - onTime);
        // firstCaseCount = evaluatedCaseCount + excludedDays（取消/未开始/缺实际开始的术间-日）。
        summary.setExcludedDays(firstCaseCount - evaluated);
        summary.setOnTimeRate(ratio(onTime, evaluated));

        OnTimeReport report = new OnTimeReport();
        report.setFrom(window.from());
        report.setTo(window.to());
        report.setTimezone(TIMEZONE);
        report.setThresholdMinutes(onTimeThresholdMinutes);
        report.setActualStartSource(OnTimeReport.ActualStartSourceEnum.SCHEDULE_UPDATED_AT);
        report.setSummary(summary);
        report.setRooms(rooms);
        return report;
    }

    private CancellationReport buildCancellations(AnalyticsWindow window) {
        List<UUID> roomIds = window.roomIds();
        List<CancellationRow> rows = roomIds.isEmpty()
                ? List.of() : analyticsMapper.selectCancellationRows(window.fromInstant(),
                        window.toExclusive(), roomIds);
        Map<UUID, List<CancellationRow>> rowsByRoom = rows.stream()
                .collect(Collectors.groupingBy(CancellationRow::getRoomId));
        int totalCases = rows.size();
        int cancelledCases = 0;
        Map<String, Integer> reasonCounts = new HashMap<>();
        for (CancellationRow row : rows) {
            if (STATUS_CANCELLED.equals(row.getStatus())) {
                cancelledCases++;
                reasonCounts.merge(normalizeReason(row.getReasonCode()), 1, Integer::sum);
            }
        }
        List<CancellationReason> byReason = new ArrayList<>();
        List<Map.Entry<String, Integer>> reasonEntries = new ArrayList<>(reasonCounts.entrySet());
        reasonEntries.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        for (Map.Entry<String, Integer> entry : reasonEntries) {
            CancellationReason item = new CancellationReason();
            item.setReasonCode(entry.getKey());
            item.setCancelledCases(entry.getValue());
            item.setShareOfCancelled(ratio(entry.getValue(), cancelledCases));
            byReason.add(item);
        }
        List<CancellationRoom> byRoom = new ArrayList<>();
        for (OperatingRoomEntity room : window.rooms()) {
            List<CancellationRow> roomRows = rowsByRoom.getOrDefault(room.getId(), List.of());
            int roomTotal = roomRows.size();
            int roomCancelled = (int) roomRows.stream()
                    .filter(row -> STATUS_CANCELLED.equals(row.getStatus())).count();
            CancellationRoom item = new CancellationRoom();
            item.setRoomId(room.getId().toString());
            item.setRoomNo(room.getRoomNo());
            item.setDeptId(room.getDeptId() == null ? null : room.getDeptId().toString());
            item.setDeptName(room.getDeptName());
            item.setTotalCases(roomTotal);
            item.setCancelledCases(roomCancelled);
            item.setCancellationRate(ratio(roomCancelled, roomTotal));
            byRoom.add(item);
        }
        CancellationSummary summary = new CancellationSummary();
        summary.setTotalCases(totalCases);
        summary.setCancelledCases(cancelledCases);
        summary.setCancellationRate(ratio(cancelledCases, totalCases));

        CancellationReport report = new CancellationReport();
        report.setFrom(window.from());
        report.setTo(window.to());
        report.setTimezone(TIMEZONE);
        report.setSummary(summary);
        report.setByReason(byReason);
        report.setByRoom(byRoom);
        return report;
    }

    /**
     * 解析并校验查询参数，同时解析数据范围与可见术间。
     *
     * <p>失败语义：日期/跨度/标识非法 → 400（{@link SchedulingValidationException}）；
     * 无数据范围/无可见术间 → 403（{@link AccessDeniedException}）。
     */
    private AnalyticsWindow window(AnalyticsQuery query, AuthPrincipal caller) {
        if (query == null) {
            throw new SchedulingValidationException("from", "查询参数不能为空");
        }
        LocalDate from = parseDate(query.from(), "from");
        LocalDate to = parseDate(query.to(), "to");
        if (to.isBefore(from)) {
            throw new SchedulingValidationException("to", "结束日期不能早于起始日期");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > maxRangeDays) {
            throw new SchedulingValidationException("to",
                    "查询跨度不能超过 " + maxRangeDays + " 天");
        }
        UUID deptId = parseOptionalUuid(query.deptId(), "deptId");
        DataScopeAssignments scope = resolveScope(caller);
        List<OperatingRoomEntity> rooms = visibleRooms(scope, deptId);
        Instant fromInstant = from.atStartOfDay(ZONE).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        List<UUID> roomIds = rooms.stream().map(OperatingRoomEntity::getId)
                .filter(Objects::nonNull).limit(MAX_PAGE_ROOMS).toList();
        return new AnalyticsWindow(from, to, fromInstant, toExclusive, (int) days, deptId,
                rooms, roomIds);
    }

    private DataScopeAssignments resolveScope(AuthPrincipal caller) {
        DataScopeAssignments scope = dataScopeResolver.resolve(caller == null ? null : caller.userId());
        if (scope == null || scope.isDenied()) {
            throw new AccessDeniedException("当前用户无数据范围");
        }
        return scope;
    }

    /**
     * 可见术间：DEPT/SITE 按术间所属 dept_id/site_id，ROOM 按 sys_user_room 绑定；
     * SELF 无术间对象范围，fail-closed 403；非 ALL 且无可见术间同样 403。
     */
    private List<OperatingRoomEntity> visibleRooms(DataScopeAssignments scope, UUID deptId) {
        if (scope.isSelfOnly()) {
            throw new AccessDeniedException("当前用户无可用术间数据范围");
        }
        QueryWrapper<OperatingRoomEntity> wrapper = new QueryWrapper<>();
        if (scope.requiresObjectFilter()) {
            List<UUID> roomIds = parseUuids(scope.allowedRoomIds());
            List<UUID> orgIds = parseUuids(scope.allowedOrgIds());
            if (roomIds.isEmpty() && orgIds.isEmpty()) {
                throw new AccessDeniedException("当前用户无可用术间数据范围");
            }
            wrapper.and(condition -> {
                if (!roomIds.isEmpty()) {
                    condition.in("id", roomIds);
                }
                if (!orgIds.isEmpty()) {
                    if (!roomIds.isEmpty()) {
                        condition.or();
                    }
                    condition.and(orgCondition -> orgCondition.in("dept_id", orgIds)
                            .or().in("site_id", orgIds));
                }
            });
        }
        if (deptId != null) {
            wrapper.eq("dept_id", deptId);
        }
        wrapper.orderByAsc("room_no");
        List<OperatingRoomEntity> rooms = roomMapper.selectList(wrapper);
        if (rooms == null || rooms.isEmpty()) {
            if (scope.requiresObjectFilter()) {
                throw new AccessDeniedException("当前用户无可用术间数据范围");
            }
            return List.of();
        }
        return rooms;
    }

    private void audit(String action, String metric, AnalyticsWindow window, AuthPrincipal caller,
            AuthRequestContext context, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("metric", metric);
        detail.put("from", window.from().toString());
        detail.put("to", window.to().toString());
        detail.put("timezone", TIMEZONE);
        if (window.deptId() != null) {
            detail.put("deptId", window.deptId().toString());
        }
        detail.put("roomCount", window.rooms().size());
        detail.putAll(extra);
        auditWriter.record(action, RESOURCE_TYPE, metric, true, detail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
    }

    private static String normalizeMetric(String metric) {
        String value = metric == null ? "" : metric.trim().toLowerCase(Locale.ROOT);
        if (!METRICS.contains(value)) {
            throw new SchedulingValidationException("metric",
                    "metric 必须为 utilization/on-time/cancellations");
        }
        return value;
    }

    private static String normalizeFormat(String format) {
        String value = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(value)) {
            throw new SchedulingValidationException("format", "format 当前仅支持 csv");
        }
        return value;
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new SchedulingValidationException(field, "日期不能为空");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new SchedulingValidationException(field, "日期格式必须为 YYYY-MM-DD");
        }
    }

    private static UUID parseOptionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new SchedulingValidationException(field, "科室标识格式不正确");
        }
    }

    private static List<UUID> parseUuids(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<UUID> parsed = new HashSet<>();
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

    private static String normalizeReason(String reasonCode) {
        return reasonCode == null || reasonCode.isBlank() ? UNSPECIFIED_REASON : reasonCode;
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf((double) numerator / denominator)
                .setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static int requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "anesop.m1.analytics." + name + " 必须在 " + min + "–" + max + " 之间");
        }
        return value;
    }

    // ---------------------------------------------------------------------
    // CSV：仅聚合值与术间/日期；UTF-8 BOM；不含患者姓名/住院号/证件号/诊断。
    // ---------------------------------------------------------------------

    private static final List<String> CSV_HEADER = List.of(
            "metric", "from", "to", "scope", "roomNo", "deptName", "reasonCode",
            "totalSurgeryMinutes", "availableMinutes", "utilizationRate", "caseCount",
            "firstCaseCount", "evaluatedCaseCount", "onTimeCount", "onTimeRate",
            "totalCases", "cancelledCases", "cancellationRate", "shareOfCancelled");

    private record CsvRows(List<List<String>> rows) {
    }

    private CsvRows csvUtilization(AnalyticsWindow window, UtilizationReport report) {
        List<List<String>> rows = new ArrayList<>();
        UtilizationSummary summary = report.getSummary();
        rows.add(row(METRIC_UTILIZATION, window.from(), window.to(), "SUMMARY", "ALL", "ALL", "",
                summary.getTotalSurgeryMinutes(), summary.getAvailableMinutes(),
                formatRate(summary.getUtilizationRate()), "", "", "", "", "", "", "", "", ""));
        for (UtilizationRoom room : report.getRooms()) {
            rows.add(row(METRIC_UTILIZATION, window.from(), window.to(), "ROOM",
                    room.getRoomNo(), room.getDeptName(), "",
                    room.getTotalSurgeryMinutes(), room.getAvailableMinutes(),
                    formatRate(room.getUtilizationRate()), room.getCaseCount(),
                    "", "", "", "", "", "", "", ""));
        }
        return new CsvRows(rows);
    }

    private CsvRows csvOnTime(AnalyticsWindow window, OnTimeReport report) {
        List<List<String>> rows = new ArrayList<>();
        OnTimeSummary summary = report.getSummary();
        rows.add(row(METRIC_ON_TIME, window.from(), window.to(), "SUMMARY", "ALL", "ALL", "",
                "", "", "", "", summary.getFirstCaseCount(), summary.getEvaluatedCaseCount(),
                summary.getOnTimeCount(), formatRate(summary.getOnTimeRate()), "", "", "", ""));
        for (OnTimeRoom room : report.getRooms()) {
            rows.add(row(METRIC_ON_TIME, window.from(), window.to(), "ROOM", room.getRoomNo(),
                    room.getDeptName(), "", "", "", "", "",
                    room.getFirstCaseCount(), room.getEvaluatedCaseCount(), room.getOnTimeCount(),
                    formatRate(room.getOnTimeRate()), "", "", "", ""));
        }
        return new CsvRows(rows);
    }

    private CsvRows csvCancellations(AnalyticsWindow window, CancellationReport report) {
        List<List<String>> rows = new ArrayList<>();
        CancellationSummary summary = report.getSummary();
        rows.add(row(METRIC_CANCELLATIONS, window.from(), window.to(), "SUMMARY", "ALL", "ALL", "",
                "", "", "", "", "", "", "", "", summary.getTotalCases(),
                summary.getCancelledCases(), formatRate(summary.getCancellationRate()), ""));
        for (CancellationRoom room : report.getByRoom()) {
            rows.add(row(METRIC_CANCELLATIONS, window.from(), window.to(), "ROOM",
                    room.getRoomNo(), room.getDeptName(), "", "", "", "", "",
                    "", "", "", "", room.getTotalCases(), room.getCancelledCases(),
                    formatRate(room.getCancellationRate()), ""));
        }
        for (CancellationReason reason : report.getByReason()) {
            rows.add(row(METRIC_CANCELLATIONS, window.from(), window.to(), "REASON", "ALL",
                    "ALL", reason.getReasonCode(), "", "", "", "", "", "", "", "", "",
                    reason.getCancelledCases(), "", formatRate(reason.getShareOfCancelled())));
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

    private static String formatRate(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static void writeCsv(OutputStream output, CsvRows rows) {
        try {
            output.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writer.write(String.join(",", CSV_HEADER));
            writer.newLine();
            for (List<String> row : rows.rows()) {
                writer.write(row.stream().map(SchedulingAnalyticsServiceImpl::csvCell)
                        .collect(Collectors.joining(",")));
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("排程分析 CSV 导出失败", e);
        }
    }

    /** Excel/Sheets 公式起始字符：命中后前置单引号，避免 CSV 被当作公式执行。 */
    private static final String FORMULA_PREFIXES = "=+-@\t\r";

    static boolean isFormulaRisk(String value) {
        return value != null && !value.isEmpty()
                && FORMULA_PREFIXES.indexOf(value.charAt(0)) >= 0;
    }

    private static String csvCell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        // B-1：公式注入防护——先前置单引号，再按 CSV 规则处理逗号/引号/换行。
        String cell = isFormulaRisk(value) ? "'" + value : value;
        boolean quote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0
                || cell.indexOf('\n') >= 0 || cell.indexOf('\r') >= 0;
        if (!quote) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    /** 解析后的查询窗口与可见术间。 */
    private record AnalyticsWindow(LocalDate from, LocalDate to, Instant fromInstant,
            Instant toExclusive, int days, UUID deptId, List<OperatingRoomEntity> rooms,
            List<UUID> roomIds) {
    }
}
