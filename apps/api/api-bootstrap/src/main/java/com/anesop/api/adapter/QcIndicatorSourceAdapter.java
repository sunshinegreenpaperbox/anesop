package com.anesop.api.adapter;

import com.anesop.m4.api.qc.QcIndicatorFacts;
import com.anesop.m4.api.qc.QcIndicatorFilter;
import com.anesop.m4.api.qc.QcIndicatorSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 质控指标计数只读投影（M4-M17-01）：{@link QcIndicatorSource} 的装配层实现。
 *
 * <p>为什么放在装配层：指标数据来自 M2 排程台次、M3 麻醉记录单/术前访视/三方核查、
 * M4 麻精专册与高值耗材流水，以及本域不良事件/病历抽查。m4-impl 不得访问其他模块的数据表
 * 或 Mapper（AGENTS「模块边界与依赖禁令」，ArchUnit 覆盖），因此本类与
 * {@code ReportCaseSourceAdapter} 同属 api-bootstrap 装配层，是**唯一**允许跨 schema 只读
 * 投影的位置（ADR-0022）。
 *
 * <p>约束（代码即约定）：
 * <ul>
 *   <li>只执行 {@code SELECT}，不写任何模块的表，不开启事务；</li>
 *   <li>只返回整数计数，不返回患者标识、自由文本或明细行（钻取在后续任务按指标单独开放）；</li>
 *   <li>数据范围条件由 {@link QcIndicatorFilter} 表达，本类原样拼装；</li>
 *   <li>纵深防御：非全院且 orgIds 为空时直接拒绝，绝不退化为全表统计（fail-closed）。</li>
 * </ul>
 */
@Component
public class QcIndicatorSourceAdapter implements QcIndicatorSource {

    /** 台次基数：非取消、未删除；术前访视/三方核查/记录单按申请或台次存在性计数。 */
    private static final String CASES_SQL = """
            SELECT count(*) AS case_count,
                   count(*) FILTER (
                       WHERE rec.id IS NOT NULL AND rec.ended_at IS NOT NULL
                   ) AS record_completed,
                   count(*) FILTER (
                       WHERE EXISTS (
                           SELECT 1 FROM m2_anes.preop_assessment p
                            WHERE p.application_id = a.id)
                   ) AS preop_count,
                   count(*) FILTER (
                       WHERE EXISTS (
                           SELECT 1 FROM m2_anes.three_way_check t
                            WHERE t.application_id = a.id AND t.sign_out_at IS NOT NULL)
                   ) AS three_way_count
              FROM m1_sched.schedule s
              JOIN m1_sched.surgery_application a
                ON a.id = s.application_id AND a.deleted = false
              LEFT JOIN m2_anes.anesthesia_record rec
                ON rec.schedule_id = s.id AND rec.deleted = false
             WHERE s.deleted = false
               AND s.status <> 'CANCELLED'
               AND s.start_at >= ?
               AND s.start_at < ?
            """;

    /** 麻精专册：双人签字完整（两人且不同人，姓名非空）。 */
    private static final String NARCOTIC_SQL = """
            SELECT count(*) AS total,
                   count(*) FILTER (
                       WHERE first_signer_id <> second_signer_id
                         AND btrim(first_signer_name) <> ''
                         AND btrim(second_signer_name) <> ''
                   ) AS dual_signed
              FROM m4_drug.narcotic_register
             WHERE usage_at >= ? AND usage_at < ?
            """;

    /** 不良事件上报数（本域表，仅计数）。 */
    private static final String ADVERSE_SQL = """
            SELECT count(*) AS total
              FROM m4_qc.adverse_event
             WHERE occurred_at >= ? AND occurred_at < ?
            """;

    /** 病历抽查：总数与得分率 ≥90% 的合格数（与指标阈值一致）。 */
    private static final String REVIEW_SQL = """
            SELECT count(*) AS total,
                   count(*) FILTER (
                       WHERE max_score > 0 AND total_score * 100.0 / max_score >= 90
                   ) AS passed
              FROM m4_qc.chart_review
             WHERE reviewed_at >= ? AND reviewed_at < ?
            """;

