package com.anesop.api.adapter;

import com.anesop.m5.api.dashboard.DashboardScope;
import com.anesop.m5.api.dashboard.DashboardStaffShift;
import com.anesop.m5.api.dashboard.DashboardStaffSource;
import com.anesop.m5.api.dashboard.DashboardStaffStats;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 看板当班人员只读投影（M5-M18-02）：{@link DashboardStaffSource} 的装配层实现。
 *
 * <p>单条聚合 {@code SELECT}（`GROUPING SETS`）：按班次分组给出**去重人数**，另用空分组行给出
 * 当日去重总人数——同一人同日多班次时，各班组之和可能大于总数，因此不能相加。只返回班次与
 * 人数，**不返回人员标识、姓名与工号**。
 *
 * <p>数据范围裁剪（fail-closed）：
 * <ul>
 *   <li>`SITE`/`DEPT`：按排班所属科室 `department_id`，或排班绑定术间所属科室/院区；</li>
 *   <li>`ROOM`：按排班绑定术间 `room_id`；</li>
 *   <li>`SELF`：人员排班无用户标识，按“本人当日台次所涉**科室**”的当班人数（EXISTS 子查询，
 *       仍是单条 SELECT）【待确认】（ADR-0023 Q-6）。</li>
 * </ul>
 */
@Component
public class DashboardStaffSourceAdapter implements DashboardStaffSource {

    private static final String AGGREGATE_SQL = """
            SELECT ss.shift AS shift,
                   COUNT(DISTINCT ss.staff_id) AS staff_count
              FROM m4_staff.staff_schedule ss
             WHERE ss.schedule_date = ?
               AND ss.status = 'PLANNED'
            """;

    private static final String SELF_DEPT_EXISTS = """
             EXISTS (
                 SELECT 1
                   FROM m1_sched.schedule s
                   JOIN m2_anes.anesthesia_record rec
                     ON rec.schedule_id = s.id AND rec.deleted = false
                   JOIN m1_sched.operating_room r ON r.id = s.room_id
                  WHERE r.dept_id = ss.department_id
                    AND s.deleted = false
                    AND s.start_at >= ?
                    AND s.start_at < ?
                    AND rec.created_by = ?
             )
            """;

    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的 JdbcTemplate 单例由容器管理；适配器只执行只读查询，不修改其内部状态")
    public DashboardStaffSourceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DashboardStaffStats countOnDuty(LocalDate date, Instant from, Instant toExclusive,
            DashboardScope scope) {
        if (date == null || from == null || toExclusive == null || scope == null) {
            return DashboardStaffStats.empty();
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
        arguments.add(java.sql.Date.valueOf(date));
        if (!scope.unrestricted()) {
            List<String> clauses = new ArrayList<>();
            if (!orgIds.isEmpty()) {
                String placeholders = placeholders(orgIds.size());
                clauses.add("ss.department_id IN (" + placeholders + ")");
                arguments.addAll(orgIds);
                // 排班可能只绑定术间（room_id）而无同一组织标识：按术间所属科室/院区再匹配一次。
                clauses.add("EXISTS (SELECT 1 FROM m1_sched.operating_room r WHERE r.id = ss.room_id"
                        + " AND (r.dept_id IN (" + placeholders + ")"
                        + " OR r.site_id IN (" + placeholders + ")))");
                arguments.addAll(orgIds);
                arguments.addAll(orgIds);
            }
            if (!roomIds.isEmpty()) {
                clauses.add("ss.room_id IN (" + placeholders(roomIds.size()) + ")");
                arguments.addAll(roomIds);
            }
            if (scope.selfScoped()) {
                clauses.add(SELF_DEPT_EXISTS);
                arguments.add(Timestamp.from(from));
                arguments.add(Timestamp.from(toExclusive));
                arguments.add(selfUserId);
            }
            if (!clauses.isEmpty()) {
                sql.append(" AND (").append(String.join(" OR ", clauses)).append(')');
            }
        }
        sql.append(" GROUP BY GROUPING SETS ((ss.shift), ())");
        List<DashboardStaffShift> shifts = new ArrayList<>();
        AtomicInteger total = new AtomicInteger();
        jdbcTemplate.query(sql.toString(), (RowCallbackHandler) resultSet -> {
            String shift = resultSet.getString("shift");
            int count = resultSet.getInt("staff_count");
            if (shift == null) {
                // 空分组行 = 当日去重总人数（同一人跨班次只计 1 次）。
                total.set(count);
            } else {
                shifts.add(new DashboardStaffShift(shift, count));
            }
        }, arguments.toArray());
        return new DashboardStaffStats(total.get(), shifts);
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
