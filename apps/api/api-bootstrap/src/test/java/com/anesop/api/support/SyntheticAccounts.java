package com.anesop.api.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 集成测试用 SYNTHETIC 账号（非生产数据，全部合成）。
 *
 * <p>{@code demo_admin} 来自 db/seed/demo；本类补充无 {@code m0:auth:read} 权限的外科医师
 * （越权 403 用例）与独立锁定用例账号（失败计数/并发锁定用例，避免污染 demo_admin）。
 * 口令为合成值，仅测试类路径可见。
 */
public final class SyntheticAccounts {

    public static final String DEMO_ADMIN_ID = "e0000000-0000-4000-8000-000000000001";
    public static final String DEMO_ADMIN_USERNAME = "demo_admin";
    public static final String DEMO_ADMIN_PASSWORD = "Anesop@2026";

    /** V100 演示种子的 BCrypt 哈希（cost=10，$2y$）；用于每个用例前把口令重置回种子值。 */
    public static final String DEMO_ADMIN_PASSWORD_HASH =
            "$2y$10$miTTPo10LatZjpihp0Gx0O2pVhVQuPK9HFgz.m7iFbpyyvztia6ue";

    public static final String SURGEON_ID = "e0000000-0000-4000-8000-0000000000a1";
    public static final String SURGEON_USERNAME = "test.surgeon";
    public static final String SURGEON_PASSWORD = "Synth@2026";
    public static final String SURGEON_ROLE_ID = "a0000000-0000-4000-8000-000000000004";

    public static final String LOCKOUT_ID = "e0000000-0000-4000-8000-0000000000b1";
    public static final String LOCKOUT_USERNAME = "test.lockout";
    public static final String LOCKOUT_PASSWORD = "Lockout@2026";

    /** V100 演示种子：DEPT 数据范围用户（SYS_OR_MANAGER，绑定麻醉科）。 */
    public static final String DEMO_DEPT_ID = "e0000000-0000-4000-8000-000000000002";
    public static final String DEMO_DEPT_USERNAME = "demo_dept";

    /** V100 演示种子：SELF 数据范围用户（SYS_ANESTHESIOLOGIST）。 */
    public static final String DEMO_SELF_ID = "e0000000-0000-4000-8000-000000000003";
    public static final String DEMO_SELF_USERNAME = "demo_self";
    public static final String DEMO_SELF_PASSWORD = "Anesop@2026";

    /** V100 演示种子：麻醉科 / 手术室组织（DEPT 范围对象）。 */
    public static final String DEMO_ANES_ORG_ID = "f1000000-0000-4000-8000-000000000002";
    public static final String DEMO_OR_ORG_ID = "f1000000-0000-4000-8000-000000000003";

    /** M2-M0-01 用例：无任何角色的账号（/me dataScopes 为空、示例接口 403）。 */
    public static final String UNBOUND_ID = "e0000000-0000-4000-8000-0000000000c1";
    public static final String UNBOUND_USERNAME = "test.unbound";
    public static final String UNBOUND_PASSWORD = "Synth@2026";

    /** M2-M0-01 用例：ROOM 数据范围账号（仅绑定术间 1）。 */
    public static final String ROOM_USER_ID = "e0000000-0000-4000-8000-0000000000c2";
    public static final String ROOM_USERNAME = "test.room";
    public static final String ROOM_PASSWORD = "Synth@2026";
    public static final String ROOM_ROLE_ID = "a0000000-0000-4000-8000-0000000000f1";
    public static final String ROOM_1_ID = "f2000000-0000-4000-8000-0000000000c1";
    public static final String ROOM_2_ID = "f2000000-0000-4000-8000-0000000000c2";

    private SyntheticAccounts() {
    }

    /** 每个用例前重置 demo_admin：恢复种子口令、关闭强制改密、清零失败计数与锁定。 */
    public static void resetDemoAdmin(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("""
                UPDATE m0_platform.sys_user
                SET password_hash = ?, must_change_password = false, failed_attempts = 0,
                    locked_until = NULL, status = 1, deleted = false
                WHERE id = ?::uuid
                """, DEMO_ADMIN_PASSWORD_HASH, DEMO_ADMIN_ID);
    }

    /**
     * 重置 V100 的三个演示种子账号（demo_admin / demo_dept / demo_self）。
     *
     * <p>演示种子默认 must_change_password=true；数据范围用例需要账号可访问普通接口，
     * 故统一恢复种子口令、关闭强制改密并清零失败/锁定。
     */
    public static void resetDemoAccounts(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("""
                UPDATE m0_platform.sys_user
                SET password_hash = ?, must_change_password = false, failed_attempts = 0,
                    locked_until = NULL, status = 1, deleted = false
                WHERE id IN (?::uuid, ?::uuid, ?::uuid)
                """, DEMO_ADMIN_PASSWORD_HASH, DEMO_ADMIN_ID, DEMO_DEPT_ID, DEMO_SELF_ID);
    }

