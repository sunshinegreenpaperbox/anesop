package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 站内消息 Mapper（m0_platform.sys_message，V27）。
 *
 * <p>只按收件人维度查询/更新已读；消息写入由 {@code MessagePublisher} 使用本 Mapper 插入。
 */
@Mapper
public interface SysMessageMapper extends BaseMapper<SysMessage> {

    /**
     * 带去重键的幂等插入（V29）：dedupe_key 冲突时不做任何写入。
     *
     * <p>用显式 SQL 而非 {@code BaseMapper.insert}，因为 MyBatis-Plus 不支持
     * {@code ON CONFLICT DO NOTHING}；返回 0 表示命中已有去重键，由服务层回查既有消息。
     * {@code params} 为 jsonb，写入用 {@code CAST(... AS jsonb)}，避免依赖 TypeHandler。
     */
    @Insert("""
            INSERT INTO m0_platform.sys_message
                (id, recipient_user_id, message_type, template_code, title, params,
                 dedupe_key, created_at, created_by)
            VALUES
                (#{id}, #{recipientUserId}, #{messageType}, #{templateCode}, #{title},
                 CAST(#{params} AS jsonb), #{dedupeKey}, #{createdAt}, #{createdBy})
            ON CONFLICT (dedupe_key) DO NOTHING
            """)
    int insertIgnoreDedupe(SysMessage message);

    /** 按去重键回查既有消息（只用于命中唯一键后返回原消息标识）。 */
    @Select("""
            SELECT id, recipient_user_id, message_type, template_code, title,
                   params::text AS params, dedupe_key, read_at, created_at, created_by
              FROM m0_platform.sys_message
             WHERE dedupe_key = #{dedupeKey}
             LIMIT 1
            """)
    SysMessage selectByDedupeKey(@Param("dedupeKey") String dedupeKey);
}
