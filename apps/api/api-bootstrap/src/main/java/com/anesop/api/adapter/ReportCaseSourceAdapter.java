package com.anesop.api.adapter;

import com.anesop.m4.api.report.ReportCaseFact;
import com.anesop.m4.api.report.ReportCaseFilter;
import com.anesop.m4.api.report.ReportCaseSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 报表统计单元只读投影（M4-M16-01）：{@link ReportCaseSource} 的装配层实现。
 *
 * <p>为什么放在装配层：工作量报表的统计单元来自 M1 排程台次与 M2 麻醉记录单，而
 * m4-impl 不得访问其他模块的数据表/Mapper，也不能反向依赖 m1-api/m2-api（AGENTS
 * 「模块边界与依赖禁令」，ArchUnit 覆盖）。本类与 {@code M2RecordVisibilityAdapter} 同属
 * api-bootstrap 装配层，是**唯一**允许跨 schema 只读投影的位置（ADR-0021）。
 *
 * <p>约束（代码即约定）：
 * <ul>
 *   <li>只执行单条 {@code SELECT}，不写任何模块的表，不开启事务；</li>
 *   <li>不复制 m1/m2 的 Mapper 或实体，只做字段投影，避免绕过模块 API 的业务规则；</li>
 *   <li>不做时区换算：按传入 Instant 过滤 {@code start_at}，本地日期归属由服务层判定；</li>
 *   <li>不返回 PHI：只有台次/术间/科室/术式标识、院内在册人员工号姓名与记录单起止时间；</li>
 *   <li>数据范围条件由 {@link ReportCaseFilter} 表达，本类原样拼装，不解释越权语义；</li>
 *   <li>纵深防御：非全院且 orgIds/roomIds/self 全空时直接拒绝，绝不退化为全表查询。</li>
 * </ul>
 */
@Component
public class ReportCaseSourceAdapter implements ReportCaseSource {

    /**
     * 投影 SQL：一台次一行。台次口径（未删除、非 CANCELLED）由服务层与 M1 共享语义，
     * 这里只按传入时间区间过滤计划开始时间；麻醉记录单 0..1 行（uk_anesthesia_record_schedule），
     * 无记录单时起止时间为 NULL（服务层计 0 并计入 unrecordedCaseCount）。
     */
    private static final String PROJECTION_SQL = """
            SELECT s.id AS schedule_id,
                   s.room_id,
                   r.room_no,
                   a.dept_id,
                   a.dept_name,
                   a.surgery_code,
                   a.surgery_name,
                   p.id AS staff_id,
                   p.staff_no,
                   p.name AS staff_name,
                   rec.started_at,
                   rec.ended_at
              FROM m1_sched.schedule s
              JOIN m1_sched.surgery_application a
                ON a.id = s.application_id AND a.deleted = false
              JOIN m1_sched.operating_room r ON r.id = s.room_id
              LEFT JOIN m2_anes.anesthesia_record rec
                ON rec.schedule_id = s.id AND rec.deleted = false
              LEFT JOIN m4_staff.staff_profile p ON p.user_id = rec.created_by
             WHERE s.deleted = false
               AND s.status <> 'CANCELLED'
               AND s.start_at >= ?
               AND s.start_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的 JdbcTemplate 单例由容器管理；适配器只执行只读查询，不修改其内部状态")
    public ReportCaseSourceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ReportCaseFact> findCases(ReportCaseFilter filter) {
        if (filter == null) {
            return List.of();
        }
        // 纵深防御：服务层已 fail-closed，但投影层不得在“非全院且无任何范围条件”时
        // 退化为不带 WHERE 的全表查询（fail-open）。这里再拦一次，避免未来调用方
        // 绕过服务层直接使用端口时泄露全院数据。
        if (!filter.unrestricted()
                && filter.orgIds().isEmpty()
                && filter.roomIds().isEmpty()
                && !filter.selfScoped()) {
            throw new AccessDeniedException("当前用户无可用报表数据范围");
        }
        StringBuilder sql = new StringBuilder(PROJECTION_SQL);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(filter.from()));
        arguments.add(Timestamp.from(filter.toExclusive()));
        if (!filter.unrestricted()) {
            List<String> clauses = new ArrayList<>();
            if (!filter.orgIds().isEmpty()) {
                // 科室/院区范围：申请科室或院区命中即可（与 M1 排程分析的 DEPT/SITE 语义一致）。
                clauses.add("(a.dept_id IN (" + placeholders(filter.orgIds().size())
                        + ") OR a.site_id IN (" + placeholders(filter.orgIds().size()) + "))");
                arguments.addAll(filter.orgIds());
                arguments.addAll(filter.orgIds());
            }
            if (!filter.roomIds().isEmpty()) {
                clauses.add("s.room_id IN (" + placeholders(filter.roomIds().size()) + ")");
                arguments.addAll(filter.roomIds());
            }
            if (filter.selfScoped()) {
                // SELF：本人创建的麻醉记录单（无记录单的台次不属于任何个人）。
                clauses.add("rec.created_by = ?");
                arguments.add(filter.selfUserId());
            }
            if (!clauses.isEmpty()) {
                sql.append(" AND (").append(String.join(" OR ", clauses)).append(')');
            }
        }
        if (filter.deptId() != null) {
            sql.append(" AND a.dept_id = ?");
            arguments.add(filter.deptId());
        }
        sql.append(" ORDER BY s.start_at, s.id");
        return jdbcTemplate.query(sql.toString(),
                (resultSet, rowNum) -> mapRow(resultSet), arguments.toArray());
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static ReportCaseFact mapRow(ResultSet resultSet) throws SQLException {
        return new ReportCaseFact(
                resultSet.getString("schedule_id"),
                uuid(resultSet, "room_id"),
                resultSet.getString("room_no"),
                uuid(resultSet, "dept_id"),
                resultSet.getString("dept_name"),
                resultSet.getString("surgery_code"),
                resultSet.getString("surgery_name"),
                uuid(resultSet, "staff_id"),
                resultSet.getString("staff_no"),
                resultSet.getString("staff_name"),
                instant(resultSet, "started_at"),
                instant(resultSet, "ended_at"));
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
