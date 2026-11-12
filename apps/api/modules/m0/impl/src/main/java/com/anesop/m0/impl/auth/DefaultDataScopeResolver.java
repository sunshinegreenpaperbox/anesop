package com.anesop.m0.impl.auth;

import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.impl.persistence.mapper.SysRoleMapper;
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
 * 数据权限解析实现（S0-07 / S-8）：读取 {@code sys_role.data_scope}（联表 sys_user_role）。
 *
 * <p>安全语义（fail-closed）：
 * <ul>
 *   <li>用户无角色、所有角色范围为空、出现未知取值或查询异常 → 返回
 *       {@link DataScopeAssignments#denied()}（{@link DataScope#NONE}），调用方必须拒绝；</li>
 *   <li>合法取值 ALL/SITE/DEPT/ROOM/SELF 按角色映射，去重后返回；含 ALL 即视为 ALL；</li>
 *   <li>角色/用户与科室、术间、患者的绑定表（scopeIds）在 M1 补齐。</li>
 * </ul>
 */
@Component
public class DefaultDataScopeResolver implements DataScopeResolver {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultDataScopeResolver.class);

    private final SysRoleMapper roleMapper;

    public DefaultDataScopeResolver(SysRoleMapper roleMapper) {
        this.roleMapper = roleMapper;
    }

    @Override
    public List<DataScopeAssignment> resolve(UUID userId) {
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
            return List.of(new DataScopeAssignment(DataScope.ALL, List.of()));
        }
        List<DataScopeAssignment> assignments = new ArrayList<>(scopes.size());
        for (DataScope scope : scopes) {
            assignments.add(new DataScopeAssignment(scope, List.of()));
        }
        return List.copyOf(assignments);
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
