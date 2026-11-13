package com.anesop.m1.impl.persistence.mapper;

import com.anesop.m1.impl.persistence.entity.ScheduleEntity;
import com.anesop.m1.impl.persistence.entity.ScheduleOverlapRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 排程台次 Mapper（{@code m1_sched.schedule}）。
 *
 * <p>{@link #selectOverlaps} 只取与候选窗口真实重叠的未取消台次（半开区间：相邻相接
 * {@code end_at = start_at} 不算重叠），并连带术间与申请的患者/术者键，供规则引擎判断
 * 同室、术者、患者与术间类型冲突。SQL 只是粗筛，规则内仍按半开区间复核。
 *
 * <p>{@code excludeScheduleId} 用 {@code <script>} 条件拼接而非 {@code ? IS NULL}，
 * 避免 PostgreSQL 无法推断 NULL 参数类型。
 */
@Mapper
public interface ScheduleMapper extends BaseMapper<ScheduleEntity> {

    /**
     * 查询与候选窗口重叠的未取消台次。
     *
     * @param startAt           候选开始（含）
     * @param endAt             候选结束（不含）
     * @param excludeScheduleId 调整时排除自身；创建传 null
     */
    @Select("""
            <script>
            SELECT s.id             AS id,
                   s.application_id AS application_id,
                   s.room_id        AS room_id,
                   s.start_at       AS start_at,
                   s.end_at         AS end_at,
                   s.sequence       AS sequence,
                   s.priority       AS priority,
                   s.status         AS status,
                   r.room_no        AS room_no,
                   r.room_type      AS room_type,
                   a.patient_id     AS patient_id,
                   a.surgeon_name   AS surgeon_name
            FROM m1_sched.schedule s
            JOIN m1_sched.operating_room r ON r.id = s.room_id
            JOIN m1_sched.surgery_application a ON a.id = s.application_id
            WHERE s.deleted = false
              AND s.status &lt;&gt; 'CANCELLED'
              AND s.start_at &lt; #{endAt}
              AND s.end_at &gt; #{startAt}
            <if test="excludeScheduleId != null">
              AND s.id &lt;&gt; #{excludeScheduleId}
            </if>
            ORDER BY s.start_at, s.room_id
            </script>
            """)
    List<ScheduleOverlapRow> selectOverlaps(@Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt,
            @Param("excludeScheduleId") UUID excludeScheduleId);

    /**
     * 统计候选窗口内同术间的未取消台次数（急诊缺省台次序号用；按术间过滤，避免把其他术间的
     * 台次计入序号）。
     */
    @Select("""
            SELECT count(*)
            FROM m1_sched.schedule s
            WHERE s.deleted = false
              AND s.status &lt;&gt; 'CANCELLED'
              AND s.room_id = #{roomId}
              AND s.start_at &lt; #{endAt}
              AND s.end_at &gt; #{startAt}
            """)
    int countOverlapsInRoom(@Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt,
            @Param("roomId") UUID roomId);

    /**
     * 事务内锁定台次行（{@code SELECT ... FOR UPDATE}）：急诊抢占在锁内复检状态/优先级/术间/
     * 时段，避免与并发改台/停台交叉；也会与 {@code updateById} 的乐观锁形成双重保护。
     *
     * <p>锁顺序：advisory 人员/患者锁 → 术间行锁 → 被抢占台次行锁；禁止反向获取。
     * 逻辑删除行不返回（视为不存在）。
     */
    @Select("""
            SELECT id, application_id, room_id, start_at, end_at, sequence, priority, status,
                   reason, created_at, created_by, updated_at, updated_by, version, deleted
            FROM m1_sched.schedule
            WHERE id = #{id} AND deleted = false
            FOR UPDATE
            """)
    ScheduleEntity selectByIdForUpdate(@Param("id") UUID id);

    /**
     * 事务级 advisory lock（{@code pg_advisory_xact_lock}）：按人员/患者维度串行化跨术间排程，
     * 事务提交或回滚时自动释放。
     *
     * <p>调用方必须按确定性顺序（字典序）获取多个 key，避免两事务交叉加锁造成死锁；
     * key 使用 {@code hashtext} 压缩为 32 位整数并升级为 bigint，哈希碰撞只会导致多余等待，
     * 不影响正确性。
     *
     * @param key 加锁键（形如 {@code schedule:surgeon:...} / {@code schedule:patient:...}）
     * @return 固定返回 1（SELECT 语句需要返回值以驱动 MyBatis 执行）
     */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(#{key}))) AS lock")
    Integer lockAdvisoryXact(@Param("key") String key);
}
