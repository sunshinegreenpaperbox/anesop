package com.anesop.m0.api.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 组织（科室）只读查询服务（M4-M15-01 B-3 / S-5）。
 *
 * <p>业务模块不得直连 {@code m0_platform.sys_org}/{@code sys_user_org}，需要“科室是否存在、
 * 是否启用、是否为 DEPT”与“调用者可见科室”时统一走本接口。实现位于 m0-impl，按调用者的
 * m0 数据范围收敛结果（ALL 全院；SITE 院区子树；DEPT 授权科室；SELF 本人绑定科室；
 * ROOM/无范围返回空或拒绝）。
 *
 * <p>安全约定：
 * <ul>
 *   <li>只返回 {@code org_type=DEPT} 且 {@code status=1 AND deleted=false} 的组织；</li>
 *   <li>{@link #visibleDepartments(UUID)} 在调用者无数据范围时抛
 *       {@code AccessDeniedException}（fail-closed，由 Web 层映射 403）；</li>
 *   <li>返回值只含科室标识/编码/名称，不含任何人员或患者信息。</li>
 * </ul>
 */
public interface OrganizationQueryService {

    /**
     * 调用者可见的启用科室（去重，按编码排序）。
     *
     * <p>语义：ALL → 全部启用科室；SITE → 授予院区子树内的启用科室；DEPT → 绑定的启用科室；
     * SELF → 本人绑定的启用科室（用于“本人所属科室”推导）；ROOM/无组织绑定 → 空列表。
     *
     * @param callerId 调用者用户标识
     * @return 可见科室列表；无可见科室时为空列表
     * @throws org.springframework.security.access.AccessDeniedException 无数据范围（fail-closed）
     */
    List<OrgDepartment> visibleDepartments(UUID callerId);

    /**
     * 用户绑定的启用科室（去重，主科室在前）。
     *
     * @param userId 用户标识；null 返回空列表
     * @return 绑定科室列表；无绑定/全部停用时为空列表
     */
    List<OrgDepartment> departmentsOfUser(UUID userId);

    /**
     * 校验科室存在、启用且为 {@code DEPT}。
     *
     * @param orgId 组织标识；null 返回 empty
     * @return 科室视图；不存在/停用/软删除/非 DEPT 时返回 empty
     */
    Optional<OrgDepartment> findActiveDepartment(UUID orgId);
}
