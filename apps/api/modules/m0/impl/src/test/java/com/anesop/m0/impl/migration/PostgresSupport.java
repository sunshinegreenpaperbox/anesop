package com.anesop.m0.impl.migration;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;

/**
 * 全模块共享的嵌入式 PostgreSQL 16 实例（Docker 不可用，EXT-08）。
 *
 * <p>同一 Surefire JVM 内只启动一次，测试类之间通过重置 schema 隔离；
 * 进程退出时由 shutdown hook 关闭数据库。迁移测试与 MyBatis 集成测试共用此实例。
 */
final class PostgresSupport {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private PostgresSupport() {
    }

    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new IllegalStateException("嵌入式 PostgreSQL 16 启动失败", e);
            }
            dataSource = postgres.getPostgresDatabase();
            Runtime.getRuntime().addShutdownHook(
                    new Thread(PostgresSupport::close, "embedded-postgres-close"));
        }
        return dataSource;
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
