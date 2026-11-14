package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.ChartReviewEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 病历质量抽查 Mapper（{@code m4_qc.chart_review}，V25）。
 *
 * <p>评分内容 append-only（守卫 55000）：只有 {@code insert} 与 {@code updateStatus}
 * （逐级前移 + {@code version+1}）。导出投影不含缺陷描述原文，时间统一转 Asia/Shanghai 文本。
 */
@Mapper
public interface ChartReviewMapper extends BaseMapper<ChartReviewEntity> {

    @Insert("""
            INSERT INTO m4_qc.chart_review
                (id, review_no, schedule_id, record_id, dept_id, dept_name, reviewer_id,
                 reviewer_name, reviewed_at, items, total_score, max_score, grade, defect_count,
                 status, version, created_at, created_by, updated_at, updated_by)
            VALUES
                (#{id}, #{reviewNo}, #{scheduleId}, #{recordId}, #{deptId}, #{deptName},
                 #{reviewerId}, #{reviewerName}, #{reviewedAt}, #{items}::jsonb, #{totalScore},
                 #{maxScore}, #{grade}, #{defectCount}, 'OPEN', 1, #{createdAt}, #{createdBy},
                 #{createdAt}, #{createdBy})
            """)
    int insertReview(ChartReviewEntity review);

    @Select("""
            SELECT c.*,
                   (SELECT rt.id FROM m4_qc.rectification rt WHERE rt.review_id = c.id)
                       AS rectification_id
              FROM m4_qc.chart_review c
             WHERE c.id = #{id}
            """)
    ChartReviewEntity selectReviewById(@Param("id") UUID id);

    @Select("""
            <script>
            SELECT c.*,
                   (SELECT rt.id FROM m4_qc.rectification rt WHERE rt.review_id = c.id)
                       AS rectification_id
              FROM m4_qc.chart_review c
            <where>
              <if test="from != null">AND c.reviewed_at &gt;= #{from}</if>
              <if test="toExclusive != null">AND c.reviewed_at &lt; #{toExclusive}</if>
              <if test="status != null and status != ''">AND c.status = #{status}</if>
              <if test="deptId != null">AND c.dept_id = #{deptId}::uuid</if>
              <if test="allowedDeptIds != null">
                AND c.dept_id IN
                <foreach collection="allowedDeptIds" item="dept" open="(" separator="," close=")">
                  #{dept}::uuid
                </foreach>
              </if>
            </where>
            ORDER BY c.reviewed_at DESC, c.review_no DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ChartReviewEntity> selectPage(@Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive,
            @Param("status") String status,
            @Param("deptId") String deptId,
            @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT count(*) FROM m4_qc.chart_review c
            <where>
              <if test="from != null">AND c.reviewed_at &gt;= #{from}</if>
              <if test="toExclusive != null">AND c.reviewed_at &lt; #{toExclusive}</if>
              <if test="status != null and status != ''">AND c.status = #{status}</if>
              <if test="deptId != null">AND c.dept_id = #{deptId}::uuid</if>
              <if test="allowedDeptIds != null">
                AND c.dept_id IN
                <foreach collection="allowedDeptIds" item="dept" open="(" separator="," close=")">
                  #{dept}::uuid
                </foreach>
              </if>
            </where>
            </script>
            """)
    long countPage(@Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive,
            @Param("status") String status,
            @Param("deptId") String deptId,
            @Param("allowedDeptIds") List<String> allowedDeptIds);

    /** 状态机前移：影响行数 0 → 409（并发/状态冲突）；触发器校验逐级与 version+1。 */
    @Update("""
            UPDATE m4_qc.chart_review
               SET status = #{toStatus},
                   version = version + 1,
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy}
             WHERE id = #{id}
               AND status = #{fromStatus}
               AND version = #{expectedVersion}
            """)
    int updateStatus(@Param("id") UUID id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("expectedVersion") int expectedVersion,
            @Param("updatedAt") Instant updatedAt, @Param("updatedBy") UUID updatedBy);

    /** 导出投影：只取抽查号/时间/科室/评分/等级/缺陷数/状态/整改进度/期限/闭环时间。 */
    @Select("""
            <script>
            SELECT c.review_no,
                   to_char(c.reviewed_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS reviewed_at,
                   c.dept_id,
                   c.dept_name,
                   c.total_score,
                   c.max_score,
                   c.grade,
                   c.defect_count,
                   c.status,
                   COALESCE(rt.status, 'NONE') AS rectification_status,
                   rt.responsible_name,
                   to_char(rt.due_date, 'YYYY-MM-DD') AS due_date,
                   to_char(rt.closed_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS closed_at
              FROM m4_qc.chart_review c
              LEFT JOIN m4_qc.rectification rt ON rt.review_id = c.id
            <where>
              <if test="from != null">AND c.reviewed_at &gt;= #{from}</if>
              <if test="toExclusive != null">AND c.reviewed_at &lt; #{toExclusive}</if>
              <if test="deptId != null">AND c.dept_id = #{deptId}::uuid</if>
              <if test="allowedDeptIds != null">
                AND c.dept_id IN
                <foreach collection="allowedDeptIds" item="dept" open="(" separator="," close=")">
                  #{dept}::uuid
                </foreach>
              </if>
            </where>
            ORDER BY c.reviewed_at DESC, c.review_no DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> selectExportRows(@Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive,
            @Param("deptId") String deptId,
            @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("limit") int limit);
}
