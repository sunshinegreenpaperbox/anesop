package com.anesop.m0.api.security;

import java.util.List;
import java.util.UUID;

/**
 * 数据范围查询服务（M2-M0-01 受保护示例接口）：按调用者的数据范围过滤查询结果。
 *
 * <p>实现位于 m0-impl，契约见 auth.yaml 的 {@code GET /v1/auth/data-scope/users}。
 * 数据范围为空（{@link DataScopeAssignments#isDenied()}）或查询对象超出范围时抛
 * {@code org.springframework.security.access.AccessDeniedException}，由 Web 层映射为
 * 403 并写 {@code ACCESS_DENIED} 审计（方案书 §3.9.1「越权访问记审计并告警」）。
 */
public interface DataScopeQueryService {

    /**
     * 查询调用者在数据范围内可见的用户标识。
     *
     * <p>过滤语义：
     * <ul>
     *   <li>不传 orgId/roomId：ALL → 全部启用用户；否则取组织绑定成员 ∪ 术间绑定成员，
     *       含 SELF 时并上调用者本人；</li>
     *   <li>orgId：需为 ALL 或落在 {@link DataScopeAssignments#allowedOrgIds()} 内，否则 403；
     *       返回该组织的成员；</li>
     *   <li>roomId：需为 ALL 或落在 {@link DataScopeAssignments#allowedRoomIds()} 内，否则 403；
     *       返回该术间的授权成员；</li>
     *   <li>同时传两个过滤条件时取交集，任一超出范围即 403。</li>
     * </ul>
     *
     * @param callerId 调用者用户标识
     * @param orgId    组织过滤（SITE/DEPT，可空）
     * @param roomId   术间过滤（可空）
     * @return 可见用户标识（UUID 字符串，去重升序）；无可见数据时为空列表
     * @throws org.springframework.security.access.AccessDeniedException 数据范围为空或过滤对象越权
     */
    List<String> visibleUserIds(UUID callerId, UUID orgId, UUID roomId);
}
