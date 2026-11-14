package com.anesop.m4.api.staff;

import com.anesop.contracts.staff.model.CancelStaffScheduleRequest;
import com.anesop.contracts.staff.model.CreateStaffScheduleRequest;
import com.anesop.contracts.staff.model.StaffSchedule;
import com.anesop.contracts.staff.model.StaffScheduleList;
import com.anesop.contracts.staff.model.UpdateStaffScheduleRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.time.LocalDate;

/**
 * 排班服务（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>班次 DAY/NIGHT/ON_CALL（班次口径【待确认】）；日期为 Asia/Shanghai 自然日；</li>
 *   <li>冲突：同一人员同一日期同一班次、同一术间同一日期同一班次 → 409，
 *       {@code details[]} 给出 field（staffId/roomId）与冲突排班标识；已取消（CANCELLED）
 *       不占用名额；</li>
 *   <li>更新/取消为乐观锁（version 过期 409）；已取消不得复活（409 + 数据库 55000）；</li>
 *   <li>数据范围：ALL/SITE/DEPT 按科室；SELF 仅本人排班（只读，写操作 403）；</li>
 *   <li>审计 SCHEDULE_CREATE/SCHEDULE_UPDATE/SCHEDULE_CANCEL：detail 只含标识、日期、
 *       班次、科室与状态，不含姓名与患者信息；失败路径写 result=0 审计。</li>
 * </ul>
 */
public interface StaffScheduleService {

    /** 排班查询（按日期、班次、工号排序）。 */
    StaffScheduleList list(String staffId, String departmentId, LocalDate from, LocalDate to,
            String shift, String status, int limit, AuthPrincipal caller);

    /** 新建排班（冲突 409 + details）。 */
    StaffSchedule create(CreateStaffScheduleRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 更新排班（乐观锁 + 冲突检测，排除自身）。 */
    StaffSchedule update(String scheduleId, UpdateStaffScheduleRequest request,
            AuthPrincipal caller, AuthRequestContext context);

    /** 取消排班（保留记录，释放班次名额）。 */
    StaffSchedule cancel(String scheduleId, CancelStaffScheduleRequest request,
            AuthPrincipal caller, AuthRequestContext context);
}