    /** 高值耗材出库流水：总数与带 UDI 编码的条数（高值口径见 ADR-0020【待确认】）。 */
    private static final String HIGH_VALUE_SQL = """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE sm.udi_code IS NOT NULL) AS with_udi
              FROM m4_material.stock_movement sm
              JOIN m4_material.material m ON m.id = sm.material_id
             WHERE sm.movement_type = 'OUT'
               AND m.high_value = true
               AND sm.occurred_at >= ?
               AND sm.occurred_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的 JdbcTemplate 单例由容器管理；适配器只执行只读查询，不修改其内部状态")
    public QcIndicatorSourceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public QcIndicatorFacts collect(QcIndicatorFilter filter) {
        if (filter == null) {
            return QcIndicatorFacts.empty();
        }
        if (!filter.unrestricted() && filter.orgIds().isEmpty()) {
            throw new AccessDeniedException("当前用户无可用质控数据范围");
        }
        CaseCounts cases = queryCases(filter);
        NarcoticCounts narcotic = queryNarcotic(filter);
        int adverse = queryAdverse(filter);
        ReviewCounts review = queryReview(filter);
        HighValueCounts highValue = queryHighValue(filter);
        return new QcIndicatorFacts(cases.caseCount(), cases.recordCompleted(),
                cases.preop(), cases.threeWay(), narcotic.total(), narcotic.dualSigned(),
                adverse, review.total(), review.passed(), highValue.total(),
                highValue.withUdi());
    }

    private CaseCounts queryCases(QcIndicatorFilter filter) {
        StringBuilder sql = new StringBuilder(CASES_SQL);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(filter.from()));
        arguments.add(Timestamp.from(filter.toExclusive()));
        if (filter.deptId() != null) {
            sql.append(" AND a.dept_id = ?");
            arguments.add(filter.deptId());
        }
        appendCaseScope(sql, arguments, filter);
        return jdbcTemplate.queryForObject(sql.toString(), (resultSet, rowNum) ->
                new CaseCounts(resultSet.getInt("case_count"),
                        resultSet.getInt("record_completed"),
                        resultSet.getInt("preop_count"),
                        resultSet.getInt("three_way_count")), arguments.toArray());
    }

    private NarcoticCounts queryNarcotic(QcIndicatorFilter filter) {
        StringBuilder sql = new StringBuilder(NARCOTIC_SQL);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(filter.from()));
        arguments.add(Timestamp.from(filter.toExclusive()));
        if (filter.deptId() != null) {
            sql.append(" AND department_id = ?");
            arguments.add(filter.deptId());
        }
        appendDeptScope(sql, arguments, filter);
        return jdbcTemplate.queryForObject(sql.toString(), (resultSet, rowNum) ->
                new NarcoticCounts(resultSet.getInt("total"),
                        resultSet.getInt("dual_signed")), arguments.toArray());
    }

    private int queryAdverse(QcIndicatorFilter filter) {
        StringBuilder sql = new StringBuilder(ADVERSE_SQL);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(filter.from()));
        arguments.add(Timestamp.from(filter.toExclusive()));
        if (filter.deptId() != null) {
            sql.append(" AND dept_id = ?");
            arguments.add(filter.deptId());
        }
        appendDeptScope(sql, arguments, filter, "dept_id");
        Integer total = jdbcTemplate.queryForObject(sql.toString(), Integer.class,
                arguments.toArray());
        return total == null ? 0 : total;
    }

    private ReviewCounts queryReview(QcIndicatorFilter filter) {
        StringBuilder sql = new StringBuilder(REVIEW_SQL);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(filter.from()));
        arguments.add(Timestamp.from(filter.toExclusive()));
        if (filter.deptId() != null) {
            sql.append(" AND dept_id = ?");
            arguments.add(filter.deptId());
        }
        appendDeptScope(sql, arguments, filter, "dept_id");
        return jdbcTemplate.queryForObject(sql.toString(), (resultSet, rowNum) ->
                new ReviewCounts(resultSet.getInt("total"),
                        resultSet.getInt("passed")), arguments.toArray());
    }

    private HighValueCounts queryHighValue(QcIndicatorFilter filter) {
        StringBuilder sql = new StringBuilder(HIGH_VALUE_SQL);
        List<Object> arguments = new ArrayList<>();
        arguments.add(Timestamp.from(filter.from()));
        arguments.add(Timestamp.from(filter.toExclusive()));
        if (filter.deptId() != null) {
            sql.append(" AND sm.department_id = ?");
            arguments.add(filter.deptId());
        }
        appendDeptScope(sql, arguments, filter, "sm.department_id");
        return jdbcTemplate.queryForObject(sql.toString(), (resultSet, rowNum) ->
                new HighValueCounts(resultSet.getInt("total"),
                        resultSet.getInt("with_udi")), arguments.toArray());
    }

    /** 台次范围：申请科室或院区命中（与排程分析的 DEPT/SITE 语义一致）。 */
    private static void appendCaseScope(StringBuilder sql, List<Object> arguments,
            QcIndicatorFilter filter) {
        if (filter.unrestricted()) {
            return;
        }
        String placeholders = placeholders(filter.orgIds().size());
        sql.append(" AND (a.dept_id IN (").append(placeholders)
                .append(") OR a.site_id IN (").append(placeholders).append("))");
        arguments.addAll(filter.orgIds());
        arguments.addAll(filter.orgIds());
    }

    private static void appendDeptScope(StringBuilder sql, List<Object> arguments,
            QcIndicatorFilter filter) {
        appendDeptScope(sql, arguments, filter, "department_id");
    }

    private static void appendDeptScope(StringBuilder sql, List<Object> arguments,
            QcIndicatorFilter filter, String column) {
        if (filter.unrestricted()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(placeholders(filter.orgIds().size())).append(')');
        arguments.addAll(filter.orgIds());
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private record CaseCounts(int caseCount, int recordCompleted, int preop, int threeWay) {
    }

    private record NarcoticCounts(int total, int dualSigned) {
    }

    private record ReviewCounts(int total, int passed) {
    }

    private record HighValueCounts(int total, int withUdi) {
    }

}
