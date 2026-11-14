package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.PainManagementEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 术后镇痛 Mapper（{@code m2_anes.pain_management}，V19，M3-M11-01）。
 *
 * <p>append-only：只提供 INSERT 与查询，无 UPDATE/DELETE 方法；数据库触发器为第二道防线。
 * 按 {@code created_at ASC, id ASC} 返回录入顺序，供随访详情展示与参数调整追溯。
 */
@Mapper
public interface PainManagementMapper extends BaseMapper<PainManagementEntity> {

    @Insert("""
            INSERT INTO m2_anes.pain_management
                (id, followup_id, modality, drug, bolus_dose, background_dose,
                 lockout_minutes, effect, adjustments, created_at, created_by, version)
            VALUES
                (#{id}, #{followupId}, #{modality}, #{drug}, #{bolusDose}, #{backgroundDose},
                 #{lockoutMinutes}, #{effect}, #{adjustments}::jsonb, #{createdAt},
                 #{createdBy}, #{version})
            """)
    int insertPainManagement(PainManagementEntity painManagement);

    /** 该随访的镇痛记录（录入顺序升序）。 */
    @Select("""
            SELECT id, followup_id, modality, drug, bolus_dose, background_dose,
                   lockout_minutes, effect, adjustments, created_at, created_by, version
              FROM m2_anes.pain_management
             WHERE followup_id = #{followupId}
             ORDER BY created_at ASC, id ASC
            """)
    List<PainManagementEntity> selectByFollowup(@Param("followupId") UUID followupId);
}
