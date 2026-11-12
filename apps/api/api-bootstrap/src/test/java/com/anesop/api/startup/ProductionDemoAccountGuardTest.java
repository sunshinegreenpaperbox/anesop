package com.anesop.api.startup;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.api.config.SecurityProperties;
import com.anesop.api.support.EmbeddedPostgresSupport;
import com.anesop.api.support.MybatisMapperSupport;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 启动安全守卫（S-9 / B-2）：真实嵌入式 PostgreSQL 16 验证 {@code username LIKE 'demo\_%'}
 * 的转义语义——构造 {@code demo_admin} 与 {@code demouser} 两个账号，只有前者命中；
 * 并验证 {@code demo-accounts-allowed} 默认拒绝、显式允许时放行。
 *
 * <p>不使用 mock Mapper：SQL 与逻辑删除条件都走真实数据库。
 */
class ProductionDemoAccountGuardTest {

    private static final String DATABASE = "anesop_guard_s9";
    private static final DefaultApplicationArguments ARGS = new DefaultApplicationArguments();

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private final SecurityProperties denied = new SecurityProperties();
    private final SecurityProperties allowed = securityProperties(true);

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(EmbeddedPostgresSupport.jdbcUrl(DATABASE), "postgres", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void drop() {
        EmbeddedPostgresSupport.dropDatabase(DATABASE);
    }

    @BeforeEach
    void cleanUsers() {
        jdbc.update("DELETE FROM m0_platform.sys_user_role");
        jdbc.update("DELETE FROM m0_platform.sys_user");
    }

    @Test
    void demouserDoesNotMatchEscapedDemoPrefix() throws Exception {
        insertUser("demouser");

        try (SqlSession session = MybatisMapperSupport.openSession(dataSource)) {
            ProductionDemoAccountGuard guard = new ProductionDemoAccountGuard(
                    session.getMapper(SysUserMapper.class), denied);
            assertThatCode(() -> guard.run(ARGS)).doesNotThrowAnyException();
        }
    }

    @Test
    void demoAdminIsRejectedAndDemouserIsNotCounted() throws Exception {
        insertUser("demouser");
        insertUser("demo_admin");

        try (SqlSession session = MybatisMapperSupport.openSession(dataSource)) {
            ProductionDemoAccountGuard guard = new ProductionDemoAccountGuard(
                    session.getMapper(SysUserMapper.class), denied);
            assertThatThrownBy(() -> guard.run(ARGS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("1 个 demo_*")
                    .hasMessageContaining("拒绝启动");
        }
    }

    @Test
    void demoAdminAllowedOnlyWhenExplicitlyEnabled() throws Exception {
        insertUser("demo_admin");

        try (SqlSession session = MybatisMapperSupport.openSession(dataSource)) {
            SysUserMapper mapper = session.getMapper(SysUserMapper.class);
            assertThatCode(() -> new ProductionDemoAccountGuard(mapper, allowed).run(ARGS))
                    .doesNotThrowAnyException();
        }
    }

    private static void insertUser(String username) {
        jdbc.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, 'synthetic-not-a-real-hash', '合成守卫测试账号', 1, false, 0, 0, false)
                """, UUID.randomUUID().toString(), username);
    }

    private static SecurityProperties securityProperties(boolean demoAccountsAllowed) {
        SecurityProperties properties = new SecurityProperties();
        properties.setDemoAccountsAllowed(demoAccountsAllowed);
        return properties;
    }
}
