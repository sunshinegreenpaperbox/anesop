package com.anesop.m0.impl.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

/**
 * 迁移测试公共支撑：定位仓库根、构造 Flyway、重置测试库。
 *
 * <p>仓库根优先取系统属性 {@code anesop.root}（scripts/mvn.sh 注入），
 * 否则从当前工作目录向上查找含 {@code db/migration} 的目录。
 *
 * <p>生产路径只挂载 {@code db/migration}；演示路径额外追加 {@code db/seed/demo}，
 * 两条路径分别由 {@link FlywayMigrationTest} 与 {@link DemoSeedMigrationTest} 覆盖。
 */
final class MigrationTestSupport {

    static final String SCHEMA = "m0_platform";

    private MigrationTestSupport() {
    }

    static Path repoRoot() {
        String configured = System.getProperty("anesop.root");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(candidate.resolve("db/migration"))) {
                return candidate;
            }
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("db/migration"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到含 db/migration 的仓库根目录，请通过 -Danesop.root 指定");
    }

    static String migrationLocation() {
        return "filesystem:" + repoRoot().resolve("db/migration");
    }

    static String demoSeedLocation() {
        return "filesystem:" + repoRoot().resolve("db/seed/demo");
    }

    /** 生产 locations：只含 db/migration，不得出现任何演示种子。 */
    static String productionLocations() {
        return migrationLocation();
    }

    /** 演示 locations：db/migration + db/seed/demo（仅 dev/test）。 */
    static String demoLocations() {
        return migrationLocation() + "," + demoSeedLocation();
    }

    static Flyway flyway(DataSource dataSource) {
        return flyway(dataSource, productionLocations());
    }

    static Flyway flyway(DataSource dataSource, String locations) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.split(","))
                .load();
    }

    static Flyway flywayAtVersion(DataSource dataSource, String version) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(productionLocations())
                .target(MigrationVersion.fromVersion(version))
                .load();
    }

    /** 清空测试库中的 schema 与 Flyway 历史，保证每个用例从真正的空库开始。 */
    static void resetDatabase(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    /** 仓库内 db/migration 下的全部迁移脚本，按文件名升序。 */
    static List<Path> migrationScripts() throws IOException {
        Path migrationDir = repoRoot().resolve("db/migration");
        try (Stream<Path> files = Files.list(migrationDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 取最底层原因，便于断言数据库抛出的 SQLState。
     */
    static Throwable rootCause(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
