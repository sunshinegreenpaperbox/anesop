package com.anesop.m0.impl.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 升级路径与 forward-only 回滚策略测试：V1 先迁移，再升级到最新版本。
 *
 * <p>回滚不使用 Flyway undo：仓库内不存在 U 脚本，版本号连续递增；
 * 生产回滚策略为备份恢复或前向修复（见 docs/db/README.md）。
 */
class MigrationUpgradePathTest {

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

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM m0_platform." + table, Integer.class);
    }

    private int historyCount() {
        return jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history", Integer.class);
    }

    @Test
    void v1ThenLatestUpgradeSucceedsAndHistoryIncrements() {
        Flyway v1 = MigrationTestSupport.flywayAtVersion(dataSource, "1");
        MigrateResult v1Result = v1.migrate();

        assertThat(v1Result.success).isTrue();
        assertThat(v1Result.migrationsExecuted).isEqualTo(1);
        assertThat(v1.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(v1.info().current().getInstalledRank()).isEqualTo(1);
        assertThat(historyCount()).isEqualTo(1);
        // V1 只建结构，不带任何种子。
        assertThat(countRows("sys_role")).isZero();
        assertThat(countRows("sys_user")).isZero();

        Flyway latest = MigrationTestSupport.flyway(dataSource);
        MigrateResult upgradeResult = latest.migrate();

        assertThat(upgradeResult.success).isTrue();
        // V1 之后至少还有 V2/V3（其他模块的 V4+ 也一并升级）。
        assertThat(upgradeResult.migrationsExecuted).isGreaterThanOrEqualTo(2);
        assertThat(latest.info().current().getVersion().getVersion()).isNotEqualTo("1");
        assertThat(latest.info().current().getInstalledRank()).isGreaterThanOrEqualTo(3);
        assertThat(latest.info().pending()).isEmpty();

        assertThat(historyCount()).isEqualTo(1 + upgradeResult.migrationsExecuted);
        assertThat(jdbc.queryForList(
                "SELECT version FROM public.flyway_schema_history ORDER BY installed_rank", String.class))
                .containsSubsequence("1", "2", "3");
        // V2 预置 6 个平台角色 + V20 新增药房药师 SYS_PHARMACIST（M4-M15-01 药品域最小角色）。
        assertThat(countRows("sys_role")).isEqualTo(7);
        assertThat(countRows("sys_dict_item")).isEqualTo(22);
        // 生产路径升级后仍无账号：演示账号只能来自独立 location。
        assertThat(countRows("sys_user")).isZero();
    }

    @Test
    void migrationScriptsAreForwardOnlyContiguousAndNeverUndo() throws IOException {
        List<String> scriptNames = MigrationTestSupport.migrationScripts().stream()
                .map(path -> path.getFileName().toString())
                .toList();

        assertThat(scriptNames).isNotEmpty();
        assertThat(scriptNames)
                .as("脚本命名必须为 V<版本>__<描述>.sql")
                .allMatch(name -> name.matches("^V[0-9]+__.+\\.sql$"));
        // forward-only：不允许 U<版本>__*.sql（Flyway undo），回滚靠备份恢复或前向修复。
        assertThat(scriptNames).noneMatch(name -> name.startsWith("U"));

        List<Integer> versions = scriptNames.stream()
                .map(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
                .sorted()
                .toList();
        assertThat(versions.get(0)).as("版本号必须从 V1 开始").isEqualTo(1);
        for (int i = 1; i < versions.size(); i++) {
            assertThat(versions.get(i))
                    .as("版本号必须连续、无缺号")
                    .isEqualTo(versions.get(i - 1) + 1);
        }
    }
}
