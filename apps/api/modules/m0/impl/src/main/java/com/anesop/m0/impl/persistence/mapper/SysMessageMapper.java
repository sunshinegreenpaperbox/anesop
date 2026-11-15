package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内消息 Mapper（m0_platform.sys_message，V27）。
 *
 * <p>只按收件人维度查询/更新已读；消息写入由 {@code MessagePublisher} 使用本 Mapper 插入。
 */
@Mapper
public interface SysMessageMapper extends BaseMapper<SysMessage> {
}
