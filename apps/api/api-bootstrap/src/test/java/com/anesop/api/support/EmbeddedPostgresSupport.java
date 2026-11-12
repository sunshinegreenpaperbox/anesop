package com.anesop.api.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试共享的嵌入式 PostgreSQL 16（Docker 不可用，EXT-08）。
 *
 * <p>同一 Surefire JVM 内只启动一次；Spring 上下文通过 {@code @DynamicPropertySource}
 * 指向该实例。不同安全 profile 的测试使用<b>独立数据库</b>（同一实例内 CREATE DATABASE），
 * 避免共享 Flyway history 与演示种子互相污染。
 */
public final class EmbeddedPostgresSupport {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static final Set<String> CREATED_DATABASES = ConcurrentHashMap.newKeySet();

    private EmbeddedPostgresSupport() {
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new IllegalStateException("嵌入式 PostgreSQL 16 启动失败", e);
            }
            dataSource = postgres.getPostgresDatabase();
            Runtime.getRuntime().addShutdownHook(
                    new Thread(EmbeddedPostgresSupport::close, "embedded-postgres-close"));
        }
        return dataSource;
    }

    public static String jdbcUrl() {
        return jdbcUrl("postgres");
    }

    /**
     * 返回指定数据库的 JDBC URL；数据库不存在时在同一 PG 实例内创建。
     *
     * @param database 数据库名，只允许小写字母/数字/下划线（含防止拼接注入）
     */
    public static synchronized String jdbcUrl(String database) {
        dataSource();
        if (!database.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("非法测试数据库名: " + database);
        }
        if (!"postgres".equals(database) && CREATED_DATABASES.add(database)) {
            JdbcTemplate admin = new JdbcTemplate(dataSource);
            Integer exists = admin.queryForObject(
                    "SELECT count(*) FROM pg_database WHERE datname = ?", Integer.class, database);
            if (exists == null || exists == 0) {
                try (Connection connection = dataSource.getConnection();
                        Statement statement = connection.createStatement()) {
                    statement.execute("CREATE DATABASE " + database);
                } catch (SQLException e) {
                    throw new IllegalStateException("创建测试数据库失败: " + database, e);
                }
            }
        }
        return postgres.getJdbcUrl("postgres", database);
    }

    /** 删除测试数据库（结束后清理，避免同一实例内累积）。 */
    public static synchronized void dropDatabase(String database) {
        if (!database.matches("[a-z][a-z0-9_]{0,62}") || "postgres".equals(database)) {
            return;
        }
        CREATED_DATABASES.remove(database);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                                + "WHERE datname = ? AND pid <> pg_backend_pid()")) {
            statement.setString(1, database);
            try (ResultSet ignored = statement.executeQuery()) {
                // 仅用于终止连接，结果集无需消费。
            }
        } catch (SQLException e) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("删除测试数据库失败: " + database, e);
        }
    }

    private static void close() {
        if (postgres == null) {
            return;
        }
        try {
            postgres.close();
        } catch (IOException e) {
            System.err.println("关闭嵌入式 PostgreSQL 失败: " + e.getMessage());
        }
    }
}
