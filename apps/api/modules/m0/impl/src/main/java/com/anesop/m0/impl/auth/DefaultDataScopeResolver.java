package com.anesop.m0.impl.auth;

import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.impl.persistence.mapper.SysRoleMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserOrgMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserRoomMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 数据权限解析实现（M2-M0-01，方案书 §3.9.2）。
 *
 * <p>解析顺序：{@code sys_role.data_scope}（联表 sys_user_role）→ 按范围类型取绑定表
 * {@code sys_user_org}（SITE/DEPT）与 {@code sys_user_room}（ROOM，仅 status=1）。
 *
 * <p>安全语义（fail-closed）：
 * <ul>
 *   <li>用户无角色、角色范围为空、出现未知取值（含 NONE）或查询异常 → 返回
 *       {@link DataScopeAssignments#denied()}，调用方必须拒绝；</li>
 *   <li>任一角色 ALL → 直接返回 ALL（不受绑定表影响，也不为它查绑定表）；</li>
 *   <li>SITE/DEPT/ROOM 属于“必须有绑定”的范围：角色声明了但绑定行为空时整体拒绝，
 *       避免配置缺失被静默降级为“看全部”或“看空”；SELF 按角色语义无需绑定；</li>
 *   <li>多个角色取并集（去重、保序）；scopeIds 为字符串 UUID，排序稳定便于断言与审计。</li>
 * </ul>
 *
 * <p>实现无状态（不缓存用户范围），权限与绑定变更即时生效；并发解析互不影响。
 */
@Component
public class DefaultDataScopeResolver implements DataScopeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultDataScopeResolver.class);

    private final SysRoleMapper roleMapper;
    private final SysUserOrgMapper userOrgMapper;
    private final SysUserRoomMapper userRoomMapper;

    public DefaultDataScopeResolver(SysRoleMapper roleMapper,
            SysUserOrgMapper userOrgMapper,
            SysUserRoomMapper userRoomMapper) {
        this.roleMapper = roleMapper;
        this.userOrgMapper = userOrgMapper;
        this.userRoomMapper = userRoomMapper;
    }

    @Override
    public DataScopeAssignments resolve(UUID userId) {
        if (userId == null) {
            return DataScopeAssignments.denied();
        }
        List<String> rawScopes;
        try {
            rawScopes = roleMapper.selectDataScopesByUserId(userId);
        } catch (RuntimeException queryFailure) {
            // 查询失败不得回退为放行（fail-closed）。
            LOG.error("读取 sys_role.data_scope 失败，按拒绝处理：userId={}", userId, queryFailure);
            return DataScopeAssignments.denied();
        }
        if (rawScopes == null || rawScopes.isEmpty()) {
            return DataScopeAssignments.denied();
        }

        Set<DataScope> scopes = new LinkedHashSet<>();
        for (String raw : rawScopes) {
            DataScope scope = parse(raw);
            if (scope == null) {
                // 任一角色范围未知/空 → 整体拒绝，避免配置错误被静默降级为放行。
                LOG.warn("角色数据范围取值未知，按拒绝处理：userId={}", userId);
                return DataScopeAssignments.denied();
            }
            scopes.add(scope);
        }

        if (scopes.contains(DataScope.ALL)) {
            return DataScopeAssignments.of(List.of(new DataScopeAssignment(DataScope.ALL, List.of())));
        }

        List<DataScopeAssignment> assignments = new ArrayList<>(scopes.size());
        try {
            for (DataScope scope : scopes) {
                List<String> scopeIds = switch (scope) {
                    case SITE -> orgScopeIds(userId, DataScope.SITE);
                    case DEPT -> orgScopeIds(userId, DataScope.DEPT);
                    case ROOM -> roomScopeIds(userId);
                    case SELF -> List.of();
                    default -> null;
                };
                if (scopeIds == null) {
                    LOG.warn("数据范围类型不支持，按拒绝处理：userId={}", userId);
                    return DataScopeAssignments.denied();
                }
                if (requiresBinding(scope) && scopeIds.isEmpty()) {
                    // 角色声明了 SITE/DEPT/ROOM 但没有任何生效绑定 → 拒绝（未绑定不得放行）。
                    LOG.warn("数据范围缺少绑定，按拒绝处理：userId={}, scope={}", userId, scope);
                    return DataScopeAssignments.denied();
                }
                assignments.add(new DataScopeAssignment(scope, scopeIds));
            }
        } catch (RuntimeException queryFailure) {
            LOG.error("读取数据范围绑定表失败，按拒绝处理：userId={}", userId, queryFailure);
            return DataScopeAssignments.denied();
        }
        return DataScopeAssignments.of(assignments);
    }

    private List<String> orgScopeIds(UUID userId, DataScope orgType) {
        return toStrings(userOrgMapper.selectOrgIdsByUserIdAndType(userId, orgType.name()));
    }

    private List<String> roomScopeIds(UUID userId) {
        return toStrings(userRoomMapper.selectActiveRoomIdsByUserId(userId));
    }

    private static boolean requiresBinding(DataScope scope) {
        return scope == DataScope.SITE || scope == DataScope.DEPT || scope == DataScope.ROOM;
    }

    private static List<String> toStrings(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                unique.add(id.toString());
            }
        }
        return List.copyOf(unique);
    }

    private static DataScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            DataScope scope = DataScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return scope == DataScope.NONE ? null : scope;
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
