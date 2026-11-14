package com.anesop.api.adapter;

import com.anesop.m5.api.dashboard.DashboardRoomFact;
import com.anesop.m5.api.dashboard.DashboardRoomSource;
import com.anesop.m5.api.dashboard.DashboardScope;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 看板术间只读投影（M5-M18-01）：{@link DashboardRoomSource} 的装配层实现。
 *
 * <p>单条 {@code SELECT} 投影 M1 术间主数据（含软删除术间，看板按“停用”计入
 * {@code OUT_OF_SERVICE}）。SELF 范围用 EXISTS 子查询限定为“本人当日台次所涉术间”，
 * 仍是单条 SELECT；不返回患者标识。
 */
@Component
public class DashboardRoomSourceAdapter implements DashboardRoomSource {

    private static final String PROJECTION_SQL = """
            SELECT r.id AS room_id,
                   r.room_no,
                   r.name AS room_name,
                   r.dept_id,
                   r.dept_name,
                   r.site_id,
                   r.status,
                   r.deleted
              FROM m1_sched.operating_room r
             WHERE 1 = 1
            """;

    private static final String SELF_EXISTS = """
             EXISTS (
                 SELECT 1
                   FROM m1_sched.schedule s
                   JOIN m2_anes.anesthesia_record rec
                     ON rec.schedule_id = s.id AND rec.deleted = false
                  WHERE s.room_id = r.id
                    AND s.deleted = false
                    AND s.start_at >= ?
                    AND s.start_at < ?
                    AND rec.created_by = ?
             )
            """;

    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的 JdbcTemplate 单例由容器管理；适配器只执行只读查询，不修改其内部状态")
    public DashboardRoomSourceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<DashboardRoomFact> findRooms(Instant from, Instant toExclusive,
            DashboardScope scope) {
        if (scope == null) {
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
        if (!scope.unrestricted()) {
            List<String> clauses = new ArrayList<>();
            if (!orgIds.isEmpty()) {
                clauses.add("(r.dept_id IN (" + placeholders(orgIds.size())
                        + ") OR r.site_id IN (" + placeholders(orgIds.size()) + "))");
                arguments.addAll(orgIds);
                arguments.addAll(orgIds);
            }
            if (!roomIds.isEmpty()) {
                clauses.add("r.id IN (" + placeholders(roomIds.size()) + ")");
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
        sql.append(" ORDER BY r.site_id, r.dept_name, r.room_no, r.id");
        return jdbcTemplate.query(sql.toString(), (resultSet, rowNum) -> mapRow(resultSet),
                arguments.toArray());
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static DashboardRoomFact mapRow(ResultSet resultSet) throws SQLException {
        return new DashboardRoomFact(
                uuid(resultSet, "room_id"),
                resultSet.getString("room_no"),
                resultSet.getString("room_name"),
                uuid(resultSet, "dept_id"),
                resultSet.getString("dept_name"),
                uuid(resultSet, "site_id"),
                resultSet.getString("status"),
                resultSet.getBoolean("deleted"));
    }

    private static String uuid(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        return value == null ? null : value.toString();
    }
}
