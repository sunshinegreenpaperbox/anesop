package com.anesop.m0.impl.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V7（M1 手术申请接收）迁移结构断言（S-7）。
 *
 * <p>覆盖：m1_sched 三表/唯一键/物理外键/索引/注释、状态历史无 updated_at/deleted、
 * append-only 触发器拒绝 UPDATE/DELETE/TRUNCATE、m1:patient:read 最小权限授权。
 * 使用 Zonky 嵌入式 PostgreSQL 16（Docker 不可用，EXT-08）。
 */
class M1MigrationTest {

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

    private void migrateToLatest() {
        MigrateResult result = MigrationTestSupport.flyway(dataSource).migrate();
        assertThat(result.success).as("Flyway migrate 必须成功").isTrue();
    }

    @Test
    void createsScheduleApplicationTablesIndexesAndComments() {
        migrateToLatest();

        List<String> tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'm1_sched' AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """, String.class);
        // V8（M2-M2-01）在同一 schema 追加术间与排程台次两表。
        assertThat(tables).containsExactly(
                "application_status_history", "operating_room", "patient_snapshot", "schedule",
                "surgery_application");

        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'm1_sched' AND indexname = 'uk_patient_snapshot_his_patient_no'
                """, String.class))
                .contains("UNIQUE", "his_patient_no");
        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'm1_sched' AND indexname = 'uk_patient_snapshot_inpatient_no_hash'
                """, String.class))
                .contains("UNIQUE", "inpatient_no_hash");
        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'm1_sched' AND indexname = 'uk_surgery_application_his_no'
                """, String.class))
                .contains("UNIQUE", "his_application_no");

        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'fk_surgery_application_patient'
                """, String.class))
                .contains("FOREIGN KEY", "patient_id", "patient_snapshot");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'fk_application_status_history_application'
                """, String.class))
                .contains("FOREIGN KEY", "application_id", "surgery_application");

        List<String> requiredIndexes = List.of(
                "idx_patient_snapshot_dept_id", "idx_patient_snapshot_site_id",
                "idx_patient_snapshot_deleted", "idx_surgery_application_patient_id",
                "idx_surgery_application_status", "idx_surgery_application_urgency",
                "idx_surgery_application_dept_id", "idx_surgery_application_site_id",
                "idx_surgery_application_submitted_at", "idx_surgery_application_status_submitted",
                "idx_surgery_application_deleted", "idx_application_status_history_application",
                "idx_application_status_history_occurred_at");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'm1_sched'
                """, String.class)).containsAll(requiredIndexes);

        Integer tablesWithoutComment = jdbc.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = 'm1_sched' AND c.relkind = 'r'
                  AND (obj_description(c.oid, 'pg_class') IS NULL
                       OR btrim(obj_description(c.oid, 'pg_class')) = '')
                """, Integer.class);
        assertThat(tablesWithoutComment).as("m1_sched 所有表必须有中文注释").isZero();

        Integer columnsWithoutComment = jdbc.queryForObject("""
                SELECT count(*) FROM pg_attribute a
                JOIN pg_class c ON a.attrelid = c.oid
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = 'm1_sched' AND c.relkind = 'r'
                  AND a.attnum > 0 AND NOT a.attisdropped
                  AND (col_description(c.oid, a.attnum) IS NULL
                       OR btrim(col_description(c.oid, a.attnum)) = '')
                """, Integer.class);
        assertThat(columnsWithoutComment).as("m1_sched 所有列必须有中文注释").isZero();

        // 状态历史 append-only：不得出现可变的 updated_at/deleted 列。
        List<String> mutableColumns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'm1_sched' AND table_name = 'application_status_history'
                  AND column_name IN ('updated_at', 'updated_by', 'deleted')
                """, String.class);
        assertThat(mutableColumns).as("状态历史不得有 updated_at/updated_by/deleted").isEmpty();

        // reason_code 与 reason 分列（S-2）。
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'm1_sched' AND table_name = 'application_status_history'
                  AND column_name IN ('reason_code', 'reason') ORDER BY column_name
                """, String.class)).containsExactly("reason", "reason_code");
        System.out.printf("[M2-M1-01][m1-migration] tables=%s indexes=%d comments=0%n",
                tables, requiredIndexes.size());
    }

    @Test
    void patientReadPermissionIsLeastPrivilege() {
        migrateToLatest();

        List<String> roles = jdbc.queryForList("""
                SELECT r.code FROM m0_platform.sys_permission p
                JOIN m0_platform.sys_role_permission rp ON rp.permission_id = p.id
                JOIN m0_platform.sys_role r ON r.id = rp.role_id
                WHERE p.code = 'm1:patient:read' ORDER BY r.code
                """, String.class);
        assertThat(roles).containsExactly("SYS_ADMIN", "SYS_OR_MANAGER", "SYS_SURGEON");
        assertThat(jdbc.queryForList("""
                SELECT p.path FROM m0_platform.sys_permission p WHERE p.code = 'm1:patient:read'
                """, String.class)).containsExactly("/api/v1/patients");
        System.out.printf("[M2-M1-01][m1-migration] m1:patient:read roles=%s%n", roles);
    }

    @Test
    void v8ScheduleRoomStructureAndLeastPrivilege() {
        migrateToLatest();

        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'm1_sched' AND indexname = 'uk_schedule_application_active'
                """, String.class))
                .contains("UNIQUE", "application_id", "status", "CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_schedule_time_window'
                """, String.class)).contains("CHECK", "end_at > start_at");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_schedule_priority'
                """, String.class)).contains("CHECK", "priority");
        assertThat(jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema = 'm1_sched' AND table_name = 'schedule'
                  AND column_name = 'priority'
                """, String.class)).contains("0");

        List<String> rooms = jdbc.queryForList("""
                SELECT room_no || ':' || room_type || ':' || dept_name
                FROM m1_sched.operating_room ORDER BY room_no
                """, String.class);
        assertThat(rooms).containsExactly(
                "OR-01:GENERAL:麻醉科（合成）", "OR-02:HYBRID:麻醉科（合成）",
                "OR-03:INTERVENTION:麻醉科（合成）", "OR-04:GENERAL:麻醉科（合成）",
                "OR-05:GENERAL:手术室（合成）");

        List<String> readRoles = jdbc.queryForList("""
                SELECT r.code FROM m0_platform.sys_permission p
                JOIN m0_platform.sys_role_permission rp ON rp.permission_id = p.id
                JOIN m0_platform.sys_role r ON r.id = rp.role_id
                WHERE p.code = 'm1:schedule:read' ORDER BY r.code
                """, String.class);
        assertThat(readRoles).containsExactly("SYS_ADMIN", "SYS_OR_MANAGER", "SYS_SURGEON");
        List<String> writeRoles = jdbc.queryForList("""
                SELECT r.code FROM m0_platform.sys_permission p
                JOIN m0_platform.sys_role_permission rp ON rp.permission_id = p.id
                JOIN m0_platform.sys_role r ON r.id = rp.role_id
                WHERE p.code = 'm1:schedule:write' ORDER BY r.code
                """, String.class);
        assertThat(writeRoles).containsExactly("SYS_ADMIN", "SYS_OR_MANAGER");
        System.out.printf("[M2-M2-01][v8-migration] rooms=%d readRoles=%s writeRoles=%s%n",
                rooms.size(), readRoles, writeRoles);
    }

    @Test
    void statusHistoryRejectsUpdateDeleteAndTruncate() {
        migrateToLatest();

        String patientId = "51000000-0000-4000-8000-0000000000a1";
        String applicationId = "61000000-0000-4000-8000-0000000000a1";
        String historyId = "71000000-0000-4000-8000-0000000000a1";
        jdbc.update("""
                INSERT INTO m1_sched.patient_snapshot
                    (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                     inpatient_no_hash, dept_name)
                VALUES (?::uuid, 'SYNTH-HIS-P-9101', '演示患者甲', 'FEMALE', DATE '1978-03-15', 48,
                        'SYNT****9101', 'hash-9101', '麻醉科（合成）')
                """, patientId);
        jdbc.update("""
                INSERT INTO m1_sched.surgery_application
                    (id, his_application_no, patient_id, surgery_name, urgency,
                     planned_duration_min, dept_name, source)
                VALUES (?::uuid, 'SYNTH-HIS-APP-9101', ?::uuid, '演示术式', 'ELECTIVE',
                        90, '麻醉科（合成）', 'HIS')
                """, applicationId, patientId);
        jdbc.update("""
                INSERT INTO m1_sched.application_status_history
                    (id, application_id, to_status, reason_code, reason)
                VALUES (?::uuid, ?::uuid, 'SUBMITTED', NULL, 'HIS 导入')
                """, historyId, applicationId);

        assertMutationRejected(() -> jdbc.update(
                "UPDATE m1_sched.application_status_history SET reason = 'TAMPERED' WHERE id = ?::uuid",
                historyId));
        assertMutationRejected(() -> jdbc.update(
                "DELETE FROM m1_sched.application_status_history WHERE id = ?::uuid", historyId));
        assertMutationRejected(() -> jdbc.execute("TRUNCATE m1_sched.application_status_history"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM m1_sched.application_status_history", Integer.class)).isEqualTo(1);
        System.out.println("[M2-M1-01][m1-migration] history UPDATE/DELETE/TRUNCATE 均被 55000 拒绝");
    }

    private static void assertMutationRejected(Runnable mutation) {
        Throwable failure = catchThrowable(mutation::run);
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class);
        SQLException sqlException = (SQLException) MigrationTestSupport.rootCause(failure);
        assertThat(sqlException.getSQLState()).isEqualTo("55000");
        assertThat(sqlException.getMessage()).contains("append-only");
    }
}
