package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysPermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
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

    /**
     * 给定角色编码集合的并集权限点（M2-M0-02 S-10 提权校验：角色权限点必须 ⊆ 操作人权限点）。
     *
     * @param roleCodes 角色编码集合（调用方已校验非空且存在）
     * @return 去重后的权限码，升序；无权限点的角色不返回任何行
     */
    @Select("""
            <script>
            SELECT DISTINCT p.code
            FROM m0_platform.sys_permission p
            JOIN m0_platform.sys_role_permission rp ON rp.permission_id = p.id
            JOIN m0_platform.sys_role r ON r.id = rp.role_id
            WHERE r.code IN
            <foreach collection="roleCodes" item="code" open="(" separator="," close=")">#{code}</foreach>
            ORDER BY p.code
            </script>
            """)
    List<String> selectCodesByRoleCodes(@Param("roleCodes") Collection<String> roleCodes);
}
