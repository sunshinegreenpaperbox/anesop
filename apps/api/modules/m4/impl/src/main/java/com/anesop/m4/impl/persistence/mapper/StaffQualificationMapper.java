package com.anesop.m4.impl.persistence.mapper;

import com.anesop.m4.impl.persistence.entity.StaffQualificationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 执业资质 Mapper（{@code m4_staff.staff_qualification}，V21）。
 *
 * <p>替换式维护：档案 PUT 时先按 staffId 删除再插入提交列表（最新快照）。
 * 资质属人员主数据而非临床留痕，允许替换；变更摘要进审计（数量与类型，不含证书号原文）。
 */
@Mapper
public interface StaffQualificationMapper extends BaseMapper<StaffQualificationEntity> {

    @Insert("""
            INSERT INTO m4_staff.staff_qualification
                (id, staff_id, qualification_type, certificate_no, issuing_authority, valid_from,
                 valid_to, status, created_at, created_by)
            VALUES
                (#{id}, #{staffId}, #{qualificationType}, #{certificateNo}, #{issuingAuthority},
                 #{validFrom}, #{validTo}, #{status}, #{createdAt}, #{createdBy})
            """)
    int insertQualification(StaffQualificationEntity qualification);

    @Delete("DELETE FROM m4_staff.staff_qualification WHERE staff_id = #{staffId}")
    int deleteByStaff(@Param("staffId") UUID staffId);

    @Select("""
            SELECT * FROM m4_staff.staff_qualification
             WHERE staff_id = #{staffId}
             ORDER BY valid_from DESC, certificate_no
            """)
    List<StaffQualificationEntity> selectByStaff(@Param("staffId") UUID staffId);
}
