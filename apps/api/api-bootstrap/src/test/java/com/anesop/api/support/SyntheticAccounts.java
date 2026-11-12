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
