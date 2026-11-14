package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.PacuAssessmentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Aldrete 评估 Mapper（{@code m2_anes.pacu_assessment}，V18，M3-M10-01）。
 *
 * <p>append-only：只提供 INSERT 与查询（UPDATE/DELETE/TRUNCATE 由数据库触发器拒绝，55000）。
 * 时间序列按 {@code assessed_at ASC, created_at ASC, id ASC} 稳定排序；最近一次评估按
 * {@code assessed_at DESC, created_at DESC, id DESC} 取 1 条（与时间序列同一 (时间, 录入, 标识)
 * 口径，服务层状态推进以该 latest 为准）。
 */
@Mapper
public interface PacuAssessmentMapper extends BaseMapper<PacuAssessmentEntity> {

    @Insert("""
            INSERT INTO m2_anes.pacu_assessment
                (id, pacu_id, assessed_at, aldrete_activity, aldrete_respiration,
                 aldrete_circulation, aldrete_consciousness, aldrete_oxygenation,
                 aldrete_total, notes, created_at, created_by)
            VALUES
                (#{id}, #{pacuId}, #{assessedAt}, #{aldreteActivity}, #{aldreteRespiration},
                 #{aldreteCirculation}, #{aldreteConsciousness}, #{aldreteOxygenation},
                 #{aldreteTotal}, #{notes}, #{createdAt}, #{createdBy})
            """)
    int insertAssessment(PacuAssessmentEntity assessment);

    @Select("""
            SELECT id, pacu_id, assessed_at, aldrete_activity, aldrete_respiration,
                   aldrete_circulation, aldrete_consciousness, aldrete_oxygenation,
                   aldrete_total, notes, created_at, created_by
              FROM m2_anes.pacu_assessment
             WHERE pacu_id = #{pacuId}
             ORDER BY assessed_at ASC, created_at ASC, id ASC
            """)
    List<PacuAssessmentEntity> selectByPacu(@Param("pacuId") UUID pacuId);

    @Select("""
            SELECT id, pacu_id, assessed_at, aldrete_activity, aldrete_respiration,
                   aldrete_circulation, aldrete_consciousness, aldrete_oxygenation,
                   aldrete_total, notes, created_at, created_by
              FROM m2_anes.pacu_assessment
             WHERE pacu_id = #{pacuId}
             ORDER BY assessed_at DESC, created_at DESC, id DESC
             LIMIT 1
            """)
    PacuAssessmentEntity selectLatestByPacu(@Param("pacuId") UUID pacuId);
}
