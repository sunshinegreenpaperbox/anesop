package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.StaffTrainingEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 培训记录 Mapper（{@code m4_staff.staff_training}，V21）。
 *
 * <p>到期提醒：{@code expires_on IS NOT NULL AND expires_on <= 今天 + days}（含已过期，便于
 * 清零），按到期时间升序；数据范围通过人员档案科室 / m0 用户过滤。
 */
@Mapper
public interface StaffTrainingMapper extends BaseMapper<StaffTrainingEntity> {

    @Insert("""
            INSERT INTO m4_staff.staff_training
                (id, staff_id, course_code, course_name, completed_on, expires_on, certificate_no,
                 created_at, created_by)
            VALUES
                (#{id}, #{staffId}, #{courseCode}, #{courseName}, #{completedOn}, #{expiresOn},
                 #{certificateNo}, #{createdAt}, #{createdBy})
            """)
    int insertTraining(StaffTrainingEntity training);

    @Select("""
            SELECT t.*, p.staff_no AS staff_no
              FROM m4_staff.staff_training t
              JOIN m4_staff.staff_profile p ON p.id = t.staff_id
             WHERE t.staff_id = #{staffId}
             ORDER BY t.completed_on DESC, t.course_code
            """)
    List<StaffTrainingEntity> selectByStaff(@Param("staffId") UUID staffId);

    @Select("""
            <script>
            SELECT t.*, p.staff_no AS staff_no
              FROM m4_staff.staff_training t
              JOIN m4_staff.staff_profile p ON p.id = t.staff_id
            <where>
              <if test="staffId != null and staffId != ''">
                AND t.staff_id = #{staffId}::uuid
              </if>
              <if test="allowedDeptIds != null">
                AND p.department_id IN
                <foreach collection="allowedDeptIds" item="deptId" open="(" separator="," close=")">
                  #{deptId}::uuid
                </foreach>
              </if>
              <if test="selfUserId != null and selfUserId != ''">
                AND p.user_id = #{selfUserId}::uuid
              </if>
            </where>
            ORDER BY t.completed_on DESC, p.staff_no
            LIMIT #{limit}
            </script>
            """)
    List<StaffTrainingEntity> selectVisible(@Param("staffId") String staffId,
            @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("selfUserId") String selfUserId, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT t.*, p.staff_no AS staff_no
              FROM m4_staff.staff_training t
              JOIN m4_staff.staff_profile p ON p.id = t.staff_id
             WHERE t.expires_on IS NOT NULL
               AND t.expires_on &lt;= #{deadline}
              <if test="departmentId != null and departmentId != ''">
                AND p.department_id = #{departmentId}::uuid
              </if>
              <if test="allowedDeptIds != null">
                AND p.department_id IN
                <foreach collection="allowedDeptIds" item="deptId" open="(" separator="," close=")">
                  #{deptId}::uuid
                </foreach>
              </if>
              <if test="selfUserId != null and selfUserId != ''">
                AND p.user_id = #{selfUserId}::uuid
              </if>
             ORDER BY t.expires_on, p.staff_no
            </script>
            """)
    List<StaffTrainingEntity> selectExpiring(@Param("deadline") LocalDate deadline,
            @Param("departmentId") String departmentId,
            @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("selfUserId") String selfUserId);

    @Select("""
            SELECT count(*) FROM m4_staff.staff_training
             WHERE staff_id = #{staffId}::uuid AND course_code = #{courseCode}
            """)
    long countCourseConflict(@Param("staffId") String staffId,
            @Param("courseCode") String courseCode);
}
