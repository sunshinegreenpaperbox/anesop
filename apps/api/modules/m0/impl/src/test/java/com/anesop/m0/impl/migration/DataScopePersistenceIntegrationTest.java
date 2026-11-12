package com.anesop.m0.impl.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.impl.auth.DataScopeQueryServiceImpl;
import com.anesop.m0.impl.auth.DefaultDataScopeResolver;
import com.anesop.m0.impl.persistence.mapper.SysRoleMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserOrgMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserRoomMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

/**
 * 数据范围绑定解析与示例查询的真实 PostgreSQL 集成测试（M2-M0-01，SYNTHETIC 夹具）。
 *
 * <p>覆盖：admin→ALL、DEPT→本科室 scopeIds、未绑定/无角色→NONE、ROOM 仅绑定可见、
 * SELF 仅本人、越权查询抛 AccessDeniedException、并发解析一致且 fail-closed。
 * 数据全部为合成数据（固定 UUID、SYN- 前缀），不含任何患者或真实人员信息。
 */
class DataScopePersistenceIntegrationTest {

    private static final UUID SITE_ID = UUID.fromString("f1000000-0000-4000-8000-000000000001");
    private static final UUID DEPT_ANES_ID = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final UUID DEPT_OR_ID = UUID.fromString("f1000000-0000-4000-8000-000000000003");
    private static final UUID ROOM_1 = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_2 = UUID.fromString("f2000000-0000-4000-8000-000000000002");

    private static final UUID ADMIN_ID = UUID.fromString("e0000000-0000-4000-8000-00000000a001");
    private static final UUID DEPT_USER_ID = UUID.fromString("e0000000-0000-4000-8000-00000000a002");
    private static final UUID DEPT_OR_USER_ID = UUID.fromString("e0000000-0000-4000-8000-00000000a003");
    private static final UUID ROOM_USER_ID = UUID.fromString("e0000000-0000-4000-8000-00000000a004");
    private static final UUID ROOM_USER_2_ID = UUID.fromString("e0000000-0000-4000-8000-00000000a005");
    private static final UUID SELF_USER_ID = UUID.fromString("e0000000-0000-4000-8000-00000000a006");
    private static final UUID UNBOUND_USER_ID = UUID.fromString("e0000000-0000-4000-8000-00000000a007");
    private static final UUID NO_ROLE_USER_ID = UUID.fromString("e0000000-0000-4000-8000-00000000a008");

    private static final UUID ROLE_ADMIN = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID ROLE_DEPT = UUID.fromString("a0000000-0000-4000-8000-000000000005");
    private static final UUID ROLE_SELF = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID ROLE_ROOM = UUID.fromString("a0000000-0000-4000-8000-00000000a0f1");

