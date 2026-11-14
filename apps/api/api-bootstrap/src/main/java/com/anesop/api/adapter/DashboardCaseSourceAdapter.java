package com.anesop.api.adapter;

import com.anesop.m5.api.dashboard.DashboardCaseFact;
import com.anesop.m5.api.dashboard.DashboardCaseSource;
import com.anesop.m5.api.dashboard.DashboardScope;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * 看板台次只读投影（M5-M18-01）：{@link DashboardCaseSource} 的装配层实现。
 *
 * <p>为什么在装配层：台次事实来自 M1 排程与 M2 麻醉记录单，而 m5-impl 不得访问其他模块的
 * 数据表/Mapper，也不能反向依赖 m1-api/m2-api（AGENTS「模块边界与依赖禁令」，ArchUnit 覆盖）。
 * 本类与 {@code ReportCaseSourceAdapter} 同属 api-bootstrap 装配层，是**唯一**允许跨 schema
 * 只读投影的位置（ADR-0023）。
 *
 * <p>约束（代码即约定）：单条 {@code SELECT}、不写任何表、不开启事务、不复制对方实体、
 * 不做时区换算（窗口由服务层按 Asia/Shanghai 换算为 UTC 半开区间）、不返回患者标识。
 * 纵深防御：非全院且 orgIds/roomIds/self 全空时直接拒绝，绝不退化为全表查询。
 */
@Component
public class DashboardCaseSourceAdapter implements DashboardCaseSource {

    /**
     * 投影 SQL：一台次一行（麻醉记录单 0..1 行，见 uk_anesthesia_record_schedule）。
     * 只取计数与时长所需字段：无患者标识、无术式名、无诊断。
     */
    private static final String PROJECTION_SQL = """
            SELECT s.id AS schedule_id,
                   s.room_id,
                   a.dept_id,
                   a.site_id,
                   s.status,
                   s.start_at,
                   s.end_at,
                   s.updated_at,
                   rec.started_at AS record_started_at,
                   rec.ended_at AS record_ended_at,
                   rec.created_by AS record_created_by
              FROM m1_sched.schedule s
              JOIN m1_sched.surgery_application a
                ON a.id = s.application_id AND a.deleted = false
              LEFT JOIN m2_anes.anesthesia_record rec
                ON rec.schedule_id = s.id AND rec.deleted = false
             WHERE s.deleted = false
               AND s.start_at >= ?
               AND s.start_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的 JdbcTemplate 单例由容器管理；适配器只执行只读查询，不修改其内部状态")
    public DashboardCaseSourceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<DashboardCaseFact> findCases(Instant from, Instant toExclusive,
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
        StringBuilder sql = new StringBuilder(PROJECTION_SQL);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(from));
        arguments.add(Timestamp.from(toExclusive));
        if (!scope.unrestricted()) {
            List<String> clauses = new ArrayList<>();
            List<Object> clauseArguments = new ArrayList<>();
            if (!orgIds.isEmpty()) {
                clauses.add("(a.dept_id IN (" + placeholders(orgIds.size())
                        + ") OR a.site_id IN (" + placeholders(orgIds.size()) + "))");
                clauseArguments.addAll(orgIds);
                clauseArguments.addAll(orgIds);
            }
            if (!roomIds.isEmpty()) {
                clauses.add("s.room_id IN (" + placeholders(roomIds.size()) + ")");
                clauseArguments.addAll(roomIds);
            }
            if (scope.selfScoped()) {
                clauses.add("rec.created_by = ?");
                clauseArguments.add(selfUserId);
            }
            if (!clauses.isEmpty()) {
                sql.append(" AND (").append(String.join(" OR ", clauses)).append(')');
                arguments.addAll(clauseArguments);
            }
        }
        sql.append(" ORDER BY s.start_at, s.id");
        return jdbcTemplate.query(sql.toString(), (resultSet, rowNum) -> mapRow(resultSet),
                arguments.toArray());
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static DashboardCaseFact mapRow(ResultSet resultSet) throws SQLException {
        return new DashboardCaseFact(
                resultSet.getString("schedule_id"),
                uuid(resultSet, "room_id"),
                uuid(resultSet, "dept_id"),
                uuid(resultSet, "site_id"),
                resultSet.getString("status"),
                instant(resultSet, "start_at"),
                instant(resultSet, "end_at"),
                instant(resultSet, "updated_at"),
                instant(resultSet, "record_started_at"),
                instant(resultSet, "record_ended_at"),
                uuid(resultSet, "record_created_by"));
    }

    private static String uuid(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        return value == null ? null : value.toString();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
