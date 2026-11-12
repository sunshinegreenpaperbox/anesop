package com.anesop.m1.api.application;

import com.anesop.contracts.scheduling.model.CancelSurgeryApplicationRequest;
import com.anesop.contracts.scheduling.model.IdResponse;
import com.anesop.contracts.scheduling.model.ImportSurgeryApplicationsRequest;
import com.anesop.contracts.scheduling.model.ImportSurgeryApplicationsResult;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.contracts.scheduling.model.SurgeryApplicationCreateRequest;
import com.anesop.contracts.scheduling.model.SurgeryApplicationPage;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 手术申请服务（M2-M1-01）：HIS 模拟导入、手工创建、分页/详情查询、取消。
 *
 * <p>数据范围：所有查询与写操作都按调用者 {@link com.anesop.m0.api.security.DataScopeAssignments}
 * 过滤；无数据范围（fail-closed）抛 {@code AccessDeniedException}（403），单对象越界返回 404。
 * 申请状态机：SUBMITTED → ACCEPTED → SCHEDULED；CANCELLED/REJECTED 为终态，非法流转 409。
 * 状态变更写 {@code application_status_history} 与审计，审计 detail 不含患者姓名/诊断。
 */
public interface SurgeryApplicationService {

    /** 分页查询手术申请（状态/紧急度/科室过滤 + 数据范围）。 */
    SurgeryApplicationPage list(SurgeryApplicationQuery query, AuthPrincipal caller);

    /** 查询申请详情（含完整状态历史）；不存在或超出数据范围返回 404。 */
    SurgeryApplication get(String applicationId, AuthPrincipal caller);

    /** 手工/测试创建申请：hisApplicationNo 重复返回 409，未知患者 422。 */
    SurgeryApplication submit(SurgeryApplicationCreateRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 模拟 HIS 拉取导入：按 hisApplicationNo 幂等，响应明确 simulated=true。 */
    ImportSurgeryApplicationsResult importFromHis(ImportSurgeryApplicationsRequest request,
            AuthPrincipal caller, AuthRequestContext context);

    /** 取消申请：写状态历史与审计；终态或版本冲突返回 409。 */
    IdResponse cancel(String applicationId, CancelSurgeryApplicationRequest request,
            AuthPrincipal caller, AuthRequestContext context);
}
