package com.anesop.m1.api.his;

import java.time.LocalDate;
import java.util.UUID;

/**
 * HIS 手术申请原始记录（适配器输出，映射前的入站模型）。
 *
 * <p>{@code inpatientNo} 是 HIS 传入的住院号明文，只允许在本进程内用于生成脱敏值与哈希，
 * 禁止入库、入日志、入审计或进入任何 API 响应。{@code simulated} 由批次统一标记。
 *
 * @param hisApplicationNo  HIS 申请号（幂等键）
 * @param hisPatientNo      HIS 患者号
 * @param patientName       患者姓名（演示文本）
 * @param gender            性别 MALE/FEMALE/UNKNOWN
 * @param birthDate         出生日期
 * @param age               年龄
 * @param inpatientNo       住院号明文（仅内存使用，立即脱敏/哈希）
 * @param deptName          科室名称
 * @param deptId            科室标识（逻辑外键，可空）
 * @param siteId            院区标识（逻辑外键，可空）
 * @param ward              病区
 * @param bedNo             床号
 * @param surgeryName       拟施术式
 * @param surgeryCode       术式编码
 * @param urgency           紧急度 ELECTIVE/URGENT/EMERGENCY
 * @param plannedDurationMin 预计时长（分钟）
 * @param surgeonName       术者姓名（演示文本）
 * @param preopDiagnosis    术前诊断（演示文本）
 * @param remark            备注
 */
public record HisSurgeryApplicationRecord(
        String hisApplicationNo,
        String hisPatientNo,
        String patientName,
        String gender,
        LocalDate birthDate,
        Integer age,
        String inpatientNo,
        String deptName,
        UUID deptId,
        UUID siteId,
        String ward,
        String bedNo,
        String surgeryName,
        String surgeryCode,
        String urgency,
        Integer plannedDurationMin,
        String surgeonName,
        String preopDiagnosis,
        String remark) {
}
