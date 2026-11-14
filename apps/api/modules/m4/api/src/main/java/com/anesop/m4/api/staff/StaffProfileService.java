package com.anesop.m4.api.staff;

import com.anesop.contracts.staff.model.CreateStaffRequest;
import com.anesop.contracts.staff.model.DepartmentList;
import com.anesop.contracts.staff.model.Staff;
import com.anesop.contracts.staff.model.StaffList;
import com.anesop.contracts.staff.model.UpdateStaffRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 人员档案服务（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>工号归一化（trim + 忽略大小写）后院内唯一，创建后不可改；userId 非空时一人一档案
 *       （重复 409）；</li>
 *   <li>科室必须存在、启用且为 {@code org_type=DEPT}（422），并在调用者数据范围内（403）；</li>
 *   <li>数据范围：ALL 全院、SITE 院区子树、DEPT 授权科室、SELF 仅本人档案（按 m0 userId）；
 *       SELF 不得写档案（403）；单对象越界与不存在统一 404（不泄露存在性）；</li>
 *   <li>更新为乐观锁（version 过期 409）；工号/userId/创建留痕不可改（数据库守卫 55000）；</li>
 *   <li>审计 STAFF_CREATE/STAFF_UPDATE：detail 只含标识、工号、科室、状态与资质数量，
 *       不含姓名、证书号原文与联系方式；失败路径写 result=0 审计。</li>
 * </ul>
 */
public interface StaffProfileService {

    /** 分页查询人员档案（关键字匹配工号/姓名/专长）。 */
    StaffList list(String keyword, String departmentId, String employmentStatus, int page,
            int size, AuthPrincipal caller);

    /** 档案详情；不存在或超出数据范围 404。 */
    Staff get(String staffId, AuthPrincipal caller);

    /** 新建档案；工号/userId 重复 409，科室不可用 422，SELF 范围 403。 */
    Staff create(CreateStaffRequest request, AuthPrincipal caller, AuthRequestContext context);

    /** 更新档案（乐观锁）；科室变更需原科室与新科室均在数据范围内。 */
    Staff update(String staffId, UpdateStaffRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 调用者可见的启用科室（档案/排班页面显式选择科室）。 */
    DepartmentList listDepartments(AuthPrincipal caller);
}
