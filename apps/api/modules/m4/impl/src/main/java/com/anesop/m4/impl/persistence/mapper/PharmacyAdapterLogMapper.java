package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.PharmacyAdapterLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 药房适配器（EXT-02）同步留痕 Mapper（{@code m4_drug.pharmacy_adapter_log}，V20）。
 *
 * <p>append-only 运维日志：只 INSERT 与查询最近一条；detail 为状态摘要 jsonb 文本。
 */
@Mapper
public interface PharmacyAdapterLogMapper extends BaseMapper<PharmacyAdapterLogEntity> {

    @Insert("""
            INSERT INTO m4_drug.pharmacy_adapter_log
                (id, adapter_code, operation, success, degraded, detail, occurred_at)
            VALUES
                (#{id}, #{adapterCode}, #{operation}, #{success}, #{degraded},
                 #{detail}::jsonb, #{occurredAt})
            """)
    int insertLog(PharmacyAdapterLogEntity log);

    @Select("""
            SELECT * FROM m4_drug.pharmacy_adapter_log
             ORDER BY occurred_at DESC, id DESC
             LIMIT 1
            """)
    PharmacyAdapterLogEntity selectLatest();

    @Select("SELECT count(*) FROM m4_drug.pharmacy_adapter_log")
    long countAll(@Param("adapterCode") String adapterCode);
}
