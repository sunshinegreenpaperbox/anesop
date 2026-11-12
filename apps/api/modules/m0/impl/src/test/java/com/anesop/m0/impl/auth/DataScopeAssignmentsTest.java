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
