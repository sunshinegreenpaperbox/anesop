package com.anesop.m4.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * 耗材数据范围守卫单测（M4-M14-01）：ALL/SITE/DEPT 放行，SELF/ROOM 与非科室 fail-closed。
 */
@ExtendWith(MockitoExtension.class)
class MaterialAccessGuardTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID DEPT_UUID = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final String DEPT = DEPT_UUID.toString();
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(USER_ID, "synth_nurse", "jti-1", Instant.now().plusSeconds(900), "sid");

    @Mock
    private DataScopeResolver resolver;
    @Mock
    private OrganizationQueryService organizationQueryService;

    private MaterialAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new MaterialAccessGuard(resolver, organizationQueryService);
        lenient().when(organizationQueryService.visibleDepartments(any()))
                .thenReturn(List.of(new OrgDepartment(DEPT, "DEPT-ANES", "麻醉科（合成）")));
        lenient().when(organizationQueryService.findActiveDepartment(DEPT_UUID))
                .thenReturn(Optional.of(new OrgDepartment(DEPT, "DEPT-ANES", "麻醉科（合成）")));
    }

    @Test
    void allScopeIsUnrestricted() {
        when(resolver.resolve(USER_ID)).thenReturn(assignments(DataScope.ALL));
        DataScopeAssignments scope = guard.scope(CALLER);
        assertThat(guard.visibleDepartmentIds(scope, CALLER)).isNull();
        assertThat(guard.requireDepartment(scope, CALLER, DEPT, "departmentId")).isEqualTo(DEPT);
        assertThat(guard.optionalDepartmentFilter(scope, CALLER, "  ", "departmentId")).isNull();
    }

    @Test
    void deptScopeFiltersVisibleDepartments() {
        when(resolver.resolve(USER_ID)).thenReturn(assignments(DataScope.DEPT));
        DataScopeAssignments scope = guard.scope(CALLER);
        assertThat(guard.visibleDepartmentIds(scope, CALLER)).containsExactly(DEPT);
        assertThat(guard.requireDepartment(scope, CALLER, DEPT, "departmentId")).isEqualTo(DEPT);
        assertThat(guard.isVisible(scope, CALLER, DEPT_UUID)).isTrue();
        assertThat(guard.isVisible(scope, CALLER, UUID.randomUUID())).isFalse();
        assertThat(guard.isVisible(scope, CALLER, null)).isFalse();
    }

    @Test
    void selfAndRoomScopesFailClosed() {
        when(resolver.resolve(USER_ID)).thenReturn(assignments(DataScope.SELF));
        DataScopeAssignments self = guard.scope(CALLER);
        assertThatThrownBy(() -> guard.visibleDepartmentIds(self, CALLER))
                .isInstanceOf(AccessDeniedException.class);

        when(resolver.resolve(USER_ID)).thenReturn(assignments(DataScope.ROOM));
        DataScopeAssignments room = guard.scope(CALLER);
        assertThatThrownBy(() -> guard.visibleDepartmentIds(room, CALLER))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(guard.isVisible(room, CALLER, DEPT_UUID)).isFalse();
    }

    @Test
    void deptWithoutVisibleDepartmentsFailsClosed() {
        when(resolver.resolve(USER_ID)).thenReturn(assignments(DataScope.DEPT));
        when(organizationQueryService.visibleDepartments(USER_ID)).thenReturn(List.of());
        DataScopeAssignments scope = guard.scope(CALLER);
        assertThatThrownBy(() -> guard.visibleDepartmentIds(scope, CALLER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deniedScopeFailsClosed() {
        when(resolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> guard.scope(CALLER)).isInstanceOf(AccessDeniedException.class);
        when(resolver.resolve(USER_ID)).thenReturn(null);
        assertThatThrownBy(() -> guard.scope(CALLER)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireDepartmentValidatesFormatExistenceAndVisibility() {
        when(resolver.resolve(USER_ID)).thenReturn(assignments(DataScope.DEPT));
        DataScopeAssignments scope = guard.scope(CALLER);

        assertThatThrownBy(() -> guard.requireDepartment(scope, CALLER, "not-a-uuid",
                "departmentId")).isInstanceOf(M4ValidationException.class);

        UUID other = UUID.fromString("f1000000-0000-4000-8000-000000000003");
        when(organizationQueryService.findActiveDepartment(other)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireDepartment(scope, CALLER, other.toString(),
                "departmentId")).isInstanceOf(M4UnprocessableException.class);

        when(organizationQueryService.findActiveDepartment(any()))
                .thenReturn(Optional.of(new OrgDepartment(
                        "f1000000-0000-4000-8000-000000000003", "DEPT-OR", "手术室（合成）")));
        when(organizationQueryService.visibleDepartments(USER_ID))
                .thenReturn(List.of(new OrgDepartment(DEPT, "DEPT-ANES", "麻醉科（合成）")));
        assertThatThrownBy(() -> guard.requireDepartment(scope, CALLER,
                "f1000000-0000-4000-8000-000000000003", "departmentId"))
                .isInstanceOf(AccessDeniedException.class);
    }

    private static DataScopeAssignments assignments(DataScope type) {
        return DataScopeAssignments.of(List.of(new DataScopeAssignment(type, List.of(DEPT))));
    }
}
