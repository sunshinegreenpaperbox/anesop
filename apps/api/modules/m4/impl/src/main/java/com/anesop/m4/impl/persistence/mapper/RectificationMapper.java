package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.RectificationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 整改 Mapper（{@code m4_qc.rectification}，V25）。
 *
 * <p>内容 append-only（守卫 55000）：只有 {@code insert} 与 {@code markClosed}；
 * 闭环字段一次性写入，影响行数 0 → 服务层 409。
 */
@Mapper
public interface RectificationMapper extends BaseMapper<RectificationEntity> {

    @Insert("""
            INSERT INTO m4_qc.rectification
                (id, review_id, review_no, corrective_action, responsible_name, due_date, status,
                 created_at, created_by)
            VALUES
                (#{id}, #{reviewId}, #{reviewNo}, #{correctiveAction}, #{responsibleName},
                 #{dueDate}, 'OPEN', #{createdAt}, #{createdBy})
            """)
    int insertRectification(RectificationEntity rectification);

    @Select("SELECT * FROM m4_qc.rectification WHERE id = #{id}")
    RectificationEntity selectRectificationById(@Param("id") UUID id);

    @Select("SELECT * FROM m4_qc.rectification WHERE review_id = #{reviewId}")
    RectificationEntity selectByReviewId(@Param("reviewId") UUID reviewId);

    /** 闭环（一次性）：写复核字段与闭环时间；已闭环时影响行数 0。 */
    @Update("""
            UPDATE m4_qc.rectification
               SET status = 'CLOSED',
                   reviewer_id = #{reviewerId},
                   reviewer_name = #{reviewerName},
                   reviewed_at = #{reviewedAt},
                   review_conclusion = #{conclusion},
                   closed_at = #{reviewedAt}
             WHERE id = #{id}
               AND status = 'OPEN'
               AND reviewed_at IS NULL
               AND closed_at IS NULL
            """)
    int markClosed(@Param("id") UUID id, @Param("reviewerId") UUID reviewerId,
            @Param("reviewerName") String reviewerName, @Param("reviewedAt") Instant reviewedAt,
            @Param("conclusion") String conclusion);
}
