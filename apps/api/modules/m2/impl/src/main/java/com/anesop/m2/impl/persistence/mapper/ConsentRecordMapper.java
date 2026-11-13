package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.ConsentRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 知情同意记录 Mapper（{@code m2_anes.consent_record}）。
 *
 * <p>状态变更全部使用显式条件更新：{@code WHERE id=? AND version=? AND status=?}，
 * 返回 0 行表示版本过期、状态已变化或并发修改，服务层统一映射 409，不静默覆盖。
 * 唯一冲突（同一申请 + 模板版本的有效记录）由部分唯一索引兜底。
 */
@Mapper
public interface ConsentRecordMapper extends BaseMapper<ConsentRecordEntity> {

    @Insert("""
            INSERT INTO m2_anes.consent_record
                (id, application_id, patient_id, template_id, template_version, status,
                 signer_name, signer_relation, sign_method, signed_at, signed_by,
                 signature_ref, signature_hash, ca_status, decline_reason, declined_at,
                 revoked_at, revoked_by, revoke_reason, created_at, created_by, updated_at,
                 updated_by, version, deleted)
            VALUES
                (#{id}, #{applicationId}, #{patientId}, #{templateId}, #{templateVersion}, #{status},
                 #{signerName}, #{signerRelation}, #{signMethod}, #{signedAt}, #{signedBy},
                 #{signatureRef}, #{signatureHash}, #{caStatus}, #{declineReason}, #{declinedAt},
                 #{revokedAt}, #{revokedBy}, #{revokeReason}, #{createdAt}, #{createdBy},
                 #{updatedAt}, #{updatedBy}, #{version}, false)
            """)
    int insertRecord(ConsentRecordEntity record);

    /** WAITING_SIGN → SIGNED；返回 0 表示版本过期/状态不是待签署/不存在。 */
    @Update("""
            UPDATE m2_anes.consent_record
               SET status = 'SIGNED',
                   signed_at = #{signedAt},
                   signed_by = #{signedBy},
                   signature_ref = #{signatureRef},
                   signature_hash = #{signatureHash},
                   ca_status = #{caStatus},
                   updated_at = #{signedAt},
                   updated_by = #{signedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND status = 'WAITING_SIGN'
               AND deleted = false
            """)
    int sign(@Param("id") UUID id,
            @Param("version") Long version,
            @Param("signedAt") Instant signedAt,
            @Param("signedBy") UUID signedBy,
            @Param("signatureRef") String signatureRef,
            @Param("signatureHash") String signatureHash,
            @Param("caStatus") String caStatus);

    /** WAITING_SIGN → DECLINED；返回 0 表示版本过期/状态不允许。 */
    @Update("""
            UPDATE m2_anes.consent_record
               SET status = 'DECLINED',
                   decline_reason = #{declineReason},
                   declined_at = #{declinedAt},
                   updated_at = #{declinedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND status = 'WAITING_SIGN'
               AND deleted = false
            """)
    int decline(@Param("id") UUID id,
            @Param("version") Long version,
            @Param("declineReason") String declineReason,
            @Param("declinedAt") Instant declinedAt,
            @Param("updatedBy") UUID updatedBy);

    /** SIGNED → REVOKED；返回 0 表示版本过期/状态不是已签署。 */
    @Update("""
            UPDATE m2_anes.consent_record
               SET status = 'REVOKED',
                   revoke_reason = #{revokeReason},
                   revoked_at = #{revokedAt},
                   revoked_by = #{revokedBy},
                   updated_at = #{revokedAt},
                   updated_by = #{revokedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND status = 'SIGNED'
               AND deleted = false
            """)
    int revoke(@Param("id") UUID id,
            @Param("version") Long version,
            @Param("revokeReason") String revokeReason,
            @Param("revokedAt") Instant revokedAt,
            @Param("revokedBy") UUID revokedBy);
}
