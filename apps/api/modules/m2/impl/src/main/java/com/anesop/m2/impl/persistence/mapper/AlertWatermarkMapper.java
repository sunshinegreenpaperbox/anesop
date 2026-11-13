package com.anesop.m2.impl.persistence.mapper;

import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 告警评估水位 Mapper（{@code m2_anes.alert_watermark}，V16）。
 *
 * <p>水位 = 该记录单已评估到的最大 {@code measured_at}；实时评估只对水位之后的新采样落库
 * 判定，水位只增（{@code GREATEST}），绝不回退。并发评估由告警 {@code dedupe_key} 唯一键兜底。
 */
@Mapper
public interface AlertWatermarkMapper {

    /** 读取水位；无行表示该记录单尚未评估过（新记录）。 */
    @Select("""
            SELECT evaluated_through
              FROM m2_anes.alert_watermark
             WHERE record_id = #{recordId}
            """)
    Instant selectEvaluatedThrough(@Param("recordId") UUID recordId);

    /**
     * 推进水位（只增）。
     *
     * @return 1=推进成功，0=已有更高水位（乱序/并发评估，无需回退）
     */
    @Insert("""
            INSERT INTO m2_anes.alert_watermark (record_id, evaluated_through, updated_at)
            VALUES (#{recordId}, #{evaluatedThrough}, #{updatedAt})
            ON CONFLICT (record_id) DO UPDATE
               SET evaluated_through = GREATEST(
                       m2_anes.alert_watermark.evaluated_through, EXCLUDED.evaluated_through),
                   updated_at = EXCLUDED.updated_at
             WHERE m2_anes.alert_watermark.evaluated_through < EXCLUDED.evaluated_through
            """)
    int advance(@Param("recordId") UUID recordId,
            @Param("evaluatedThrough") Instant evaluatedThrough,
            @Param("updatedAt") Instant updatedAt);
}
