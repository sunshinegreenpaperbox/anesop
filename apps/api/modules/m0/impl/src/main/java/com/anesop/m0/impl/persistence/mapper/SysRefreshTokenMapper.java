package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysRefreshToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 刷新令牌表 Mapper（m0_platform.sys_refresh_token，V3）。
 *
 * <p>只允许插入、按哈希查询、条件撤销（revoked_at）与分页查询；
 * 不提供物理删除入口，历史会话保留用于审计与在线设备查看。
 */
@Mapper
public interface SysRefreshTokenMapper extends BaseMapper<SysRefreshToken> {
}
