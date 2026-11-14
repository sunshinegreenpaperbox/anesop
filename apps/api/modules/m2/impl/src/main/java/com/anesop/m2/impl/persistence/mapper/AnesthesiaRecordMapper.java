package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 麻醉记录单 Mapper（{@code m2_anes.anesthesia_record}，V13）。
 *
 * <p>更新统一使用条件更新：{@code WHERE id=? AND version=?}，返回 0 行表示版本过期或并发
 * 修改，服务层映射 409，不静默覆盖。一个台次唯一记录单由唯一索引 uk_anesthesia_record_schedule
 * 兜底（并发创建 → DuplicateKeyException → 409）。
 */
@Mapper
public interface AnesthesiaRecordMapper extends BaseMapper<AnesthesiaRecordEntity> {

    @Insert("""
            INSERT INTO m2_anes.anesthesia_record
                (id, schedule_id, application_id, patient_id, room_id, status, anesthesia_type,
                 started_at, ended_at, created_at, created_by, updated_at, updated_by,
                 version, deleted)
            VALUES
                (#{id}, #{scheduleId}, #{applicationId}, #{patientId}, #{roomId}, #{status},
                 #{anesthesiaType}, #{startedAt}, #{endedAt}, #{createdAt}, #{createdBy},
                 #{updatedAt}, #{updatedBy}, #{version}, false)
            """)
    int insertRecord(AnesthesiaRecordEntity record);

    /**
     * 按主键锁定记录单行（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>同一记录单的时间轴追加/记录单变更先锁行、再取 seq 与写入，把并发写串行化。
     * 否则两个事务同时读到相同 {@code MAX(seq)}，后提交者命中唯一键冲突，PostgreSQL 事务
     * 进入 aborted 状态，后续任何语句（包括重试查询）都报 25P02，最终 500 而非 409。
     * 必须在事务内调用；返回 null 表示记录单不存在。
     */
    @Select("""
            SELECT id, schedule_id, application_id, patient_id, room_id, status, anesthesia_type,
                   started_at, ended_at, locked_at, locked_by, current_revision,
                   lock_signature_ref, lock_signature_hash, lock_ca_status,
                   created_at, created_by, updated_at, updated_by,
                   version, deleted
              FROM m2_anes.anesthesia_record
             WHERE id = #{id}
               AND deleted = false
             FOR UPDATE
            """)
    AnesthesiaRecordEntity selectByIdForUpdate(@Param("id") UUID id);

    /**
     * 保存记录单（未锁定可改）：{@code WHERE id=? AND version=? AND deleted=false}。
     * 返回 0 表示版本过期或不存在，服务层映射 409。
     */
    @Update("""
            UPDATE m2_anes.anesthesia_record
               SET anesthesia_type = #{anesthesiaType},
                   status = #{status},
                   started_at = #{startedAt},
                   ended_at = #{endedAt},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND deleted = false
            """)
    int updateRecord(@Param("id") UUID id,
            @Param("version") Long version,
            @Param("anesthesiaType") String anesthesiaType,
            @Param("status") String status,
            @Param("startedAt") Instant startedAt,
            @Param("endedAt") Instant endedAt,
            @Param("updatedAt") Instant updatedAt,
            @Param("updatedBy") UUID updatedBy);

    /**
     * 术间内进行中且已开始的记录单（V16 room_id 快照）。
     *
     * <p>采集落库后的领域事件按此匹配活动记录：只评估 {@code status=ONGOING 且
     * started_at IS NOT NULL、ended_at IS NULL} 的记录；DRAFT 不评估（评审 B-3）。
     * 一个术间通常只有一张活动记录，按开始时间倒序返回以便多处异常时优先最新。
     */
    @Select("""
            SELECT id, schedule_id, application_id, patient_id, room_id, status, anesthesia_type,
                   started_at, ended_at, locked_at, locked_by, current_revision,
                   lock_signature_ref, lock_signature_hash, lock_ca_status,
                   created_at, created_by, updated_at, updated_by,
                   version, deleted
              FROM m2_anes.anesthesia_record
             WHERE room_id = #{roomId}
               AND status = 'ONGOING'
               AND started_at IS NOT NULL
               AND ended_at IS NULL
               AND deleted = false
             ORDER BY started_at DESC
            """)
    List<AnesthesiaRecordEntity> selectOngoingByRoom(@Param("roomId") UUID roomId);

    /**
     * 锁单（M3-M2-03）：一次性写入锁单时间/操作人/CA 占位签名并置 LOCKED，
     * {@code WHERE id=? AND version=? AND locked_at IS NULL}；返回 0 行表示版本过期、
     * 已锁单或并发锁单，服务层映射 409。锁单不改变记录内容（数据库守卫强制）。
     */
    @Update("""
            UPDATE m2_anes.anesthesia_record
               SET locked_at = #{lockedAt},
                   locked_by = #{lockedBy},
                   status = 'LOCKED',
                   lock_signature_ref = #{signatureRef},
                   lock_signature_hash = #{signatureHash},
                   lock_ca_status = #{caStatus},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND locked_at IS NULL
               AND deleted = false
            """)
    int lockRecord(@Param("id") UUID id,
            @Param("version") Long version,
            @Param("lockedAt") Instant lockedAt,
            @Param("lockedBy") UUID lockedBy,
            @Param("signatureRef") String signatureRef,
            @Param("signatureHash") String signatureHash,
            @Param("caStatus") String caStatus,
            @Param("updatedAt") Instant updatedAt,
            @Param("updatedBy") UUID updatedBy);

    /**
     * 推进修订版本号（M3-M2-03）：置 REVISED 并把 current_revision 从上一版本 +1，
     * {@code WHERE id=? AND version=? AND locked_at IS NOT NULL AND current_revision=?}；
     * 返回 0 行表示并发修订或版本过期，服务层映射 409。
     */
    @Update("""
            UPDATE m2_anes.anesthesia_record
               SET status = 'REVISED',
                   current_revision = #{revisionNo},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND locked_at IS NOT NULL
               AND current_revision = #{previousRevision}
               AND deleted = false
            """)
    int markRevision(@Param("id") UUID id,
            @Param("version") Long version,
            @Param("previousRevision") Integer previousRevision,
            @Param("revisionNo") Integer revisionNo,
            @Param("updatedAt") Instant updatedAt,
            @Param("updatedBy") UUID updatedBy);
}
