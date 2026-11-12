package com.anesop.m0.impl.auth;

import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeQueryService;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserOrgMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserRoomMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * 数据范围查询实现（M2-M0-01 受保护示例接口）。
 *
 * <p>每次查询实时解析调用者数据范围（不缓存），范围为空即 403；带 orgId/roomId 过滤时
 * 先做范围校验再做查询，校验失败不访问业务表（避免通过 404/空结果泄露对象是否存在）。
 * 越权由 Web 层统一记 {@code ACCESS_DENIED} 审计。
 *
 * <p>SITE 语义：院区范围覆盖 {@code sys_org.path} 的整棵子树（院区自身 + 下属科室），
 * 因此 SITE 用户既能看下属科室成员，也能用任意下属科室的 {@code orgId} 过滤；DEPT 只精确匹配。
 * ROOM 仍只匹配已授权术间；SELF 只看本人。成员口径统一为启用且未删除用户（与 ALL 路径一致）。
 */
@Service
public class DataScopeQueryServiceImpl implements DataScopeQueryService {

    private static final Logger LOG = LoggerFactory.getLogger(DataScopeQueryServiceImpl.class);

    /** 统一越权文案（不区分“范围为空”与“对象不在范围内”，避免信息泄露）。 */
    private static final String DENIED_MESSAGE = "数据范围不足，拒绝访问";

    private final DataScopeResolver dataScopeResolver;
    private final SysUserMapper userMapper;
    private final SysUserOrgMapper userOrgMapper;
    private final SysUserRoomMapper userRoomMapper;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 单例注入的 MyBatis Mapper 代理，生命周期由容器管理，服务不对外暴露该引用（评审 Q-1）")
    public DataScopeQueryServiceImpl(DataScopeResolver dataScopeResolver,
            SysUserMapper userMapper,
            SysUserOrgMapper userOrgMapper,
            SysUserRoomMapper userRoomMapper) {
        this.dataScopeResolver = dataScopeResolver;
        this.userMapper = userMapper;
        this.userOrgMapper = userOrgMapper;
        this.userRoomMapper = userRoomMapper;
    }

    @Override
    public List<String> visibleUserIds(UUID callerId, UUID orgId, UUID roomId) {
        if (callerId == null) {
            throw new AccessDeniedException(DENIED_MESSAGE);
        }
        DataScopeAssignments scopes = dataScopeResolver.resolve(callerId);
        if (scopes.isDenied()) {
            LOG.warn("数据范围为空，拒绝查询：userId={}", callerId);
            throw new AccessDeniedException(DENIED_MESSAGE);
        }
        if (orgId != null) {
            requireOrgAllowed(scopes, orgId);
        }
        if (roomId != null) {
            requireAllowed(scopes.isUnrestricted(), scopes.allowedRoomIds(), roomId);
        }

        if (orgId != null && roomId != null) {
            Set<UUID> intersection = new LinkedHashSet<>(orgMembers(orgId));
            intersection.retainAll(new LinkedHashSet<>(safe(userRoomMapper.selectUserIdsByRoomId(roomId))));
            return toStrings(intersection);
        }
        if (orgId != null) {
            return toStrings(orgMembers(orgId));
        }
        if (roomId != null) {
            return toStrings(safe(userRoomMapper.selectUserIdsByRoomId(roomId)));
        }

        if (scopes.isUnrestricted()) {
            return toStrings(safe(userMapper.selectAllActiveUserIds()));
        }
        Set<UUID> visible = new LinkedHashSet<>();
        for (DataScopeAssignment assignment : scopes.assignments()) {
            if (assignment.type() == DataScope.SITE) {
                // 院区范围 = 院区自身 + 下属科室（path 前缀），不是只取院区直属成员。
                for (String siteId : assignment.scopeIds()) {
                    visible.addAll(safe(userOrgMapper.selectUserIdsByOrgSubtree(UUID.fromString(siteId))));
                }
            } else if (assignment.type() == DataScope.DEPT) {
                for (String deptId : assignment.scopeIds()) {
                    visible.addAll(safe(userOrgMapper.selectUserIdsByOrgId(UUID.fromString(deptId))));
                }
            }
        }
        for (String allowedRoomId : scopes.allowedRoomIds()) {
            visible.addAll(safe(userRoomMapper.selectUserIdsByRoomId(UUID.fromString(allowedRoomId))));
        }
        if (hasSelfScope(scopes)) {
            // SELF：只能看到本人；不因组织/术间绑定而扩大。
            visible.add(callerId);
        }
        return toStrings(visible);
    }

    /**
     * 组织成员：SITE 取整棵子树（含院区自身），DEPT 及其他按组织精确匹配。
     * 组织在查询前被停用/删除时，按精确匹配返回空（fail-closed，不放大）。
     */
    private List<UUID> orgMembers(UUID orgId) {
        String orgType = userOrgMapper.selectActiveOrgType(orgId);
        if ("SITE".equals(orgType)) {
            return safe(userOrgMapper.selectUserIdsByOrgSubtree(orgId));
        }
        return safe(userOrgMapper.selectUserIdsByOrgId(orgId));
    }

    /**
     * 组织过滤校验：ALL 放行；DEPT 精确匹配；SITE 允许院区自身及任意下属组织（path 子树）。
     * 未通过时抛 {@link AccessDeniedException}，不访问成员表。
     */
    private void requireOrgAllowed(DataScopeAssignments scopes, UUID orgId) {
        if (scopes.isUnrestricted() || scopes.allowedOrgIds().contains(orgId.toString())) {
            return;
        }
        for (DataScopeAssignment assignment : scopes.assignments()) {
            if (assignment.type() != DataScope.SITE) {
                continue;
            }
            for (String siteId : assignment.scopeIds()) {
                if (userOrgMapper.isOrgInSubtree(UUID.fromString(siteId), orgId)) {
                    return;
                }
            }
        }
        LOG.warn("数据范围不包含请求对象，拒绝访问：requestedObject={}", orgId);
        throw new AccessDeniedException(DENIED_MESSAGE);
    }

    private static void requireAllowed(boolean unrestricted, List<String> allowedIds, UUID requestedId) {
        if (unrestricted || allowedIds.contains(requestedId.toString())) {
            return;
        }
        LOG.warn("数据范围不包含请求对象，拒绝访问：requestedObject={}", requestedId);
        throw new AccessDeniedException(DENIED_MESSAGE);
    }

    private static boolean hasSelfScope(DataScopeAssignments scopes) {
        return scopes.assignments().stream()
                .anyMatch(assignment -> assignment.type() == DataScope.SELF);
    }

    private static List<UUID> safe(List<UUID> ids) {
        return ids == null ? List.of() : ids;
    }

    private static List<String> toStrings(Set<UUID> ids) {
        Set<String> sorted = new TreeSet<>();
        for (UUID id : ids) {
            if (id != null) {
                sorted.add(id.toString());
            }
        }
        return List.copyOf(sorted);
    }

    private static List<String> toStrings(List<UUID> ids) {
        return toStrings(new LinkedHashSet<>(safe(ids)));
    }
}
