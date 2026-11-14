package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.AdverseRcaEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * RCA Mapper（{@code m4_qc.adverse_rca}，V25）。
 *
 * <p>内容 append-only（守卫 55000）：只有 {@code insert} 与两个一次性写入语句
 * （{@code markReviewed} 写复核字段、{@code markClosed} 写闭环），不提供内容更新/删除。
 * 两个写入语句都带 “目标字段仍为 NULL” 的前置条件，影响行数 0 → 服务层 409。
 */
@Mapper
public interface AdverseRcaMapper extends BaseMapper<AdverseRcaEntity> {

    @Insert("""
            INSERT INTO m4_qc.adverse_rca
                (id, event_id, event_no, timeline, root_cause, corrective_action, responsible_name,
                 due_date, status, created_at, created_by)
            VALUES
                (#{id}, #{eventId}, #{eventNo}, #{timeline}::jsonb, #{rootCause},
                 #{correctiveAction}, #{responsibleName}, #{dueDate}, 'OPEN', #{createdAt},
                 #{createdBy})
            """)
    int insertRca(AdverseRcaEntity rca);

    @Select("SELECT * FROM m4_qc.adverse_rca WHERE id = #{id}")
    AdverseRcaEntity selectRcaById(@Param("id") UUID id);

    @Select("SELECT * FROM m4_qc.adverse_rca WHERE event_id = #{eventId}")
    AdverseRcaEntity selectByEventId(@Param("eventId") UUID eventId);

    /** 阶段 1：写复核字段（一次性；reviewed_at 已存在时影响行数 0）。 */
    @Update("""
            UPDATE m4_qc.adverse_rca
               SET reviewer_id = #{reviewerId},
                   reviewer_name = #{reviewerName},
                   reviewed_at = #{reviewedAt},
                   review_conclusion = #{conclusion}
             WHERE id = #{id}
               AND reviewed_at IS NULL
               AND status = 'OPEN'
            """)
    int markReviewed(@Param("id") UUID id, @Param("reviewerId") UUID reviewerId,
            @Param("reviewerName") String reviewerName, @Param("reviewedAt") Instant reviewedAt,
            @Param("conclusion") String conclusion);

    /** 阶段 2：闭环（一次性；必须已复核且未闭环）。 */
    @Update("""
            UPDATE m4_qc.adverse_rca
               SET status = 'CLOSED',
                   closed_at = #{closedAt}
             WHERE id = #{id}
               AND status = 'OPEN'
               AND reviewed_at IS NOT NULL
               AND closed_at IS NULL
            """)
    int markClosed(@Param("id") UUID id, @Param("closedAt") Instant closedAt);
}
