package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.StaffScheduleEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 排班 Mapper（{@code m4_staff.staff_schedule}，V21）。
 *
 * <p>冲突检测：同一人员同一日期同一班次、同一术间同一日期同一班次（仅 PLANNED 占用名额）；
 * 服务层先查冲突返回 409 + details，数据库部分唯一索引是并发兜底。取消保留记录并释放名额，
 * 已取消记录不可复活（数据库守卫 55000）。
 */
@Mapper
public interface StaffScheduleMapper extends BaseMapper<StaffScheduleEntity> {

    @Insert("""
            INSERT INTO m4_staff.staff_schedule
                (id, staff_id, schedule_date, shift, room_id, department_id, status, note,
                 created_at, created_by, updated_at, updated_by, version)
            VALUES
                (#{id}, #{staffId}, #{scheduleDate}, #{shift}, #{roomId}, #{departmentId},
                 #{status}, #{note}, #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy},
                 #{version})
            """)
    int insertSchedule(StaffScheduleEntity schedule);

    /** 乐观锁更新（不改 status；取消走 {@link #cancelSchedule}）。 */
    @Update("""
            UPDATE m4_staff.staff_schedule
               SET staff_id = #{staffId},
                   schedule_date = #{scheduleDate},
                   shift = #{shift},
                   room_id = #{roomId},
                   department_id = #{departmentId},
                   note = #{note},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id} AND version = #{version} AND status = 'PLANNED'
            """)
    int updateSchedule(StaffScheduleEntity schedule);

    /** 取消：仅 PLANNED 可取消；version 过期或已取消时影响行数 0（服务层 409）。 */
    @Update("""
            UPDATE m4_staff.staff_schedule
               SET status = 'CANCELLED',
                   cancelled_at = #{cancelledAt},
                   cancelled_by = #{cancelledBy},
                   cancel_reason = #{cancelReason},
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id} AND version = #{version} AND status = 'PLANNED'
            """)
    int cancelSchedule(StaffScheduleEntity schedule);

    @Select("""
            SELECT s.*, p.staff_no AS staff_no
              FROM m4_staff.staff_schedule s
              JOIN m4_staff.staff_profile p ON p.id = s.staff_id
             WHERE s.id = #{id}
            """)
    StaffScheduleEntity selectById(@Param("id") UUID id);

    @Select("""
            <script>
            SELECT s.*, p.staff_no AS staff_no
              FROM m4_staff.staff_schedule s
              JOIN m4_staff.staff_profile p ON p.id = s.staff_id
            <where>
              <if test="staffId != null and staffId != ''">
                AND s.staff_id = #{staffId}::uuid
              </if>
              <if test="departmentId != null and departmentId != ''">
                AND s.department_id = #{departmentId}::uuid
              </if>
              <if test="from != null">
                AND s.schedule_date &gt;= #{from}
              </if>
              <if test="to != null">
                AND s.schedule_date &lt;= #{to}
              </if>
              <if test="shift != null and shift != ''">
                AND s.shift = #{shift}
              </if>
              <if test="status != null and status != ''">
                AND s.status = #{status}
              </if>
              <if test="allowedDeptIds != null">
                AND s.department_id IN
                <foreach collection="allowedDeptIds" item="deptId" open="(" separator="," close=")">
                  #{deptId}::uuid
                </foreach>
              </if>
              <if test="selfUserId != null and selfUserId != ''">
                AND p.user_id = #{selfUserId}::uuid
              </if>
            </where>
            ORDER BY s.schedule_date, s.shift, p.staff_no
            LIMIT #{limit}
            </script>
            """)
    List<StaffScheduleEntity> selectList(@Param("staffId") String staffId,
            @Param("departmentId") String departmentId, @Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("shift") String shift,
            @Param("status") String status, @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("selfUserId") String selfUserId, @Param("limit") int limit);

    /** 同人员同日同班次冲突（PLANNED；排除自身）。 */
    @Select("""
            SELECT s.*, p.staff_no AS staff_no
              FROM m4_staff.staff_schedule s
              JOIN m4_staff.staff_profile p ON p.id = s.staff_id
             WHERE s.staff_id = #{staffId}::uuid
               AND s.schedule_date = #{scheduleDate}
               AND s.shift = #{shift}
               AND s.status = 'PLANNED'
               AND (#{excludeId}::text IS NULL OR s.id <> #{excludeId}::uuid)
             LIMIT 1
            """)
    StaffScheduleEntity selectStaffConflict(@Param("staffId") String staffId,
            @Param("scheduleDate") LocalDate scheduleDate, @Param("shift") String shift,
            @Param("excludeId") String excludeId);

    /** 同术间同日同班次冲突（PLANNED；排除自身；roomId 为空不参与）。 */
    @Select("""
            SELECT s.*, p.staff_no AS staff_no
              FROM m4_staff.staff_schedule s
              JOIN m4_staff.staff_profile p ON p.id = s.staff_id
             WHERE s.room_id = #{roomId}::uuid
               AND s.schedule_date = #{scheduleDate}
               AND s.shift = #{shift}
               AND s.status = 'PLANNED'
               AND (#{excludeId}::text IS NULL OR s.id <> #{excludeId}::uuid)
             LIMIT 1
            """)
    StaffScheduleEntity selectRoomConflict(@Param("roomId") String roomId,
            @Param("scheduleDate") LocalDate scheduleDate, @Param("shift") String shift,
            @Param("excludeId") String excludeId);
}
