package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.RecordEventEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 时间轴事件 Mapper（{@code m2_anes.record_event}，V13）。
 *
 * <p>append-only：只提供 INSERT 与查询，不提供 UPDATE/DELETE（数据库触发器为第二道防线）。
 * seq 由服务端在锁定记录单行（{@code SELECT ... FOR UPDATE}）后分配，并发追加被串行化；
 * {@code (record_id, seq)} 唯一索引仅作最后兜底，命中时映射 409 而不是让 PG 事务 aborted。
 */
@Mapper
public interface RecordEventMapper extends BaseMapper<RecordEventEntity> {

    @Insert("""
            INSERT INTO m2_anes.record_event
                (id, record_id, seq, event_type, occurred_at, source, payload, created_at, created_by)
            VALUES
                (#{id}, #{recordId}, #{seq}, #{eventType}, #{occurredAt}, #{source},
                 #{payload}::jsonb, #{createdAt}, #{createdBy})
            """)
    int insertEvent(RecordEventEntity event);

    /** 记录单内下一个创建序号（调用前已锁定记录单行，正常情况下不会并发冲突）。 */
    @Select("""
            SELECT COALESCE(MAX(seq), 0) + 1 FROM m2_anes.record_event WHERE record_id = #{recordId}
            """)
    int selectNextSeq(@Param("recordId") UUID recordId);

    /** 记录单时间轴事件数（锁单前置：至少 1 个事件）。 */
    @Select("""
            SELECT count(*) FROM m2_anes.record_event WHERE record_id = #{recordId}
            """)
    int countByRecord(@Param("recordId") UUID recordId);

    /** 记录单内最新事件的发生时间（无事件返回 null），用于补录原因判定。 */
    @Select("""
            SELECT MAX(occurred_at) FROM m2_anes.record_event WHERE record_id = #{recordId}
            """)
    OffsetDateTime selectLatestOccurredAt(@Param("recordId") UUID recordId);

    /** 时间轴：occurredAt 升序，同一时刻按 seq（服务端创建序）升序。 */
    @Select("""
            SELECT id, record_id, seq, event_type, occurred_at, source,
                   payload::text AS payload, created_at, created_by
              FROM m2_anes.record_event
             WHERE record_id = #{recordId}
             ORDER BY occurred_at ASC, seq ASC
            """)
    List<RecordEventEntity> selectByRecord(@Param("recordId") UUID recordId);
}
