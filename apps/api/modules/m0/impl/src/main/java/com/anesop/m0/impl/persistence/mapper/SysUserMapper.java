package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户表 Mapper（m0_platform.sys_user）。
 *
 * <p>失败计数与锁定必须用单条原子 SQL（B-3）：读-改-写在并发下会丢失更新，
 * 10 个并发错误口令可能只累计 1–2 次。这里的 UPDATE 依赖行锁与
 * {@code failed_attempts = failed_attempts + 1} 的自增语义，保证不丢计数。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 原子累加一次登录失败；达到阈值（5 次）时在同一条语句内置 30 分钟锁定。
     *
     * <p>不再读出 failed_attempts 再写回；锁定到期后的清零由
     * {@link #resetExpiredLock(UUID)} 单独完成（幂等）。
     *
     * @return 影响行数（1=账号存在且未删除；0=账号不存在/已删除）
     */
    @Update("""
            UPDATE m0_platform.sys_user
            SET failed_attempts = failed_attempts + 1,
                locked_until = CASE
                    WHEN failed_attempts + 1 >= 5 THEN now() + interval '30 minutes'
                    ELSE locked_until
                END,
                updated_at = now()
            WHERE id = #{userId} AND deleted = false
            """)
    int registerFailedAttempt(@Param("userId") UUID userId);

    /**
     * 锁定已过期时清零失败计数（幂等，仅在 locked_until <= now() 时生效）。
     *
     * @return 影响行数（1=本次清除了过期锁定；0=无过期锁定）
     */
    @Update("""
            UPDATE m0_platform.sys_user
            SET failed_attempts = 0,
                locked_until = NULL,
                updated_at = now()
            WHERE id = #{userId}
              AND deleted = false
              AND locked_until IS NOT NULL
              AND locked_until <= now()
            """)
    int resetExpiredLock(@Param("userId") UUID userId);

    /**
     * 全部启用用户标识（未删除且 status=1）；数据范围 ALL 的示例查询使用。
     *
     * <p>只返回主键，不返回姓名/工号/手机号；调用方仍需按最小必要原则使用。
     *
     * @return 用户标识列表（升序）
     */
    @Select("""
            SELECT id
            FROM m0_platform.sys_user
            WHERE deleted = false AND status = 1
            ORDER BY id
            """)
    List<UUID> selectAllActiveUserIds();

    /**
     * 某角色下的启用用户标识（M9-01 通知接收人目录）：未删除且 status=1，升序。
     *
     * <p>只返回主键，不返回姓名/工号/手机号；角色→用户走 V1
     * {@code idx_sys_user_role_role_id} 反向索引。调用方不得据此跨模块访问用户表。
     */
    @Select("""
            SELECT u.id
            FROM m0_platform.sys_user u
            JOIN m0_platform.sys_user_role ur ON ur.user_id = u.id
            JOIN m0_platform.sys_role r ON r.id = ur.role_id
            WHERE r.code = #{roleCode}
              AND u.deleted = false
              AND u.status = 1
            ORDER BY u.id
            """)
    List<UUID> selectActiveUserIdsByRoleCode(@Param("roleCode") String roleCode);

    /**
     * 修改口令并清除强制改密标记、失败计数与锁定（成功改密后允许立即登录）。
     *
     * @return 影响行数（1=成功；0=用户不存在/已删除）
     */
    @Update("""
            UPDATE m0_platform.sys_user
            SET password_hash = #{passwordHash},
                must_change_password = false,
                failed_attempts = 0,
                locked_until = NULL,
                updated_at = now(),
                version = version + 1
            WHERE id = #{userId} AND deleted = false
            """)
    int updatePassword(@Param("userId") UUID userId, @Param("passwordHash") String passwordHash);

    /**
     * 管理员重置口令（M2-M0-02）：写入新的 BCrypt 哈希并**强制**下次登录改密。
     *
     * <p>与 {@link #updatePassword} 的区别：自助改密清除强制标记，管理员重置重新置 true；
     * 同时清零失败计数与锁定，避免用户拿着一次性口令仍被旧计数锁住。
     *
     * @return 影响行数（1=成功；0=用户不存在/已删除）
     */
    @Update("""
            UPDATE m0_platform.sys_user
            SET password_hash = #{passwordHash},
                must_change_password = true,
                failed_attempts = 0,
                locked_until = NULL,
                updated_at = now(),
                updated_by = #{actorId},
                version = version + 1
            WHERE id = #{userId} AND deleted = false
            """)
    int adminResetPassword(@Param("userId") UUID userId,
            @Param("passwordHash") String passwordHash,
            @Param("actorId") UUID actorId);

    /**
     * 清空用户角色绑定（用户管理改角色时先删后插，事务内保证不出现半套角色）。
     *
     * @return 删除行数
     */
    @Delete("DELETE FROM m0_platform.sys_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") UUID userId);

    /**
     * 按角色编码绑定用户角色（编码由服务层先校验存在，避免授予不存在的角色）。
     *
     * @return 影响行数（1=绑定成功；0=角色编码不存在）
     */
    @Insert("""
            INSERT INTO m0_platform.sys_user_role (user_id, role_id, created_by)
            SELECT #{userId}, r.id, #{actorId}
            FROM m0_platform.sys_role r
            WHERE r.code = #{roleCode}
            """)
    int insertUserRoleByCode(@Param("userId") UUID userId,
            @Param("roleCode") String roleCode,
            @Param("actorId") UUID actorId);
}
