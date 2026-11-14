package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysOrgRow;
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
 * SITE 范围按 {@code sys_org.path} 前缀覆盖整棵子树（院区下的全部科室），DEPT 只精确匹配本科室。
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
     * 组织下的有效成员（启用且未删除用户），供数据范围示例接口按组织过滤。
     *
     * <p>成员口径与 ALL 路径（{@code SysUserMapper#selectAllActiveUserIds}）一致：
     * {@code u.status = 1 AND u.deleted = false}；停用用户不得因历史绑定继续可见。
     *
     * @param orgId 组织标识
     * @return 用户标识列表（升序）；组织不存在/停用/已删除时为空
     */
    @Select("""
            SELECT uo.user_id
            FROM m0_platform.sys_user_org uo
            JOIN m0_platform.sys_user u ON u.id = uo.user_id
                AND u.status = 1 AND u.deleted = false
            JOIN m0_platform.sys_org o ON o.id = uo.org_id
                AND o.status = 1 AND o.deleted = false
            WHERE uo.org_id = #{orgId}
            ORDER BY uo.user_id
            """)
    List<UUID> selectUserIdsByOrgId(@Param("orgId") UUID orgId);

    /**
     * SITE 子树（含院区自身）下的有效成员，供 SITE 数据范围展开到下属科室。
     *
     * <p>用 {@code sys_org.path} 前缀匹配（{@code o.path = root.path} 或
     * {@code o.path LIKE root.path || '/%'}），停用/删除的组织与用户不参与；根组织停用或删除时
     * 返回空，由调用方按 fail-closed 处理。
     *
     * @param rootOrgId 院区组织标识
     * @return 用户标识列表（升序，去重）；根组织无效或子树无成员时为空
     */
    @Select("""
            SELECT DISTINCT uo.user_id
            FROM m0_platform.sys_user_org uo
            JOIN m0_platform.sys_user u ON u.id = uo.user_id
                AND u.status = 1 AND u.deleted = false
            JOIN m0_platform.sys_org o ON o.id = uo.org_id
                AND o.status = 1 AND o.deleted = false
            JOIN m0_platform.sys_org root ON root.id = #{rootOrgId}
                AND root.status = 1 AND root.deleted = false
            WHERE o.path = root.path OR o.path LIKE root.path || '/%'
            ORDER BY uo.user_id
            """)
    List<UUID> selectUserIdsByOrgSubtree(@Param("rootOrgId") UUID rootOrgId);

    /**
     * 启用且未删除组织的类型（SITE / DEPT），用于区分“组织成员”取子树还是精确匹配。
     *
     * @param orgId 组织标识
     * @return 组织类型；不存在/停用/已删除时返回 null
     */
    @Select("""
            SELECT org_type
            FROM m0_platform.sys_org
            WHERE id = #{orgId} AND status = 1 AND deleted = false
            """)
    String selectActiveOrgType(@Param("orgId") UUID orgId);

    /**
     * 请求组织是否落在给定院区的子树内（含院区自身），用于 SITE 范围的 orgId 越权校验。
     *
     * <p>任一侧停用/删除即返回 false（fail-closed），避免停用组织通过旧路径继续放行。
     *
     * @param rootOrgId 授予的院区组织标识
     * @param orgId     请求过滤的组织标识
     * @return true 表示在子树内
     */
    @Select("""
            SELECT count(*) > 0
            FROM m0_platform.sys_org root
            JOIN m0_platform.sys_org o ON o.id = #{orgId}
            WHERE root.id = #{rootOrgId}
              AND root.status = 1 AND root.deleted = false
              AND o.status = 1 AND o.deleted = false
              AND (o.path = root.path OR o.path LIKE root.path || '/%')
            """)
    boolean isOrgInSubtree(@Param("rootOrgId") UUID rootOrgId, @Param("orgId") UUID orgId);

    /**
     * 全部启用科室（{@code org_type=DEPT}），供 ALL 数据范围与科室选择使用。
     *
     * @return 科室行（按排序号、编码升序）；无科室时为空
     */
    @Select("""
            SELECT o.id, o.code, o.name
            FROM m0_platform.sys_org o
            WHERE o.org_type = 'DEPT' AND o.status = 1 AND o.deleted = false
            ORDER BY o.sort, o.code
            """)
    List<SysOrgRow> selectAllActiveDepartments();

    /**
     * 用户绑定的启用科室（主科室在前），供 SELF 范围推导“本人所属科室”。
     *
     * @param userId 用户标识
     * @return 科室行（主科室在前，其后按排序号/编码）；无绑定或组织停用时为空
     */
    @Select("""
            SELECT o.id, o.code, o.name
            FROM m0_platform.sys_user_org uo
            JOIN m0_platform.sys_org o ON o.id = uo.org_id
            WHERE uo.user_id = #{userId}
              AND o.org_type = 'DEPT' AND o.status = 1 AND o.deleted = false
            ORDER BY uo.is_primary DESC, o.sort, o.code
            """)
    List<SysOrgRow> selectActiveDepartmentsByUserId(@Param("userId") UUID userId);

    /**
     * 院区子树内的启用科室（含院区自身仅当其为 DEPT，正常不命中），供 SITE 范围展开。
     *
     * @param siteId 授予的院区组织标识
     * @return 科室行（按排序号/编码）；院区停用/删除或子树内无科室时为空
     */
    @Select("""
            SELECT o.id, o.code, o.name
            FROM m0_platform.sys_org o
            WHERE o.org_type = 'DEPT' AND o.status = 1 AND o.deleted = false
              AND EXISTS (
                  SELECT 1 FROM m0_platform.sys_org root
                  WHERE root.id = #{siteId}
                    AND root.status = 1 AND root.deleted = false
                    AND (o.path = root.path OR o.path LIKE root.path || '/%')
              )
            ORDER BY o.sort, o.code
            """)
    List<SysOrgRow> selectActiveDepartmentsInSubtree(@Param("siteId") UUID siteId);

    /**
     * 单个启用科室（{@code org_type=DEPT}）。
     *
     * @param orgId 组织标识
     * @return 科室行；不存在/停用/软删除/非 DEPT 时返回 null
     */
    @Select("""
            SELECT o.id, o.code, o.name
            FROM m0_platform.sys_org o
            WHERE o.id = #{orgId}
              AND o.org_type = 'DEPT' AND o.status = 1 AND o.deleted = false
            """)
    SysOrgRow selectActiveDepartment(@Param("orgId") UUID orgId);
}
