package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.PreopAssessmentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 术前访视与评估 Mapper（{@code m2_anes.preop_assessment}）。
 *
 * <p>写入用显式 SQL：jsonb 列以 {@code CAST(#{...} AS jsonb)} 绑定；读取用
 * {@code BaseMapper.selectById}（PG JDBC 的 {@code getString} 直接返回 jsonb 文本）。
 * 没有复用 m0 的 {@code JsonbTypeHandler}，避免 m2-impl 依赖 m0-impl（模块边界）。
 *
 * <p>更新/最终化都以 {@code version} 做条件更新并自增版本（乐观锁）；返回 0 表示版本过期、
 * 已最终化或已被并发修改，服务层统一映射 409，不静默覆盖。
 */
@Mapper
public interface PreopAssessmentMapper extends BaseMapper<PreopAssessmentEntity> {

    /** 插入草稿；同一申请已有草稿时由部分唯一索引抛 DuplicateKeyException（409 兜底）。 */
    @Insert("""
            INSERT INTO m2_anes.preop_assessment
                (id, application_id, patient_id, patient_snapshot, history_summary, asa_class,
                 mallampati, airway_details, score_input, rcri_score, apfel_score, stopbang_score,
                 dasi_score, rcri_risk, apfel_risk, stopbang_risk, functional_capacity_low,
                 risk_level, calculator_version, scores, plan_type, airway_plan, plan_notes, status,
                 finalize_note, finalized_at, finalized_by, created_at, created_by, updated_at,
                 updated_by, version, deleted)
            VALUES
                (#{id}, #{applicationId}, #{patientId}, CAST(#{patientSnapshotJson} AS jsonb),
                 #{historySummary}, #{asaClass}, #{mallampati}, CAST(#{airwayDetailsJson} AS jsonb),
                 CAST(#{scoreInputJson} AS jsonb), #{rcriScore}, #{apfelScore}, #{stopbangScore},
                 #{dasiScore}, #{rcriRisk}, #{apfelRisk}, #{stopbangRisk}, #{functionalCapacityLow},
                 #{riskLevel}, #{calculatorVersion}, CAST(#{scoresJson} AS jsonb), #{planType}, #{airwayPlan}, #{planNotes},
                 #{status}, #{finalizeNote}, #{finalizedAt}, #{finalizedBy}, #{createdAt},
                 #{createdBy}, #{updatedAt}, #{updatedBy}, #{version}, false)
            """)
    int insertDraft(PreopAssessmentEntity assessment);

    /** 全量保存草稿；返回 0 表示版本过期/已最终化/不存在。 */
    @Update("""
            UPDATE m2_anes.preop_assessment
               SET history_summary = #{historySummary},
                   asa_class = #{asaClass},
                   mallampati = #{mallampati},
                   airway_details = CAST(#{airwayDetailsJson} AS jsonb),
                   score_input = CAST(#{scoreInputJson} AS jsonb),
                   rcri_score = #{rcriScore},
                   apfel_score = #{apfelScore},
                   stopbang_score = #{stopbangScore},
                   dasi_score = #{dasiScore},
                   rcri_risk = #{rcriRisk},
                   apfel_risk = #{apfelRisk},
                   stopbang_risk = #{stopbangRisk},
                   functional_capacity_low = #{functionalCapacityLow},
                   risk_level = #{riskLevel},
                   calculator_version = #{calculatorVersion},
                   scores = CAST(#{scoresJson} AS jsonb),
                   plan_type = #{planType},
                   airway_plan = #{airwayPlan},
                   plan_notes = #{planNotes},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND status = 'DRAFT'
               AND deleted = false
            """)
    int updateDraft(PreopAssessmentEntity assessment);

    /** 最终化草稿；返回 0 表示版本过期/已最终化/不存在。 */
    @Update("""
            UPDATE m2_anes.preop_assessment
               SET status = 'FINALIZED',
                   finalize_note = #{finalizeNote},
                   finalized_at = #{finalizedAt},
                   finalized_by = #{finalizedBy},
                   updated_at = #{finalizedAt},
                   updated_by = #{finalizedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND status = 'DRAFT'
               AND deleted = false
            """)
    int finalizeDraft(@Param("id") UUID id,
            @Param("version") Long version,
            @Param("finalizedAt") java.time.Instant finalizedAt,
            @Param("finalizedBy") UUID finalizedBy,
            @Param("finalizeNote") String finalizeNote);
}
