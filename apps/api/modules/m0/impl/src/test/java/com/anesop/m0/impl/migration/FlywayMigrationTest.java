package com.anesop.m0.impl.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 生产迁移路径（locations 只含 db/migration）的集成测试：
 * 空库迁移、结构/注释、幂等、平台预置基线、审计 append-only。
 *
 * <p>使用 Zonky 嵌入式 PostgreSQL 16（Docker 不可用，EXT-08），真实执行 Flyway；
 * 演示账号必须只存在于独立 location，见 {@link DemoSeedMigrationTest}。
 */
class FlywayMigrationTest {

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

    private Flyway migrateToLatest() {
        Flyway flyway = MigrationTestSupport.flyway(dataSource);
        MigrateResult result = flyway.migrate();
        assertThat(result.success).as("Flyway migrate 必须成功").isTrue();
        return flyway;
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM m0_platform." + table, Integer.class);
    }

    private int historyCount() {
        return jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history", Integer.class);
    }

    @Test
    void emptyDatabaseMigratesSuccessfully() {
        MigrateResult result = MigrationTestSupport.flyway(dataSource).migrate();

        assertThat(result.success).isTrue();
        // 其他模块（如 S0-09 的 V4 m2_anes）也会新增迁移，这里只锁定“至少含本任务 V3”。
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(3);
        assertThat(historyCount()).isEqualTo(result.migrationsExecuted);
        assertThat(jdbc.queryForList(
                "SELECT version FROM public.flyway_schema_history ORDER BY installed_rank", String.class))
                .containsSubsequence("1", "2", "3");
    }

    @Test
    void createsCoreBaselineTables() {
        migrateToLatest();

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'm0_platform' AND table_type = 'BASE TABLE'",
                String.class);

