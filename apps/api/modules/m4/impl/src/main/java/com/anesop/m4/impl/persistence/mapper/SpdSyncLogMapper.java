package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.SpdSyncLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * SPD 适配器（EXT-03）同步留痕 Mapper（{@code m4_material.spd_sync_log}，V23）。
 *
 * <p>只 INSERT 与查询；detail 为 jsonb 状态摘要，不含外部凭据、供应商明细与患者信息。
 */
@Mapper
public interface SpdSyncLogMapper extends BaseMapper<SpdSyncLogEntity> {

    @Insert("""
            INSERT INTO m4_material.spd_sync_log
                (id, adapter_code, operation, success, degraded, detail, occurred_at)
            VALUES
                (#{id}, #{adapterCode}, #{operation}, #{success}, #{degraded}, #{detail}::jsonb,
                 #{occurredAt})
            """)
    int insertLog(SpdSyncLogEntity log);

    @Select("""
            SELECT * FROM m4_material.spd_sync_log
             ORDER BY occurred_at DESC, id
             LIMIT 1
            """)
    SpdSyncLogEntity selectLast();

    /** 最近一次成功之后仍未成功的条数（模拟待同步）。 */
    @Select("""
            SELECT count(*) FROM m4_material.spd_sync_log
             WHERE success = false
               AND occurred_at > COALESCE(
                   (SELECT max(occurred_at) FROM m4_material.spd_sync_log WHERE success),
                   TIMESTAMPTZ 'epoch')
            """)
    long countPending();
}
