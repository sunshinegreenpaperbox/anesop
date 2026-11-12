package com.anesop.m0.impl.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 唯一性与 CHECK 约束的负向用例（S0-06 评审 S-8）。
 *
 * <p>断言数据库层而非应用层拒绝非法数据：大小写不敏感登录名、状态枚举、
 * 工号的部分唯一索引（NULL 可重复、非空重复失败）。
 */
class ConstraintNegativeTest {

    private static final String SYNTHETIC_HASH = "synthetic-password-hash-not-a-credential";

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
        assertThat(MigrationTestSupport.flyway(dataSource).migrate().success).isTrue();
    }

    private void insertUser(String username, String employeeNo, int status) {
        jdbc.update("INSERT INTO m0_platform.sys_user "
                        + "(id, username, password_hash, display_name, employee_no, status) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), username, SYNTHETIC_HASH, "合成约束测试用户", employeeNo, status);
    }

    @Test
    void usernameUniquenessIsCaseInsensitive() {
        insertUser("Admin", null, 1);

        Throwable failure = catchThrowable(() -> insertUser("admin", null, 1));

        assertThat(failure).isInstanceOf(DuplicateKeyException.class);
        SQLException sqlException = (SQLException) MigrationTestSupport.rootCause(failure);
        assertThat(sqlException.getSQLState()).isEqualTo("23505");
        assertThat(sqlException.getMessage()).contains("uk_sys_user_username");
    }

    @Test
    void statusOutsideAllowedValuesIsRejectedByCheckConstraint() {
        Throwable failure = catchThrowable(() -> insertUser("status_invalid", null, 2));

        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(failure).isNotInstanceOf(DuplicateKeyException.class);
        SQLException sqlException = (SQLException) MigrationTestSupport.rootCause(failure);
        assertThat(sqlException.getSQLState()).isEqualTo("23514");
        assertThat(sqlException.getMessage()).contains("ck_sys_user_status");
    }

    @Test
    void employeeNoIsUniqueOnlyWhenPresent() {
        insertUser("emp_null_a", null, 1);
        insertUser("emp_null_b", null, 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM m0_platform.sys_user WHERE employee_no IS NULL", Integer.class))
                .isEqualTo(2);

        insertUser("emp_first", "E-1001", 1);
        Throwable failure = catchThrowable(() -> insertUser("emp_second", "E-1001", 1));

        assertThat(failure).isInstanceOf(DuplicateKeyException.class);
        SQLException sqlException = (SQLException) MigrationTestSupport.rootCause(failure);
        assertThat(sqlException.getSQLState()).isEqualTo("23505");
        assertThat(sqlException.getMessage()).contains("uk_sys_user_employee_no");
    }
}
