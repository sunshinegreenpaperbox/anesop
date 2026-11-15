package com.anesop.api.adapter;

import com.anesop.m5.api.dashboard.DashboardAlertBucket;
import com.anesop.m5.api.dashboard.DashboardAlertSource;
import com.anesop.m5.api.dashboard.DashboardAlertStats;
import com.anesop.m5.api.dashboard.DashboardScope;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 看板告警只读投影（M5-M18-01）：{@link DashboardAlertSource} 的装配层实现。
 *
 * <p>单条聚合 {@code SELECT}：按窗口统计未确认（TRIGGERED）告警数及严重度分布、已确认/已忽略数
 * 与最近触发时间。**不返回**告警值、规则原文、处置原因与患者/记录单标识（看板只做态势计数）。
 * 数据范围经“告警 → 记录单 → 台次 → 申请”关联裁剪（SITE/DEPT/ROOM/SELF）。
 */
@Component
public class DashboardAlertSourceAdapter implements DashboardAlertSource {

    private static final String AGGREGATE_SQL = """
            SELECT COUNT(*) FILTER (WHERE al.status = 'TRIGGERED') AS triggered,
                   COUNT(*) FILTER (WHERE al.status = 'TRIGGERED'
                                      AND al.severity = 'CRITICAL') AS critical,
                   COUNT(*) FILTER (WHERE al.status = 'TRIGGERED'
                                      AND al.severity = 'WARN') AS warn,
                   COUNT(*) FILTER (WHERE al.status = 'TRIGGERED'
                                      AND al.severity = 'INFO') AS info,
                   COUNT(*) FILTER (WHERE al.status = 'ACKNOWLEDGED') AS acknowledged,
                   COUNT(*) FILTER (WHERE al.status = 'IGNORED') AS ignored,
                   MAX(al.triggered_at) AS last_triggered_at
              FROM m2_anes.alert al
              JOIN m2_anes.anesthesia_record rec ON rec.id = al.record_id
              JOIN m1_sched.schedule s ON s.id = rec.schedule_id
              JOIN m1_sched.surgery_application a ON a.id = s.application_id
             WHERE al.triggered_at >= ?
               AND al.triggered_at < ?
               AND s.deleted = false
               AND a.deleted = false
            """;

