package com.anesop.m0.impl.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 数据范围断言集合（fail-closed 语义）的单元测试。
 */
class DataScopeAssignmentsTest {

    @Test
    void deniedIsDeniedAndNotUnrestricted() {
        DataScopeAssignments denied = DataScopeAssignments.denied();

        assertThat(denied.isDenied()).isTrue();
        assertThat(denied.isUnrestricted()).isFalse();
        assertThat(denied.assignments()).extracting(DataScopeAssignment::type).containsExactly(DataScope.NONE);
        assertThat(denied.allowedOrgIds()).isEmpty();
        assertThat(denied.allowedRoomIds()).isEmpty();
        assertThat(denied.hasObjectScope()).as("拒绝不是\"按对象过滤\"，调用方必须先拒绝").isFalse();
        assertThat(denied.requiresObjectFilter()).isFalse();
        assertThat(denied.isSelfOnly()).isFalse();
    }

    @Test
    void nullOrEmptyAssignmentsAreDenied() {
        assertThat(DataScopeAssignments.of(null).isDenied()).isTrue();
        assertThat(DataScopeAssignments.of(List.of()).isDenied()).isTrue();
    }

    @Test
    void everyNullAssignmentFailsClosed() {
        List<DataScopeAssignment> assignments = new ArrayList<>();
        assignments.add(null);

        assertThat(DataScopeAssignments.of(assignments).isDenied()).isTrue();
    }

    @Test
    void allScopeIsUnrestrictedWithEmptyObjectLists() {
        DataScopeAssignments scopes = DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.ALL, List.of())));

        assertThat(scopes.isDenied()).isFalse();
        assertThat(scopes.isUnrestricted()).isTrue();
        assertThat(scopes.allowedOrgIds()).isEmpty();
        assertThat(scopes.allowedRoomIds()).isEmpty();
        assertThat(scopes.hasObjectScope()).as("ALL 不需要对象过滤").isFalse();
        assertThat(scopes.requiresObjectFilter()).isFalse();
        assertThat(scopes.isSelfOnly()).isFalse();
    }

    @Test
    void selfScopeRequiresFilterDespiteEmptyObjectLists() {
        DataScopeAssignments scopes = DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.SELF, List.of())));

        assertThat(scopes.isDenied()).isFalse();
        assertThat(scopes.isUnrestricted()).isFalse();
        assertThat(scopes.allowedOrgIds()).as("SELF 的 allowedOrgIds 为空不等于不过滤").isEmpty();
        assertThat(scopes.allowedRoomIds()).isEmpty();
        assertThat(scopes.hasObjectScope()).isTrue();
        assertThat(scopes.requiresObjectFilter()).isTrue();
        assertThat(scopes.isSelfOnly()).isTrue();
    }

    @Test
    void deptScopeRequiresObjectFilterAndIsNotSelfOnly() {
        DataScopeAssignments scopes = DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of("dept-1"))));

        assertThat(scopes.isUnrestricted()).isFalse();
        assertThat(scopes.hasObjectScope()).isTrue();
        assertThat(scopes.requiresObjectFilter()).isTrue();
        assertThat(scopes.isSelfOnly()).isFalse();
        assertThat(scopes.allowedOrgIds()).containsExactly("dept-1");
    }

    @Test
    void allMixedWithScopedAssignmentsIsStillUnrestricted() {
        DataScopeAssignments scopes = DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.SELF, List.of()),
                new DataScopeAssignment(DataScope.DEPT, List.of("dept-1")),
                new DataScopeAssignment(DataScope.ALL, List.of())));

        assertThat(scopes.isUnrestricted()).isTrue();
        assertThat(scopes.hasObjectScope()).isTrue();
        assertThat(scopes.requiresObjectFilter()).as("任一 ALL 即全院可见，不再注入过滤").isFalse();
        assertThat(scopes.isSelfOnly()).isFalse();
    }

    @Test
    void selfMixedWithDeptIsNotSelfOnly() {
        DataScopeAssignments scopes = DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.SELF, List.of()),
                new DataScopeAssignment(DataScope.DEPT, List.of("dept-1"))));

        assertThat(scopes.isSelfOnly()).isFalse();
        assertThat(scopes.requiresObjectFilter()).isTrue();
    }

    @Test
    void orgAndRoomIdsAreUnionedAndDeduplicated() {
        DataScopeAssignments scopes = DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.SITE, List.of("site-1")),
                new DataScopeAssignment(DataScope.DEPT, List.of("dept-1", "dept-2", "dept-1")),
                new DataScopeAssignment(DataScope.ROOM, List.of("room-1")),
                new DataScopeAssignment(DataScope.SELF, List.of())));

        assertThat(scopes.allowedOrgIds()).containsExactly("site-1", "dept-1", "dept-2");
        assertThat(scopes.allowedRoomIds()).containsExactly("room-1");
        assertThat(scopes.isUnrestricted()).isFalse();
    }

    @Test
    void blankScopeIdsAreIgnored() {
        DataScopeAssignments scopes = DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of(" ", "dept-1"))));

        assertThat(scopes.allowedOrgIds()).containsExactly("dept-1");
    }

    @Test
    void anyAllAssignmentMarksUnrestricted() {
        DataScopeAssignments scopes = DataScopeAssignments.of(List.of(
                new DataScopeAssignment(DataScope.DEPT, List.of("dept-1")),
                new DataScopeAssignment(DataScope.ALL, List.of())));

        assertThat(scopes.isUnrestricted()).isTrue();
        assertThat(scopes.allowedOrgIds()).isEmpty();

        assertThat(scopes.assignments()).extracting(DataScopeAssignment::type)
                .containsExactly(DataScope.DEPT, DataScope.ALL);
    }
}
