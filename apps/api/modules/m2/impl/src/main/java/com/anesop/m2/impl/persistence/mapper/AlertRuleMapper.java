package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.AlertRuleEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 预警规则 Mapper（{@code m2_anes.alert_rule}，V15）。
 *
 * <p>版本化写入：新版本 = INSERT（version+1），旧版本 = UPDATE enabled=false。
 * 唯一键 (code, version) 与部分唯一索引 (code) WHERE enabled 兜底并发改版：第二请求
 * 命中 DuplicateKeyException，服务层映射 409，绝不静默覆盖。
 */
@Mapper
public interface AlertRuleMapper {

    /** 每个 code 的当前（最大）版本，按 code 升序。 */
    @Select("""
            SELECT r.id, r.code, r.name, r.metric, r.operator, r.threshold_low, r.threshold_high,
                   r.duration_seconds, r.severity, r.version, r.enabled,
                   r.pending_confirmation, r.created_at, r.created_by, r.updated_at, r.updated_by
              FROM m2_anes.alert_rule r
             WHERE r.version = (SELECT max(x.version) FROM m2_anes.alert_rule x WHERE x.code = r.code)
             ORDER BY r.code
            """)
    List<AlertRuleEntity> selectCurrentRules();

    /** 仅启用规则的当前版本（评估引擎输入）。 */
    @Select("""
            SELECT id, code, name, metric, operator, threshold_low, threshold_high,
                   duration_seconds, severity, version, enabled, pending_confirmation,
                   created_at, created_by, updated_at, updated_by
              FROM m2_anes.alert_rule
             WHERE enabled = true
             ORDER BY code
            """)
    List<AlertRuleEntity> selectEnabledRules();

    /** 按版本行主键查询。 */
    @Select("""
            SELECT id, code, name, metric, operator, threshold_low, threshold_high,
                   duration_seconds, severity, version, enabled, pending_confirmation,
                   created_at, created_by, updated_at, updated_by
              FROM m2_anes.alert_rule
             WHERE id = #{id}
            """)
    AlertRuleEntity selectById(@Param("id") UUID id);

    /** 锁定同一 code 的最新版本行（事务内串行化改版，避免并发生成相同 version）。 */
    @Select("""
            SELECT id, code, name, metric, operator, threshold_low, threshold_high,
                   duration_seconds, severity, version, enabled, pending_confirmation,
                   created_at, created_by, updated_at, updated_by
              FROM m2_anes.alert_rule
             WHERE code = #{code}
             ORDER BY version DESC
             LIMIT 1
             FOR UPDATE
            """)
    AlertRuleEntity selectLatestByCodeForUpdate(@Param("code") String code);

    /** 新版本插入；唯一键冲突由服务层捕获并映射 409。 */
    @Insert("""
            INSERT INTO m2_anes.alert_rule
                (id, code, name, metric, operator, threshold_low, threshold_high,
                 duration_seconds, severity, version, enabled, pending_confirmation,
                 created_at, created_by, updated_at, updated_by)
            VALUES
                (#{id}, #{code}, #{name}, #{metric}, #{operator}, #{thresholdLow},
                 #{thresholdHigh}, #{durationSeconds}, #{severity}, #{version}, #{enabled},
                 #{pendingConfirmation}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy})
            """)
    int insertVersion(AlertRuleEntity rule);

    /** 停用旧版本（触发器只允许 enabled true→false，且配置列不变）。 */
    @Update("""
            UPDATE m2_anes.alert_rule
               SET enabled = false, updated_at = #{updatedAt}, updated_by = #{updatedBy}
             WHERE id = #{id}
               AND enabled = true
            """)
    int disableVersion(@Param("id") UUID id,
            @Param("updatedAt") Instant updatedAt,
            @Param("updatedBy") UUID updatedBy);

    /**
     * 同一规则编码的全部历史版本（按 version 倒序），供 {@code ruleVersion} 追溯与
     * “规则历史版本查询”端点使用（additive）。
     */
    @Select("""
            SELECT id, code, name, metric, operator, threshold_low, threshold_high,
                   duration_seconds, severity, version, enabled, pending_confirmation,
                   created_at, created_by, updated_at, updated_by
              FROM m2_anes.alert_rule
             WHERE code = #{code}
             ORDER BY version DESC
            """)
    List<AlertRuleEntity> selectVersionsByCode(@Param("code") String code);
}
