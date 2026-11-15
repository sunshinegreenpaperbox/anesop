package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.GatewayQualityRow;
import com.anesop.m2.impl.persistence.entity.VitalInsertOutcome;
import com.anesop.m2.impl.persistence.entity.VitalInsertRow;
import com.anesop.m2.impl.persistence.entity.VitalRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备体征 Mapper（{@code m2_anes.anesthesia_vital}，V4 表，本任务只读 + 幂等批量插入）。
 *
 * <p>行级幂等：批量插入统一 {@code ON CONFLICT (device_id, seq, measured_at) DO NOTHING}，
 * 返回值为实际插入行数；与批量大小之差即重复帧数。一次批量一条 SQL，避免逐行往返。
 * 查询只按 {@code measured_at}（设备原始时间）过滤/排序，保留 V4 索引
 * {@code idx_anesthesia_vital_device_measured} 可用；不做 UPDATE/DELETE（append-only）。
 */
@Mapper
public interface AnesthesiaVitalMapper {

    /**
     * 批量幂等插入，并聚合**本次实际插入**行数与质量分布（可疑/缺失）。
     *
     * <p>用数据修改 CTE（{@code WITH ins AS (INSERT ... RETURNING quality) SELECT ...}）一次往返完成：
     * 唯一键冲突的行被 {@code DO NOTHING} 丢弃、不出现在 {@code RETURNING} 中，
     * 因此质量比例分子分母口径一致（重复帧不污染质量统计）。
     */
    @Select("""
            <script>
            WITH ins AS (
                INSERT INTO m2_anes.anesthesia_vital
                    (gateway_id, device_id, seq, measured_at, measured_at_corrected, received_at,
                     hr, spo2, nibp_sys, nibp_dia, etco2, temp,
                     quality, quality_flags, source, synthetic)
                VALUES
                <foreach collection="frames" item="f" separator=",">
                    (#{f.gatewayId}, #{f.deviceId}, #{f.seq}, #{f.measuredAt},
                     #{f.measuredAtCorrected}, #{f.receivedAt},
                     #{f.hr}, #{f.spo2}, #{f.nibpSys}, #{f.nibpDia}, #{f.etco2}, #{f.temp},
                     #{f.quality}, #{f.qualityFlags}, #{f.source}, #{f.synthetic})
                </foreach>
                ON CONFLICT (device_id, seq, measured_at) DO NOTHING
                RETURNING quality
            )
            SELECT COUNT(*) AS inserted,
                   COUNT(*) FILTER (WHERE quality = 1) AS suspect_rows,
                   COUNT(*) FILTER (WHERE quality &gt;= 2) AS missing_rows
              FROM ins
            </script>
            """)
    VitalInsertOutcome insertFrames(@Param("frames") List<VitalInsertRow> frames);

    /** 窗口内采样帧数（分页 total）。 */
    @Select("""
            <script>
            SELECT COUNT(*)
              FROM m2_anes.anesthesia_vital
             WHERE gateway_id = #{gatewayId}
               AND device_id = #{deviceId}
               AND measured_at &gt;= #{from}
               AND measured_at &lt; #{to}
            <if test="quality != null">
               AND quality = #{quality}
            </if>
            </script>
            """)
    long countVitals(@Param("gatewayId") String gatewayId,
            @Param("deviceId") String deviceId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("quality") Integer quality);

