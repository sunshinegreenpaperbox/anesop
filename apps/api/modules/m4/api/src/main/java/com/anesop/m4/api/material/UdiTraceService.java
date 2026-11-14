package com.anesop.m4.api.material;

import com.anesop.contracts.material.model.RegisterUdiInstanceRequest;
import com.anesop.contracts.material.model.UdiInstance;
import com.anesop.contracts.material.model.UdiTrace;
import com.anesop.contracts.material.model.UseUdiInstanceRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * UDI 一物一码追溯服务（契约 material.yaml 0.1.0，M4-M14-01）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>登记 = 该件入库：UDI 实例（IN_STOCK）+ 批次库存 +1 + IN 流水，三者同事务；
 *       UDI-DI + UDI-PI 组合唯一（重复 409）；UDI-DI 必须与耗材主数据一致（422）；</li>
 *   <li>使用：仅 IN_STOCK 可 USED（重复使用 409）；绑定 recordId 与台次，记录不存在或
 *       对调用者不可见 422（不泄露存在性）；scheduleId 与记录所属台次不一致 422；
 *       使用同时写 OUT 流水并扣减批次库存（库存不足 409）；</li>
 *   <li>追溯：material → UDI → record 全链路；不存在或超出科室数据范围统一 404；</li>
 *   <li>数据范围 ALL/SITE/DEPT；SELF/ROOM fail-closed 403；</li>
 *   <li>UDI 组合长度 udiDi + '^' + udiPi ≤160（越界 400）；使用/退回扣减前校验实例效期与
 *       批次库存行一致（不一致 409）；</li>
 *   <li>审计 UDI_REGISTER/UDI_USE：detail 只含 UDI-DI、状态与记录/台次标识，不含 UDI 原文
 *       全串、患者信息与临床自由文本。</li>
 * </ul>
 */
public interface UdiTraceService {

    /** 登记 UDI 实例（逐件入库）；重复 409，UDI-DI 不符/效期非法 422。 */
    UdiInstance register(RegisterUdiInstanceRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 查询 material → UDI → record 追溯链；不存在或不可见 404。 */
    UdiTrace trace(String udi, AuthPrincipal caller);

    /** 使用 UDI 实例（绑定记录/台次）；重复使用 409，记录不存在/不可见 422。 */
    UdiInstance use(String udi, UseUdiInstanceRequest request, AuthPrincipal caller,
            AuthRequestContext context);
}
