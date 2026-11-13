package com.anesop.m1.impl.persistence.mapper;

import com.anesop.m1.impl.persistence.entity.NotificationOutboxRow;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 通知发件箱 Mapper（{@code m1_sched.notification_outbox}，V9）。
 *
 * <p>用显式 SQL 而非 MyBatis-Plus 实体：{@code payload} 为 jsonb，写入用
 * {@code CAST(#{payload} AS jsonb)}、读取用 {@code payload::text}，避免 m1 模块依赖
 * m0 的 jsonb TypeHandler（模块边界）。
 *
 * <p>派发并发：{@code SELECT ... FOR UPDATE SKIP LOCKED} 保证多实例轮询时同一条只被
 * 一个派发者取走；状态机 PENDING → SENT（成功）/ FAILED（重试耗尽）。
 */
@Mapper
public interface NotificationOutboxMapper {

    /** 入队一条待发通知（PENDING，attempts=0，立即可发）。 */
    @Insert("""
            INSERT INTO m1_sched.notification_outbox
                (id, event_type, payload, status, attempts, next_attempt_at, created_at)
            VALUES (#{id}, #{eventType}, CAST(#{payload} AS jsonb), 'PENDING', 0, #{now}, #{now})
            """)
    int insert(@Param("id") UUID id, @Param("eventType") String eventType,
            @Param("payload") String payload, @Param("now") Instant now);

    /** 取一条到期待发通知并加行锁（多实例安全）；无待发返回 null。 */
    @Select("""
            SELECT id, event_type, payload::text AS payload, status, attempts,
                   next_attempt_at, created_at, sent_at
            FROM m1_sched.notification_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= #{now}
            ORDER BY created_at, id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """)
    NotificationOutboxRow selectPendingOne(@Param("now") Instant now);

    /** 标记发送成功（attempts+1、sent_at=now）。 */
    @Update("""
            UPDATE m1_sched.notification_outbox
            SET status = 'SENT', attempts = attempts + 1, sent_at = #{now}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int markSent(@Param("id") UUID id, @Param("now") Instant now);

    /** 记录一次失败：更新次数/下次重试时间/状态（PENDING 退避或 FAILED）。 */
    @Update("""
            UPDATE m1_sched.notification_outbox
            SET attempts = #{attempts}, status = #{status},
                next_attempt_at = #{nextAttemptAt}, last_error = #{lastError}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int markFailure(@Param("id") UUID id, @Param("attempts") int attempts,
            @Param("status") String status, @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError);

    /** 按状态计数（运维/测试只读查询）。 */
    @Select("SELECT count(*) FROM m1_sched.notification_outbox WHERE status = #{status}")
    int countByStatus(@Param("status") String status);
}
