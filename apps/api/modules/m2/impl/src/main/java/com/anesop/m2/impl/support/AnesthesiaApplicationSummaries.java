package com.anesop.m2.impl.support;

import com.anesop.contracts.anesthesia.model.AnesthesiaApplicationSummary;
import com.anesop.contracts.anesthesia.model.PreopPatientSnapshot;
import com.anesop.contracts.scheduling.model.PatientSnapshot;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.m2.api.error.M2UnprocessableException;

/**
 * 知情同意/三方核查页所需的申请上下文映射（M2-M5-01）。
 *
 * <p>只依赖 {@code m1-api} 返回的 {@link SurgeryApplication} DTO，不访问 m1 表/Mapper。
 * 患者快照沿用 anesthesia 契约既有的 {@code PreopPatientSnapshot} 结构（同一麻醉域字段口径），
 * 不新增重复 schema。
 */
public final class AnesthesiaApplicationSummaries {

    private AnesthesiaApplicationSummaries() {
    }

    /** 申请上下文：术式/科室/状态 + 患者快照（服务端由申请解析，不信任客户端）。 */
    public static AnesthesiaApplicationSummary toSummary(SurgeryApplication application) {
        PatientSnapshot patient = application.getPatient();
        if (patient == null) {
            throw new M2UnprocessableException("applicationId", "手术申请缺少患者快照");
        }
        AnesthesiaApplicationSummary summary = new AnesthesiaApplicationSummary();
        summary.setApplicationId(application.getApplicationId());
        summary.setPatientId(patient.getPatientId());
        summary.setPatientSnapshot(toPatientSnapshot(patient));
        summary.setSurgeryName(application.getSurgeryName());
        summary.setDepartmentName(application.getDepartmentName());
        summary.setStatus(AnesthesiaApplicationSummary.StatusEnum.fromValue(
                application.getStatus().getValue()));
        return summary;
    }

    /** 患者快照（院内患者号/姓名/性别/出生日期/年龄/科室病区床号；无证件号/手机号/住院号明文）。 */
    public static PreopPatientSnapshot toPatientSnapshot(PatientSnapshot patient) {
        PreopPatientSnapshot snapshot = new PreopPatientSnapshot();
        snapshot.setPatientId(patient.getPatientId());
        snapshot.setHisPatientNo(patient.getHisPatientNo());
        snapshot.setInpatientNoMasked(patient.getInpatientNoMasked());
        snapshot.setPatientName(patient.getName());
        snapshot.setGender(patient.getGender() == null ? PreopPatientSnapshot.GenderEnum.UNKNOWN
                : PreopPatientSnapshot.GenderEnum.fromValue(patient.getGender().getValue()));
        snapshot.setBirthDate(patient.getBirthDate());
        snapshot.setAge(patient.getAge());
        snapshot.setDepartmentName(patient.getDepartmentName());
        snapshot.setWard(patient.getWard());
        snapshot.setBedNo(patient.getBedNo());
        return snapshot;
    }
}