    /** 合成口令哈希占位：集成测试不登录，只校验数据范围解析；非任何真实口令。 */
    private static final String SYNTHETIC_PASSWORD_HASH =
            "$2y$10$SYNTHETICONLY00000000000000000000000000000000000000000000";

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        dataSource = PostgresSupport.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        sqlSessionFactory = MybatisTestSupport.sqlSessionFactory(dataSource);
    }

    @BeforeEach
    void migrateAndSeed() throws SQLException {
        MigrationTestSupport.resetDatabase(dataSource);
        assertThat(MigrationTestSupport.flyway(dataSource).migrate().success).isTrue();
        seedOrganisations();
        seedRoles();
        seedUsersAndBindings();
    }

    @Test
    void adminResolvesToUnrestricted() {
        DataScopeAssignments scopes = resolve(ADMIN_ID);

        assertThat(scopes.isDenied()).isFalse();
        assertThat(scopes.isUnrestricted()).isTrue();
        assertThat(scopes.assignments()).extracting(DataScopeAssignment::type).containsExactly(DataScope.ALL);
        assertThat(scopes.allowedOrgIds()).isEmpty();
    }

    @Test
    void deptUserResolvesToOwnDepartmentScopeIds() {
        DataScopeAssignments scopes = resolve(DEPT_USER_ID);

        assertThat(scopes.isDenied()).isFalse();
        assertThat(scopes.isUnrestricted()).isFalse();
        assertThat(scopes.allowedOrgIds()).containsExactly(DEPT_ANES_ID.toString());
        assertThat(scopes.assignments().get(0).type()).isEqualTo(DataScope.DEPT);
        assertThat(scopes.assignments().get(0).scopeIds()).containsExactly(DEPT_ANES_ID.toString());
    }

    @Test
    void unboundDeptAndRolelessUsersAreDenied() {
        assertThat(resolve(UNBOUND_USER_ID).isDenied())
                .as("角色声明 DEPT 但无绑定行必须 fail-closed")
                .isTrue();
        assertThat(resolve(NO_ROLE_USER_ID).isDenied()).as("无角色必须 fail-closed").isTrue();
        assertThat(resolve(null).isDenied()).isTrue();
    }

    @Test
    void roomBindingLimitsVisibilityToBoundRoomsOnly() {
        DataScopeAssignments scopes = resolve(ROOM_USER_ID);
        assertThat(scopes.allowedRoomIds()).containsExactly(ROOM_1.toString());
        assertThat(scopes.allowedOrgIds()).isEmpty();

        List<String> visible = queryIds(scopesSession -> queryService(scopesSession)
                .visibleUserIds(ROOM_USER_ID, null, null));
        assertThat(visible).containsExactly(ROOM_USER_ID.toString());

        assertThatThrownBy(() -> queryIds(session -> queryService(session).visibleUserIds(ROOM_USER_ID, null, ROOM_2)))
                .as("未绑定的术间必须 403（AccessDeniedException）")
                .isInstanceOf(AccessDeniedException.class);

        assertThat(queryIds(session -> queryService(session).visibleUserIds(ROOM_USER_ID, null, ROOM_1)))
                .containsExactly(ROOM_USER_ID.toString());
    }

    @Test
    void deptQueryIsFilteredToOwnDepartmentAndDeniesOtherDepartment() {
        List<String> visible = queryIds(session -> queryService(session).visibleUserIds(DEPT_USER_ID, null, null));

        assertThat(visible)
                .as("DEPT 只能看到本科室成员")
                .containsExactly(DEPT_USER_ID.toString(), ROOM_USER_ID.toString())
                .doesNotContain(DEPT_OR_USER_ID.toString(), ADMIN_ID.toString());

        assertThat(queryIds(session -> queryService(session).visibleUserIds(DEPT_USER_ID, DEPT_ANES_ID, null)))
                .containsExactly(DEPT_USER_ID.toString(), ROOM_USER_ID.toString());

        assertThatThrownBy(() -> queryIds(session -> queryService(session).visibleUserIds(DEPT_USER_ID, DEPT_OR_ID, null)))
                .as("他科室数据必须 403")
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> queryIds(session -> queryService(session).visibleUserIds(DEPT_USER_ID, SITE_ID, null)))
                .as("院区范围不在 DEPT 绑定内，必须 403")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void selfScopeSeesOnlyCallerAndUnboundScopeIsDenied() {
        assertThat(queryIds(session -> queryService(session).visibleUserIds(SELF_USER_ID, null, null)))
                .containsExactly(SELF_USER_ID.toString());

        assertThatThrownBy(() -> queryIds(session -> queryService(session).visibleUserIds(UNBOUND_USER_ID, null, null)))
                .as("数据范围为空时示例接口必须 403")
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> queryIds(session -> queryService(session).visibleUserIds(null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminSeesAllActiveUsersAndCombinedFiltersIntersect() {
        List<String> all = queryIds(session -> queryService(session).visibleUserIds(ADMIN_ID, null, null));

        assertThat(all).contains(
                ADMIN_ID.toString(), DEPT_USER_ID.toString(), DEPT_OR_USER_ID.toString(),
                ROOM_USER_ID.toString(), SELF_USER_ID.toString());

        assertThat(queryIds(session -> queryService(session).visibleUserIds(ADMIN_ID, DEPT_ANES_ID, ROOM_1)))
                .as("orgId 与 roomId 同时提供时取交集")
                .containsExactly(ROOM_USER_ID.toString());

        assertThat(queryIds(session -> queryService(session).visibleUserIds(ADMIN_ID, DEPT_OR_ID, null)))
                .containsExactly(DEPT_OR_USER_ID.toString());
    }

    @Test
    void concurrentResolveIsConsistentAndFailClosed() throws Exception {
        int threads = 8;
        int iterations = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    for (int iteration = 0; iteration < iterations; iteration++) {
                        if (!resolve(UNBOUND_USER_ID).isDenied()) {
                            return false;
                        }
                        DataScopeAssignments deptScopes = resolve(DEPT_USER_ID);
                        if (deptScopes.isDenied()
                                || !deptScopes.allowedOrgIds().equals(List.of(DEPT_ANES_ID.toString()))) {
                            return false;
                        }
                        if (!resolve(ADMIN_ID).isUnrestricted()) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS))
                        .as("并发解析必须稳定：未绑定始终 NONE、DEPT 始终本科室、ADMIN 始终 ALL")
                        .isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static DataScopeAssignments resolve(UUID userId) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return resolver(session).resolve(userId);
        }
    }

    private static DefaultDataScopeResolver resolver(SqlSession session) {
        return new DefaultDataScopeResolver(
                session.getMapper(SysRoleMapper.class),
                session.getMapper(SysUserOrgMapper.class),
                session.getMapper(SysUserRoomMapper.class));
    }

    private static DataScopeQueryServiceImpl queryService(SqlSession session) {
        return new DataScopeQueryServiceImpl(
                resolver(session),
                session.getMapper(SysUserMapper.class),
                session.getMapper(SysUserOrgMapper.class),
                session.getMapper(SysUserRoomMapper.class));
    }

    private interface SessionQuery<T> {
        T run(SqlSession session);
    }

    private static List<String> queryIds(SessionQuery<List<String>> action) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return action.run(session);
        }
    }

    private void seedOrganisations() {
        jdbc.update("""
                INSERT INTO m0_platform.sys_org (id, code, name, org_type, parent_id, path, sort, status) VALUES
                    (?::uuid, 'SYN-SITE-01', '合成院区', 'SITE', NULL, '/SYN-SITE-01', 1, 1),
                    (?::uuid, 'SYN-DEPT-ANES', '合成麻醉科', 'DEPT', ?::uuid, '/SYN-SITE-01/SYN-DEPT-ANES', 1, 1),
                    (?::uuid, 'SYN-DEPT-OR', '合成手术室', 'DEPT', ?::uuid, '/SYN-SITE-01/SYN-DEPT-OR', 2, 1)
                """, SITE_ID.toString(), DEPT_ANES_ID.toString(), SITE_ID.toString(),
                DEPT_OR_ID.toString(), SITE_ID.toString());
    }

    private void seedRoles() {
        jdbc.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, data_scope)
                VALUES (?::uuid, 'SYN_ROOM', '合成术间角色', 'ROOM')
                """, ROLE_ROOM.toString());
    }

    private void seedUsersAndBindings() {
        insertUser(ADMIN_ID, "syn.admin");
        insertUser(DEPT_USER_ID, "syn.dept");
        insertUser(DEPT_OR_USER_ID, "syn.or-dept");
        insertUser(ROOM_USER_ID, "syn.room");
        insertUser(ROOM_USER_2_ID, "syn.room-2");
        insertUser(SELF_USER_ID, "syn.self");
        insertUser(UNBOUND_USER_ID, "syn.unbound");
        insertUser(NO_ROLE_USER_ID, "syn.no-role");

        bindRole(ADMIN_ID, ROLE_ADMIN);
        bindRole(DEPT_USER_ID, ROLE_DEPT);
        bindRole(DEPT_OR_USER_ID, ROLE_DEPT);
        bindRole(ROOM_USER_ID, ROLE_ROOM);
        bindRole(ROOM_USER_2_ID, ROLE_ROOM);
        bindRole(SELF_USER_ID, ROLE_SELF);
        // UNBOUND_USER_ID 有 DEPT 角色但故意不建组织绑定；NO_ROLE_USER_ID 不建角色。

        bindOrg(ADMIN_ID, SITE_ID, true);
        bindOrg(DEPT_USER_ID, DEPT_ANES_ID, true);
        bindOrg(DEPT_OR_USER_ID, DEPT_OR_ID, true);
        // ROOM_USER 同时属于麻醉科：用于验证 orgId+roomId 交集与“术间/组织”双绑定。
        bindOrg(ROOM_USER_ID, DEPT_ANES_ID, true);
        bindRoom(ROOM_USER_ID, ROOM_1);
        bindRoom(ROOM_USER_2_ID, ROOM_2);
    }

    private void insertUser(UUID id, String username) {
        jdbc.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, employee_no, status,
                     must_change_password, failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, ?, ?, 1, false, 0, 0, false)
                """, id.toString(), username, SYNTHETIC_PASSWORD_HASH, "合成用户 " + username,
                "SYN-" + username);
    }

    private void bindRole(UUID userId, UUID roleId) {
        jdbc.update("INSERT INTO m0_platform.sys_user_role (user_id, role_id) VALUES (?::uuid, ?::uuid)",
                userId.toString(), roleId.toString());
    }

    private void bindOrg(UUID userId, UUID orgId, boolean primary) {
        jdbc.update("""
                INSERT INTO m0_platform.sys_user_org (user_id, org_id, is_primary)
                VALUES (?::uuid, ?::uuid, ?)
                """, userId.toString(), orgId.toString(), primary);
    }

    private void bindRoom(UUID userId, UUID roomId) {
        jdbc.update("""
                INSERT INTO m0_platform.sys_user_room (user_id, room_id, status)
                VALUES (?::uuid, ?::uuid, 1)
                """, userId.toString(), roomId.toString());
    }
}