    /** 窗口内采样帧（按 measured_at 升序分页）。 */
    @Select("""
            <script>
            SELECT seq, measured_at, hr, spo2, nibp_sys, nibp_dia, etco2, temp,
                   quality, quality_flags
              FROM m2_anes.anesthesia_vital
             WHERE gateway_id = #{gatewayId}
               AND device_id = #{deviceId}
               AND measured_at &gt;= #{from}
               AND measured_at &lt; #{to}
            <if test="quality != null">
               AND quality = #{quality}
            </if>
             ORDER BY measured_at ASC, seq ASC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<VitalRow> selectVitals(@Param("gatewayId") String gatewayId,
            @Param("deviceId") String deviceId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("quality") Integer quality,
            @Param("limit") int limit,
            @Param("offset") long offset);

    /**
     * 健康窗口内按网关聚合的质量分布（只统计已登记设备的帧）。
     *
     * <p>分子（可疑+缺失）与分母（总帧数）使用同一窗口
     * {@code measured_at ∈ [since, now]}：上界 now 排除设备时钟异常导致的未来帧，
     * 保证 qualityRate 不会被窗口外数据稀释或放大。
     */
    @Select("""
            SELECT v.gateway_id AS gateway_id,
                   COUNT(*) AS total_rows,
                   COUNT(*) FILTER (WHERE v.quality = 1) AS suspect_rows,
                   COUNT(*) FILTER (WHERE v.quality >= 2) AS missing_rows
              FROM m2_anes.anesthesia_vital v
              JOIN m2_anes.device_registry r
                ON r.gateway_id = v.gateway_id
               AND r.device_id = v.device_id
             WHERE v.measured_at >= #{since}
               AND v.measured_at <= #{now}
             GROUP BY v.gateway_id
             ORDER BY v.gateway_id
            """)
    List<GatewayQualityRow> qualitySince(@Param("since") Instant since,
            @Param("now") Instant now);

    /**
     * 告警评估用体征采样（{@code m2_anes.anesthesia_vital}，只读）。
     *
     * <p>取最近 {@code limit} 帧并按 measured_at 升序返回（服务层再正序处理）：记录时长可能
     * 超过 1 Hz × 数小时，用上限截断保护内存，截断时保留**最近**的采样（临床最相关），
     * 截断事实由服务层写入日志与评估结果说明，不静默。
     */
    @Select("""
            SELECT seq, measured_at, hr, spo2, nibp_sys, nibp_dia, etco2, temp,
                   quality, quality_flags
              FROM (
                    SELECT seq, measured_at, hr, spo2, nibp_sys, nibp_dia, etco2, temp,
                           quality, quality_flags
                      FROM m2_anes.anesthesia_vital
                     WHERE gateway_id = #{gatewayId}
                       AND device_id = #{deviceId}
                       AND measured_at >= #{from}
                       AND measured_at <= #{to}
                     ORDER BY measured_at DESC, seq DESC
                     LIMIT #{limit}
                   ) recent
             ORDER BY measured_at ASC, seq ASC
            """)
    List<VitalRow> selectAlertSamples(@Param("gatewayId") String gatewayId,
            @Param("deviceId") String deviceId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("limit") int limit);

    /**
     * 记录单体征映射用：术间内**全部已登记设备**在记录时间窗内的采样帧（只读）。
     *
     * <p>M9-01：记录单 `vitalSigns` 不再恒空。按记录单创建时快照的术间（anesthesia_record.room_id）
     * 关联 {@code device_registry}，窗口 {@code [from, to]} 含端点，按 measured_at 升序；
     * {@code limit} 由服务层传 MAX+1 以判定截断。不跨模块读表（设备/体征均在 m2 自身 schema）。
     */
    @Select("""
            SELECT v.seq, v.measured_at, v.hr, v.spo2, v.nibp_sys, v.nibp_dia, v.etco2, v.temp,
                   v.quality, v.quality_flags
              FROM m2_anes.anesthesia_vital v
              JOIN m2_anes.device_registry r
                ON r.gateway_id = v.gateway_id
               AND r.device_id = v.device_id
             WHERE r.room_id = #{roomId}
               AND v.measured_at >= #{from}
               AND v.measured_at <= #{to}
             ORDER BY v.measured_at ASC, v.seq ASC, v.device_id ASC
             LIMIT #{limit}
            """)
    List<VitalRow> selectVitalsByRoom(@Param("roomId") UUID roomId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("limit") int limit);
}
