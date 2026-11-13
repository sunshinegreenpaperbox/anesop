package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.AlertEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 告警 Mapper（{@code m2_anes.alert}，V15）。
 *
 * <p>写入幂等：{@code ON CONFLICT (dedupe_key) DO NOTHING}，返回 1 表示新增、0 表示同一越界
 * 窗口已存在告警（重复评估不重复插入）。处置用条件更新 {@code WHERE id=? AND version=?
 * AND status='TRIGGERED'}，返回 0 行即已处理或版本过期（服务层映射 409），不做静默覆盖。
 */
@Mapper
public interface AlertMapper {

    /**
     * 幂等插入告警（dedupe_key 唯一）。
     *
     * @return 1=新增，0=同一去重键已存在（重复评估）
     */
    @Insert("""
            INSERT INTO m2_anes.alert
                (id, record_id, rule_id, rule_code, rule_version, metric, value, unit, severity,
                 triggered_at, dedupe_key, status, created_at, created_by, updated_at, updated_by,
                 version)
            VALUES
                (#{id}, #{recordId}, #{ruleId}, #{ruleCode}, #{ruleVersion}, #{metric}, #{value},
                 #{unit}, #{severity}, #{triggeredAt}, #{dedupeKey}, 'TRIGGERED',
                 #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, 1)
            ON CONFLICT (dedupe_key) DO NOTHING
            """)
    int insertIgnoreDuplicate(AlertEntity alert);

    /** 记录单告警列表（按触发时间倒序）。 */
    @Select("""
            SELECT id, record_id, rule_id, rule_code, rule_version, metric, value, unit, severity,
                   triggered_at, dedupe_key, status, acknowledged_by, acknowledged_at,
                   acknowledge_reason, created_at, created_by, updated_at, updated_by, version
              FROM m2_anes.alert
             WHERE record_id = #{recordId}
             ORDER BY triggered_at DESC, rule_code ASC
            """)
    List<AlertEntity> selectByRecord(@Param("recordId") UUID recordId);

    /** 按主键查询（确认/忽略前校验存在性与数据范围）。 */
    @Select("""
            SELECT id, record_id, rule_id, rule_code, rule_version, metric, value, unit, severity,
                   triggered_at, dedupe_key, status, acknowledged_by, acknowledged_at,
                   acknowledge_reason, created_at, created_by, updated_at, updated_by, version
              FROM m2_anes.alert
             WHERE id = #{id}
            """)
    AlertEntity selectById(@Param("id") UUID id);

    /**
     * 一次性处置（TRIGGERED → ACKNOWLEDGED/IGNORED）：条件含 version 与 status。
     *
     * @return 1=成功，0=已处理或版本过期（409）
     */
    @Update("""
            UPDATE m2_anes.alert
               SET status = #{status},
                   acknowledged_by = #{acknowledgedBy},
                   acknowledged_at = #{acknowledgedAt},
                   acknowledge_reason = #{reason},
                   updated_at = #{acknowledgedAt},
                   updated_by = #{acknowledgedBy},
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
               AND status = 'TRIGGERED'
            """)
    int acknowledge(@Param("id") UUID id,
            @Param("version") Long version,
            @Param("status") String status,
            @Param("acknowledgedBy") UUID acknowledgedBy,
            @Param("acknowledgedAt") Instant acknowledgedAt,
            @Param("reason") String reason);
}
