package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
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
                (id, schedule_id, application_id, patient_id, status, anesthesia_type,
                 started_at, ended_at, created_at, created_by, updated_at, updated_by,
                 version, deleted)
            VALUES
                (#{id}, #{scheduleId}, #{applicationId}, #{patientId}, #{status},
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
            SELECT id, schedule_id, application_id, patient_id, status, anesthesia_type,
                   started_at, ended_at, created_at, created_by, updated_at, updated_by,
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
}
