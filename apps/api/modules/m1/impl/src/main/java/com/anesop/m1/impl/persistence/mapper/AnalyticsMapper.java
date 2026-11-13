package com.anesop.m1.impl.persistence.mapper;

import com.anesop.m1.impl.analytics.CancellationRow;
import com.anesop.m1.impl.analytics.FirstCaseRow;
import com.anesop.m1.impl.analytics.RoomUsageRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 排程分析只读 Mapper（M2-M6-01，schema m1_sched）。
 *
 * <p>所有查询只做聚合，不返回患者姓名/住院号/诊断；术间范围由服务层先解析为
 * {@code roomIds}（数据范围 fail-closed 在服务层处理），SQL 只按 {@code IN} 过滤，
 * 避免在 SQL 中拼接权限条件造成越权。
 *
 * <p>时间参数为 UTC {@link Instant}，区间边界已在服务层按 {@code Asia/Shanghai} 本地日期
 * 换算为半开区间 {@code [fromInstant, toExclusive)}。
 */
@Mapper
public interface AnalyticsMapper {

    /**
     * 利用率：非取消台次与查询区间的重叠分钟数（跨日/跨区间裁剪）与台次数。
     */
    @Select("""
            <script>
            SELECT s.room_id AS room_id,
                   COUNT(*) AS case_count,
                   COALESCE(SUM(EXTRACT(EPOCH FROM (
                       LEAST(s.end_at, #{toExclusive}) - GREATEST(s.start_at, #{fromInstant})
                   )) / 60.0), 0) AS total_minutes
            FROM m1_sched.schedule s
            WHERE s.deleted = false
              AND s.status &lt;&gt; 'CANCELLED'
              AND s.start_at &lt; #{toExclusive}
              AND s.end_at &gt; #{fromInstant}
              AND s.room_id IN
              <foreach collection="roomIds" item="roomId" open="(" separator="," close=")">
                #{roomId}
              </foreach>
            GROUP BY s.room_id
            </script>
            """)
    List<RoomUsageRow> selectRoomUsage(@Param("fromInstant") Instant fromInstant,
            @Param("toExclusive") Instant toExclusive,
            @Param("roomIds") List<UUID> roomIds);

    /**
     * 首台：每术间每 Asia/Shanghai 本地日<strong>计划开始最早</strong>的台次（不论状态）。
     *
     * <p>取消/未开始（或状态未推进到 IN_PROGRESS/COMPLETED）的计划首台由服务层计入
     * {@code excludedDays} 并从准点分母排除，不以当日后续台次替代；MVP 以
     * {@code updated_at} 近似实际开台时间。
     */
    @Select("""
            <script>
            SELECT t.room_id AS room_id,
                   t.start_at AS start_at,
                   t.updated_at AS updated_at,
                   t.status AS status
            FROM (
                SELECT s.room_id,
                       s.start_at,
                       s.updated_at,
                       s.status,
                       ROW_NUMBER() OVER (
                           PARTITION BY s.room_id,
                               (s.start_at AT TIME ZONE 'Asia/Shanghai')::date
                           ORDER BY s.start_at, s.sequence, s.id
                       ) AS rn
                FROM m1_sched.schedule s
                WHERE s.deleted = false
                  AND s.start_at &gt;= #{fromInstant}
                  AND s.start_at &lt; #{toExclusive}
                  AND s.room_id IN
                  <foreach collection="roomIds" item="roomId" open="(" separator="," close=")">
                    #{roomId}
                  </foreach>
            ) t
            WHERE t.rn = 1
            ORDER BY t.room_id, t.start_at
            </script>
            """)
    List<FirstCaseRow> selectFirstCases(@Param("fromInstant") Instant fromInstant,
            @Param("toExclusive") Instant toExclusive,
            @Param("roomIds") List<UUID> roomIds);

    /**
     * 取消率：区间内（按计划开始日期归属）全部台次的状态与最近 STOP 原因码。
     *
     * <p>无 STOP 变更记录的历史数据归 {@code UNSPECIFIED}，不臆造原因。
     */
    @Select("""
            <script>
            SELECT s.room_id AS room_id,
                   s.status AS status,
                   COALESCE(c.reason_code, 'UNSPECIFIED') AS reason_code
            FROM m1_sched.schedule s
            LEFT JOIN LATERAL (
                SELECT cr.reason_code
                FROM m1_sched.schedule_change_request cr
                WHERE cr.schedule_id = s.id
                  AND cr.change_type = 'STOP'
                ORDER BY cr.requested_at DESC, cr.id DESC
                LIMIT 1
            ) c ON true
            WHERE s.deleted = false
              AND s.start_at &gt;= #{fromInstant}
              AND s.start_at &lt; #{toExclusive}
              AND s.room_id IN
              <foreach collection="roomIds" item="roomId" open="(" separator="," close=")">
                #{roomId}
              </foreach>
            ORDER BY s.room_id, s.start_at
            </script>
            """)
    List<CancellationRow> selectCancellationRows(@Param("fromInstant") Instant fromInstant,
            @Param("toExclusive") Instant toExclusive,
            @Param("roomIds") List<UUID> roomIds);
}
