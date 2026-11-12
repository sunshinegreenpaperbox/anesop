package com.anesop.m0.impl.persistence.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户—术间绑定 Mapper（m0_platform.sys_user_room，V5）。
 *
 * <p>数据范围 ROOM 的 scopeIds 来源；{@code room_id} 为逻辑外键（术间主数据由 M2-M2-01 在
 * m1_sched 建立），本 Mapper 不做跨 schema 联表，只按 status=1 读取生效授权。
 */
@Mapper
public interface SysUserRoomMapper {

    /**
     * 用户生效的术间授权标识。
     *
     * @param userId 用户标识
     * @return 术间标识列表（升序）；无授权时为空
     */
    @Select("""
            SELECT room_id
            FROM m0_platform.sys_user_room
            WHERE user_id = #{userId} AND status = 1
            ORDER BY room_id
            """)
    List<UUID> selectActiveRoomIdsByUserId(@Param("userId") UUID userId);

    /**
     * 术间下生效授权的有效用户，供数据范围示例接口按术间过滤。
     *
     * @param roomId 术间标识（逻辑外键）
     * @return 用户标识列表（升序）；无授权时为空
     */
    @Select("""
            SELECT ur.user_id
            FROM m0_platform.sys_user_room ur
            JOIN m0_platform.sys_user u ON u.id = ur.user_id AND u.deleted = false
            WHERE ur.room_id = #{roomId} AND ur.status = 1
            ORDER BY ur.user_id
            """)
    List<UUID> selectUserIdsByRoomId(@Param("roomId") UUID roomId);
}
