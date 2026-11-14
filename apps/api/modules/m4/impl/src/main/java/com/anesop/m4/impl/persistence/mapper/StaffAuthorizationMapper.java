package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.StaffAuthorizationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 分级授权 Mapper（{@code m4_staff.staff_authorization}，V21/V22，append-only）。
 *
 * <p>只提供 INSERT 与查询：历史版本只增不改（UPDATE/DELETE/TRUNCATE 由数据库触发器拒绝，
 * SQLSTATE 55000）。当前版本统一读视图 {@code staff_authorization_current}（避免“视图 +
 * ORDER BY version DESC”双口径漂移）；回溯判定另按 {@code approved_at/valid_from/revoked_at}
 * 选择**当时生效版本**。
 */
@Mapper
public interface StaffAuthorizationMapper extends BaseMapper<StaffAuthorizationEntity> {

    @Insert("""
            INSERT INTO m4_staff.staff_authorization
                (id, staff_id, version, level, surgery_scopes, age_min, age_max, asa_max,
                 valid_from, valid_to, status, approved_by, approved_at, second_approver_id,
                 second_approved_at, second_approver_reason, revoked_at, revoked_by,
                 change_reason, created_at, created_by)
            VALUES
                (#{id}, #{staffId}, #{version}, #{level}, #{surgeryScopes}::jsonb, #{ageMin},
                 #{ageMax}, #{asaMax}, #{validFrom}, #{validTo}, #{status}, #{approvedBy},
                 #{approvedAt}, #{secondApproverId}, #{secondApprovedAt},
                 #{secondApproverReason}, #{revokedAt}, #{revokedBy}, #{changeReason},
                 #{createdAt}, #{createdBy})
            """)
    int insertAuthorization(StaffAuthorizationEntity authorization);

    @Select("""
            SELECT * FROM m4_staff.staff_authorization
             WHERE staff_id = #{staffId}
             ORDER BY version DESC
            """)
    List<StaffAuthorizationEntity> selectHistory(@Param("staffId") UUID staffId);

    /** 当前版本：统一读视图（每人员 version 最大的一行；无授权返回 null）。 */
    @Select("""
            SELECT * FROM m4_staff.staff_authorization_current
             WHERE staff_id = #{staffId}
            """)
    StaffAuthorizationEntity selectCurrent(@Param("staffId") UUID staffId);

    /**
     * 判定时点 {@code at} 之前已批准的“最新治理版本”。
     *
     * <p>用于识别撤销：若该版本为 REVOKED 且 revocation 已生效，则当时无有效授权，
     * 不回退到更早的 ACTIVE 版本（撤销不追溯既往，但撤销生效后不得回退）。
     */
    @Select("""
            SELECT * FROM m4_staff.staff_authorization
             WHERE staff_id = #{staffId}
               AND approved_at <= CAST(#{at} AS timestamptz)
             ORDER BY version DESC
             LIMIT 1
            """)
    StaffAuthorizationEntity selectLatestApprovedAt(@Param("staffId") UUID staffId,
            @Param("at") Instant at);

    /**
     * 当时生效版本：{@code approved_at <= at AND valid_from <= at 当日 AND
     * (revoked_at IS NULL OR revoked_at > at)}，取 version 最大者。
     *
     * <p>该查询用于回溯判定（补录/复盘）：不会把后来扩大的授权范围套到过去时点。
     * 未生效（valid_from 晚于 at）返回 null，由服务层返回 NOT_YET_VALID。
     */
    @Select("""
            SELECT * FROM m4_staff.staff_authorization
             WHERE staff_id = #{staffId}
               AND approved_at <= CAST(#{at} AS timestamptz)
               AND valid_from <= (CAST(#{at} AS timestamptz) AT TIME ZONE 'Asia/Shanghai')::date
               AND (revoked_at IS NULL OR revoked_at > CAST(#{at} AS timestamptz))
             ORDER BY version DESC
             LIMIT 1
            """)
    StaffAuthorizationEntity selectEffectiveAt(@Param("staffId") UUID staffId,
            @Param("at") Instant at);

    @Select("""
            SELECT coalesce(max(version), 0) FROM m4_staff.staff_authorization
             WHERE staff_id = #{staffId}
            """)
    int selectMaxVersion(@Param("staffId") UUID staffId);
}
