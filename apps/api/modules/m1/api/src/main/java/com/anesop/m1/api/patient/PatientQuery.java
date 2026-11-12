package com.anesop.m1.api.patient;

/**
 * 患者主索引查询条件（契约 patient.yaml 0.3.0 {@code GET /v1/patients}）。
 *
 * @param page         页码
 * @param size         每页条数
 * @param hisPatientNo HIS 患者号（精确）
 * @param patientNo    院内患者编号（精确）
 * @param name         姓名关键字（模糊；只用于检索，不写日志）
 * @param inpatientNo  住院号（精确；服务端先哈希再匹配，明文不落库）
 * @param departmentId 科室标识
 */
public record PatientQuery(
        int page,
        int size,
        String hisPatientNo,
        String patientNo,
        String name,
        String inpatientNo,
        String departmentId) {
}
