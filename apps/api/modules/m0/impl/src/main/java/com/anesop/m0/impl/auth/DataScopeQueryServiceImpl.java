package com.anesop.m0.impl.auth;

import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeQueryService;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserOrgMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserRoomMapper;
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
            requireAllowed(scopes.isUnrestricted(), scopes.allowedOrgIds(), orgId);
        }
        if (roomId != null) {
            requireAllowed(scopes.isUnrestricted(), scopes.allowedRoomIds(), roomId);
        }

        if (orgId != null && roomId != null) {
            Set<UUID> intersection = new LinkedHashSet<>(safe(userOrgMapper.selectUserIdsByOrgId(orgId)));
            intersection.retainAll(new LinkedHashSet<>(safe(userRoomMapper.selectUserIdsByRoomId(roomId))));
            return toStrings(intersection);
        }
        if (orgId != null) {
            return toStrings(safe(userOrgMapper.selectUserIdsByOrgId(orgId)));
        }
        if (roomId != null) {
            return toStrings(safe(userRoomMapper.selectUserIdsByRoomId(roomId)));
        }

        if (scopes.isUnrestricted()) {
            return toStrings(safe(userMapper.selectAllActiveUserIds()));
        }
        Set<UUID> visible = new LinkedHashSet<>();
        for (String allowedOrgId : scopes.allowedOrgIds()) {
            visible.addAll(safe(userOrgMapper.selectUserIdsByOrgId(UUID.fromString(allowedOrgId))));
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