    /** 设置强制改密标记（S-3 用例）。 */
    public static void setMustChangePassword(JdbcTemplate jdbcTemplate, String username, boolean required) {
        jdbcTemplate.update("UPDATE m0_platform.sys_user SET must_change_password = ? WHERE username = ?",
                required, username);
    }

    /** 幂等创建外科医师账号并绑定 SYS_SURGEON（无 m0:auth:read）。 */
    public static void ensureSurgeon(JdbcTemplate jdbcTemplate) {
        String hash = new BCryptPasswordEncoder().encode(SURGEON_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试外科医师（合成）', 1, false, 0, 0, false)
                ON CONFLICT DO NOTHING
                """, SURGEON_ID, SURGEON_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, SURGEON_ID, SURGEON_ROLE_ID);
    }

    /** 幂等创建锁定用例账号（绑定 SYS_ANESTHESIOLOGIST，不影响 demo_admin 用例）。 */
    public static void ensureLockoutUser(JdbcTemplate jdbcTemplate) {
        String hash = new BCryptPasswordEncoder().encode(LOCKOUT_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试锁定账号（合成）', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    failed_attempts = 0, locked_until = NULL, must_change_password = false, status = 1
                """, LOCKOUT_ID, LOCKOUT_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, LOCKOUT_ID, "a0000000-0000-4000-8000-000000000002");
    }

    /**
     * 幂等创建“有 DEPT 角色、无组织绑定”的账号（数据范围 fail-closed 用例）。
     *
     * <p>授予 SYS_OR_MANAGER（V5 起含 m0:org:read），确保 403 来自数据范围为空而非缺权限。
     */
    public static void ensureUnboundUser(JdbcTemplate jdbcTemplate) {
        String hash = new BCryptPasswordEncoder().encode(UNBOUND_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试无角色账号（合成）', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, UNBOUND_ID, UNBOUND_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, 'a0000000-0000-4000-8000-000000000005') ON CONFLICT DO NOTHING
                """, UNBOUND_ID);
        // 仍保持“无组织绑定”，由解析器 fail-closed 为 NONE。
        jdbcTemplate.update("DELETE FROM m0_platform.sys_user_org WHERE user_id = ?::uuid", UNBOUND_ID);
    }

    /** 幂等创建 ROOM 数据范围账号：自定义 ROOM 角色 + 术间 1 绑定（术间 2 不授权）。 */
    public static void ensureRoomUser(JdbcTemplate jdbcTemplate) {
        String hash = new BCryptPasswordEncoder().encode(ROOM_PASSWORD);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role (id, code, name, description, data_scope)
                VALUES (?::uuid, 'SYN_ROOM', '合成术间角色', 'SYNTHETIC：数据范围 ROOM 用例', 'ROOM')
                ON CONFLICT (id) DO NOTHING
                """, ROOM_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user
                    (id, username, password_hash, display_name, status, must_change_password,
                     failed_attempts, version, deleted)
                VALUES (?::uuid, ?, ?, '测试术间账号（合成）', 1, false, 0, 0, false)
                ON CONFLICT (id) DO UPDATE SET password_hash = EXCLUDED.password_hash,
                    must_change_password = false, failed_attempts = 0, locked_until = NULL,
                    status = 1, deleted = false
                """, ROOM_USER_ID, ROOM_USERNAME, hash);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_role (user_id, role_id)
                VALUES (?::uuid, ?::uuid) ON CONFLICT DO NOTHING
                """, ROOM_USER_ID, ROOM_ROLE_ID);
        // 示例接口要求 m0:org:read；仅授予该测试角色，不改变迁移基线。
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_role_permission (role_id, permission_id)
                VALUES (?::uuid, 'b0000000-0000-4000-8000-000000000020') ON CONFLICT DO NOTHING
                """, ROOM_ROLE_ID);
        jdbcTemplate.update("""
                INSERT INTO m0_platform.sys_user_room (user_id, room_id, status)
                VALUES (?::uuid, ?::uuid, 1) ON CONFLICT DO NOTHING
                """, ROOM_USER_ID, ROOM_1_ID);
        // 未授权术间 2 的绑定必须不存在，避免用例之间互相污染。
        jdbcTemplate.update("""
                DELETE FROM m0_platform.sys_user_room WHERE user_id = ?::uuid AND room_id = ?::uuid
                """, ROOM_USER_ID, ROOM_2_ID);
    }

    /** 查询用户当前 failed_attempts / locked_until（并发锁定断言用）。 */
    public static LockState lockState(JdbcTemplate jdbcTemplate, String userId) {
        return jdbcTemplate.queryForObject("""
                SELECT failed_attempts, locked_until
                FROM m0_platform.sys_user WHERE id = ?::uuid
                """, (rs, rowNum) -> new LockState(rs.getInt("failed_attempts"), rs.getTimestamp("locked_until")),
                userId);
    }

    /** 失败计数快照。 */
    public record LockState(int failedAttempts, java.sql.Timestamp lockedUntil) {
        public boolean locked() {
            return lockedUntil != null;
        }
    }
}
