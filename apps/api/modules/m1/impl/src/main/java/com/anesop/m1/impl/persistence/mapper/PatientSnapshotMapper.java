package com.anesop.m1.impl.persistence.mapper;

import com.anesop.m1.impl.persistence.entity.PatientSnapshotEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 患者排程快照 Mapper（{@code m1_sched.patient_snapshot}）。
 *
 * <p>查询条件由服务层用 MyBatis-Plus Wrapper 构造；本接口只保留 BaseMapper 能力，
 * 避免在 Mapper 内拼接触摸患者标识的 SQL。
 *
 * <p>S-3：HIS 导入的患者快照写入使用**单条** {@code INSERT ... ON CONFLICT ... RETURNING id}
 * upsert。并发导入同一 HIS 患者号时数据库原子合并，不在已中止事务内回查（PostgreSQL 事务
 * 一旦冲突报错即不可继续执行 SQL）。
 */
@Mapper
public interface PatientSnapshotMapper extends BaseMapper<PatientSnapshotEntity> {

    /**
     * 按 HIS 患者号幂等 upsert 患者快照，返回数据库中的行 ID。
     *
     * @param patient 待写入快照（id 字段仅作新行候选值）
     * @return 实际行 ID（新增或已存在行）
     */
    @Select("""
            INSERT INTO m1_sched.patient_snapshot
                (id, his_patient_no, name, gender, birth_date, age, inpatient_no_masked,
                 inpatient_no_hash, dept_name, dept_id, ward, bed_no, site_id,
                 created_at, created_by, updated_at, updated_by, version, deleted)
            VALUES
                (#{id}, #{hisPatientNo}, #{name}, #{gender}, #{birthDate}, #{age}, #{inpatientNoMasked},
                 #{inpatientNoHash}, #{deptName}, #{deptId}, #{ward}, #{bedNo}, #{siteId},
                 #{createdAt}, #{createdBy}, #{updatedAt}, #{updatedBy}, 1, false)
            ON CONFLICT (his_patient_no) DO UPDATE
                SET name = EXCLUDED.name,
                    gender = EXCLUDED.gender,
                    birth_date = EXCLUDED.birth_date,
                    age = EXCLUDED.age,
                    inpatient_no_masked = EXCLUDED.inpatient_no_masked,
                    inpatient_no_hash = EXCLUDED.inpatient_no_hash,
                    dept_name = EXCLUDED.dept_name,
                    dept_id = EXCLUDED.dept_id,
                    ward = EXCLUDED.ward,
                    bed_no = EXCLUDED.bed_no,
                    site_id = EXCLUDED.site_id,
                    updated_at = EXCLUDED.updated_at,
                    updated_by = EXCLUDED.updated_by,
                    version = m1_sched.patient_snapshot.version + 1
            RETURNING id
            """)
    UUID upsertReturningId(PatientSnapshotEntity patient);
}
