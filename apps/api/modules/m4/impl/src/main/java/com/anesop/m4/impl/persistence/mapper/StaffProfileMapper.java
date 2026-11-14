package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 人员档案 Mapper（{@code m4_staff.staff_profile}，V21）。
 *
 * <p>数据范围过滤由服务层计算：{@code allowedDeptIds == null} 表示全院（ALL），否则按科室
 * 列表过滤；{@code selfUserId != null} 时按 m0 用户（SELF 范围）过滤。返回值含姓名，
 * 调用方不得把姓名写入日志或审计。
 */
@Mapper
public interface StaffProfileMapper extends BaseMapper<StaffProfileEntity> {

    @Insert("""
            INSERT INTO m4_staff.staff_profile
                (id, user_id, staff_no, name, department_id, site_id, title, job_grade,
                 employment_status, specialties, created_at, created_by, updated_at, updated_by,
                 version)
            VALUES
                (#{id}, #{userId}, #{staffNo}, #{name}, #{departmentId}, #{siteId}, #{title},
                 #{jobGrade}, #{employmentStatus}, #{specialties}::jsonb, #{createdAt},
                 #{createdBy}, #{updatedAt}, #{updatedBy}, #{version})
            """)
    int insertProfile(StaffProfileEntity profile);

    /** 乐观锁更新：工号/userId 不可改（数据库守卫 55000），版本过期影响行数 0（服务层 409）。 */
    @Update("""
            UPDATE m4_staff.staff_profile
               SET name = #{name},
                   department_id = #{departmentId},
                   site_id = #{siteId},
                   title = #{title},
                   job_grade = #{jobGrade},
                   employment_status = #{employmentStatus},
                   specialties = #{specialties}::jsonb,
                   updated_at = #{updatedAt},
                   updated_by = #{updatedBy},
                   version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int updateProfile(StaffProfileEntity profile);

    @Select("SELECT * FROM m4_staff.staff_profile WHERE id = #{id}")
    StaffProfileEntity selectById(@Param("id") UUID id);

    @Select("""
            SELECT * FROM m4_staff.staff_profile
             WHERE lower(btrim(staff_no)) = lower(btrim(#{staffNo}))
             LIMIT 1
            """)
    StaffProfileEntity selectByStaffNo(@Param("staffNo") String staffNo);

    @Select("SELECT * FROM m4_staff.staff_profile WHERE user_id = #{userId} LIMIT 1")
    StaffProfileEntity selectByUserId(@Param("userId") UUID userId);

    @Select("""
            <script>
            SELECT * FROM m4_staff.staff_profile
            <where>
              <if test="keyword != null and keyword != ''">
                (lower(staff_no) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(name) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(specialties::text) LIKE lower(concat('%', #{keyword}, '%')))
              </if>
              <if test="employmentStatus != null and employmentStatus != ''">
                AND employment_status = #{employmentStatus}
              </if>
              <if test="departmentId != null and departmentId != ''">
                AND department_id = #{departmentId}::uuid
              </if>
              <if test="allowedDeptIds != null">
                AND department_id IN
                <foreach collection="allowedDeptIds" item="deptId" open="(" separator="," close=")">
                  #{deptId}::uuid
                </foreach>
              </if>
              <if test="selfUserId != null and selfUserId != ''">
                AND user_id = #{selfUserId}::uuid
              </if>
            </where>
            ORDER BY department_id, staff_no
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<StaffProfileEntity> selectPage(@Param("keyword") String keyword,
            @Param("employmentStatus") String employmentStatus,
            @Param("departmentId") String departmentId,
            @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("selfUserId") String selfUserId,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT count(*) FROM m4_staff.staff_profile
            <where>
              <if test="keyword != null and keyword != ''">
                (lower(staff_no) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(name) LIKE lower(concat('%', #{keyword}, '%'))
                 OR lower(specialties::text) LIKE lower(concat('%', #{keyword}, '%')))
              </if>
              <if test="employmentStatus != null and employmentStatus != ''">
                AND employment_status = #{employmentStatus}
              </if>
              <if test="departmentId != null and departmentId != ''">
                AND department_id = #{departmentId}::uuid
              </if>
              <if test="allowedDeptIds != null">
                AND department_id IN
                <foreach collection="allowedDeptIds" item="deptId" open="(" separator="," close=")">
                  #{deptId}::uuid
                </foreach>
              </if>
              <if test="selfUserId != null and selfUserId != ''">
                AND user_id = #{selfUserId}::uuid
              </if>
            </where>
            </script>
            """)
    long countPage(@Param("keyword") String keyword,
            @Param("employmentStatus") String employmentStatus,
            @Param("departmentId") String departmentId,
            @Param("allowedDeptIds") List<String> allowedDeptIds,
            @Param("selfUserId") String selfUserId);
}
