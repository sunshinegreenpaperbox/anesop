package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.QcIndicatorResultEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 指标结果快照 Mapper（{@code m4_qc.qc_indicator_result}，V25）。
 *
 * <p>唯一键 = 指标 + 周期 + 科室（{@code dept_id} 全 0 哨兵=全院）：重算用
 * {@code ON CONFLICT DO UPDATE} 覆盖同一快照的分子/分母/值/达标与计算时间，不产生多行；
 * 指标升版本后因 {@code indicator_id}/{@code indicator_version} 不同而独立保留历史结果。
 */
@Mapper
public interface QcIndicatorResultMapper extends BaseMapper<QcIndicatorResultEntity> {

    /** 幂等写入/重算快照（同键覆盖，跨版本独立）。 */
    @Insert("""
            INSERT INTO m4_qc.qc_indicator_result
                (id, indicator_id, indicator_code, indicator_version, period_from, period_to,
                 dept_id, numerator, denominator, value, target_met, computed_at, created_at)
            VALUES
                (#{id}, #{indicatorId}, #{indicatorCode}, #{indicatorVersion}, #{periodFrom},
                 #{periodTo}, #{deptId}, #{numerator}, #{denominator}, #{value}, #{targetMet},
                 #{computedAt}, #{computedAt})
            ON CONFLICT (indicator_id, period_from, period_to, dept_id)
            DO UPDATE SET
                indicator_code = EXCLUDED.indicator_code,
                indicator_version = EXCLUDED.indicator_version,
                numerator = EXCLUDED.numerator,
                denominator = EXCLUDED.denominator,
                value = EXCLUDED.value,
                target_met = EXCLUDED.target_met,
                computed_at = EXCLUDED.computed_at
            """)
    int upsert(QcIndicatorResultEntity result);
}
