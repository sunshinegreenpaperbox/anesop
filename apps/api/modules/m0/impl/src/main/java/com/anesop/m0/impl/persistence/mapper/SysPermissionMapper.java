package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysPermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 权限点 Mapper（m0_platform.sys_permission）。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /** 用户经角色继承的功能权限码列表（去重）；权限变更即时生效。 */
    @Select("""
            SELECT DISTINCT p.code
            FROM m0_platform.sys_permission p
            JOIN m0_platform.sys_role_permission rp ON rp.permission_id = p.id
            JOIN m0_platform.sys_user_role ur ON ur.role_id = rp.role_id
            WHERE ur.user_id = #{userId}
            ORDER BY p.code
            """)
    List<String> selectCodesByUserId(@Param("userId") UUID userId);
}
