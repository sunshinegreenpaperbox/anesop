package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.PacuRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * PACU 复苏记录 Mapper（{@code m2_anes.pacu_record}，V18，M3-M10-01）。
 *
 * <p>状态推进与转出统一使用条件更新：{@code WHERE id=? AND version=? AND status<>'DISCHARGED'}，
 * 返回 0 行表示版本过期/并发修改/已转出，服务层映射 409。一条记录单同时最多一条未转出记录由
 * 部分唯一索引 uk_pacu_record_active 兜底（并发入室 → DuplicateKeyException → 409）。
 */
@Mapper
public interface PacuRecordMapper extends BaseMapper<PacuRecordEntity> {

    @Insert("""
            INSERT INTO m2_anes.pacu_record
                (id, anesthesia_record_id, patient_id, admitted_at, status,
                 admitted_vital_signs, consciousness, pain_score, nausea, airway,
                 created_at, created_by, updated_at, updated_by, version, deleted)
            VALUES
                (#{id}, #{anesthesiaRecordId}, #{patientId}, #{admittedAt}, #{status},
                 #{admittedVitalSigns}::jsonb, #{consciousness}, #{painScore}, #{nausea},
                 #{airway}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, 1, false)
            """)
    int insertRecord(PacuRecordEntity record);

    /** 按主键锁定复苏记录行（{@code SELECT ... FOR UPDATE}）；必须在事务内调用。 */
    @Select("""
            SELECT id, anesthesia_record_id, patient_id, admitted_at, discharged_at, status,
                   admitted_vital_signs, consciousness, pain_score, nausea, airway,
                   created_at, created_by, updated_at, updated_by, version, deleted
              FROM m2_anes.pacu_record
             WHERE id = #{id}
               AND deleted = false
             FOR UPDATE
            """)
    PacuRecordEntity selectByIdForUpdate(@Param("id") UUID id);

    /** 该记录单当前未转出的复苏记录（最多 1 条；部分唯一索引保证）。 */
    @Select("""
            SELECT id, anesthesia_record_id, patient_id, admitted_at, discharged_at, status,
                   admitted_vital_signs, consciousness, pain_score, nausea, airway,
                   created_at, created_by, updated_at, updated_by, version, deleted
              FROM m2_anes.pacu_record
             WHERE anesthesia_record_id = #{recordId}
               AND status <> 'DISCHARGED'
               AND deleted = false
             ORDER BY created_at DESC
             LIMIT 1
            """)
    PacuRecordEntity selectActiveByRecord(@Param("recordId") UUID recordId);

    /**
     * 评估后推进状态（READY/MONITORING）：条件 update，version 过期或已转出返回 0 行（409）。
     */
    @Update("""
            UPDATE m2_anes.pacu_record
               SET status = #{status},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND status <> 'DISCHARGED'
               AND deleted = false
            """)
    int markAssessed(@Param("id") UUID id,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("updatedAt") Instant updatedAt,
            @Param("updatedBy") UUID updatedBy);

    /**
     * 转出：置 DISCHARGED 并写转出时间（写一次），条件 update；返回 0 行即并发/版本过期/已转出。
     */
    @Update("""
            UPDATE m2_anes.pacu_record
               SET status = 'DISCHARGED',
                   discharged_at = #{dischargedAt},
                   updated_at = #{dischargedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND status <> 'DISCHARGED'
               AND discharged_at IS NULL
               AND deleted = false
            """)
    int markDischarged(@Param("id") UUID id,
            @Param("version") Long version,
            @Param("dischargedAt") Instant dischargedAt,
            @Param("updatedBy") UUID updatedBy);
}
