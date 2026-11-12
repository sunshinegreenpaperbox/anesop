package com.anesop.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Docker 真实可用时的 Testcontainers 冒烟用例。
 *
 * <p>默认被 surefire 的 {@code excludedGroups=docker} 排除；{@code mvn -Pdocker verify}
 * 时执行。Docker 不可用（EXT-08）时 {@code disabledWithoutDocker=true} 使其 skipped，
 * 不会伪造成通过。
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class TestcontainersPostgresSmokeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = TestcontainersPostgresSupport.newPostgresContainer();

    @Test
    void postgres16AcceptsSql() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        try (Connection connection = POSTGRES.createConnection("");
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(1);
        }
    }
}
