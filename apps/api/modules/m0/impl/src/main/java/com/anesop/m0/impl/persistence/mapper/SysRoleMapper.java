package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 角色表 Mapper（m0_platform.sys_role）。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /** 用户的角色编码列表（RBAC 快照）；只读联表，禁止跨 schema。 */
    @Select("""
            SELECT r.code
            FROM m0_platform.sys_role r
            JOIN m0_platform.sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            ORDER BY r.code
            """)
    List<String> selectCodesByUserId(@Param("userId") UUID userId);

    /**
     * 用户的角色数据范围列表（联表 sys_user_role → sys_role.data_scope）。
     *
     * <p>返回原始值（ALL/SITE/DEPT/ROOM/SELF）；未知值或空字符串由解析器 fail-closed。
     * 角色绑定表（用户-科室/术间）在 M1 补齐。
     */
    @Select("""
            SELECT r.data_scope
            FROM m0_platform.sys_role r
            JOIN m0_platform.sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
            ORDER BY r.code
            """)
    List<String> selectDataScopesByUserId(@Param("userId") UUID userId);
}
