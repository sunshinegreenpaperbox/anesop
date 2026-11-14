package com.anesop.api.adapter;

import com.anesop.m4.api.qc.QcCaseReferenceSource;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 质控逻辑关联校验只读投影（M4-M17-01，评审 S-3）：{@link QcCaseReferenceSource} 的装配层实现。
 *
 * <p>只执行两条只读 SELECT，判定台次/麻醉记录单是否存在（未删除）及其所属科室；
 * 不读取患者标识、不写任何模块的表。SQL 放在装配层的原因同 {@code QcIndicatorSourceAdapter}
 * （AGENTS 模块边界：m4-impl 不访问其他模块表/Mapper）。
 *
 * <p>fail-closed：不存在/已删除返回空（服务层 422）；跨科室由服务层 403。
 */
@Component
public class QcCaseReferenceAdapter implements QcCaseReferenceSource {

    /** 台次所属科室：排程 → 手术申请；两边都必须未删除。 */
    private static final String SCHEDULE_DEPT_SQL = """
            SELECT a.dept_id
              FROM m1_sched.schedule s
              JOIN m1_sched.surgery_application a ON a.id = s.application_id
             WHERE s.id = ?
               AND s.deleted = false
               AND a.deleted = false
            """;

    /** 麻醉记录单所属科室：记录单 → 手术申请；两边都必须未删除。 */
    private static final String RECORD_DEPT_SQL = """
            SELECT a.dept_id
              FROM m2_anes.anesthesia_record r
              JOIN m1_sched.surgery_application a ON a.id = r.application_id
             WHERE r.id = ?
               AND r.deleted = false
               AND a.deleted = false
            """;

    private final JdbcTemplate jdbcTemplate;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的 JdbcTemplate 单例由容器管理；适配器只执行只读查询")
    public QcCaseReferenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UUID> scheduleDepartment(UUID scheduleId) {
        return firstDepartment(SCHEDULE_DEPT_SQL, scheduleId);
    }

    @Override
    public Optional<UUID> recordDepartment(UUID recordId) {
        return firstDepartment(RECORD_DEPT_SQL, recordId);
    }

    private Optional<UUID> firstDepartment(String sql, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        List<UUID> departments = jdbcTemplate.query(sql,
                (resultSet, rowNum) -> resultSet.getObject("dept_id", UUID.class), id);
        return departments.isEmpty() ? Optional.empty() : Optional.of(departments.get(0));
    }
}
