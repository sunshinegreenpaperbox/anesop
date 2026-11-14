package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.PacuDischargeEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * PACU 转出留痕 Mapper（{@code m2_anes.pacu_discharge}，V18，M3-M10-01）。
 *
 * <p>每条复苏记录最多一条（pacu_id 唯一）；append-only：只提供 INSERT 与查询，
 * UPDATE/DELETE/TRUNCATE 由数据库触发器拒绝（SQLSTATE 55000）。
 */
@Mapper
public interface PacuDischargeMapper extends BaseMapper<PacuDischargeEntity> {

    @Insert("""
            INSERT INTO m2_anes.pacu_discharge
                (id, pacu_id, discharged_at, destination, handover, criteria_snapshot,
                 created_at, created_by)
            VALUES
                (#{id}, #{pacuId}, #{dischargedAt}, #{destination}, #{handover}::jsonb,
                 #{criteriaSnapshot}::jsonb, #{createdAt}, #{createdBy})
            """)
    int insertDischarge(PacuDischargeEntity discharge);

    @Select("""
            SELECT id, pacu_id, discharged_at, destination, handover, criteria_snapshot,
                   created_at, created_by
              FROM m2_anes.pacu_discharge
             WHERE pacu_id = #{pacuId}
            """)
    PacuDischargeEntity selectByPacu(@Param("pacuId") UUID pacuId);
}
