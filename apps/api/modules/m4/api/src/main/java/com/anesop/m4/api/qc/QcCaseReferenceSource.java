package com.anesop.m4.api.qc;

import java.util.Optional;
import java.util.UUID;

/**
 * 质控逻辑关联台次/记录单的只读校验端口（M4-M17-01）。
 *
 * <p>不良事件的 {@code scheduleId} 与病历抽查的 {@code scheduleId}/{@code recordId} 是跨模块
 * **逻辑关联**（不建外键）：写入前必须校验目标存在且属于提交科室，避免把事件/抽查挂到不存在
 * 或他科的台次/记录单上（评审 S-3）。
 *
 * <p>端口只返回目标所属科室标识；不存在/已删除返回 {@link Optional#empty()}。实际 SQL 放在
 * api-bootstrap 装配层（与 {@link QcIndicatorSource} 同理），m4-impl 不访问其他模块表/Mapper。
 */
public interface QcCaseReferenceSource {

    /** 台次所属科室（{@code m1_sched.schedule → surgery_application.dept_id}）。 */
    Optional<UUID> scheduleDepartment(UUID scheduleId);

    /** 麻醉记录单所属科室（{@code m2_anes.anesthesia_record → surgery_application.dept_id}）。 */
    Optional<UUID> recordDepartment(UUID recordId);
}
