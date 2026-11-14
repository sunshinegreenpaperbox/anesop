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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * 质控数据范围守卫单测（M4-M17-01）：ALL/SITE/DEPT 可用、SELF/ROOM/无范围 403、
 * 科室格式/存在性/越界与单对象可见性（404 不泄露存在性）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QcAccessGuardTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final String DEPT_ID = "f1000000-0000-4000-8000-000000000002";
    private static final String DEPT_OTHER = "f1000000-0000-4000-8000-000000000003";
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_qc", "jti-1", Instant.parse("2031-03-10T00:00:00Z"),
                    "sid-1");

    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private OrganizationQueryService organizationQueryService;

    private QcAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new QcAccessGuard(dataScopeResolver, organizationQueryService);
        lenient().when(organizationQueryService.findActiveDepartment(any()))
                .thenReturn(Optional.of(new OrgDepartment(DEPT_ID, "ANES", "麻醉科（合成）")));
        lenient().when(organizationQueryService.visibleDepartments(any()))
                .thenReturn(List.of(new OrgDepartment(DEPT_ID, "ANES", "麻醉科（合成）")));
    }

    @Test
    void allScopeIsUnrestrictedAndMapperFilterIsNull() {
        when(dataScopeResolver.resolve(any())).thenReturn(scopes(DataScope.ALL));
        QcAccessGuard.QcVisibility visibility = guard.visibility(CALLER);
        assertThat(visibility.unrestricted()).isTrue();
        assertThat(visibility.allowedDeptIds()).isEmpty();
        assertThat(visibility.mapperFilter()).isNull();
        guard.requireVisible(CALLER, UUID.fromString(DEPT_OTHER));
    }

    @Test
    void deptScopeUsesVisibleDepartmentsAndRejectsOutOfScopeDepartment() {
        when(dataScopeResolver.resolve(any())).thenReturn(
                scopes(DataScope.DEPT, DEPT_ID));
        QcAccessGuard.QcVisibility visibility = guard.visibility(CALLER);
        assertThat(visibility.unrestricted()).isFalse();
        assertThat(visibility.allowedDeptIds()).containsExactly(DEPT_ID);
        assertThat(visibility.mapperFilter()).containsExactly(DEPT_ID);
        assertThat(guard.requireDepartment(CALLER, DEPT_ID, "deptId").orgId())
                .isEqualTo(DEPT_ID);
        guard.requireVisible(CALLER, UUID.fromString(DEPT_ID));
        assertThatThrownBy(() -> guard.requireVisible(CALLER, UUID.fromString(DEPT_OTHER)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void selfRoomDeniedAndEmptyDepartmentsAreForbidden() {
        when(dataScopeResolver.resolve(any())).thenReturn(scopes(DataScope.SELF));
        assertThatThrownBy(() -> guard.visibility(CALLER))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("科室或全院");

        when(dataScopeResolver.resolve(any())).thenReturn(
                scopes(DataScope.ROOM, "f2000000-0000-4000-8000-0000000000c1"));
        assertThatThrownBy(() -> guard.visibility(CALLER))
                .isInstanceOf(AccessDeniedException.class);

        when(dataScopeResolver.resolve(any())).thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> guard.visibility(CALLER))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("无数据范围");

        when(dataScopeResolver.resolve(any())).thenReturn(scopes(DataScope.DEPT, DEPT_ID));
        when(organizationQueryService.visibleDepartments(any())).thenReturn(List.of());
        assertThatThrownBy(() -> guard.visibility(CALLER))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("无可用质控数据范围");
    }

    @Test
    void requireDepartmentValidatesFormatExistenceAndScope() {
        when(dataScopeResolver.resolve(any())).thenReturn(scopes(DataScope.DEPT, DEPT_ID));
        assertThatThrownBy(() -> guard.requireDepartment(CALLER, null, "deptId"))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> guard.requireDepartment(CALLER, "not-a-uuid", "deptId"))
                .isInstanceOf(M4ValidationException.class);
        when(organizationQueryService.findActiveDepartment(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireDepartment(CALLER, DEPT_ID, "deptId"))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("科室不存在");
        when(organizationQueryService.findActiveDepartment(any()))
                .thenReturn(Optional.of(new OrgDepartment(DEPT_ID, "ANES", "麻醉科（合成）")));
        when(organizationQueryService.visibleDepartments(any()))
                .thenReturn(List.of(new OrgDepartment(DEPT_OTHER, "OR", "手术室（合成）")));
        assertThatThrownBy(() -> guard.requireDepartment(CALLER, DEPT_ID, "deptId"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("不在当前用户数据范围");
    }

    @Test
    void requireVisibleTreatsNullDepartmentAsNotFound() {
        when(dataScopeResolver.resolve(any())).thenReturn(scopes(DataScope.DEPT, DEPT_ID));
        assertThatThrownBy(() -> guard.requireVisible(CALLER, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    private static DataScopeAssignments scopes(DataScope type, String... scopeIds) {
        return DataScopeAssignments.of(List.of(new DataScopeAssignment(type, List.of(scopeIds))));
    }
}
