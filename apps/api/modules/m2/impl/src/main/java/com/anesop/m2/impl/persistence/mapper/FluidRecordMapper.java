package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.FluidRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 输液/出入量明细 Mapper（{@code m2_anes.fluid_record}，V13）。
 *
 * <p>append-only：只提供 INSERT 与查询；出入量汇总由 SQL 按 direction 聚合，
 * 服务层不持有可被客户端覆盖的合计数。
 */
@Mapper
public interface FluidRecordMapper extends BaseMapper<FluidRecordEntity> {

    @Insert("""
            INSERT INTO m2_anes.fluid_record
                (id, record_id, direction, fluid_type, amount_ml, recorded_at, note,
                 created_at, created_by)
            VALUES
                (#{id}, #{recordId}, #{direction}, #{fluidType}, #{amountMl}, #{recordedAt},
                 #{note}, #{createdAt}, #{createdBy})
            """)
    int insertFluid(FluidRecordEntity fluid);

    /** 按记录时间倒序（同一时间按录入时间倒序）。 */
    @Select("""
            SELECT id, record_id, direction, fluid_type, amount_ml, recorded_at, note,
                   created_at, created_by
              FROM m2_anes.fluid_record
             WHERE record_id = #{recordId}
             ORDER BY recorded_at DESC, created_at DESC
            """)
    List<FluidRecordEntity> selectByRecord(@Param("recordId") UUID recordId);

    /** 出入量聚合：intake / output / fluidCount（无明细时均为 0）。 */
    @Select("""
            SELECT COALESCE(SUM(amount_ml) FILTER (WHERE direction = 'INTAKE'), 0) AS intake,
                   COALESCE(SUM(amount_ml) FILTER (WHERE direction = 'OUTPUT'), 0) AS output,
                   COUNT(*) AS fluid_count
              FROM m2_anes.fluid_record
             WHERE record_id = #{recordId}
            """)
    Map<String, Object> selectTotals(@Param("recordId") UUID recordId);
}
