package com.anesop.m0.impl.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.impl.persistence.mapper.SysRoleMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 数据权限解析（S-8）：读取 sys_role.data_scope（联表），未知/空/异常 fail-closed。
 */
class DefaultDataScopeResolverTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    private final SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    private final DefaultDataScopeResolver resolver = new DefaultDataScopeResolver(roleMapper);

    @Test
    void adminScopeResolvesToAll() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("ALL"));

        List<DataScopeAssignment> scopes = resolver.resolve(USER_ID);

        assertThat(scopes).hasSize(1);
        assertThat(scopes.get(0).type()).isEqualTo(DataScope.ALL);
        assertThat(scopes.get(0).scopeIds()).isEmpty();
        assertThat(DataScopeAssignments.isDenied(scopes)).isFalse();
    }

    @Test
    void multipleRolesKeepDistinctScopes() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("DEPT", "SELF", "DEPT"));

        List<DataScopeAssignment> scopes = resolver.resolve(USER_ID);

        assertThat(scopes).extracting(DataScopeAssignment::type)
                .containsExactly(DataScope.DEPT, DataScope.SELF);
        assertThat(DataScopeAssignments.isDenied(scopes)).isFalse();
    }

    @Test
    void allWinsOverNarrowerScopes() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("SELF", "ALL"));

        assertThat(resolver.resolve(USER_ID)).extracting(DataScopeAssignment::type)
                .containsExactly(DataScope.ALL);
    }

    @Test
    void unknownScopeFailsClosed() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("ALL", "EVERYTHING"));

        List<DataScopeAssignment> scopes = resolver.resolve(USER_ID);

        assertThat(scopes).extracting(DataScopeAssignment::type).containsExactly(DataScope.NONE);
        assertThat(DataScopeAssignments.isDenied(scopes)).isTrue();
    }

    @Test
    void blankOrNoRolesFailsClosed() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of());

        assertThat(DataScopeAssignments.isDenied(resolver.resolve(USER_ID))).isTrue();

        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("  "));
        assertThat(DataScopeAssignments.isDenied(resolver.resolve(USER_ID))).isTrue();
    }

    @Test
    void queryFailureFailsClosed() {
        when(roleMapper.selectDataScopesByUserId(USER_ID))
                .thenThrow(new IllegalStateException("SYNTHETIC DB DOWN"));

        assertThat(DataScopeAssignments.isDenied(resolver.resolve(USER_ID))).isTrue();
    }

    @Test
    void nullUserIsDeniedWithoutQuery() {
        assertThat(DataScopeAssignments.isDenied(resolver.resolve(null))).isTrue();
        verifyNoInteractions(roleMapper);
    }
}
