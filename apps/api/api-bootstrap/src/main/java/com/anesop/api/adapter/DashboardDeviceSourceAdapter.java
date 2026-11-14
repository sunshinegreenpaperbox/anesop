package com.anesop.api.adapter;

import com.anesop.m5.api.dashboard.DashboardDeviceSource;
import com.anesop.m5.api.dashboard.DashboardDeviceStats;
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
 * 看板设备只读投影（M5-M18-01）：{@link DashboardDeviceSource} 的装配层实现。
 *
 * <p>单条聚合 {@code SELECT}：总数、在线/维护中计数与最近上线时间。在线状态为**查询期推导**
 * （{@code status <> 'MAINTENANCE' AND last_seen_at > now − 超时阈值}），与 M2 采集的离线判定
 * 一致，不写回 {@code device_registry}。不返回网关/设备序列号与质量统计明细。
 */
@Component
public class DashboardDeviceSourceAdapter implements DashboardDeviceSource {

    private static final String AGGREGATE_SQL = """
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE d.status <> 'MAINTENANCE'
                                      AND d.last_seen_at IS NOT NULL
                                      AND d.last_seen_at > ?) AS online,
                   COUNT(*) FILTER (WHERE d.status = 'MAINTENANCE') AS maintenance,
                   MAX(d.last_seen_at) AS last_seen_at
              FROM m2_anes.device_registry d
              LEFT JOIN m1_sched.operating_room r ON r.id = d.room_id
             WHERE 1 = 1
            """;

    private static final String SELF_EXISTS = """
             EXISTS (
                 SELECT 1
                   FROM m1_sched.schedule s
                   JOIN m2_anes.anesthesia_record rec
                     ON rec.schedule_id = s.id AND rec.deleted = false
                  WHERE s.room_id = d.room_id
                    AND s.deleted = false
                    AND s.start_at >= ?
                    AND s.start_at < ?
                    AND rec.created_by = ?
             )
            """;

    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的 JdbcTemplate 单例由容器管理；适配器只执行只读查询，不修改其内部状态")
    public DashboardDeviceSourceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DashboardDeviceStats countDevices(Instant now, Instant from, Instant toExclusive,
            DashboardScope scope, int offlineTimeoutSeconds) {
        if (now == null || from == null || toExclusive == null || scope == null) {
            return DashboardDeviceStats.empty();
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
        // 在线判定：last_seen_at 晚于“当前时间 − 超时阈值”。
        arguments.add(Timestamp.from(now.minusSeconds(Math.max(1, offlineTimeoutSeconds))));
        if (!scope.unrestricted()) {
            List<String> clauses = new ArrayList<>();
            if (!orgIds.isEmpty()) {
                clauses.add("(r.dept_id IN (" + placeholders(orgIds.size())
                        + ") OR r.site_id IN (" + placeholders(orgIds.size()) + "))");
                arguments.addAll(orgIds);
                arguments.addAll(orgIds);
            }
            if (!roomIds.isEmpty()) {
                clauses.add("d.room_id IN (" + placeholders(roomIds.size()) + ")");
                arguments.addAll(roomIds);
            }
            if (scope.selfScoped()) {
                clauses.add(SELF_EXISTS);
                arguments.add(Timestamp.from(from));
                arguments.add(Timestamp.from(toExclusive));
                arguments.add(selfUserId);
            }
            if (!clauses.isEmpty()) {
                sql.append(" AND (").append(String.join(" OR ", clauses)).append(')');
            }
        }
        return jdbcTemplate.queryForObject(sql.toString(), (resultSet, rowNum) -> {
            int total = resultSet.getInt("total");
            int online = resultSet.getInt("online");
            int maintenance = resultSet.getInt("maintenance");
            int offline = Math.max(0, total - online - maintenance);
            OffsetDateTime lastSeen = resultSet.getObject("last_seen_at", OffsetDateTime.class);
            return new DashboardDeviceStats(total, online, offline, maintenance,
                    lastSeen == null ? null : lastSeen.toInstant());
        }, arguments.toArray());
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
