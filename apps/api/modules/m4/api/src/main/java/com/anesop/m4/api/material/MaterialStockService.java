package com.anesop.m4.api.material;

import com.anesop.contracts.material.model.CreateStockMovementRequest;
import com.anesop.contracts.material.model.DepartmentList;
import com.anesop.contracts.material.model.MaterialStockLevelList;
import com.anesop.contracts.material.model.StockMovement;
import com.anesop.contracts.material.model.StockMovementList;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.time.OffsetDateTime;

/**
 * 耗材出入库与批次库存服务（契约 material.yaml 0.1.0，M4-M14-01）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>数据范围 ALL/SITE/DEPT；SELF/ROOM/无范围 fail-closed 403；写入科室必须是启用
 *       {@code org_type=DEPT} 组织（422）且在范围内（403）；</li>
 *   <li>流水 append-only：只 INSERT，不修改/删除（数据库触发器兜底 55000）；
 *       更正走反向 ADJUST 并填写原因；</li>
 *   <li>数量不足（OUT/RETURN/负向 ADJUST 超过批次库存）409，details 给出 available/requested；
 *       扣减前按 材料 + 批次 + 科室 定位库存行，请求效期与库存行不一致 409；过期批次 OUT 422；
 *       效期已过/效期管理耗材缺效期 422；数量最多 2 位小数（越界 400）；</li>
 *   <li>高值耗材（highValue=true）禁止裸数量 IN/OUT/ADJUST（422）：入库走
 *       {@link UdiTraceService#register}，使用走 {@link UdiTraceService#use}；RETURN 带 udi
 *       时把该实例标记 RETURNED，并以实例批次/效期为扣减依据（请求不一致 422）；</li>
 *   <li>审计 STOCK_MOVE：detail 只含标识、类型、数量、科室与批次摘要，不含患者信息；
 *       失败路径（400/409/422）写 result=0 审计。</li>
 * </ul>
 */
public interface MaterialStockService {

    /** 调用者可访问的启用科室（出入库/UDI 登记显式选择；SELF/ROOM 403）。 */
    DepartmentList listDepartments(AuthPrincipal caller);

    /** 批次库存列表（ALL 不过滤；SELF/ROOM 403）。 */
    MaterialStockLevelList listStock(String materialId, String departmentId, int limit,
            AuthPrincipal caller);

    /** 出入库流水列表（倒序；时间区间 from/to 可空）。 */
    StockMovementList listMovements(String materialId, String departmentId, OffsetDateTime from,
            OffsetDateTime to, int limit, AuthPrincipal caller);

    /** 登记出入库流水并原子更新批次库存。 */
    StockMovement createMovement(CreateStockMovementRequest request, AuthPrincipal caller,
            AuthRequestContext context);
}
