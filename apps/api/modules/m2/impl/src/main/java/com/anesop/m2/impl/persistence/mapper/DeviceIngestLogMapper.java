package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.GatewayIngestStatsRow;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 网关批量上报日志 Mapper（{@code m2_anes.device_ingest_log}，V14）。
 *
 * <p>批次幂等：{@code INSERT ... ON CONFLICT (gateway_id, batch_key) DO NOTHING} 返回 1 表示
 * 本批首次接收、0 表示重复批次。计数在体征行写入后回填（同一事务），因此崩溃/回滚时
 * 日志与体征同生共死，不会出现"日志已记、体征未写"的伪幂等。
 */
@Mapper
public interface DeviceIngestLogMapper {

    /** 登记批次；重复批次返回 0（唯一键冲突被 DO NOTHING 吞掉，不抛异常）。 */
    @Insert("""
            INSERT INTO m2_anes.device_ingest_log
                (gateway_id, device_id, batch_key, received_at,
                 accepted_rows, duplicate_rows, rejected_rows)
            VALUES
                (#{gatewayId}, #{deviceId}, #{batchKey}, #{receivedAt}, 0, 0, 0)
            ON CONFLICT (gateway_id, batch_key) DO NOTHING
            """)
    int insertIgnoreDuplicate(@Param("gatewayId") String gatewayId,
            @Param("deviceId") String deviceId,
            @Param("batchKey") String batchKey,
            @Param("receivedAt") Instant receivedAt);

    /** 回填本批计数（同一事务内、体征写入之后）。 */
    @Update("""
            UPDATE m2_anes.device_ingest_log
               SET accepted_rows = #{accepted},
                   duplicate_rows = #{duplicate},
                   rejected_rows = #{rejected}
             WHERE gateway_id = #{gatewayId}
               AND batch_key = #{batchKey}
            """)
    int updateCounts(@Param("gatewayId") String gatewayId,
            @Param("batchKey") String batchKey,
            @Param("accepted") long accepted,
            @Param("duplicate") long duplicate,
            @Param("rejected") long rejected);

    /**
     * 健康窗口内按网关聚合的上报统计：{@code received_at ∈ [since, now]}。
     *
     * <p>上界 now 与质量统计窗口一致，避免把未来时间戳（时钟异常）计入健康摘要。
     */
    @Select("""
            SELECT gateway_id,
                   COUNT(*) AS batches,
                   COALESCE(SUM(accepted_rows), 0) AS accepted_rows,
                   COALESCE(SUM(duplicate_rows), 0) AS duplicate_rows,
                   COALESCE(SUM(rejected_rows), 0) AS rejected_rows,
                   MAX(received_at) AS last_received_at
              FROM m2_anes.device_ingest_log
             WHERE received_at >= #{since}
               AND received_at <= #{now}
             GROUP BY gateway_id
             ORDER BY gateway_id
            """)
    List<GatewayIngestStatsRow> statsSince(@Param("since") Instant since,
            @Param("now") Instant now);
}
