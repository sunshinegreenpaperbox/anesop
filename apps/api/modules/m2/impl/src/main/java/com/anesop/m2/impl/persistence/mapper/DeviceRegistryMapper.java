package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.DeviceRegistryRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 设备登记 Mapper（{@code m2_anes.device_registry}，V14）。
 *
 * <p>jsonb 列 {@code quality_stats} 写入用 {@code jsonb_build_object} 原子累加（并发批次不丢计数），
 * 读取用 {@code ::text} 后由服务层解析；不使用 MyBatis-Plus 实体，避免依赖 m0-impl 的
 * JsonbTypeHandler（模块边界）。
 */
@Mapper
public interface DeviceRegistryMapper {

    /** 按可选网关/术间过滤登记列表（按 gatewayId、deviceId 升序）。 */
    @Select("""
            <script>
            SELECT id, gateway_id, device_id, room_id, device_type, status, last_seen_at,
                   quality_stats::text AS quality_stats, created_at, created_by, updated_at,
                   updated_by, version
              FROM m2_anes.device_registry
             WHERE 1 = 1
            <if test="gatewayId != null and gatewayId != ''">
               AND gateway_id = #{gatewayId}
            </if>
            <if test="roomId != null">
               AND room_id = #{roomId}
            </if>
             ORDER BY gateway_id, device_id
            </script>
            """)
    List<DeviceRegistryRow> selectDevices(@Param("gatewayId") String gatewayId,
            @Param("roomId") UUID roomId);

    /** 按唯一键查询登记行；未登记返回 null。 */
    @Select("""
            SELECT id, gateway_id, device_id, room_id, device_type, status, last_seen_at,
                   quality_stats::text AS quality_stats, created_at, created_by, updated_at,
                   updated_by, version
              FROM m2_anes.device_registry
             WHERE gateway_id = #{gatewayId}
               AND device_id = #{deviceId}
            """)
    DeviceRegistryRow selectByKey(@Param("gatewayId") String gatewayId,
            @Param("deviceId") String deviceId);

    /** 首次登记；唯一键冲突由服务层捕获 DuplicateKeyException 映射 409。 */
    @Insert("""
            INSERT INTO m2_anes.device_registry
                (id, gateway_id, device_id, room_id, device_type, status, quality_stats,
                 created_at, created_by, updated_at, updated_by, version)
            VALUES
                (#{id}, #{gatewayId}, #{deviceId}, #{roomId}, #{deviceType}, #{status},
                 '{}'::jsonb, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, 1)
            """)
    int insert(DeviceRegistryRow row);

    /** 换台/改类型/改状态绑定（乐观锁）；返回 0 表示版本过期或并发修改。 */
    @Update("""
            UPDATE m2_anes.device_registry
               SET room_id = #{roomId},
                   device_type = #{deviceType},
                   status = #{status},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE gateway_id = #{gatewayId}
               AND device_id = #{deviceId}
               AND version = #{version}
            """)
    int updateBinding(@Param("gatewayId") String gatewayId,
            @Param("deviceId") String deviceId,
            @Param("version") Long version,
            @Param("roomId") UUID roomId,
            @Param("deviceType") String deviceType,
            @Param("status") String status,
            @Param("updatedAt") Instant updatedAt,
            @Param("updatedBy") UUID updatedBy);

    /**
     * 上报后原子累加质量统计（批次/接受/重复/拒绝/可疑/缺失）。
     *
     * <p>{@code last_seen_at} 与 ONLINE 推导只在 {@code accepted > 0} 时推进：全部重复或全部
     * 被拒绝的批次不刷新在线时间（避免"网关还在发、但没有任何有效数据"被误判为在线）。
     * MAINTENANCE 不被自动改回 ONLINE。
     */
    @Update("""
            UPDATE m2_anes.device_registry
               SET last_seen_at = CASE WHEN #{accepted} > 0
                                       THEN GREATEST(COALESCE(last_seen_at, #{seenAt}), #{seenAt})
                                       ELSE last_seen_at END,
                   status = CASE WHEN status = 'MAINTENANCE' THEN status
                                 WHEN #{accepted} > 0 THEN 'ONLINE'
                                 ELSE status END,
                   quality_stats = jsonb_build_object(
                       'batches', COALESCE((quality_stats->>'batches')::bigint, 0) + 1,
                       'acceptedRows', COALESCE((quality_stats->>'acceptedRows')::bigint, 0)
                           + #{accepted},
                       'duplicateRows', COALESCE((quality_stats->>'duplicateRows')::bigint, 0)
                           + #{duplicate},
                       'rejectedRows', COALESCE((quality_stats->>'rejectedRows')::bigint, 0)
                           + #{rejected},
                       'suspectRows', COALESCE((quality_stats->>'suspectRows')::bigint, 0)
                           + #{suspect},
                       'missingRows', COALESCE((quality_stats->>'missingRows')::bigint, 0)
                           + #{missing},
                       'lastBatchKey', #{batchKey},
                       'lastBatchAtMs', #{seenAtMs}
                   ),
                   updated_at = #{seenAt},
                   version = version + 1
             WHERE gateway_id = #{gatewayId}
               AND device_id = #{deviceId}
            """)
    int markSeen(@Param("gatewayId") String gatewayId,
            @Param("deviceId") String deviceId,
            @Param("seenAt") Instant seenAt,
            @Param("seenAtMs") long seenAtMs,
            @Param("batchKey") String batchKey,
            @Param("accepted") long accepted,
            @Param("duplicate") long duplicate,
            @Param("rejected") long rejected,
            @Param("suspect") long suspect,
            @Param("missing") long missing);
}
