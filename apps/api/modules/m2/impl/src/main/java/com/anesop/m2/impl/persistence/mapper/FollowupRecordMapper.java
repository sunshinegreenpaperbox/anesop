package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.FollowupRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 术后随访 Mapper（{@code m2_anes.followup_record}，V19，M3-M11-01）。
 *
 * <p>append-only：只提供 INSERT 与查询，无 UPDATE/DELETE 方法；数据库触发器为第二道防线。
 * 时间序列按 {@code assessed_at DESC, created_at DESC, id DESC} 稳定排序；latest 按同一
 * 口径取 1 条，用于同记录同类型的随访时间单调校验。
 */
@Mapper
public interface FollowupRecordMapper extends BaseMapper<FollowupRecordEntity> {

    @Insert("""
            INSERT INTO m2_anes.followup_record
                (id, anesthesia_record_id, patient_id, followup_type, assessed_at,
                 nrs_score, ponv_score, complications, complication_notes, patient_feedback,
                 followed_up_by, created_at, created_by, version)
            VALUES
                (#{id}, #{anesthesiaRecordId}, #{patientId}, #{followupType}, #{assessedAt},
                 #{nrsScore}, #{ponvScore}, #{complications}::jsonb, #{complicationNotes},
                 #{patientFeedback}, #{followedUpBy}, #{createdAt}, #{createdBy}, #{version})
            """)
    int insertFollowup(FollowupRecordEntity followup);

    /** 同一记录单同一随访类型的最新一条（时间单调校验用）。 */
    @Select("""
            SELECT id, anesthesia_record_id, patient_id, followup_type, assessed_at,
                   nrs_score, ponv_score, complications, complication_notes, patient_feedback,
                   followed_up_by, created_at, created_by, version
              FROM m2_anes.followup_record
             WHERE anesthesia_record_id = #{recordId}
               AND followup_type = #{followupType}
             ORDER BY assessed_at DESC, created_at DESC, id DESC
             LIMIT 1
            """)
    FollowupRecordEntity selectLatestByRecordAndType(@Param("recordId") UUID recordId,
            @Param("followupType") String followupType);

    /** 该记录单的随访列表（时间倒序）。 */
    @Select("""
            SELECT id, anesthesia_record_id, patient_id, followup_type, assessed_at,
                   nrs_score, ponv_score, complications, complication_notes, patient_feedback,
                   followed_up_by, created_at, created_by, version
              FROM m2_anes.followup_record
             WHERE anesthesia_record_id = #{recordId}
             ORDER BY assessed_at DESC, created_at DESC, id DESC
            """)
    List<FollowupRecordEntity> selectByRecord(@Param("recordId") UUID recordId);
}
