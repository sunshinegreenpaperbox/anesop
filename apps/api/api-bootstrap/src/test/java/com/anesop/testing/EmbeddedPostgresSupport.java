package com.anesop.testing;

import javax.sql.DataSource;

/**
 * 嵌入式 PostgreSQL 16 测试基座（S0-11 统一入口，Docker 不可用 EXT-08）。
 *
 * <p>实现复用 {@code com.anesop.api.support.EmbeddedPostgresSupport}（Zonky embedded-postgres，
 * Apache-2.0 + PostgreSQL License）：同一 Surefire JVM 只启动一个 PG 实例，避免重复拉起。
 * 本类只是测试支撑层入口，新增测试请优先使用 {@code com.anesop.testing} 包，便于后续把
 * 实现从 {@code api.support} 迁移过来而不改测试 import。
 */
public final class EmbeddedPostgresSupport {

    private EmbeddedPostgresSupport() {
    }

    /**
     * 共享 PG 实例的 {@code postgres} 库数据源。
     *
     * @return 数据源
     */
    public static DataSource dataSource() {
        return com.anesop.api.support.EmbeddedPostgresSupport.dataSource();
    }

    /**
     * 默认库 JDBC URL。
     *
     * @return JDBC URL
     */
    public static String jdbcUrl() {
        return com.anesop.api.support.EmbeddedPostgresSupport.jdbcUrl();
    }

    /**
     * 指定数据库的 JDBC URL（不存在时在同一实例内创建）。
     *
     * @param database 数据库名（小写字母/数字/下划线）
     * @return JDBC URL
     */
    public static String jdbcUrl(String database) {
        return com.anesop.api.support.EmbeddedPostgresSupport.jdbcUrl(database);
    }

    /**
     * 删除测试数据库。
     *
     * @param database 数据库名
     */
    public static void dropDatabase(String database) {
        com.anesop.api.support.EmbeddedPostgresSupport.dropDatabase(database);
    }
}
