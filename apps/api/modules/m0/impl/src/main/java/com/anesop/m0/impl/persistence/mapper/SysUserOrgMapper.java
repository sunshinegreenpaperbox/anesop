package com.anesop.m0.impl.persistence.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户—组织绑定 Mapper（m0_platform.sys_user_org，V5）。
 *
 * <p>数据范围 SITE/DEPT 的 scopeIds 来源；只读联表 sys_org，未绑定/组织停用或删除时返回空，
 * 由 {@code DefaultDataScopeResolver} 按 fail-closed 处理（不放大为 ALL/SELF）。
 * 绑定关系的维护（组织 CRUD）在 M2 组织接口，本 Mapper 只提供解析与示例查询所需的读取。
 */
@Mapper
public interface SysUserOrgMapper {

    /**
     * 用户绑定到的、指定类型的启用组织标识（SITE 或 DEPT）。
     *
     * @param userId  用户标识
     * @param orgType 组织类型：SITE / DEPT
     * @return 组织标识列表（升序）；无绑定或组织停用/删除时为空
     */
    @Select("""
            SELECT uo.org_id
            FROM m0_platform.sys_user_org uo
            JOIN m0_platform.sys_org o ON o.id = uo.org_id
            WHERE uo.user_id = #{userId}
              AND o.org_type = #{orgType}
              AND o.status = 1
              AND o.deleted = false
            ORDER BY uo.org_id
            """)
    List<UUID> selectOrgIdsByUserIdAndType(@Param("userId") UUID userId, @Param("orgType") String orgType);

    /**
     * 组织下的有效成员（未删除用户），供数据范围示例接口按组织过滤。
     *
     * @param orgId 组织标识
     * @return 用户标识列表（升序）；组织不存在/已删除时为空
     */
    @Select("""
            SELECT uo.user_id
            FROM m0_platform.sys_user_org uo
            JOIN m0_platform.sys_user u ON u.id = uo.user_id AND u.deleted = false
            JOIN m0_platform.sys_org o ON o.id = uo.org_id AND o.deleted = false
            WHERE uo.org_id = #{orgId}
            ORDER BY uo.user_id
            """)
    List<UUID> selectUserIdsByOrgId(@Param("orgId") UUID orgId);
}
