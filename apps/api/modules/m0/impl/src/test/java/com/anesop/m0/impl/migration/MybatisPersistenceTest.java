package com.anesop.m0.impl.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.anesop.m0.impl.persistence.entity.SysAuditLog;
import com.anesop.m0.impl.persistence.entity.SysUser;
import com.anesop.m0.impl.persistence.mapper.SysAuditLogMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MyBatis-Plus 持久层集成测试（真实嵌入式 PostgreSQL 16）。
 *
 * <p>S-3：jsonb / inet TypeHandler 往返，以及审计 append-only 在 Mapper 路径下仍被拒绝；
 * S-4：@TableLogic 软删除过滤、@Version 乐观锁拒绝旧版本更新。
 */
class MybatisPersistenceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
    void migrateDatabase() throws SQLException {
        MigrationTestSupport.resetDatabase(dataSource);
        assertThat(MigrationTestSupport.flyway(dataSource).migrate().success).isTrue();
    }

    @Test
    void jsonbAndInetTypeHandlersRoundTripAndAuditStaysAppendOnly() throws Exception {
        UUID auditId = UUID.randomUUID();
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SysAuditLogMapper mapper = session.getMapper(SysAuditLogMapper.class);

            SysAuditLog log = new SysAuditLog();
            log.setId(auditId);
            log.setActorName("SYNTHETIC TEST");
            log.setAction("LOGIN");
            log.setResourceType("USER");
            log.setResourceId("synthetic-user");
            log.setResult(1);
            log.setDetail("{\"synthetic\":true,\"fields\":[\"status\"]}");
            log.setIp("192.0.2.10");
            log.setTraceId("trace-s3-001");
            log.setOccurredAt(Instant.now());
            assertThat(mapper.insert(log)).isEqualTo(1);

            SysAuditLog loaded = mapper.selectById(auditId);
            assertThat(loaded).isNotNull();
            assertThat(loaded.getIp()).isEqualTo("192.0.2.10");
            JsonNode detail = JSON.readTree(loaded.getDetail());
            assertThat(detail.get("synthetic").asBoolean()).isTrue();
            assertThat(detail.get("fields").get(0).asText()).isEqualTo("status");
            assertThat(loaded.getOccurredAt()).isNotNull();

            // Mapper 路径的 UPDATE / DELETE 同样被数据库触发器拒绝（SQLState 55000）。
            SysAuditLog tampered = new SysAuditLog();
            tampered.setId(auditId);
            tampered.setAction("TAMPERED");
            assertAppendOnlyRejected(catchThrowable(() -> mapper.updateById(tampered)));
            assertAppendOnlyRejected(catchThrowable(() -> mapper.deleteById(auditId)));
        }

        assertThat(jdbc.queryForObject(
                "SELECT pg_typeof(detail)::text FROM m0_platform.sys_audit_log WHERE id = ?::uuid",
                String.class, auditId.toString())).isEqualTo("jsonb");
        assertThat(jdbc.queryForObject(
                "SELECT pg_typeof(ip)::text FROM m0_platform.sys_audit_log WHERE id = ?::uuid",
                String.class, auditId.toString())).isEqualTo("inet");
        assertThat(jdbc.queryForObject(
                "SELECT action FROM m0_platform.sys_audit_log WHERE id = ?::uuid",
                String.class, auditId.toString())).isEqualTo("LOGIN");
    }

    @Test
    void logicalDeleteHidesUserButKeepsRow() {
        UUID userId = insertUser("soft_delete_user", null, 0L);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SysUserMapper mapper = session.getMapper(SysUserMapper.class);
            assertThat(mapper.selectById(userId)).isNotNull();
            assertThat(mapper.deleteById(userId)).isEqualTo(1);
            assertThat(mapper.selectById(userId)).as("软删除后 selectById 返回 null").isNull();
        }

        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM m0_platform.sys_user WHERE id = ?::uuid",
                Boolean.class, userId.toString())).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_user WHERE id = ?::uuid",
                Integer.class, userId.toString())).isEqualTo(1);
    }

    @Test
    void staleVersionUpdateAffectsNoRows() {
        UUID userId = insertUser("optimistic_user", null, 0L);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SysUserMapper mapper = session.getMapper(SysUserMapper.class);

            SysUser current = mapper.selectById(userId);
            assertThat(current.getVersion()).isZero();
            current.setDisplayName("第一次更新");
            assertThat(mapper.updateById(current)).isEqualTo(1);

            SysUser stale = mapper.selectById(userId);
            assertThat(stale.getVersion()).isEqualTo(1L);
            stale.setVersion(0L);
            stale.setDisplayName("过期更新");
            assertThat(mapper.updateById(stale)).as("旧 version 更新影响行数必须为 0").isZero();
        }

        assertThat(jdbc.queryForObject(
                "SELECT display_name FROM m0_platform.sys_user WHERE id = ?::uuid",
                String.class, userId.toString())).isEqualTo("第一次更新");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM m0_platform.sys_user WHERE id = ?::uuid",
                Long.class, userId.toString())).isEqualTo(1L);
    }

    private UUID insertUser(String username, String employeeNo, long version) {
        UUID userId = UUID.randomUUID();
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername(username);
        user.setPasswordHash("synthetic-password-hash-not-a-credential");
        user.setDisplayName("合成持久层测试用户");
        user.setEmployeeNo(employeeNo);
        user.setStatus(1);
        user.setMustChangePassword(true);
        user.setFailedAttempts(0);
        user.setVersion(version);
        user.setDeleted(false);

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            assertThat(session.getMapper(SysUserMapper.class).insert(user)).isEqualTo(1);
        }
        return userId;
    }

    private static void assertAppendOnlyRejected(Throwable failure) {
        assertThat(failure).isInstanceOf(PersistenceException.class);
        SQLException sqlException = (SQLException) MigrationTestSupport.rootCause(failure);
        assertThat(sqlException.getSQLState()).isEqualTo("55000");
        assertThat(sqlException.getMessage()).contains("append-only");
    }
}
