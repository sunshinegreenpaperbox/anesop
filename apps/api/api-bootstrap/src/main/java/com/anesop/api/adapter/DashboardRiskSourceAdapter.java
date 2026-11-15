package com.anesop.api.adapter;

import com.anesop.m5.api.dashboard.DashboardRiskBucket;
import com.anesop.m5.api.dashboard.DashboardRiskSource;
import com.anesop.m5.api.dashboard.DashboardScope;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
 * 看板风险分层只读投影（M5-M18-02）：{@link DashboardRiskSource} 的装配层实现。
 *
 * <p>单条聚合 {@code SELECT}：以 M1 台次为骨架，按 {@code (asa_class, risk_level)} 分组计数；
 * 术前评估取该申请的**最新一份已最终化**（`status=FINALIZED` 且未删除）版本，保证临床修订
 * 只影响最新口径。未评估台次归入两个 NULL 的桶（不进入分布，只用于统计未评估数）。
 *
 * <p>**不返回**患者标识、申请/台次标识与单项评分明细（ASA 分级与风险等级只以计数形式出现）；
 * 单条 SELECT、不写任何表、不做时区换算。
 */
@Component
public class DashboardRiskSourceAdapter implements DashboardRiskSource {

    private static final String AGGREGATE_SQL = """
            SELECT pa.asa_class AS asa_class,
                   pa.risk_level AS risk_level,
                   COUNT(*) AS case_count
              FROM m1_sched.schedule s
              JOIN m1_sched.surgery_application a
                ON a.id = s.application_id AND a.deleted = false
              LEFT JOIN LATERAL (
                   SELECT p.asa_class, p.risk_level
                     FROM m2_anes.preop_assessment p
                    WHERE p.application_id = a.id
                      AND p.status = 'FINALIZED'
                      AND p.deleted = false
                    ORDER BY p.finalized_at DESC, p.id DESC
                    LIMIT 1
              ) pa ON true
             WHERE s.deleted = false
               AND s.status <> 'CANCELLED'
               AND s.start_at >= ?
               AND s.start_at < ?
            """;

    private static final String SELF_EXISTS = """
             EXISTS (
                 SELECT 1
                   FROM m2_anes.anesthesia_record rec
                  WHERE rec.schedule_id = s.id
                    AND rec.deleted = false
                    AND rec.created_by = ?
             )
            """;

    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的 JdbcTemplate 单例由容器管理；适配器只执行只读查询，不修改其内部状态")
    public DashboardRiskSourceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<DashboardRiskBucket> findRiskBuckets(Instant from, Instant toExclusive,
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
                clauses.add(SELF_EXISTS);
                arguments.add(selfUserId);
            }
            if (!clauses.isEmpty()) {
                sql.append(" AND (").append(String.join(" OR ", clauses)).append(')');
            }
        }
        sql.append(" GROUP BY pa.asa_class, pa.risk_level");
        return jdbcTemplate.query(sql.toString(), (resultSet, rowNum) -> new DashboardRiskBucket(
                resultSet.getString("asa_class"),
                resultSet.getString("risk_level"),
                resultSet.getInt("case_count")), arguments.toArray());
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
