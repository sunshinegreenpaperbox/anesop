package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysRefreshToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 刷新令牌表 Mapper（m0_platform.sys_refresh_token，V3）。
 *
 * <p>只允许插入、按哈希查询、条件撤销（revoked_at）与分页查询；
 * 不提供物理删除入口，历史会话保留用于审计与在线设备查看。
 */
@Mapper
public interface SysRefreshTokenMapper extends BaseMapper<SysRefreshToken> {

    /**
     * 撤销某用户的全部未撤销刷新令牌（M2-M0-02 S-4：管理员重置口令时使既有会话失效）。
     *
     * <p>只置 {@code revoked_at}，不物理删除：历史会话保留用于审计与在线设备查看；
     * 条件 {@code revoked_at IS NULL} 保证幂等。
     *
     * @return 本次撤销的令牌行数（0=无有效会话）
     */
    @Update("""
            UPDATE m0_platform.sys_refresh_token
            SET revoked_at = now()
            WHERE user_id = #{userId} AND revoked_at IS NULL
            """)
    int revokeAllByUserId(@Param("userId") UUID userId);
}
