package com.anesop.m4.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
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
 * 人员域数据范围守卫单测（M4-M13-01）：ALL/SITE/DEPT/SELF/ROOM 五种范围、写操作拒绝、
 * 单对象不可见 404、科室校验 400/422/403。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaffAccessGuardTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final String DEPT = "f1000000-0000-4000-8000-000000000002";
    private static final String OTHER_DEPT = "f1000000-0000-4000-8000-000000000003";
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_user", "jti-1", Instant.now().plusSeconds(900), "sid-1");

    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private OrganizationQueryService organizationQueryService;

    private StaffAccessGuard guard;

    @BeforeEach
    void setUp() {
        guard = new StaffAccessGuard(dataScopeResolver, organizationQueryService);
        when(organizationQueryService.findActiveDepartment(any()))
                .thenReturn(Optional.of(new OrgDepartment(DEPT, "DEPT-ANES", "麻醉科（合成）")));
        when(organizationQueryService.visibleDepartments(any())).thenReturn(
                List.of(new OrgDepartment(DEPT, "DEPT-ANES", "麻醉科（合成）")));
        when(organizationQueryService.departmentsOfUser(any())).thenReturn(
                List.of(new OrgDepartment(DEPT, "DEPT-ANES", "麻醉科（合成）")));
    }

    @Test
    void allScopeIsUnrestrictedAndWritable() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(assignments(DataScope.ALL));

        StaffAccessGuard.StaffVisibility visibility = guard.visibility(CALLER);

        assertThat(visibility.unrestricted()).isTrue();
        assertThat(visibility.allowedDeptIds()).isNull();
        assertThat(visibility.selfUserId()).isNull();
        guard.requireWritable(CALLER);
        assertThat(guard.visibleDepartmentIds(CALLER)).isEmpty();
    }

    @Test
    void selfScopeIsReadOnlyAndFiltersByUserId() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(assignments(DataScope.SELF));

        StaffAccessGuard.StaffVisibility visibility = guard.visibility(CALLER);

        assertThat(visibility.selfUserId()).isEqualTo(ACTOR.toString());
        assertThatThrownBy(() -> guard.requireWritable(CALLER))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("仅允许查看");
        assertThatThrownBy(() -> guard.requireDepartment(CALLER, DEPT, "departmentId"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deptScopeFiltersByVisibleDepartments() {
        when(dataScopeResolver.resolve(ACTOR))
                .thenReturn(assignments(DataScope.DEPT, List.of(DEPT)));

        StaffAccessGuard.StaffVisibility visibility = guard.visibility(CALLER);

        assertThat(visibility.allowedDeptIds()).containsExactly(DEPT);
        assertThat(visibility.unrestricted()).isFalse();
        assertThat(guard.visibleDepartmentIds(CALLER)).containsExactly(DEPT);
        assertThat(guard.requireDepartmentFilter(CALLER, DEPT, "departmentId")).isEqualTo(DEPT);
    }

    @Test
    void deptScopeRejectsDepartmentOutsideVisibility() {
        when(dataScopeResolver.resolve(ACTOR))
                .thenReturn(assignments(DataScope.DEPT, List.of(DEPT)));
        when(organizationQueryService.findActiveDepartment(
                UUID.fromString(OTHER_DEPT)))
                .thenReturn(Optional.of(new OrgDepartment(OTHER_DEPT, "DEPT-OR", "手术室（合成）")));

        assertThatThrownBy(() -> guard.requireDepartmentFilter(CALLER, OTHER_DEPT,
                "departmentId"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("数据范围");
    }

    @Test
    void siteScopeUsesVisibleDepartmentsLikeDept() {
        when(dataScopeResolver.resolve(ACTOR))
                .thenReturn(assignments(DataScope.SITE, List.of("3000000000000000001")));

        StaffAccessGuard.StaffVisibility visibility = guard.visibility(CALLER);

        assertThat(visibility.allowedDeptIds()).containsExactly(DEPT);
    }

    @Test
    void roomAndDeniedScopesFailClosed() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(assignments(DataScope.ROOM));
        assertThatThrownBy(() -> guard.visibility(CALLER))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("科室数据范围");

        when(dataScopeResolver.resolve(ACTOR)).thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> guard.scope(CALLER))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("无数据范围");

        when(dataScopeResolver.resolve(null)).thenReturn(null);
        assertThatThrownBy(() -> guard.scope(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deptScopeWithoutVisibleDepartmentsIsDenied() {
        when(dataScopeResolver.resolve(ACTOR))
                .thenReturn(assignments(DataScope.DEPT, List.of(DEPT)));
        when(organizationQueryService.visibleDepartments(any())).thenReturn(List.of());

        assertThatThrownBy(() -> guard.visibility(CALLER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void selfScopeWithoutUserIdIsDenied() {
        when(dataScopeResolver.resolve(null)).thenReturn(assignments(DataScope.SELF));

        assertThatThrownBy(() -> guard.visibility(null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireDepartmentValidatesBlankFormatAndExistence() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(assignments(DataScope.ALL));

        assertThatThrownBy(() -> guard.requireDepartment(CALLER, null, "departmentId"))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> guard.requireDepartment(CALLER, "not-a-uuid", "departmentId"))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("格式非法");
        when(organizationQueryService.findActiveDepartment(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.requireDepartment(CALLER, DEPT, "departmentId"))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("科室不存在");
    }

    @Test
    void requireReadableHidesOutOfScopeStaffAsNotFound() {
        when(dataScopeResolver.resolve(ACTOR))
                .thenReturn(assignments(DataScope.DEPT, List.of(DEPT)));
        StaffProfileEntity sameDept = staff(ACTOR, DEPT);
        StaffProfileEntity otherDept = staff(null, OTHER_DEPT);

        guard.requireReadable(sameDept, CALLER);
        assertThatThrownBy(() -> guard.requireReadable(otherDept, CALLER))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("不可见");
    }

    @Test
    void selfScopeCanReadOwnProfileOnly() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(assignments(DataScope.SELF));

        guard.requireReadable(staff(ACTOR, DEPT), CALLER);
        assertThatThrownBy(() -> guard.requireReadable(staff(null, DEPT), CALLER))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> guard.requireReadable(
                staff(UUID.randomUUID(), DEPT), CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void unrestrictedScopeReadsEveryStaff() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(assignments(DataScope.ALL));

        guard.requireReadable(staff(null, OTHER_DEPT), CALLER);
    }

    @Test
    void parseUuidHandlesBlankAndInvalidValues() {
        assertThat(StaffAccessGuard.parseUuid(null)).isNull();
        assertThat(StaffAccessGuard.parseUuid(" ")).isNull();
        assertThat(StaffAccessGuard.parseUuid("bad")).isNull();
        assertThat(StaffAccessGuard.parseUuid(ACTOR.toString())).isEqualTo(ACTOR);
    }

    @Test
    void staffVisibilityDefensivelyCopiesDepartmentList() {
        java.util.ArrayList<String> mutable = new java.util.ArrayList<>(List.of(DEPT));
        StaffAccessGuard.StaffVisibility visibility =
                new StaffAccessGuard.StaffVisibility(mutable, null);
        mutable.add(OTHER_DEPT);

        assertThat(visibility.allowedDeptIds()).containsExactly(DEPT);
        assertThat(visibility.unrestricted()).isFalse();
        assertThat(new StaffAccessGuard.StaffVisibility(null, null).unrestricted()).isTrue();
    }

    private static DataScopeAssignments assignments(DataScope scope) {
        return assignments(scope, List.of());
    }

    private static DataScopeAssignments assignments(DataScope scope, List<String> scopeIds) {
        return DataScopeAssignments.of(List.of(new DataScopeAssignment(scope, scopeIds)));
    }

    private static StaffProfileEntity staff(UUID userId, String departmentId) {
        StaffProfileEntity entity = new StaffProfileEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setStaffNo("STAFF-ANES-001");
        entity.setName("演示麻醉医师甲（合成）");
        entity.setDepartmentId(UUID.fromString(departmentId));
        entity.setTitle("主任医师");
        entity.setEmploymentStatus("ACTIVE");
        entity.setSpecialties("[]");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setVersion(1);
        return entity;
    }
}
