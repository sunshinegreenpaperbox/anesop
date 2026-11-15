package com.anesop.m5.impl.dashboard;

import com.anesop.contracts.dashboard.model.AlertSummary;
import com.anesop.contracts.dashboard.model.AsaClass;
import com.anesop.contracts.dashboard.model.AsaClassCount;
import com.anesop.contracts.dashboard.model.CaseProgress;
import com.anesop.contracts.dashboard.model.CaseStatus;
import com.anesop.contracts.dashboard.model.CaseStatusCount;
import com.anesop.contracts.dashboard.model.DashboardKpi;
import com.anesop.contracts.dashboard.model.DataQuality;
import com.anesop.contracts.dashboard.model.DataScopeType;
import com.anesop.contracts.dashboard.model.DeviceSummary;
import com.anesop.contracts.dashboard.model.RealtimeDashboard;
import com.anesop.contracts.dashboard.model.ResourceDashboard;
import com.anesop.contracts.dashboard.model.ResourceGap;
import com.anesop.contracts.dashboard.model.ResourceGapCode;
import com.anesop.contracts.dashboard.model.ResourceGapSeverity;
import com.anesop.contracts.dashboard.model.RiskDashboard;
import com.anesop.contracts.dashboard.model.RiskLevel;
import com.anesop.contracts.dashboard.model.RiskLevelCount;
import com.anesop.contracts.dashboard.model.RoomSituation;
import com.anesop.contracts.dashboard.model.RoomStatus;
import com.anesop.contracts.dashboard.model.RoomStatusCounts;
import com.anesop.contracts.dashboard.model.RoomUtilization;
import com.anesop.contracts.dashboard.model.Shift;
import com.anesop.contracts.dashboard.model.StaffOnDuty;
import com.anesop.contracts.dashboard.model.StaffShiftCount;
import com.anesop.contracts.dashboard.model.TrendDashboard;
import com.anesop.contracts.dashboard.model.TrendPoint;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
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
import com.anesop.m5.api.dashboard.DashboardService;
import com.anesop.m5.api.dashboard.DashboardStaffShift;
import com.anesop.m5.api.dashboard.DashboardStaffSource;
import com.anesop.m5.api.dashboard.DashboardStaffStats;
import com.anesop.m5.api.error.M5ValidationException;
import com.anesop.m5.impl.config.DashboardProperties;
import com.anesop.m5.impl.support.DashboardAuditSampler;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * 实时态势与 KPI 看板实现（M5-M18-01/M5-M18-02，契约 dashboard.yaml 0.2.0，
 * 口径 dashboard-1.1.0）。
 *
 * <p>口径（与 ADR-0023 一致，上游复用 M2-M6-01 analytics-1.0.0 与 M4 report-1.0.0）：
 * <ul>
 *   <li>时间：所有“当日”按 {@code Asia/Shanghai} 自然日 {@code [00:00, 次日 00:00)}；
 *       实时态势的告警窗口为当日 {@code [00:00, now]}，KPI 为整日；</li>
 *   <li>台次：{@code deleted=false}；非取消台次要求 {@code status ≠ CANCELLED}；
 *       按计划开始时间 {@code start_at} 的本地日期归集，跨日只计首日；</li>
 *   <li>已开始/已完成：麻醉记录单 {@code started_at}/{@code ended_at} 非空，或台次状态
 *       {@code IN_PROGRESS}/{@code COMPLETED}（只看非取消台次）；</li>
 *   <li>准点率：每术间当日计划开始最早台次中，{|实际开始 − 计划开始| ≤ 阈值} 的比例；
 *       首台取消/未开始/缺实际开始不计入分母；实际开始优先取记录单 {@code started_at}，
 *       缺失回退台次 {@code updated_at}（【待确认】）；</li>
 *   <li>取消率：取消台次 / 当日总台次（分母含取消）；</li>
 *   <li>利用率：非取消台次与当日窗口重叠的分钟 / (可见术间数 × 每术间每日可用分钟)
 *       （计划口径，【待确认】）；</li>
 *   <li>平均麻醉时长：Σ floor((ended_at − started_at)/60s) / 有可用时长台次数，2 位小数；</li>
 *   <li>比率一律 0–1 小数（4 位小数），分母为 0 返回 0，不做除零。</li>
 * </ul>
 *
 * <p>0.2.0 新增（M5-M18-02）：风险分层（ASA/术前评分风险等级/未确认严重告警/超时台次）、
 * 资源态势（术间利用率与状态分布、设备在线率、当班人员、确定性缺口提示）与按小时趋势
 * （告警/台次/利用率）。三者仍为只读聚合，**只返回计数与比率，不含患者标识**。
 *
 * <p>数据范围 fail-closed（与 M4 报表一致）：ALL 全院；SITE/DEPT 按术间院区/科室（台次另按
 * 申请科室/院区）；ROOM 按术间；SELF 按本人创建麻醉记录单的当日台次所涉术间（人员排班按
 * 所涉科室，【待确认】）。无可用范围、SELF 缺用户标识 → 403，绝不退化为全院查询。
 *
 * <p>审计：{@code DASHBOARD_VIEW} 按用户采样（默认 300s 一次），detail 仅看板类型/日期/
 * 数据范围/计数/延迟，不含患者标识与告警处置原因。
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String TIMEZONE = "Asia/Shanghai";
    private static final String ACTION = "DASHBOARD_VIEW";
    private static final String RESOURCE_TYPE = "DASHBOARD";
    private static final String KIND_REALTIME = "realtime";
    private static final String KIND_KPI = "kpi";
    private static final String KIND_RISK = "risk";
    private static final String KIND_RESOURCES = "resources";
    private static final String KIND_TRENDS = "trends";

    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";

    /** 台次状态展示顺序（只输出计数 &gt; 0 的状态）。 */
    private static final List<String> CASE_STATUS_ORDER = List.of(
            "PLANNED", "PUBLISHED", "IN_PROGRESS", "COMPLETED", "CANCELLED", "DELAYED");

    /** KPI 允许回看的最大天数（与 M4 报表的 366 天上限一致）。 */
    public static final int MAX_PAST_DAYS = 366;

    /** KPI 允许预看的天数（未来多为计划台次，避免把远期排程当作当日态势）。 */
    public static final int MAX_FUTURE_DAYS = 31;

    /** 趋势窗口下限（分钟）：契约固定 30 分钟（0.5 小时）。 */
    public static final int MIN_TREND_WINDOW_MINUTES = 30;

    /** 趋势窗口上限（分钟）：契约固定 1440 分钟（24 小时）。 */
    public static final int MAX_TREND_WINDOW_MINUTES = 1440;

    /** 趋势桶长度（分钟）：契约固定整小时分桶。 */
    public static final int TREND_BUCKET_MINUTES = 60;

    /** 趋势窗口缺省值（分钟）= 24 小时。 */
    public static final int DEFAULT_TREND_WINDOW_MINUTES = 1440;

    /** 数据时效阈值的硬上限（方案书 §2.4.9：延迟超过 2 分钟须标注）。 */
    private static final int MAX_STALE_THRESHOLD_SECONDS = 120;

    private static final String FORMULA = "台次 = 当日非取消台次；准点率 = 准点首台 / 已评估首台；"
            + "取消率 = 取消台次 / 当日总台次；利用率 = 当日重叠手术分钟 / (术间数 × 每日可用分钟)；"
            + "平均麻醉时长 = Σ floor((ended_at − started_at)/60s) / 有记录台次";

    /** 尚未获院方确认的口径项（随 KPI 返回，前端展示“口径待确认”标识）。 */
    private static final List<String> PENDING_CONFIRMATIONS = List.of(
            "准点率的“实际开始时间”优先取麻醉记录单 started_at，缺失回退台次 updated_at【待确认】",
            "利用率为“计划手术时长 / 可用时长”，是否改为实际占用【待确认】",
            "停用术间语义：M1 无独立停用状态，暂以软删除表示【待确认】",
            "SELF 数据范围按“本人台次所涉术间”裁剪是否满足麻醉医师个人看板需求【待确认】");

    /** ASA 分级展示顺序（只输出计数 &gt; 0 的分级）。 */
    private static final List<String> ASA_ORDER = List.of("I", "II", "III", "IV", "V", "VI");

    /** 术前评分风险等级展示顺序（只输出计数 &gt; 0 的等级）。 */
    private static final List<String> RISK_ORDER = List.of("LOW", "MODERATE", "HIGH");

    /** 班次展示顺序（只输出人数 &gt; 0 的班次）。 */
    private static final List<String> SHIFT_ORDER = List.of("DAY", "NIGHT", "ON_CALL");

    /** 高危 ASA 分级：III 及以上（与 M2 术前评估的 ASA 语义一致）。 */
    private static final Set<String> HIGH_ASA = Set.of("III", "IV", "V", "VI");

    /** 高危术前评分风险等级。 */
    private static final String HIGH_RISK_LEVEL = "HIGH";

    private static final String RISK_FORMULA = "风险分层 = 当日非取消台次按 ASA 分级与术前评估风险等级计数；"
            + "高危 = ASA ≥ III 或风险等级 HIGH；"
            + "超时 = 计划开始/结束超过宽限仍未开始/未完成（并集去重）";

    private static final List<String> RISK_PENDING_CONFIRMATIONS = List.of(
            "超时台次的宽限分钟数与“延迟开始/超时结束”是否分开考核【待确认】",
            "高危台次定义为 ASA ≥ III 或风险等级 HIGH，是否再加未确认严重告警【待确认】",
            "术前评分风险等级取 M2 评估单最新最终化版本，草稿是否参与分层【待确认】");

    private static final String RESOURCE_FORMULA = "利用率 = 当日重叠手术分钟 / (可见术间数 × 每术间每日可用分钟)；"
            + "设备在线率 = 在线 / 设备总数；"
            + "当班人数 = 当日 PLANNED 排班人数（按人员去重，按班次分组）";

    private static final List<String> RESOURCE_PENDING_CONFIRMATIONS = List.of(
            "利用率为“计划手术时长 / 可用时长”，是否改为实际占用【待确认】",
            "备班（ON_CALL）是否计入“当班人员”【待确认】",
            "人员排班无用户标识，SELF 范围按“本人台次所涉科室”裁剪【待确认】",
            "资源缺口规则（设备离线/停用术间/排程超载）的阈值与是否分科室展示【待确认】");

    private static final String TREND_FORMULA = "告警 = 桶内触发告警数；"
            + "台次 = 桶内计划开始的非取消台次；"
            + "利用率 = 桶内重叠手术分钟 / (可见术间数 × 桶与窗口交集分钟)";

    private static final List<String> TREND_PENDING_CONFIRMATIONS = List.of(
            "利用率为“计划手术时长 / 可用时长”，是否改为实际占用【待确认】",
            "台次趋势按“计划开始”归桶，是否需要同时提供“实际开始”口径【待确认】");

    private final DashboardCaseSource caseSource;
    private final DashboardRoomSource roomSource;
    private final DashboardAlertSource alertSource;
    private final DashboardDeviceSource deviceSource;
    private final DashboardRiskSource riskSource;
    private final DashboardStaffSource staffSource;
    private final DataScopeResolver dataScopeResolver;
    private final AuditWriter auditWriter;
    private final DashboardProperties properties;
    private final DashboardAuditSampler auditSampler;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的只读端口/时钟/配置由容器管理；服务不修改其内部状态")
    public DashboardServiceImpl(DashboardCaseSource caseSource,
            DashboardRoomSource roomSource,
            DashboardAlertSource alertSource,
            DashboardDeviceSource deviceSource,
            DashboardRiskSource riskSource,
            DashboardStaffSource staffSource,
            DataScopeResolver dataScopeResolver,
            AuditWriter auditWriter,
            DashboardProperties properties,
            DashboardAuditSampler auditSampler,
            Clock clock) {
        this.caseSource = caseSource;
        this.roomSource = roomSource;
        this.alertSource = alertSource;
        this.deviceSource = deviceSource;
        this.riskSource = riskSource;
        this.staffSource = staffSource;
        this.dataScopeResolver = dataScopeResolver;
        this.auditWriter = auditWriter;
        this.properties = properties;
        this.auditSampler = auditSampler;
        this.clock = clock;
    }

    /**
     * 口径阈值校验（fail-closed，启动即失败）：设备离线阈值、准点阈值、每日可用分钟与
     * 审计采样间隔必须落在可解释区间；数据时效阈值上限为契约固定的 120s
     * （方案书 §2.4.9“延迟超过 2 分钟须标注”），配置超过上限即拒绝启动，不允许放宽。
     */
    @PostConstruct
    void validateConfiguration() {
        requireRange("device-offline-timeout-seconds",
                properties.getDeviceOfflineTimeoutSeconds(), 1, 3600);
        requireRange("on-time-threshold-minutes", properties.getOnTimeThresholdMinutes(), 0, 1440);
        requireRange("room-daily-available-minutes",
                properties.getRoomDailyAvailableMinutes(), 1, 1440);
        requireRange("overdue-grace-minutes", properties.getOverdueGraceMinutes(), 0, 120);
        if (properties.getAuditSampleSeconds() < 0) {
            throw new IllegalArgumentException("anesop.m5.dashboard.audit-sample-seconds 不能为负");
        }
        if (properties.getStaleThresholdSeconds() <= 0) {
            throw new IllegalArgumentException("anesop.m5.dashboard.stale-threshold-seconds 必须为正");
        }
        if (properties.getStaleThresholdSeconds() > MAX_STALE_THRESHOLD_SECONDS) {
            throw new IllegalArgumentException("anesop.m5.dashboard.stale-threshold-seconds 不能超过契约上限 "
                    + MAX_STALE_THRESHOLD_SECONDS + "s（延迟超过 2 分钟须标注）");
        }
    }

    // ---------------------------------------------------------------------
    // 实时态势
    // ---------------------------------------------------------------------

    @Override
    public RealtimeDashboard realtime(AuthPrincipal caller, AuthRequestContext context) {
        Instant now = clock.instant();
        DayWindow window = dayWindow(now.atZone(ZONE).toLocalDate());
        ScopeView view = resolveScope(caller);
        List<DashboardRoomFact> rooms = safeRooms(window, view);
        List<DashboardCaseFact> cases = safeCases(window, view);
        Instant alertEnd = now.isBefore(window.toExclusive()) ? now : window.toExclusive();
        DashboardAlertStats alerts = alertSource.countAlerts(window.from(), alertEnd, view.scope());
        DashboardDeviceStats devices = deviceSource.countDevices(now, window.from(),
                window.toExclusive(), view.scope(), properties.getDeviceOfflineTimeoutSeconds());

        RealtimeDashboard model = new RealtimeDashboard();
        model.setUpdatedAt(OffsetDateTime.ofInstant(now, ZONE));
        model.setTimezone(TIMEZONE);
        model.setDataScope(view.type());
        model.setRooms(roomSituations(rooms, cases));
        model.setRoomStatusCounts(roomStatusCounts(rooms));
        model.setCaseProgress(caseProgress(cases));
        model.setAlerts(alertSummary(alerts));
        DeviceSummary deviceSummary = new DeviceSummary();
        deviceSummary.setTotal(devices.total());
        deviceSummary.setOnline(devices.online());
        deviceSummary.setOffline(devices.offline());
        deviceSummary.setMaintenance(devices.maintenance());
        deviceSummary.setOfflineTimeoutSeconds(properties.getDeviceOfflineTimeoutSeconds());
        model.setDevices(deviceSummary);

        Instant latest = latestOf(cases, alerts, devices);
        int delaySeconds = delaySeconds(now, latest);
        model.setDataDelaySeconds(delaySeconds);
        model.setDataQuality(dataQuality(latest, delaySeconds));

        audit(KIND_REALTIME, null, view, model.getRooms().size(), cases.size(),
                alerts.triggered(), delaySeconds, caller, context);
        return model;
    }

    // ---------------------------------------------------------------------
    // 当日 KPI
    // ---------------------------------------------------------------------

    @Override
    public DashboardKpi kpi(String date, AuthPrincipal caller, AuthRequestContext context) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(ZONE).toLocalDate();
        LocalDate day = parseDate(date, today);
        DayWindow window = dayWindow(day);
        ScopeView view = resolveScope(caller);
        List<DashboardRoomFact> rooms = safeRooms(window, view);
        List<DashboardCaseFact> cases = safeCases(window, view);
        DashboardAlertStats alerts = alertSource.countAlerts(window.from(), window.toExclusive(),
                view.scope());
        // 设备状态不参与 KPI 数值，但其 last_seen_at 参与“数据延迟”计算（契约统一时效口径）。
        DashboardDeviceStats devices = deviceSource.countDevices(now, window.from(),
                window.toExclusive(), view.scope(), properties.getDeviceOfflineTimeoutSeconds());

        int totalCaseCount = cases.size();
        int cancelledCount = 0;
        int startedCount = 0;
        int completedCount = 0;
        int recordedCount = 0;
        long anesthesiaMinutes = 0L;
        long surgerySeconds = 0L;
        for (DashboardCaseFact fact : cases) {
            if (STATUS_CANCELLED.equals(fact.status())) {
                cancelledCount++;
                continue;
            }
            if (started(fact)) {
                startedCount++;
            }
            if (completed(fact)) {
                completedCount++;
            }
            if (hasDuration(fact)) {
                recordedCount++;
                anesthesiaMinutes += minutes(fact);
            }
            surgerySeconds += overlapSeconds(fact, window);
        }
        int caseCount = totalCaseCount - cancelledCount;

        OnTime onTime = onTime(cases);
        int roomCount = (int) rooms.stream().filter(room -> !room.deleted()).count();
        long availableMinutes = (long) roomCount * properties.getRoomDailyAvailableMinutes();
        int totalSurgeryMinutes = (int) Math.min(Integer.MAX_VALUE, surgerySeconds / 60);

        DashboardKpi model = new DashboardKpi();
        model.setDate(day);
        model.setTimezone(TIMEZONE);
        model.setUpdatedAt(OffsetDateTime.ofInstant(now, ZONE));
        model.setDataScope(view.type());
        model.setCaseCount(caseCount);
        model.setTotalCaseCount(totalCaseCount);
        model.setStartedCount(startedCount);
        model.setCompletedCount(completedCount);
        model.setCancelledCount(cancelledCount);
        model.setOnTimeEvaluatedCount(onTime.evaluated());
        model.setOnTimeCount(onTime.onTime());
        model.setOnTimeRate(ratio(onTime.onTime(), onTime.evaluated()));
        model.setOnTimeThresholdMinutes(properties.getOnTimeThresholdMinutes());
        model.setCancellationRate(ratio(cancelledCount, totalCaseCount));
        model.setUtilizationRate(ratio(totalSurgeryMinutes, availableMinutes));
        model.setTotalSurgeryMinutes(totalSurgeryMinutes);
        model.setAvailableMinutes((int) Math.min(Integer.MAX_VALUE, availableMinutes));
        model.setRoomCount(roomCount);
        model.setAlertCount(alerts.triggered());
        model.setCriticalAlertCount(alerts.critical());
        model.setAverageAnesthesiaMinutes(average(anesthesiaMinutes, recordedCount));
        model.setRecordedCaseCount(recordedCount);
        model.setUnrecordedCaseCount(caseCount - recordedCount);
        model.setFormula(FORMULA);
        model.setPendingConfirmations(new ArrayList<>(PENDING_CONFIRMATIONS));

        Instant latest = latestOf(cases, alerts, devices);
        int delaySeconds = delaySeconds(now, latest);
        model.setDataDelaySeconds(delaySeconds);
        model.setDataQuality(dataQuality(latest, delaySeconds));

        audit(KIND_KPI, day.toString(), view, roomCount, caseCount, alerts.triggered(),
                delaySeconds, caller, context);
        return model;
    }

    // ---------------------------------------------------------------------
    // 风险分层（M5-M18-02，契约 0.2.0）
    // ---------------------------------------------------------------------

    @Override
    public RiskDashboard risk(AuthPrincipal caller, AuthRequestContext context) {
        Instant now = clock.instant();
        LocalDate day = now.atZone(ZONE).toLocalDate();
        DayWindow window = dayWindow(day);
        ScopeView view = resolveScope(caller);
        List<DashboardCaseFact> cases = safeCases(window, view);
        Instant alertEnd = now.isBefore(window.toExclusive()) ? now : window.toExclusive();
        DashboardAlertStats alerts = alertSource.countAlerts(window.from(), alertEnd, view.scope());
        List<DashboardRiskBucket> buckets = safeRiskBuckets(window, view);

        int totalCaseCount = 0;
        for (DashboardCaseFact fact : cases) {
            if (!STATUS_CANCELLED.equals(fact.status())) {
                totalCaseCount++;
            }
        }
        Map<String, Integer> asaCounts = new LinkedHashMap<>();
        Map<String, Integer> riskCounts = new LinkedHashMap<>();
        int assessed = 0;
        int highRisk = 0;
        for (DashboardRiskBucket bucket : buckets) {
            if (bucket == null || bucket.count() <= 0) {
                continue;
            }
            // 两个维度同时为空 = 未评估桶（未访视/仅草稿）：不进入分布，也不计入已评估。
            if (bucket.asaClass() == null && bucket.riskLevel() == null) {
                continue;
            }
            if (bucket.asaClass() != null) {
                assessed += bucket.count();
                asaCounts.merge(bucket.asaClass(), bucket.count(), Integer::sum);
            }
            if (bucket.riskLevel() != null) {
                riskCounts.merge(bucket.riskLevel(), bucket.count(), Integer::sum);
            }
            if (highRisk(bucket)) {
                highRisk += bucket.count();
            }
        }
        Overdue overdue = overdue(cases, now);

        RiskDashboard model = new RiskDashboard();
        model.setUpdatedAt(OffsetDateTime.ofInstant(now, ZONE));
        model.setTimezone(TIMEZONE);
        model.setDate(day);
        model.setDataScope(view.type());
        model.setTotalCaseCount(totalCaseCount);
        model.setAssessedCaseCount(assessed);
        model.setUnassessedCaseCount(Math.max(0, totalCaseCount - assessed));
        model.setHighRiskCaseCount(highRisk);
        model.setAsaClassCounts(asaCounts(asaCounts));
        model.setRiskLevelCounts(riskLevelCounts(riskCounts));
        model.setSevereAlertCount(alerts.critical());
        model.setOverdueCaseCount(overdue.total());
        model.setDelayedStartCount(overdue.delayedStart());
        model.setOverrunCaseCount(overdue.overrun());
        model.setOverdueThresholdMinutes(properties.getOverdueGraceMinutes());
        model.setFormula(RISK_FORMULA);
        model.setPendingConfirmations(new ArrayList<>(RISK_PENDING_CONFIRMATIONS));

        Instant latest = latestOf(cases, alerts, null);
        int delaySeconds = delaySeconds(now, latest);
        model.setDataDelaySeconds(delaySeconds);
        model.setDataQuality(dataQuality(latest, delaySeconds));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", KIND_RISK);
        detail.put("date", day.toString());
        detail.put("timezone", TIMEZONE);
        detail.put("dataScope", view.type().getValue());
        detail.put("caseCount", totalCaseCount);
        detail.put("assessedCaseCount", assessed);
        detail.put("highRiskCaseCount", highRisk);
        detail.put("alertCount", alerts.critical());
        detail.put("overdueCaseCount", overdue.total());
        detail.put("dataDelaySeconds", delaySeconds);
        audit(KIND_RISK, view, detail, caller, context);
        return model;
    }

    // ---------------------------------------------------------------------
    // 资源态势（M5-M18-02，契约 0.2.0）
    // ---------------------------------------------------------------------

    @Override
    public ResourceDashboard resources(AuthPrincipal caller, AuthRequestContext context) {
        Instant now = clock.instant();
        LocalDate day = now.atZone(ZONE).toLocalDate();
        DayWindow window = dayWindow(day);
        ScopeView view = resolveScope(caller);
        List<DashboardRoomFact> rooms = safeRooms(window, view);
        List<DashboardCaseFact> cases = safeCases(window, view);
        DashboardDeviceStats devices = deviceSource.countDevices(now, window.from(),
                window.toExclusive(), view.scope(), properties.getDeviceOfflineTimeoutSeconds());
        DashboardStaffStats staff = safeStaff(window, view);

        int roomCount = (int) rooms.stream().filter(room -> !room.deleted()).count();
        long surgerySeconds = 0L;
        for (DashboardCaseFact fact : cases) {
            if (STATUS_CANCELLED.equals(fact.status())) {
                continue;
            }
            surgerySeconds += overlapSeconds(fact, window);
        }
        int surgeryMinutes = (int) Math.min(Integer.MAX_VALUE, surgerySeconds / 60);
        long availableMinutes = (long) roomCount * properties.getRoomDailyAvailableMinutes();
        Double utilizationRate = ratio(surgeryMinutes, availableMinutes);
        RoomStatusCounts statusCounts = roomStatusCounts(rooms);

        ResourceDashboard model = new ResourceDashboard();
        model.setUpdatedAt(OffsetDateTime.ofInstant(now, ZONE));
        model.setTimezone(TIMEZONE);
        model.setDate(day);
        model.setDataScope(view.type());

        RoomUtilization utilization = new RoomUtilization();
        utilization.setRoomCount(roomCount);
        utilization.setSurgeryMinutes(surgeryMinutes);
        utilization.setAvailableMinutes((int) Math.min(Integer.MAX_VALUE, availableMinutes));
        utilization.setUtilizationRate(utilizationRate);
        model.setRoomUtilization(utilization);
        model.setRoomStatusCounts(statusCounts);

        DeviceSummary deviceSummary = new DeviceSummary();
        deviceSummary.setTotal(devices.total());
        deviceSummary.setOnline(devices.online());
        deviceSummary.setOffline(devices.offline());
        deviceSummary.setMaintenance(devices.maintenance());
        deviceSummary.setOfflineTimeoutSeconds(properties.getDeviceOfflineTimeoutSeconds());
        model.setDevices(deviceSummary);
        model.setDeviceOnlineRate(ratio(devices.online(), devices.total()));

        StaffOnDuty onDuty = new StaffOnDuty();
        onDuty.setTotal(staff.total());
        onDuty.setByShift(staffShifts(staff.byShift()));
        model.setStaffOnDuty(onDuty);

        model.setResourceGaps(resourceGaps(devices, statusCounts, utilizationRate, surgeryMinutes,
                availableMinutes, roomCount, staff));
        model.setFormula(RESOURCE_FORMULA);
        model.setPendingConfirmations(new ArrayList<>(RESOURCE_PENDING_CONFIRMATIONS));

        Instant latest = latestOf(cases, null, devices);
        int delaySeconds = delaySeconds(now, latest);
        model.setDataDelaySeconds(delaySeconds);
        model.setDataQuality(dataQuality(latest, delaySeconds));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", KIND_RESOURCES);
        detail.put("date", day.toString());
        detail.put("timezone", TIMEZONE);
        detail.put("dataScope", view.type().getValue());
        detail.put("roomCount", roomCount);
        detail.put("caseCount", cases.size());
        detail.put("staffOnDutyTotal", staff.total());
        detail.put("deviceOffline", devices.offline());
        detail.put("dataDelaySeconds", delaySeconds);
        audit(KIND_RESOURCES, view, detail, caller, context);
        return model;
    }

    // ---------------------------------------------------------------------
    // 按小时趋势（M5-M18-02，契约 0.2.0）
    // ---------------------------------------------------------------------

    @Override
    public TrendDashboard trends(Double hours, AuthPrincipal caller, AuthRequestContext context) {
        Instant now = clock.instant();
        int windowMinutes = trendWindowMinutes(hours);
        Instant from = now.minusSeconds(windowMinutes * 60L);
        ScopeView view = resolveScope(caller);
        List<DashboardCaseFact> cases = safeCases(from, now, view);
        List<DashboardRoomFact> rooms = safeRooms(from, now, view);
        Map<Instant, DashboardAlertBucket> alertBuckets = alertBuckets(from, now, view);
        int roomCount = (int) rooms.stream().filter(room -> !room.deleted()).count();

        List<TrendPoint> points = new ArrayList<>();
        int totalCases = 0;
        int totalAlerts = 0;
        long bucketSeconds = TREND_BUCKET_MINUTES * 60L;
        for (Instant bucketStart = floorHour(from); bucketStart.isBefore(now);
                bucketStart = bucketStart.plusSeconds(bucketSeconds)) {
            Instant bucketEnd = bucketStart.plusSeconds(bucketSeconds);
            if (bucketEnd.isAfter(now)) {
                bucketEnd = now;
            }
            Instant start = bucketStart.isBefore(from) ? from : bucketStart;
            if (!bucketEnd.isAfter(start)) {
                continue;
            }
            int bucketMinutes = (int) Duration.between(start, bucketEnd).toMinutes();
            int caseCount = 0;
            long surgerySeconds = 0L;
            for (DashboardCaseFact fact : cases) {
                if (STATUS_CANCELLED.equals(fact.status())) {
                    continue;
                }
                if (fact.startAt() != null && !fact.startAt().isBefore(start)
                        && fact.startAt().isBefore(bucketEnd)) {
                    caseCount++;
                }
                surgerySeconds += overlapSeconds(fact, start, bucketEnd);
            }
            int surgeryMinutes = (int) Math.min(Integer.MAX_VALUE, surgerySeconds / 60);
            DashboardAlertBucket alertBucket = alertBuckets.get(bucketStart);
            TrendPoint point = new TrendPoint();
            point.setStartsAt(OffsetDateTime.ofInstant(bucketStart, ZONE));
            point.setAlertCount(alertBucket == null ? 0 : alertBucket.triggered());
            point.setCriticalAlertCount(alertBucket == null ? 0 : alertBucket.critical());
            point.setCaseCount(caseCount);
            point.setSurgeryMinutes(surgeryMinutes);
            point.setUtilizationRate(ratio(surgeryMinutes, (long) roomCount * bucketMinutes));
            points.add(point);
            totalCases += caseCount;
            totalAlerts += point.getAlertCount();
        }

        TrendDashboard model = new TrendDashboard();
        model.setUpdatedAt(OffsetDateTime.ofInstant(now, ZONE));
        model.setTimezone(TIMEZONE);
        model.setDataScope(view.type());
        model.setWindowMinutes(windowMinutes);
        model.setBucketMinutes(TREND_BUCKET_MINUTES);
        model.setFrom(OffsetDateTime.ofInstant(from, ZONE));
        model.setTo(OffsetDateTime.ofInstant(now, ZONE));
        model.setRoomCount(roomCount);
        model.setPoints(points);
        model.setFormula(TREND_FORMULA);
        model.setPendingConfirmations(new ArrayList<>(TREND_PENDING_CONFIRMATIONS));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", KIND_TRENDS);
        detail.put("timezone", TIMEZONE);
        detail.put("dataScope", view.type().getValue());
        detail.put("windowMinutes", windowMinutes);
        detail.put("roomCount", roomCount);
        detail.put("pointCount", points.size());
        detail.put("caseCount", totalCases);
        detail.put("alertCount", totalAlerts);
        audit(KIND_TRENDS, view, detail, caller, context);
        return model;
    }

    // ---------------------------------------------------------------------
    // 风险/资源/趋势聚合辅助
    // ---------------------------------------------------------------------

    /** 高危判定：ASA ≥ III 或术前评分风险等级 HIGH（同一台次只有一个组合，天然去重）。 */
    private static boolean highRisk(DashboardRiskBucket bucket) {
        return (bucket.asaClass() != null && HIGH_ASA.contains(bucket.asaClass()))
                || HIGH_RISK_LEVEL.equals(bucket.riskLevel());
    }

    /** ASA 计数 → 契约数组（按 I→VI 顺序，只输出已知且计数 &gt; 0 的分级）。 */
    private static List<AsaClassCount> asaCounts(Map<String, Integer> counts) {
        List<AsaClassCount> items = new ArrayList<>();
        for (String code : ASA_ORDER) {
            Integer count = counts.get(code);
            if (count == null || count <= 0) {
                continue;
            }
            AsaClassCount item = new AsaClassCount();
            item.setAsaClass(AsaClass.valueOf(code));
            item.setCount(count);
            items.add(item);
        }
        return items;
    }

    /** 风险等级计数 → 契约数组（按 LOW→MODERATE→HIGH 顺序，只输出已知且计数 &gt; 0 的等级）。 */
    private static List<RiskLevelCount> riskLevelCounts(Map<String, Integer> counts) {
        List<RiskLevelCount> items = new ArrayList<>();
        for (String code : RISK_ORDER) {
            Integer count = counts.get(code);
            if (count == null || count <= 0) {
                continue;
            }
            RiskLevelCount item = new RiskLevelCount();
            item.setRiskLevel(RiskLevel.valueOf(code));
            item.setCount(count);
            items.add(item);
        }
        return items;
    }

    /** 班组计数 → 契约数组（按 DAY→NIGHT→ON_CALL 顺序，只输出已知且人数 &gt; 0 的班次）。 */
    private static List<StaffShiftCount> staffShifts(List<DashboardStaffShift> shifts) {
        List<StaffShiftCount> items = new ArrayList<>();
        for (String code : SHIFT_ORDER) {
            int count = 0;
            for (DashboardStaffShift shift : shifts) {
                if (shift != null && code.equals(shift.shift())) {
                    count += shift.count();
                }
            }
            if (count <= 0) {
                continue;
            }
            StaffShiftCount item = new StaffShiftCount();
            item.setShift(Shift.valueOf(code));
            item.setCount(count);
            items.add(item);
        }
        return items;
    }

    /**
     * 超时台次：延迟开始（计划开始已过宽限且未开始）与超时未结束（计划结束已过宽限且未完成）
     * 两类并集去重；取消台次不参与。
     */
    private Overdue overdue(List<DashboardCaseFact> cases, Instant now) {
        long graceSeconds = properties.getOverdueGraceMinutes() * 60L;
        int delayedStart = 0;
        int overrun = 0;
        Set<Object> overdueKeys = new HashSet<>();
        for (int index = 0; index < cases.size(); index++) {
            DashboardCaseFact fact = cases.get(index);
            if (STATUS_CANCELLED.equals(fact.status())) {
                continue;
            }
            boolean lateStart = fact.startAt() != null
                    && fact.startAt().plusSeconds(graceSeconds).isBefore(now)
                    && !started(fact);
            boolean lateEnd = fact.endAt() != null
                    && fact.endAt().plusSeconds(graceSeconds).isBefore(now)
                    && !completed(fact);
            if (lateStart) {
                delayedStart++;
            }
            if (lateEnd) {
                overrun++;
            }
            if (lateStart || lateEnd) {
                overdueKeys.add(fact.scheduleId() == null ? Integer.valueOf(index)
                        : fact.scheduleId());
            }
        }
        return new Overdue(overdueKeys.size(), delayedStart, overrun);
    }

    /**
     * 资源缺口提示（确定性规则，固定顺序；只含计数与中文提示，不含任何标识）。
     *
     * <p>规则是展示辅助，不构成临床/管理决策；阈值与是否需要分科室细化【待确认】。
     */
    private List<ResourceGap> resourceGaps(DashboardDeviceStats devices,
            RoomStatusCounts statusCounts, Double utilizationRate, int surgeryMinutes,
            long availableMinutes, int roomCount, DashboardStaffStats staff) {
        List<ResourceGap> gaps = new ArrayList<>();
        if (devices.offline() > 0) {
            gaps.add(gap(ResourceGapCode.DEVICE_OFFLINE, ResourceGapSeverity.WARN,
                    devices.offline(), devices.offline()
                            + " 台设备离线（超时未上报），请检查采集网关与设备电源"));
        }
        if (devices.maintenance() > 0) {
            gaps.add(gap(ResourceGapCode.DEVICE_MAINTENANCE, ResourceGapSeverity.INFO,
                    devices.maintenance(), devices.maintenance()
                            + " 台设备维护中，不在线属预期（不参与离线判定）"));
        }
        if (statusCounts.getOutOfService() > 0) {
            gaps.add(gap(ResourceGapCode.ROOM_OUT_OF_SERVICE, ResourceGapSeverity.INFO,
                    statusCounts.getOutOfService(), statusCounts.getOutOfService()
                            + " 间术间停用（软删除或状态未知），排程能力相应减少"));
        }
        if (availableMinutes > 0 && utilizationRate != null && utilizationRate > 1.0D) {
            long excessMinutes = Math.max(0L, surgeryMinutes - availableMinutes);
            long perRoom = properties.getRoomDailyAvailableMinutes();
            int equivalentRooms = (int) Math.max(1L, (excessMinutes + perRoom - 1) / perRoom);
            gaps.add(gap(ResourceGapCode.ROOM_OVERLOADED, ResourceGapSeverity.WARN,
                    equivalentRooms, "当日排程超出可用时长约 " + excessMinutes
                            + " 分钟（等效 " + equivalentRooms + " 间术间），请复核台次安排"));
        }
        if (staff.total() == 0) {
            gaps.add(gap(ResourceGapCode.STAFF_NOT_SCHEDULED, ResourceGapSeverity.WARN, 0,
                    "今日无当班排班（M4 排班未录入），请核对班组安排"));
        }
        if (roomCount == 0) {
            gaps.add(gap(ResourceGapCode.NO_VISIBLE_ROOM, ResourceGapSeverity.WARN, 0,
                    "当前数据范围内无可见术间，利用率与状态分布不可用"));
        }
        return gaps;
    }

    private static ResourceGap gap(ResourceGapCode code, ResourceGapSeverity severity, int count,
            String message) {
        ResourceGap item = new ResourceGap();
        item.setCode(code);
        item.setSeverity(severity);
        item.setCount(count);
        item.setMessage(message);
        return item;
    }

    /** 趋势窗口长度（分钟）：缺省 24h；超出 30–1440 分钟或非有限数 → 400。 */
    static int trendWindowMinutes(Double hours) {
        if (hours == null) {
            return DEFAULT_TREND_WINDOW_MINUTES;
        }
        if (hours.isNaN() || hours.isInfinite() || hours < 0.5D || hours > 24D) {
            throw new M5ValidationException("hours", "hours 必须在 0.5–24 之间（30–1440 分钟）");
        }
        long minutes = Math.round(hours * 60D);
        if (minutes < MIN_TREND_WINDOW_MINUTES || minutes > MAX_TREND_WINDOW_MINUTES) {
            throw new M5ValidationException("hours", "hours 必须在 0.5–24 之间（30–1440 分钟）");
        }
        return (int) minutes;
    }

    /** 本地整点（UTC 整点与 Asia/Shanghai 整点一一对应，上海无夏令时）。 */
    private static Instant floorHour(Instant instant) {
        return instant.atZone(ZONE).truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant();
    }

    private Map<Instant, DashboardAlertBucket> alertBuckets(Instant from, Instant toExclusive,
            ScopeView view) {
        List<DashboardAlertBucket> buckets = alertSource.countAlertsByHour(from, toExclusive,
                view.scope());
        Map<Instant, DashboardAlertBucket> map = new LinkedHashMap<>();
        if (buckets == null) {
            return map;
        }
        for (DashboardAlertBucket bucket : buckets) {
            if (bucket != null && bucket.hourStart() != null) {
                map.put(bucket.hourStart(), bucket);
            }
        }
        return map;
    }

    // ---------------------------------------------------------------------
    // 模型组装
    // ---------------------------------------------------------------------

    private List<RoomSituation> roomSituations(List<DashboardRoomFact> rooms,
            List<DashboardCaseFact> cases) {
        Map<String, Integer> plannedByRoom = new LinkedHashMap<>();
        Map<String, Boolean> activeByRoom = new LinkedHashMap<>();
        for (DashboardCaseFact fact : cases) {
            if (fact.roomId() == null) {
                continue;
            }
            if (!STATUS_CANCELLED.equals(fact.status())) {
                plannedByRoom.merge(fact.roomId(), 1, Integer::sum);
            }
            if (STATUS_IN_PROGRESS.equals(fact.status())) {
                activeByRoom.put(fact.roomId(), Boolean.TRUE);
            }
        }
        List<DashboardRoomFact> ordered = new ArrayList<>(rooms);
        ordered.sort(roomOrder());
        List<RoomSituation> items = new ArrayList<>(ordered.size());
        for (DashboardRoomFact room : ordered) {
            RoomSituation item = new RoomSituation();
            item.setRoomId(uuid(room.roomId()));
            item.setRoomNo(room.roomNo());
            item.setRoomName(room.roomName());
            item.setDeptId(uuid(room.deptId()));
            item.setDeptName(room.deptName());
            item.setStatus(roomStatus(room));
            item.setCaseInProgress(Boolean.TRUE.equals(activeByRoom.get(room.roomId())));
            item.setPlannedCaseCount(plannedByRoom.getOrDefault(room.roomId(), 0));
            items.add(item);
        }
        return items;
    }

    private static RoomStatusCounts roomStatusCounts(List<DashboardRoomFact> rooms) {
        RoomStatusCounts counts = new RoomStatusCounts();
        int inSurgery = 0;
        int idle = 0;
        int cleaning = 0;
        int preparing = 0;
        int recovery = 0;
        int outOfService = 0;
        for (DashboardRoomFact room : rooms) {
            switch (roomStatus(room)) {
                case IN_SURGERY -> inSurgery++;
                case IDLE -> idle++;
                case CLEANING -> cleaning++;
                case PREPARING -> preparing++;
                case RECOVERY -> recovery++;
                default -> outOfService++;
            }
        }
        counts.setTotal(rooms.size());
        counts.setInSurgery(inSurgery);
        counts.setIdle(idle);
        counts.setCleaning(cleaning);
        counts.setPreparing(preparing);
        counts.setRecovery(recovery);
        counts.setOutOfService(outOfService);
        return counts;
    }

    private static CaseProgress caseProgress(List<DashboardCaseFact> cases) {
        CaseProgress progress = new CaseProgress();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        int active = 0;
        for (DashboardCaseFact fact : cases) {
            String status = fact.status();
            if (status == null || !CASE_STATUS_ORDER.contains(status)) {
                continue;
            }
            byStatus.merge(status, 1, Integer::sum);
            if (STATUS_IN_PROGRESS.equals(status)) {
                active++;
            }
        }
        List<CaseStatusCount> items = new ArrayList<>();
        for (String status : CASE_STATUS_ORDER) {
            Integer count = byStatus.get(status);
            if (count == null || count == 0) {
                continue;
            }
            CaseStatusCount item = new CaseStatusCount();
            item.setStatus(CaseStatus.valueOf(status));
            item.setCount(count);
            items.add(item);
        }
        progress.setTotalCount(cases.size());
        progress.setActiveCount(active);
        progress.setStatusCounts(items);
        return progress;
    }

    private static AlertSummary alertSummary(DashboardAlertStats stats) {
        AlertSummary summary = new AlertSummary();
        summary.setUnacknowledgedCount(stats.triggered());
        summary.setAcknowledgedCount(stats.acknowledged());
        summary.setIgnoredCount(stats.ignored());
        summary.setCriticalCount(stats.critical());
        summary.setWarnCount(stats.warn());
        summary.setInfoCount(stats.info());
        if (stats.lastTriggeredAt() != null) {
            summary.setLatestTriggeredAt(OffsetDateTime.ofInstant(stats.lastTriggeredAt(), ZONE));
        }
        return summary;
    }

    // ---------------------------------------------------------------------
    // 台次口径
    // ---------------------------------------------------------------------

    /** 已开始：记录单 started_at 非空，或台次已推进到 IN_PROGRESS/COMPLETED。 */
    static boolean started(DashboardCaseFact fact) {
        return fact.recordStartedAt() != null
                || STATUS_IN_PROGRESS.equals(fact.status())
                || STATUS_COMPLETED.equals(fact.status());
    }

    /** 已完成：记录单 ended_at 非空，或台次状态 COMPLETED。 */
    static boolean completed(DashboardCaseFact fact) {
        return fact.recordEndedAt() != null || STATUS_COMPLETED.equals(fact.status());
    }

    /** 是否有可用麻醉时长（有开始与结束且结束不早于开始；与 report-1.0.0 一致）。 */
    static boolean hasDuration(DashboardCaseFact fact) {
        return fact.recordStartedAt() != null && fact.recordEndedAt() != null
                && !fact.recordEndedAt().isBefore(fact.recordStartedAt());
    }

    /** 麻醉时长（分钟，向下取整）；无可用时长返回 0。 */
    static int minutes(DashboardCaseFact fact) {
        if (!hasDuration(fact)) {
            return 0;
        }
        long seconds = Duration.between(fact.recordStartedAt(), fact.recordEndedAt()).getSeconds();
        return seconds <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, seconds / 60);
    }

    /** 台次计划区间与当日窗口的重叠秒数；缺起止时间返回 0。 */
    static long overlapSeconds(DashboardCaseFact fact, DayWindow window) {
        return overlapSeconds(fact, window.from(), window.toExclusive());
    }

    /** 台次计划区间与任意半开窗口的重叠秒数（趋势小时桶复用）；缺起止时间返回 0。 */
    static long overlapSeconds(DashboardCaseFact fact, Instant from, Instant toExclusive) {
        if (fact.startAt() == null || fact.endAt() == null) {
            return 0L;
        }
        Instant start = fact.startAt().isAfter(from) ? fact.startAt() : from;
        Instant end = fact.endAt().isBefore(toExclusive) ? fact.endAt() : toExclusive;
        if (!end.isAfter(start)) {
            return 0L;
        }
        return Duration.between(start, end).getSeconds();
    }

    /** 准点统计：每术间当日计划最早台次中可评估者的准点/已评估数量。 */
    private OnTime onTime(List<DashboardCaseFact> cases) {
        Map<String, DashboardCaseFact> firstByRoom = new LinkedHashMap<>();
        for (DashboardCaseFact fact : cases) {
            if (fact.roomId() == null || fact.startAt() == null) {
                continue;
            }
            DashboardCaseFact current = firstByRoom.get(fact.roomId());
            if (current == null || firstBefore(fact, current)) {
                firstByRoom.put(fact.roomId(), fact);
            }
        }
        int evaluated = 0;
        int onTime = 0;
        long thresholdMillis = properties.getOnTimeThresholdMinutes() * 60_000L;
        for (DashboardCaseFact first : firstByRoom.values()) {
            // 计划首台取消/未开始/缺实际开始 → 该术间当日不纳入准点评估（不拿第二台顶替）。
            if (STATUS_CANCELLED.equals(first.status()) || !started(first)) {
                continue;
            }
            Instant actualStart = first.recordStartedAt() != null
                    ? first.recordStartedAt() : first.updatedAt();
            if (actualStart == null) {
                continue;
            }
            evaluated++;
            long diffMillis = Math.abs(Duration.between(first.startAt(), actualStart).toMillis());
            if (diffMillis <= thresholdMillis) {
                onTime++;
            }
        }
        return new OnTime(onTime, evaluated);
    }

    private static boolean firstBefore(DashboardCaseFact candidate, DashboardCaseFact current) {
        int byStart = candidate.startAt().compareTo(current.startAt());
        if (byStart != 0) {
            return byStart < 0;
        }
        String left = candidate.scheduleId() == null ? "" : candidate.scheduleId();
        String right = current.scheduleId() == null ? "" : current.scheduleId();
        return left.compareTo(right) < 0;
    }

    // ---------------------------------------------------------------------
    // 数据时效
    // ---------------------------------------------------------------------

    private static Instant latestOf(List<DashboardCaseFact> cases, DashboardAlertStats alerts,
            DashboardDeviceStats devices) {
        Instant latest = null;
        for (DashboardCaseFact fact : cases) {
            latest = later(latest, fact.updatedAt());
            latest = later(latest, fact.recordStartedAt());
            latest = later(latest, fact.recordEndedAt());
        }
        latest = later(latest, alerts == null ? null : alerts.lastTriggeredAt());
        latest = later(latest, devices == null ? null : devices.lastSeenAt());
        return latest;
    }

    private static Instant later(Instant current, Instant candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static int delaySeconds(Instant now, Instant latest) {
        if (latest == null) {
            return 0;
        }
        long seconds = Duration.between(latest, now).getSeconds();
        return seconds <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    private DataQuality dataQuality(Instant latest, int delaySeconds) {
        if (latest == null) {
            return DataQuality.NO_DATA;
        }
        int threshold = Math.min(properties.getStaleThresholdSeconds(),
                MAX_STALE_THRESHOLD_SECONDS);
        return delaySeconds > threshold ? DataQuality.DEGRADED : DataQuality.OK;
    }

    // ---------------------------------------------------------------------
    // 数据范围
    // ---------------------------------------------------------------------

    private List<DashboardRoomFact> safeRooms(DayWindow window, ScopeView view) {
        return safeRooms(window.from(), window.toExclusive(), view);
    }

    private List<DashboardRoomFact> safeRooms(Instant from, Instant toExclusive, ScopeView view) {
        List<DashboardRoomFact> rooms = roomSource.findRooms(from, toExclusive, view.scope());
        return rooms == null ? List.of() : rooms;
    }

    private List<DashboardCaseFact> safeCases(DayWindow window, ScopeView view) {
        return safeCases(window.from(), window.toExclusive(), view);
    }

    private List<DashboardCaseFact> safeCases(Instant from, Instant toExclusive, ScopeView view) {
        List<DashboardCaseFact> cases = caseSource.findCases(from, toExclusive, view.scope());
        return cases == null ? List.of() : cases;
    }

    private List<DashboardRiskBucket> safeRiskBuckets(DayWindow window, ScopeView view) {
        List<DashboardRiskBucket> buckets = riskSource.findRiskBuckets(window.from(),
                window.toExclusive(), view.scope());
        return buckets == null ? List.of() : buckets;
    }

    private DashboardStaffStats safeStaff(DayWindow window, ScopeView view) {
        DashboardStaffStats stats = staffSource.countOnDuty(window.date(), window.from(),
                window.toExclusive(), view.scope());
        return stats == null ? DashboardStaffStats.empty() : stats;
    }

    /**
     * 解析数据范围并构造端口输入。
     *
     * <p>失败语义：无可用范围、SELF 缺用户标识 → 403（{@link AccessDeniedException}），
     * 与 M4 报表一致；绝不回退为全院查询。
     */
    private ScopeView resolveScope(AuthPrincipal caller) {
        UUID userId = caller == null ? null : caller.userId();
        DataScopeAssignments assignments = dataScopeResolver.resolve(userId);
        if (assignments == null || assignments.isDenied()) {
            throw new AccessDeniedException("当前用户无数据范围");
        }
        boolean unrestricted = assignments.isUnrestricted();
        boolean selfScoped = !unrestricted && hasSelf(assignments);
        List<String> orgIds = unrestricted ? List.of() : assignments.allowedOrgIds();
        List<String> roomIds = unrestricted ? List.of() : assignments.allowedRoomIds();
        if (!unrestricted && orgIds.isEmpty() && roomIds.isEmpty() && !selfScoped) {
            throw new AccessDeniedException("当前用户无可用看板数据范围");
        }
        if (selfScoped && userId == null) {
            throw new AccessDeniedException("当前用户无可用看板数据范围");
        }
        DataScopeType type = scopeType(assignments, unrestricted, selfScoped);
        DashboardScope scope = new DashboardScope(unrestricted, orgIds, roomIds, selfScoped,
                userId == null ? null : userId.toString());
        return new ScopeView(type, scope);
    }

    private static boolean hasSelf(DataScopeAssignments assignments) {
        for (DataScopeAssignment assignment : assignments.assignments()) {
            if (assignment != null && assignment.type() == DataScope.SELF) {
                return true;
            }
        }
        return false;
    }

    /** 数据范围类型（展示用途）：取声明中最高优先级的范围（ALL &gt; SITE &gt; DEPT &gt; ROOM &gt; SELF）。 */
    private static DataScopeType scopeType(DataScopeAssignments assignments, boolean unrestricted,
            boolean selfScoped) {
        if (unrestricted) {
            return DataScopeType.ALL;
        }
        boolean site = false;
        boolean dept = false;
        boolean room = false;
        for (DataScopeAssignment assignment : assignments.assignments()) {
            if (assignment == null || assignment.type() == null) {
                continue;
            }
            switch (assignment.type()) {
                case SITE -> site = true;
                case DEPT -> dept = true;
                case ROOM -> room = true;
                default -> {
                    // SELF/NONE/ALL 已由上层处理或不需要在此聚合。
                }
            }
        }
        if (site) {
            return DataScopeType.SITE;
        }
        if (dept) {
            return DataScopeType.DEPT;
        }
        if (room) {
            return DataScopeType.ROOM;
        }
        return selfScoped ? DataScopeType.SELF : DataScopeType.ALL;
    }

    // ---------------------------------------------------------------------
    // 参数、审计与工具
    // ---------------------------------------------------------------------

    private LocalDate parseDate(String raw, LocalDate today) {
        if (raw == null || raw.isBlank()) {
            return today;
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException invalid) {
            throw new M5ValidationException("date", "date 必须为 YYYY-MM-DD 格式的本地日期");
        }
        if (parsed.isAfter(today.plusDays(MAX_FUTURE_DAYS))) {
            throw new M5ValidationException("date", "date 不能晚于今日之后 " + MAX_FUTURE_DAYS + " 天");
        }
        if (parsed.isBefore(today.minusDays(MAX_PAST_DAYS))) {
            throw new M5ValidationException("date", "date 不能早于今日之前 " + MAX_PAST_DAYS + " 天");
        }
        return parsed;
    }

    private static DayWindow dayWindow(LocalDate date) {
        return new DayWindow(date, date.atStartOfDay(ZONE).toInstant(),
                date.plusDays(1).atStartOfDay(ZONE).toInstant());
    }

    private void audit(String kind, String date, ScopeView view, int roomCount, int caseCount,
            int alertCount, int delaySeconds, AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", kind);
        if (date != null) {
            detail.put("date", date);
        }
        detail.put("timezone", TIMEZONE);
        detail.put("dataScope", view.type().getValue());
        detail.put("roomCount", roomCount);
        detail.put("caseCount", caseCount);
        detail.put("alertCount", alertCount);
        detail.put("dataDelaySeconds", delaySeconds);
        audit(kind, view, detail, caller, context);
    }

    /**
     * 写看板读审计（采样）：同一用户 + 同一看板类型在采样窗口内只落一条，
     * detail 由调用方构造（只允许计数/范围/延迟，禁止患者标识与自由文本）。
     */
    private void audit(String kind, ScopeView view, Map<String, Object> detail,
            AuthPrincipal caller, AuthRequestContext context) {
        Duration window = Duration.ofSeconds(properties.getAuditSampleSeconds());
        String key = (caller == null || caller.userId() == null ? "anonymous" : caller.userId())
                + "|" + kind;
        if (!auditSampler.shouldRecord(key, clock.instant(), window)) {
            return;
        }
        Map<String, Object> safeDetail = new LinkedHashMap<>(detail);
        safeDetail.put("dataScope", view.type().getValue());
        auditWriter.record(ACTION, RESOURCE_TYPE, kind, true, safeDetail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
    }

    private static RoomStatus roomStatus(DashboardRoomFact room) {
        if (room.deleted()) {
            return RoomStatus.OUT_OF_SERVICE;
        }
        String status = room.status();
        if (status == null) {
            return RoomStatus.OUT_OF_SERVICE;
        }
        return switch (status) {
            case "IDLE" -> RoomStatus.IDLE;
            case "PREPARING" -> RoomStatus.PREPARING;
            case "ANESTHESIA", "SURGERY" -> RoomStatus.IN_SURGERY;
            case "PACU" -> RoomStatus.RECOVERY;
            case "CLEANING" -> RoomStatus.CLEANING;
            // 未知状态按“不可用”处理，绝不把状态不明的术间计入空闲/手术中。
            default -> RoomStatus.OUT_OF_SERVICE;
        };
    }

    private static Comparator<DashboardRoomFact> roomOrder() {
        return Comparator.comparing((DashboardRoomFact room) -> sortKey(room.siteId()))
                .thenComparing(room -> sortKey(room.deptName()))
                .thenComparing(room -> sortKey(room.deptId()))
                .thenComparing(room -> sortKey(room.roomNo()))
                .thenComparing(room -> sortKey(room.roomId()));
    }

    private static String sortKey(String value) {
        return value == null ? "" : value;
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0D;
        }
        return BigDecimal.valueOf((double) numerator / denominator)
                .setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double average(long totalMinutes, int count) {
        if (count <= 0) {
            return 0.0D;
        }
        return BigDecimal.valueOf((double) totalMinutes / count)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "anesop.m5.dashboard." + name + " 必须在 " + min + "–" + max + " 之间");
        }
    }

    /** 当日窗口（本地日期 + 半开 UTC 区间）。 */
    record DayWindow(LocalDate date, Instant from, Instant toExclusive) {
    }

    /** 解析后的数据范围（类型用于响应展示，scope 用于端口过滤）。 */
    record ScopeView(DataScopeType type, DashboardScope scope) {
    }

    /** 准点统计中间值。 */
    record OnTime(int onTime, int evaluated) {
    }

    /** 超时台次中间值（并集去重计数 + 两类分列计数）。 */
    record Overdue(int total, int delayedStart, int overrun) {
    }
}
