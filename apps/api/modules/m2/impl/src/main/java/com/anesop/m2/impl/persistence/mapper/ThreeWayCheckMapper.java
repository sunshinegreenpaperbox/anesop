package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.ThreeWayCheckEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 三方核查 Mapper（{@code m2_anes.three_way_check}）。
 *
 * <p>append-only：只提供 INSERT 与查询，没有 UPDATE/DELETE 方法（数据库触发器再兜底）。
 * 版本号 = 同一申请 {@code max(check_version) + 1}，并发冲突由唯一索引抛
 * DuplicateKeyException，服务层映射 409。
 */
@Mapper
public interface ThreeWayCheckMapper extends BaseMapper<ThreeWayCheckEntity> {

    @Insert("""
            INSERT INTO m2_anes.three_way_check
                (id, application_id, check_version, time_out_at, time_out_by, sign_out_at,
                 sign_out_by, specimen_checked, implant_checked, issues, created_at, created_by,
                 updated_at, updated_by)
            VALUES
                (#{id}, #{applicationId}, #{checkVersion}, #{timeOutAt}, #{timeOutBy}, #{signOutAt},
                 #{signOutBy}, #{specimenChecked}, #{implantChecked},
                 CAST(#{issuesJson} AS jsonb), #{createdAt}, #{createdBy},
                 #{updatedAt}, #{updatedBy})
            """)
    int insertCheck(ThreeWayCheckEntity check);

    /** 申请内最大核查版本号；无记录返回 0。 */
    @Select("""
            SELECT coalesce(max(check_version), 0)
            FROM m2_anes.three_way_check
            WHERE application_id = #{applicationId}
            """)
    int maxCheckVersion(@Param("applicationId") UUID applicationId);

    /** 最新核查版本（版本号倒序第一行）；无记录返回 null。 */
    @Select("""
            SELECT *
            FROM m2_anes.three_way_check
            WHERE application_id = #{applicationId}
            ORDER BY check_version DESC
            LIMIT 1
            """)
    ThreeWayCheckEntity selectLatest(@Param("applicationId") UUID applicationId);
}
