package com.anesop.m2.impl.persistence.mapper;

import com.anesop.m2.impl.persistence.entity.ResuscitationRecordEntity;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 抢救记录 Mapper（{@code m2_anes.resuscitation_record}，V15）。
 *
 * <p>append-only：只提供 INSERT 与查询，不提供 UPDATE/DELETE（数据库触发器兜底拒绝，
 * SQLSTATE 55000）。jsonb 列以字符串写入，{@code ::jsonb} 转换由数据库完成。
 */
@Mapper
public interface ResuscitationRecordMapper {

    /** 新增抢救记录（append-only）。 */
    @Insert("""
            INSERT INTO m2_anes.resuscitation_record
                (id, record_id, resus_type, started_at, ended_at, event_summary, drugs,
                 leader, rescuers, outcome, created_at, created_by)
            VALUES
                (#{id}, #{recordId}, #{resusType}, #{startedAt}, #{endedAt},
                 #{eventSummary}::jsonb, #{drugs}::jsonb, #{leader}::jsonb, #{rescuers}::jsonb,
                 #{outcome}, #{createdAt}, #{createdBy})
            """)
    int insertRecord(ResuscitationRecordEntity record);

    /** 按记录单查询（开始时间倒序）。 */
    @Select("""
            SELECT id, record_id, resus_type, started_at, ended_at,
                   event_summary::text AS event_summary, drugs::text AS drugs,
                   leader::text AS leader, rescuers::text AS rescuers,
                   outcome, created_at, created_by
              FROM m2_anes.resuscitation_record
             WHERE record_id = #{recordId}
             ORDER BY started_at DESC, created_at DESC
            """)
    List<ResuscitationRecordEntity> selectByRecord(@Param("recordId") UUID recordId);
}
