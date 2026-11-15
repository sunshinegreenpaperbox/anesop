package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.ReportSnapshotEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 报表锁定快照 Mapper（{@code m4_report.report_snapshot}，V28）。
 *
 * <p>只提供 INSERT 与只读查询：快照 append-only（库层触发器兜底 55000），
 * 服务层不提供更新/删除路径。同参数命中以唯一键 (report, period, params_hash) 兜底，
 * 并发重复锁定由 {@code DuplicateKeyException} 映射 409。
 */
@Mapper
public interface ReportSnapshotMapper extends BaseMapper<ReportSnapshotEntity> {

    @Insert("""
            INSERT INTO m4_report.report_snapshot
                (id, report, period, from_date, to_date, params, params_hash, payload,
                 payload_hash, data_scope, scope_hash, row_count, locked_by, locked_by_name,
                 locked_at)
            VALUES
                (#{id}, #{report}, #{period}, #{fromDate}, #{toDate}, #{params}::jsonb,
                 #{paramsHash}, #{payload}::jsonb, #{payloadHash}, #{dataScope}, #{scopeHash},
                 #{rowCount}, #{lockedBy}, #{lockedByName}, #{lockedAt})
            """)
    int insertSnapshot(ReportSnapshotEntity snapshot);

    /** 同参数快照（report + params_hash 唯一命中；排序兜底防止历史重复数据）。 */
    @Select("""
            SELECT * FROM m4_report.report_snapshot
             WHERE report = #{report} AND params_hash = #{paramsHash}
             ORDER BY locked_at DESC, id
             LIMIT 1
            """)
    ReportSnapshotEntity selectByReportAndParamsHash(@Param("report") String report,
            @Param("paramsHash") String paramsHash);

    @Select("""
            <script>
            SELECT * FROM m4_report.report_snapshot
            <where>
              <if test="report != null and report != ''">
                report = #{report}
              </if>
            </where>
            ORDER BY locked_at DESC, id
            LIMIT #{limit}
            </script>
            """)
    List<ReportSnapshotEntity> selectLocks(@Param("report") String report,
            @Param("limit") int limit);

    @Select("SELECT * FROM m4_report.report_snapshot WHERE id = #{id}")
    ReportSnapshotEntity selectById(@Param("id") UUID id);
}
