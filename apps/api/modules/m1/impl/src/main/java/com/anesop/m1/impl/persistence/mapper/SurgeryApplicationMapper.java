package com.anesop.m1.impl.persistence.mapper;

import com.anesop.m1.impl.persistence.entity.SurgeryApplicationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 手术申请 Mapper（{@code m1_sched.surgery_application}）。
 *
 * <p>HIS 导入必须按 {@code his_application_no} 幂等：{@link #insertIfAbsent} 使用
 * {@code ON CONFLICT DO NOTHING}，返回 0 表示该申请号已存在（跳过），1 表示本次新增。
 * 手工创建走 {@code BaseMapper.insert}，重复由服务层先查再返回 409，并发撞唯一键由
 * Web 层 {@code DuplicateKeyException} 兜底映射 409。
 */
@Mapper
public interface SurgeryApplicationMapper extends BaseMapper<SurgeryApplicationEntity> {

    /**
     * 幂等插入一条手术申请。
     *
     * @return 1=新增；0=his_application_no 已存在（未写入）
     */
    @Insert("""
            INSERT INTO m1_sched.surgery_application
                (id, his_application_no, patient_id, surgery_name, surgery_code, urgency,
                 planned_duration_min, dept_name, dept_id, site_id, surgeon_name, preop_diagnosis,
                 status, submitted_at, source, remark, created_at, created_by, updated_at, updated_by,
                 version, deleted)
            VALUES
                (#{id}, #{hisApplicationNo}, #{patientId}, #{surgeryName}, #{surgeryCode}, #{urgency},
                 #{plannedDurationMin}, #{deptName}, #{deptId}, #{siteId}, #{surgeonName}, #{preopDiagnosis},
                 #{status}, #{submittedAt}, #{source}, #{remark}, #{createdAt}, #{createdBy}, #{updatedAt},
                 #{updatedBy}, #{version}, false)
            ON CONFLICT (his_application_no) DO NOTHING
            """)
    int insertIfAbsent(SurgeryApplicationEntity application);
}
