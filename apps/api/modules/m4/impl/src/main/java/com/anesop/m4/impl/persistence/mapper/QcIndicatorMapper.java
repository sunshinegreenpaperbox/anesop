package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.QcIndicatorEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 质控指标定义 Mapper（{@code m4_qc.qc_indicator}，V25）。
 *
 * <p>只读接口：指标口径/阈值变更必须新增版本行（数据库守卫 55000），本期不提供写入端点。
 * 两个查询语义不同：
 * <ul>
 *   <li>{@link #selectAllActive()}：指标目录（全部 ACTIVE 版本，含未来生效版本）——只做配置展示；</li>
 *   <li>{@link #selectActive(LocalDate, LocalDate)}：**按查询周期选择生效版本**，
 *       {@code effective_from <= to AND (effective_to IS NULL OR effective_to >= from)}，
 *       计算与导出必须使用本查询，避免把未来版本算进历史周期。</li>
 * </ul>
 */
@Mapper
public interface QcIndicatorMapper extends BaseMapper<QcIndicatorEntity> {

    /** 指标目录：全部 {@code ACTIVE} 版本（类别 → 编码 → 版本倒序），不按日期裁剪。 */
    @Select("""
            SELECT * FROM m4_qc.qc_indicator
             WHERE status = 'ACTIVE'
             ORDER BY category, code, version DESC
            """)
    List<QcIndicatorEntity> selectAllActive();

    /**
     * 查询周期内的生效版本：{@code effective_from <= to} 且
     * {@code effective_to 为空或 >= from}（闭区间重叠）。
     *
     * <p>同一 {@code code} 可能返回多个版本（周期跨版本时相邻版本各返回一次）；
     * 版本区间是否重叠由服务层 fail-closed 校验（409）。
     */
    @Select("""
            SELECT * FROM m4_qc.qc_indicator
             WHERE status = 'ACTIVE'
               AND effective_from <= #{to}
               AND (effective_to IS NULL OR effective_to >= #{from})
             ORDER BY category, code, effective_from, version
            """)
    List<QcIndicatorEntity> selectActive(@Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** 指定编码当前生效的最新版本（供测试/运维核对使用）。 */
    @Select("""
            SELECT * FROM m4_qc.qc_indicator
             WHERE code = #{code} AND status = 'ACTIVE'
             ORDER BY effective_from DESC, version DESC
             LIMIT 1
            """)
    QcIndicatorEntity selectActiveByCode(String code);
}
