package com.anesop.m4.api.staff;

import com.anesop.contracts.staff.model.CreateStaffTrainingRequest;
import com.anesop.contracts.staff.model.ExpiringTrainingList;
import com.anesop.contracts.staff.model.StaffTraining;
import com.anesop.contracts.staff.model.StaffTrainingList;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 培训记录服务（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>同一人员同一课程编码唯一（重复 409）；完成日期不得晚于今天（400）；到期日期不得早于
 *       完成日期（400）；</li>
 *   <li>到期提醒：{@code expiresOn <= 今天 + days}（含已过期，便于清零），默认 30 天
 *       （方案书 §2.4.4“证书到期前 30 天提醒”）；返回计算后的 status 与 daysUntilExpiry；</li>
 *   <li>数据范围同人员档案（ALL/SITE/DEPT/SELF）；</li>
 *   <li>审计 TRAINING_RECORD：detail 只含标识、课程编码、完成/到期日期，不含证书号原文；
 *       失败路径写 result=0 审计。</li>
 * </ul>
 */
public interface StaffTrainingService {

    /** 培训记录列表（按完成日期倒序）。 */
    StaffTrainingList list(String staffId, int limit, AuthPrincipal caller);

    /** 即将到期/已到期的培训（按到期日期升序）。 */
    ExpiringTrainingList expiring(Integer days, String departmentId, AuthPrincipal caller);

    /** 登记培训记录（同一人员同一课程编码重复 409）。 */
    StaffTraining create(CreateStaffTrainingRequest request, AuthPrincipal caller,
            AuthRequestContext context);
}
