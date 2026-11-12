package com.anesop.m0.impl.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.impl.persistence.mapper.SysRoleMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserOrgMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserRoomMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 数据权限解析（M2-M0-01）：角色 data_scope + 组织/术间绑定表，未绑定/未知/异常 fail-closed。
 */
class DefaultDataScopeResolverTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SITE_ID = UUID.fromString("f1000000-0000-4000-8000-000000000001");
    private static final UUID DEPT_ID = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final UUID ROOM_ID = UUID.fromString("f2000000-0000-4000-8000-000000000001");

    private final SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    private final SysUserOrgMapper userOrgMapper = mock(SysUserOrgMapper.class);
    private final SysUserRoomMapper userRoomMapper = mock(SysUserRoomMapper.class);
    private final DefaultDataScopeResolver resolver =
            new DefaultDataScopeResolver(roleMapper, userOrgMapper, userRoomMapper);

    @Test
    void adminScopeResolvesToAllWithoutQueryingBindings() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("ALL"));

        DataScopeAssignments scopes = resolver.resolve(USER_ID);

        assertThat(scopes.isDenied()).isFalse();
        assertThat(scopes.isUnrestricted()).isTrue();
        assertThat(scopes.assignments()).hasSize(1);
        assertThat(scopes.assignments().get(0).type()).isEqualTo(DataScope.ALL);
        assertThat(scopes.assignments().get(0).scopeIds()).isEmpty();
        assertThat(scopes.allowedOrgIds()).isEmpty();
        assertThat(scopes.allowedRoomIds()).isEmpty();
        verifyNoInteractions(userOrgMapper, userRoomMapper);
    }

    @Test
    void deptScopeCarriesBoundOrgIdsAsScopeIds() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("DEPT"));
        when(userOrgMapper.selectOrgIdsByUserIdAndType(USER_ID, "DEPT")).thenReturn(List.of(DEPT_ID));

        DataScopeAssignments scopes = resolver.resolve(USER_ID);

        assertThat(scopes.isDenied()).isFalse();
        assertThat(scopes.isUnrestricted()).isFalse();
        assertThat(scopes.assignments()).hasSize(1);
        assertThat(scopes.assignments().get(0).type()).isEqualTo(DataScope.DEPT);
        assertThat(scopes.assignments().get(0).scopeIds()).containsExactly(DEPT_ID.toString());
        assertThat(scopes.allowedOrgIds()).containsExactly(DEPT_ID.toString());
        assertThat(scopes.allowedRoomIds()).isEmpty();
    }

    @Test
    void siteScopeCarriesBoundSiteIds() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("SITE"));
        when(userOrgMapper.selectOrgIdsByUserIdAndType(USER_ID, "SITE")).thenReturn(List.of(SITE_ID));

        DataScopeAssignments scopes = resolver.resolve(USER_ID);

        assertThat(scopes.assignments()).extracting(DataScopeAssignment::type).containsExactly(DataScope.SITE);
        assertThat(scopes.allowedOrgIds()).containsExactly(SITE_ID.toString());
    }

    @Test
    void roomScopeCarriesOnlyActiveRoomBindings() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("ROOM"));
        when(userRoomMapper.selectActiveRoomIdsByUserId(USER_ID)).thenReturn(List.of(ROOM_ID));

        DataScopeAssignments scopes = resolver.resolve(USER_ID);

        assertThat(scopes.assignments()).extracting(DataScopeAssignment::type).containsExactly(DataScope.ROOM);
        assertThat(scopes.allowedRoomIds()).containsExactly(ROOM_ID.toString());
        assertThat(scopes.allowedOrgIds()).isEmpty();
    }

    @Test
    void selfScopeNeedsNoBindingAndKeepsEmptyScopeIds() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("SELF"));

        DataScopeAssignments scopes = resolver.resolve(USER_ID);

        assertThat(scopes.isDenied()).isFalse();
        assertThat(scopes.assignments()).extracting(DataScopeAssignment::type).containsExactly(DataScope.SELF);
        assertThat(scopes.assignments().get(0).scopeIds()).isEmpty();
    }

    @Test
    void unboundDeptScopeFailsClosed() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("DEPT"));
        when(userOrgMapper.selectOrgIdsByUserIdAndType(USER_ID, "DEPT")).thenReturn(List.of());

        assertThat(resolver.resolve(USER_ID).isDenied())
                .as("声明 DEPT 但未绑定科室必须拒绝，不得降级为 ALL/SELF")
                .isTrue();
    }

    @Test
    void unboundRoomScopeFailsClosed() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("ROOM"));
        when(userRoomMapper.selectActiveRoomIdsByUserId(USER_ID)).thenReturn(List.of());

        assertThat(resolver.resolve(USER_ID).isDenied()).isTrue();
    }

    @Test
    void multipleRolesUnionScopeIds() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("DEPT", "SELF", "ROOM", "DEPT"));
        when(userOrgMapper.selectOrgIdsByUserIdAndType(USER_ID, "DEPT")).thenReturn(List.of(DEPT_ID, DEPT_ID));
        when(userRoomMapper.selectActiveRoomIdsByUserId(USER_ID)).thenReturn(List.of(ROOM_ID));

        DataScopeAssignments scopes = resolver.resolve(USER_ID);

        assertThat(scopes.assignments()).extracting(DataScopeAssignment::type)
                .containsExactly(DataScope.DEPT, DataScope.SELF, DataScope.ROOM);
        assertThat(scopes.assignments().get(0).scopeIds()).containsExactly(DEPT_ID.toString());
        assertThat(scopes.allowedOrgIds()).containsExactly(DEPT_ID.toString());
        assertThat(scopes.allowedRoomIds()).containsExactly(ROOM_ID.toString());
    }

    @Test
    void allWinsOverNarrowerScopes() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("SELF", "ALL"));

        DataScopeAssignments scopes = resolver.resolve(USER_ID);

        assertThat(scopes.assignments()).extracting(DataScopeAssignment::type).containsExactly(DataScope.ALL);
        assertThat(scopes.isUnrestricted()).isTrue();
        verifyNoInteractions(userOrgMapper, userRoomMapper);
    }

    @Test
    void unknownScopeFailsClosed() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("ALL", "EVERYTHING"));

        assertThat(resolver.resolve(USER_ID).isDenied()).isTrue();
    }

    @Test
    void blankOrNoRolesFailsClosed() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of());

        assertThat(resolver.resolve(USER_ID).isDenied()).isTrue();

        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("  "));
        assertThat(resolver.resolve(USER_ID).isDenied()).isTrue();
    }

    @Test
    void roleQueryFailureFailsClosed() {
        when(roleMapper.selectDataScopesByUserId(USER_ID))
                .thenThrow(new IllegalStateException("SYNTHETIC DB DOWN"));

        assertThat(resolver.resolve(USER_ID).isDenied()).isTrue();
    }

    @Test
    void bindingQueryFailureFailsClosed() {
        when(roleMapper.selectDataScopesByUserId(USER_ID)).thenReturn(List.of("DEPT"));
        when(userOrgMapper.selectOrgIdsByUserIdAndType(USER_ID, "DEPT"))
                .thenThrow(new IllegalStateException("SYNTHETIC BINDING QUERY FAILED"));

        assertThat(resolver.resolve(USER_ID).isDenied()).isTrue();
    }

    @Test
    void nullUserIsDeniedWithoutQuery() {
        assertThat(resolver.resolve(null).isDenied()).isTrue();
        verifyNoInteractions(roleMapper, userOrgMapper, userRoomMapper);
    }
}
