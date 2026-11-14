package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.AdverseEventEntity;
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
 * 不良事件 Mapper（{@code m4_qc.adverse_event}，V25）。
 *
 * <p>业务内容 append-only（数据库守卫 55000）：本 Mapper 只有 {@code insert} 与
 * {@code updateStatus}（逐级前移 + {@code version+1}）；不提供内容更新/删除语句。
 * 数据范围由服务层计算：{@code allowedDeptIds == null} 表示全院，否则按科室过滤；
 * {@code deptId} 为收窄条件。列表附带 {@code rca_id} 只读投影（子查询，非物理列）。
 */
@Mapper
public interface AdverseEventMapper extends BaseMapper<AdverseEventEntity> {

    @Insert("""
            INSERT INTO m4_qc.adverse_event
                (id, event_no, event_type, severity, occurred_at, dept_id, dept_name, schedule_id,
                 description, reporter_id, reporter_name, anonymous, status, status_changed_at,
                 version, created_at, created_by)
            VALUES
                (#{id}, #{eventNo}, #{eventType}, #{severity}, #{occurredAt}, #{deptId},
                 #{deptName}, #{scheduleId}, #{description}, #{reporterId}, #{reporterName},
                 #{anonymous}, 'REPORTED', now(), 1, #{createdAt}, #{createdBy})
            """)
    int insertEvent(AdverseEventEntity event);

    @Select("""
            SELECT e.*,
                   (SELECT r.id FROM m4_qc.adverse_rca r WHERE r.event_id = e.id) AS rca_id
              FROM m4_qc.adverse_event e
             WHERE e.id = #{id}
            """)
    AdverseEventEntity selectEventById(@Param("id") UUID id);

    @Select("""
            <script>
            SELECT e.*,
                   (SELECT r.id FROM m4_qc.adverse_rca r WHERE r.event_id = e.id) AS rca_id
              FROM m4_qc.adverse_event e
            <where>
              <if test="from != null">AND e.occurred_at &gt;= #{from}</if>
              <if test="toExclusive != null">AND e.occurred_at &lt; #{toExclusive}</if>
              <if test="status != null and status != ''">AND e.status = #{status}</if>
              <if test="deptId != null">AND e.dept_id = #{deptId}::uuid</if>
              <if test="allowedDeptIds != null">
                AND e.dept_id IN
                <foreach collection="allowedDeptIds" item="dept" open="(" separator="," close=")">
                  #{dept}::uuid
                </foreach>
              </if>
            </where>
            ORDER BY e.occurred_at DESC, e.event_no DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AdverseEventEntity> selectPage(@Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive,
            @Param("status") String status,
            @Param("deptId") String deptId,
            @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT count(*)
              FROM m4_qc.adverse_event e
            <where>
              <if test="from != null">AND e.occurred_at &gt;= #{from}</if>
              <if test="toExclusive != null">AND e.occurred_at &lt; #{toExclusive}</if>
              <if test="status != null and status != ''">AND e.status = #{status}</if>
              <if test="deptId != null">AND e.dept_id = #{deptId}::uuid</if>
              <if test="allowedDeptIds != null">
                AND e.dept_id IN
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

    /**
     * 状态机前移：{@code WHERE status = fromStatus AND version = expectedVersion}，
     * 影响行数 0 表示并发/状态冲突（服务层 409）；触发器校验逐级与 version+1。
     */
    @Update("""
            UPDATE m4_qc.adverse_event
               SET status = #{toStatus},
                   version = #{expectedVersion} + 1
             WHERE id = #{id}
               AND status = #{fromStatus}
               AND version = #{expectedVersion}
            """)
    int updateStatus(@Param("id") UUID id, @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus, @Param("expectedVersion") int expectedVersion);

    /**
     * 导出投影：只取标识/类型/等级/时间/科室/状态/RCA 状态/责任人/期限/复核人/闭环时间，
     * **不含** {@code description}、根因、整改等自由文本；时间统一转 Asia/Shanghai 文本。
     */
    @Select("""
            <script>
            SELECT e.event_no,
                   e.event_type,
                   e.severity,
                   to_char(e.occurred_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS occurred_at,
                   e.dept_id,
                   e.dept_name,
                   e.status,
                   COALESCE(r.status, 'NONE') AS rca_status,
                   r.responsible_name,
                   to_char(r.due_date, 'YYYY-MM-DD') AS due_date,
                   r.reviewer_name,
                   to_char(r.closed_at AT TIME ZONE 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI') AS closed_at,
                   e.anonymous
              FROM m4_qc.adverse_event e
              LEFT JOIN m4_qc.adverse_rca r ON r.event_id = e.id
            <where>
              <if test="from != null">AND e.occurred_at &gt;= #{from}</if>
              <if test="toExclusive != null">AND e.occurred_at &lt; #{toExclusive}</if>
              <if test="deptId != null">AND e.dept_id = #{deptId}::uuid</if>
              <if test="allowedDeptIds != null">
                AND e.dept_id IN
                <foreach collection="allowedDeptIds" item="dept" open="(" separator="," close=")">
                  #{dept}::uuid
                </foreach>
              </if>
            </where>
            ORDER BY e.occurred_at DESC, e.event_no DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> selectExportRows(@Param("from") Instant from,
            @Param("toExclusive") Instant toExclusive,
            @Param("deptId") String deptId,
            @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("limit") int limit);
}
