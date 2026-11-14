package com.anesop.m0.impl.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 演示迁移路径（locations = db/migration + db/seed/demo）的集成测试。
 *
 * <p>演示账号只允许出现在独立 location；本类与 {@link FlywayMigrationTest}
 * 分别锁定“生产无账号 / 演示有账号”两条路径（S0-06 评审 B-1）。
 */
class DemoSeedMigrationTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() {
        dataSource = PostgresSupport.dataSource();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void resetDatabase() throws SQLException {
        MigrationTestSupport.resetDatabase(dataSource);
    }

    @Test
    void demoLocationCreatesDemoAdminOnlyWhenExplicitlyEnabled() {
        MigrateResult result = MigrationTestSupport.flyway(dataSource, MigrationTestSupport.demoLocations())
                .migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(4);
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM public.flyway_schema_history ORDER BY installed_rank", String.class);
        assertThat(versions).contains("1", "2", "3", "100", "101");
        assertThat(versions.get(versions.size() - 1))
                .as("演示种子 V101（M4 演示药品）必须最后执行").isEqualTo("101");

        // 平台预置基线仍在（V20 SYS_PHARMACIST + V22 SYS_AUTH_MANAGER），演示种子只追加账号。
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_role", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_dict_item", Integer.class)).isEqualTo(22);
        // M2-M0-01 追加 demo_dept / demo_self 两个受限演示账号（SYNTHETIC）。
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_user", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_user_role", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_org", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_user_org", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_user_room", Integer.class)).isEqualTo(1);

        Map<String, Object> admin = jdbc.queryForMap(
                "SELECT id, username, display_name, employee_no, password_hash, must_change_password, status "
                        + "FROM m0_platform.sys_user WHERE username = 'demo_admin'");
        assertThat(admin.get("id").toString()).isEqualTo("e0000000-0000-4000-8000-000000000001");
        assertThat(admin.get("display_name").toString()).contains("合成");
        assertThat(admin.get("employee_no")).isEqualTo("SYN-DEMO-0001");
        assertThat(admin.get("must_change_password")).isEqualTo(true);
        assertThat(((Number) admin.get("status")).intValue()).isEqualTo(1);
        assertThat(admin.get("password_hash").toString())
                .startsWith("$2y$10$")
                .hasSize(60);
        assertThat(new BCryptPasswordEncoder().matches("Anesop@2026", admin.get("password_hash").toString()))
                .as("演示口令哈希必须可被 BCrypt 校验")
                .isTrue();

        String adminRoleCode = jdbc.queryForObject(
                "SELECT r.code FROM m0_platform.sys_user u "
                        + "JOIN m0_platform.sys_user_role ur ON ur.user_id = u.id "
                        + "JOIN m0_platform.sys_role r ON r.id = ur.role_id "
                        + "WHERE u.username = 'demo_admin'",
                String.class);
        assertThat(adminRoleCode).isEqualTo("SYS_ADMIN");

        // 数据范围演示：DEPT 用户绑定麻醉科，SELF 用户按角色且不因绑定放大范围。
        String deptScope = jdbc.queryForObject("""
                SELECT o.code FROM m0_platform.sys_user u
                JOIN m0_platform.sys_user_org uo ON uo.user_id = u.id
                JOIN m0_platform.sys_org o ON o.id = uo.org_id
                WHERE u.username = 'demo_dept' AND uo.is_primary
                """, String.class);
        assertThat(deptScope).isEqualTo("DEPT-ANES");
        assertThat(jdbc.queryForObject("""
                SELECT r.data_scope FROM m0_platform.sys_user u
                JOIN m0_platform.sys_user_role ur ON ur.user_id = u.id
                JOIN m0_platform.sys_role r ON r.id = ur.role_id
                WHERE u.username = 'demo_dept'
                """, String.class)).isEqualTo("DEPT");
        assertThat(jdbc.queryForObject("""
                SELECT r.data_scope FROM m0_platform.sys_user u
                JOIN m0_platform.sys_user_role ur ON ur.user_id = u.id
                JOIN m0_platform.sys_role r ON r.id = ur.role_id
                WHERE u.username = 'demo_self'
                """, String.class)).isEqualTo("SELF");
        assertThat(jdbc.queryForList("""
                SELECT o.org_type FROM m0_platform.sys_org o ORDER BY o.code
                """, String.class)).containsExactly("DEPT", "DEPT", "SITE");
        System.out.printf("[M2-M0-01][demo] demo_dept dept=%s sys_org=%d sys_user_org=%d sys_user_room=%d%n",
                deptScope,
                jdbc.queryForObject("SELECT count(*) FROM m0_platform.sys_org", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM m0_platform.sys_user_org", Integer.class),
                jdbc.queryForObject("SELECT count(*) FROM m0_platform.sys_user_room", Integer.class));
        System.out.printf("[B-1][demo] locations=%s sys_user=%d demo_admin=%s role=%s must_change_password=%s%n",
                MigrationTestSupport.demoLocations(),
                jdbc.queryForObject("SELECT count(*) FROM m0_platform.sys_user", Integer.class),
                admin.get("username"), adminRoleCode, admin.get("must_change_password"));
    }
}
