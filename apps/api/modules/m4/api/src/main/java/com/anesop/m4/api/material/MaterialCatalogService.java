package com.anesop.m4.api.material;

import com.anesop.contracts.material.model.CreateMaterialRequest;
import com.anesop.contracts.material.model.Material;
import com.anesop.contracts.material.model.MaterialList;
import com.anesop.contracts.material.model.MaterialStatusRequest;
import com.anesop.contracts.material.model.UpdateMaterialRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 耗材目录服务（契约 material.yaml 0.1.0，M4-M14-01）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>编码、名称（trim + 忽略大小写）院内唯一；UDI-DI 非空时唯一（扫码反查主数据）；
 *       冲突 409；</li>
 *   <li>编码创建后不可改；更新必须回传 version，过期 409（乐观锁，数据库守卫兜底）；</li>
 *   <li>目录全院可读（不按科室数据范围过滤）；维护需要 {@code m4:material:write}；</li>
 *   <li>highValue 由 false 改 true 时，若该耗材仍有数量 &gt; 0 的批次库存，409
 *       （先清零或改走逐件 UDI 登记，避免出现无法追溯的高值库存）；</li>
 *   <li>存在 UDI 实例或数量 &gt; 0 的批次库存时，修改/清空 udiDi → 409；</li>
 *   <li>启停走 {@link #changeStatus}（ACTIVE/INACTIVE，幂等）；停用后服务层拒绝新出入库/UDI 登记；</li>
 *   <li>审计 MATERIAL_CREATE/MATERIAL_UPDATE：detail 只含标识、编码、名称与
 *       highValue/expiryManaged/status 变更摘要，不含备注原文与患者信息；失败路径写
 *       result=0 审计。</li>
 * </ul>
 */
public interface MaterialCatalogService {

    /** 分页查询（keyword 匹配编码/名称/UDI-DI；status/highValue 可空）。 */
    MaterialList list(String keyword, String status, Boolean highValue, int page, int size);

    /** 扫码/录入联想（只返回启用耗材；q 为空返回空列表）。 */
    MaterialList search(String q, int limit);

    /** 耗材详情；不存在 404。 */
    Material get(String materialId);

    /** 创建耗材；编码/名称/UDI-DI 冲突 409。 */
    Material create(CreateMaterialRequest request, AuthPrincipal caller, AuthRequestContext context);

    /** 更新耗材（编码不可改）；版本过期/名称/UDI-DI 冲突/高值切换存在库存/UDI-DI 变更 409。 */
    Material update(String materialId, UpdateMaterialRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 启用/停用耗材（ACTIVE/INACTIVE，需要 {@code m4:material:write}）。
     *
     * <p>状态未变化时幂等返回当前主数据（version 不变）；真正变更时 version +1。
     * 停用后服务层拒绝新的出入库与 UDI 登记，历史库存/追溯只读保留。
     */
    Material changeStatus(String materialId, MaterialStatusRequest request, AuthPrincipal caller,
            AuthRequestContext context);
}