        assertThat(tables).contains(
                "sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission",
                "sys_audit_log", "sys_dict_type", "sys_dict_item", "sys_refresh_token",
                // M2-M0-01（V5）：数据范围绑定表
                "sys_org", "sys_user_org", "sys_user_room");
        assertThat(historyCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void allTimeSemanticColumnsUseTimestamptz() {
        migrateToLatest();

        List<String> timeColumns = jdbc.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'm0_platform' "
                        + "AND (column_name LIKE '%\\_at' ESCAPE '\\' "
                        + "OR column_name LIKE '%\\_time' ESCAPE '\\') ORDER BY 1",
                String.class);
        assertThat(timeColumns)
                .as("schema 必须存在时间语义列")
                .isNotEmpty()
                .contains("sys_user.created_at", "sys_user.updated_at", "sys_audit_log.occurred_at");

        List<String> wrongTimeTypes = jdbc.queryForList(
                "SELECT table_name || '.' || column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'm0_platform' "
                        + "AND (column_name LIKE '%\\_at' ESCAPE '\\' "
                        + "OR column_name LIKE '%\\_time' ESCAPE '\\') "
                        + "AND data_type <> 'timestamp with time zone'",
                String.class);
        assertThat(wrongTimeTypes).as("所有 _at/_time 列必须为 timestamptz").isEmpty();

        Integer mutableAuditColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'm0_platform' AND table_name = 'sys_audit_log' "
                        + "AND column_name IN ('updated_at', 'deleted')",
                Integer.class);
        assertThat(mutableAuditColumns).isZero();
    }

    @Test
    void allTablesAndColumnsHaveComments() {
        migrateToLatest();

        Integer tablesWithoutComment = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid "
                        + "WHERE n.nspname = 'm0_platform' AND c.relkind = 'r' "
                        + "AND (obj_description(c.oid, 'pg_class') IS NULL "
                        + "OR btrim(obj_description(c.oid, 'pg_class')) = '')",
                Integer.class);
        assertThat(tablesWithoutComment).as("所有表必须有中文注释").isZero();

        Integer columnsWithoutComment = jdbc.queryForObject(
                "SELECT count(*) FROM pg_attribute a "
                        + "JOIN pg_class c ON a.attrelid = c.oid "
                        + "JOIN pg_namespace n ON c.relnamespace = n.oid "
                        + "WHERE n.nspname = 'm0_platform' AND c.relkind = 'r' "
                        + "AND a.attnum > 0 AND NOT a.attisdropped "
                        + "AND (col_description(c.oid, a.attnum) IS NULL "
                        + "OR btrim(col_description(c.oid, a.attnum)) = '')",
                Integer.class);
        assertThat(columnsWithoutComment).as("所有列必须有中文注释").isZero();
    }

    @Test
    void createsKeyConstraintsAndIndexes() {
        migrateToLatest();

        List<String> constraints = jdbc.queryForList(
                "SELECT c.conname FROM pg_constraint c "
                        + "JOIN pg_class t ON c.conrelid = t.oid "
                        + "JOIN pg_namespace n ON t.relnamespace = n.oid "
                        + "WHERE n.nspname = 'm0_platform'", String.class);
        assertThat(constraints).contains(
                "ck_sys_user_status", "ck_sys_role_data_scope", "ck_sys_permission_type",
                "ck_sys_audit_log_result", "fk_sys_user_role_user", "fk_sys_dict_item_type");

        String usernameIndex = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE schemaname = 'm0_platform' AND indexname = 'uk_sys_user_username'",
                String.class);
        assertThat(usernameIndex)
                .as("登录名唯一索引必须大小写不敏感")
                .contains("UNIQUE", "lower", "username");

        String employeeNoIndex = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE schemaname = 'm0_platform' AND indexname = 'uk_sys_user_employee_no'",
                String.class);
        assertThat(employeeNoIndex)
                .as("工号唯一索引必须只为非空值生效")
                .contains("WHERE (employee_no IS NOT NULL)");

        String auditOccurredIndex = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE schemaname = 'm0_platform' AND indexname = 'idx_sys_audit_log_occurred_at'",
                String.class);
        assertThat(auditOccurredIndex).contains("occurred_at DESC");

        String auditResourceIndex = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE schemaname = 'm0_platform' AND indexname = 'idx_sys_audit_log_resource'",
                String.class);
        assertThat(auditResourceIndex)
                .as("审计对象维度索引（S-6）")
                .contains("resource_type", "resource_id", "occurred_at DESC");
    }

    @Test
    void repeatedMigrateIsIdempotent() {
        Flyway flyway = MigrationTestSupport.flyway(dataSource);

        MigrateResult first = flyway.migrate();
        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isGreaterThanOrEqualTo(3);

        MigrateResult second = flyway.migrate();
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(historyCount()).isEqualTo(first.migrationsExecuted);
    }

    @Test
    void productionSeedContainsOnlyPlatformBaseline() {
        migrateToLatest();

        assertThat(jdbc.queryForList("SELECT code FROM m0_platform.sys_role ORDER BY code", String.class))
                .containsExactly("SYS_ADMIN", "SYS_ANESTHESIOLOGIST", "SYS_NURSE_ANESTHETIST",
                        "SYS_OR_MANAGER", "SYS_PHARMACIST", "SYS_QC_MANAGER", "SYS_SURGEON");

        List<String> permissionCodes = jdbc.queryForList(
                "SELECT code FROM m0_platform.sys_permission ORDER BY code", String.class);
        // 结构性断言：锁定“必须存在的基线权限点”，不再硬编码数量（后续迁移可继续新增）。
        assertThat(permissionCodes)
                .contains("auth:login", "user:read", "user:write", "role:read", "role:write",
                        "audit:read", "dict:read", "dict:write")
                .contains("m0:access", "m1:access", "m2:access", "m3:access", "m4:access",
                        "m5:access", "m6:access", "m7:access", "m8:access")
                .contains("m0:auth:read", "m0:auth:write")
                .contains("m0:org:read", "m0:org:write")
                .contains("m0:user:read", "m0:user:write")
                .contains("m1:application:read", "m1:application:write", "m1:patient:read");

        assertThat(jdbc.queryForList("SELECT code FROM m0_platform.sys_dict_type ORDER BY code", String.class))
                .containsExactly("ASA", "CASE_STATUS", "OR_ROOM_STATUS", "ROLE_TYPE", "SEX",
                        "SURGERY_URGENCY");
        assertThat(countRows("sys_dict_item")).isEqualTo(22);

        // 结构性授权断言：关键权限点必须授给正确角色，替代脆弱的“总行数”硬编码。
        assertThat(roleCodesForPermission("m0:user:read")).containsExactly("SYS_ADMIN");
        assertThat(roleCodesForPermission("m1:application:write"))
                .containsExactly("SYS_ADMIN", "SYS_OR_MANAGER");
        assertThat(roleCodesForPermission("m1:patient:read"))
                .containsExactly("SYS_ADMIN", "SYS_OR_MANAGER", "SYS_SURGEON");

        // B-1 核心断言：生产迁移路径不产生任何账号，也没有演示账号及其角色绑定。
        assertThat(countRows("sys_user")).as("生产路径 sys_user 必须为 0").isZero();
        assertThat(countRows("sys_user_role")).as("生产路径 sys_user_role 必须为 0").isZero();
        assertThat(jdbc.queryForList(
                "SELECT username FROM m0_platform.sys_user WHERE username = 'demo_admin'", String.class))
                .isEmpty();
        System.out.printf("[B-1][production] locations=%s sys_user=%d sys_user_role=%d demo_admin=%d%n",
                MigrationTestSupport.productionLocations(), countRows("sys_user"), countRows("sys_user_role"),
                jdbc.queryForObject(
                        "SELECT count(*) FROM m0_platform.sys_user WHERE username = 'demo_admin'",
                        Integer.class));
    }

    @Test
    void phoneHashAndRefreshTokenColumnsAreReady() {
        migrateToLatest();

        // S-11：V3 expand 增加 phone_hash（写路径 M1），与 phone_enc 配套。
        Integer phoneHashColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'm0_platform' AND table_name = 'sys_user'
                  AND column_name = 'phone_hash' AND data_type = 'character varying'
                  AND character_maximum_length = 128
                """, Integer.class);
        assertThat(phoneHashColumns).as("phone_hash varchar(128) 必须由 V3 就绪").isEqualTo(1);

        Integer refreshTokenTable = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'm0_platform' AND table_name = 'sys_refresh_token'
                """, Integer.class);
        assertThat(refreshTokenTable).as("sys_refresh_token 必须由 V3 创建").isEqualTo(1);

        // 权限码统一三段式（S-7）。
        assertThat(jdbc.queryForList("""
                SELECT code FROM m0_platform.sys_permission
                WHERE code IN ('m0:auth:read', 'm0:auth:write') ORDER BY code
                """, String.class)).containsExactly("m0:auth:read", "m0:auth:write");
        System.out.printf("[S-11][migration] phone_hash=%d sys_refresh_token=%d permissions=%s%n",
                phoneHashColumns, refreshTokenTable,
                jdbc.queryForList("SELECT code FROM m0_platform.sys_permission "
                        + "WHERE code LIKE 'm0:auth:%' ORDER BY code", String.class));
    }

    @Test
    void dataScopeBindingTablesAreReady() {
        migrateToLatest();

        // sys_org：类型/状态/路径约束 + 唯一编码索引（数据范围对象）。
        String orgTypeCheck = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_sys_org_org_type'",
                String.class);
        assertThat(orgTypeCheck).contains("SITE", "DEPT");
        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'm0_platform' "
                        + "AND indexname = 'uk_sys_org_code'", String.class))
                .contains("UNIQUE", "code");

        // sys_user_org：复合主键 (user_id, org_id) + 主科室部分唯一索引。
        String userOrgPrimaryKey = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'pk_sys_user_org'",
                String.class);
        assertThat(userOrgPrimaryKey).contains("user_id", "org_id");
        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'm0_platform' "
                        + "AND indexname = 'uk_sys_user_org_primary'", String.class))
                .contains("UNIQUE", "is_primary");
        // 授权变更可追溯：sys_user_org 与 sys_user_room 一样具备 updated_at/updated_by。
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'm0_platform' AND table_name = 'sys_user_org'
                  AND column_name IN ('updated_at', 'updated_by')
                ORDER BY column_name
                """, String.class)).containsExactly("updated_at", "updated_by");

        // sys_user_room：唯一 (user_id, room_id) + 术间反向索引；room_id 为逻辑外键（无物理 FK）。
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'pk_sys_user_room'",
                String.class))
                .contains("user_id", "room_id");
        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'm0_platform' "
                        + "AND indexname = 'idx_sys_user_room_room_id'", String.class))
                .contains("room_id");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = 'm0_platform' AND table_name = 'sys_user_room' "
                        + "AND constraint_type = 'FOREIGN KEY' AND constraint_name = 'fk_sys_user_room_room'",
                Integer.class))
                .as("room_id 是跨模块逻辑外键，不得建物理外键")
                .isZero();

        // m0:org:read 授予 SYS_ADMIN + SYS_OR_MANAGER，m0:org:write 仅 SYS_ADMIN（Q-2）。
        List<String> orgPermissionRoles = jdbc.queryForList("""
                SELECT r.code FROM m0_platform.sys_permission p
                JOIN m0_platform.sys_role_permission rp ON rp.permission_id = p.id
                JOIN m0_platform.sys_role r ON r.id = rp.role_id
                WHERE p.code LIKE 'm0:org:%' ORDER BY r.code
                """, String.class);
        assertThat(orgPermissionRoles).containsExactly("SYS_ADMIN", "SYS_ADMIN", "SYS_OR_MANAGER");

        // 生产迁移路径不产生任何组织/绑定数据（只有结构）。
        assertThat(countRows("sys_org")).isZero();
        assertThat(countRows("sys_user_org")).isZero();
        assertThat(countRows("sys_user_room")).isZero();
        System.out.printf("[M2-M0-01][migration] sys_org=%d sys_user_org=%d sys_user_room=%d orgPermissions=%s%n",
                countRows("sys_org"), countRows("sys_user_org"), countRows("sys_user_room"), orgPermissionRoles);
    }

    @Test
    void phoneCryptoColumnsAndPermissionsAreReady() {
        migrateToLatest();

        // V6（M2-M0-02）：密钥版本与更新时间列就绪，phone_enc/phone_hash 沿用 V1/V3。
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'm0_platform' AND table_name = 'sys_user'
                  AND column_name = 'phone_key_id' AND data_type = 'character varying'
                  AND character_maximum_length = 32
                """, Integer.class)).as("phone_key_id varchar(32)").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'm0_platform' AND table_name = 'sys_user'
                  AND column_name = 'phone_updated_at' AND data_type = 'timestamp with time zone'
                """, Integer.class)).as("phone_updated_at timestamptz").isEqualTo(1);

        // 四列一致性约束：只写密文不写 hash/key_id 必须被库层拒绝（可失败断言）。
        String partial = "f0000000-0000-4000-8000-0000000000c9";
        Throwable rejected = catchThrowable(() -> jdbc.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted, phone_enc, phone_key_id)
                VALUES (?::uuid, 'test.partial.phone', 'synthetic-hash', '合成半写手机号', 1, false,
                        0, 0, false, 'SYNTHETIC-CIPHERTEXT', 'v1')
                """, partial));
        assertThat(rejected)
                .as("ck_sys_user_phone_crypto 必须拒绝只写密文不写哈希/时间")
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        assertThat(jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'm0_platform' "
                        + "AND indexname = 'idx_sys_user_phone_hash'", String.class))
                .contains("phone_hash");

        // 新权限点仅授予 SYS_ADMIN；V2 历史权限点保留（兼容，M1 收敛）。
        assertThat(jdbc.queryForList("""
                SELECT r.code FROM m0_platform.sys_permission p
                JOIN m0_platform.sys_role_permission rp ON rp.permission_id = p.id
                JOIN m0_platform.sys_role r ON r.id = rp.role_id
                WHERE p.code LIKE 'm0:user:%' ORDER BY p.code, r.code
                """, String.class)).containsExactly("SYS_ADMIN", "SYS_ADMIN");
        assertThat(jdbc.queryForList("""
                SELECT code FROM m0_platform.sys_permission
                WHERE code IN ('user:read', 'user:write') ORDER BY code
                """, String.class)).containsExactly("user:read", "user:write");
        System.out.printf("[M2-M0-02][migration] phone_key_id/phone_updated_at 就绪，m0:user:* 仅 SYS_ADMIN；"
                + "历史 user:read/user:write 保留%n");
    }

    @Test
    void auditLogRejectsUpdateDeleteAndTruncate() {
        migrateToLatest();

        String auditId = "f0000000-0000-4000-8000-000000000001";
        jdbc.update("INSERT INTO m0_platform.sys_audit_log "
                        + "(id, actor_name, action, resource_type, resource_id, result, detail, ip, trace_id) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?::jsonb, ?::inet, ?)",
                auditId, "SYNTHETIC TEST", "LOGIN", "USER", "synthetic-user", 1,
                "{\"synthetic\":true}", "192.0.2.10", "trace-synthetic-0001");

        Throwable updateFailure = catchThrowable(() -> jdbc.update(
                "UPDATE m0_platform.sys_audit_log SET action = 'TAMPERED' WHERE id = ?::uuid", auditId));
        assertThat(updateFailure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        SQLException updateSqlException = (SQLException) MigrationTestSupport.rootCause(updateFailure);
        assertThat(updateSqlException.getSQLState()).isEqualTo("55000");
        assertThat(updateSqlException.getMessage()).contains("append-only");

        Throwable deleteFailure = catchThrowable(() -> jdbc.update(
                "DELETE FROM m0_platform.sys_audit_log WHERE id = ?::uuid", auditId));
        assertThat(deleteFailure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        SQLException deleteSqlException = (SQLException) MigrationTestSupport.rootCause(deleteFailure);
        assertThat(deleteSqlException.getSQLState()).isEqualTo("55000");
        assertThat(deleteSqlException.getMessage()).contains("append-only");

        Throwable truncateFailure = catchThrowable(
                () -> jdbc.execute("TRUNCATE m0_platform.sys_audit_log"));
        assertThat(truncateFailure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(MigrationTestSupport.rootCause(truncateFailure).getMessage()).contains("append-only");

        assertThat(countRows("sys_audit_log")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT action FROM m0_platform.sys_audit_log WHERE id = ?::uuid", String.class, auditId))
                .isEqualTo("LOGIN");
    }

    @Test
    void auditRowsCarryJsonbAndInetTypes() {
        migrateToLatest();

        String auditId = "f0000000-0000-4000-8000-000000000002";
        jdbc.update("INSERT INTO m0_platform.sys_audit_log "
                        + "(id, action, result, detail, ip) VALUES (?::uuid, ?, ?, ?::jsonb, ?::inet)",
                auditId, "ROLE_GRANT", 1, "{\"role\":\"SYS_ADMIN\"}", "2001:db8::1");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT pg_typeof(detail)::text AS detail_type, pg_typeof(ip)::text AS ip_type "
                        + "FROM m0_platform.sys_audit_log WHERE id = ?::uuid", auditId);
        assertThat(row.get("detail_type")).isEqualTo("jsonb");
        assertThat(row.get("ip_type")).isEqualTo("inet");
        assertThat(jdbc.queryForObject(
                "SELECT detail ->> 'role' FROM m0_platform.sys_audit_log WHERE id = ?::uuid",
                String.class, auditId)).isEqualTo("SYS_ADMIN");
    }

    private List<String> roleCodesForPermission(String permissionCode) {
        return jdbc.queryForList("""
                SELECT r.code FROM m0_platform.sys_permission p
                JOIN m0_platform.sys_role_permission rp ON rp.permission_id = p.id
                JOIN m0_platform.sys_role r ON r.id = rp.role_id
                WHERE p.code = ? ORDER BY r.code
                """, String.class, permissionCode);
    }
}