    private static final String BY_HOUR_SQL = """
            SELECT date_trunc('hour', al.triggered_at) AS hour_start,
                   COUNT(*) AS triggered,
                   COUNT(*) FILTER (WHERE al.severity = 'CRITICAL') AS critical
              FROM m2_anes.alert al
              JOIN m2_anes.anesthesia_record rec ON rec.id = al.record_id
              JOIN m1_sched.schedule s ON s.id = rec.schedule_id
              JOIN m1_sched.surgery_application a ON a.id = s.application_id
             WHERE al.triggered_at >= ?
               AND al.triggered_at < ?
               AND s.deleted = false
               AND a.deleted = false
            """;

    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的 JdbcTemplate 单例由容器管理；适配器只执行只读查询，不修改其内部状态")
    public DashboardAlertSourceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DashboardAlertStats countAlerts(Instant from, Instant toExclusive,
            DashboardScope scope) {
        if (from == null || toExclusive == null || scope == null) {
            return DashboardAlertStats.empty();
        }
        List<UUID> orgIds = DashboardScopeValues.uuids(scope.orgIds());
        List<UUID> roomIds = DashboardScopeValues.uuids(scope.roomIds());
        UUID selfUserId = scope.selfScoped()
                ? DashboardScopeValues.selfUserId(scope.selfUserId()) : null;
        if (!scope.unrestricted() && orgIds.isEmpty() && roomIds.isEmpty()
                && !scope.selfScoped()) {
            throw new AccessDeniedException("当前用户无可用看板数据范围");
        }
        StringBuilder sql = new StringBuilder(AGGREGATE_SQL);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(from));
        arguments.add(Timestamp.from(toExclusive));
        if (!scope.unrestricted()) {
            List<String> clauses = new ArrayList<>();
            if (!orgIds.isEmpty()) {
                clauses.add("(a.dept_id IN (" + placeholders(orgIds.size())
                        + ") OR a.site_id IN (" + placeholders(orgIds.size()) + "))");
                arguments.addAll(orgIds);
                arguments.addAll(orgIds);
            }
            if (!roomIds.isEmpty()) {
                clauses.add("s.room_id IN (" + placeholders(roomIds.size()) + ")");
                arguments.addAll(roomIds);
            }
            if (scope.selfScoped()) {
                clauses.add("rec.created_by = ?");
                arguments.add(selfUserId);
            }
            if (!clauses.isEmpty()) {
                sql.append(" AND (").append(String.join(" OR ", clauses)).append(')');
            }
        }
        return jdbcTemplate.queryForObject(sql.toString(), (resultSet, rowNum) -> {
            OffsetDateTime lastTriggered = resultSet.getObject("last_triggered_at",
                    OffsetDateTime.class);
            return new DashboardAlertStats(
                    resultSet.getInt("triggered"),
                    resultSet.getInt("critical"),
                    resultSet.getInt("warn"),
                    resultSet.getInt("info"),
                    resultSet.getInt("acknowledged"),
                    resultSet.getInt("ignored"),
                    lastTriggered == null ? null : lastTriggered.toInstant());
        }, arguments.toArray());
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    /**
     * 按小时分桶的告警计数（趋势端点用）：单条 GROUP BY 聚合，桶起点为 UTC 整点
     * （上海无夏令时，UTC 整点与本地整点一一对应）。
     */
    @Override
    public List<DashboardAlertBucket> countAlertsByHour(Instant from, Instant toExclusive,
            DashboardScope scope) {
        if (from == null || toExclusive == null || scope == null) {
            return List.of();
        }
        List<UUID> orgIds = DashboardScopeValues.uuids(scope.orgIds());
        List<UUID> roomIds = DashboardScopeValues.uuids(scope.roomIds());
        UUID selfUserId = scope.selfScoped()
                ? DashboardScopeValues.selfUserId(scope.selfUserId()) : null;
        if (!scope.unrestricted() && orgIds.isEmpty() && roomIds.isEmpty()
                && !scope.selfScoped()) {
            throw new AccessDeniedException("当前用户无可用看板数据范围");
        }
        StringBuilder sql = new StringBuilder(BY_HOUR_SQL);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(from));
        arguments.add(Timestamp.from(toExclusive));
        if (!scope.unrestricted()) {
            List<String> clauses = new ArrayList<>();
            if (!orgIds.isEmpty()) {
                clauses.add("(a.dept_id IN (" + placeholders(orgIds.size())
                        + ") OR a.site_id IN (" + placeholders(orgIds.size()) + "))");
                arguments.addAll(orgIds);
                arguments.addAll(orgIds);
            }
            if (!roomIds.isEmpty()) {
                clauses.add("s.room_id IN (" + placeholders(roomIds.size()) + ")");
                arguments.addAll(roomIds);
            }
            if (scope.selfScoped()) {
                clauses.add("rec.created_by = ?");
                arguments.add(selfUserId);
            }
            if (!clauses.isEmpty()) {
                sql.append(" AND (").append(String.join(" OR ", clauses)).append(')');
            }
        }
        sql.append(" GROUP BY 1 ORDER BY 1");
        return jdbcTemplate.query(sql.toString(), (resultSet, rowNum) -> {
            OffsetDateTime hourStart = resultSet.getObject("hour_start", OffsetDateTime.class);
            return new DashboardAlertBucket(hourStart == null ? null : hourStart.toInstant(),
                    resultSet.getInt("triggered"), resultSet.getInt("critical"));
        }, arguments.toArray());
    }
}
