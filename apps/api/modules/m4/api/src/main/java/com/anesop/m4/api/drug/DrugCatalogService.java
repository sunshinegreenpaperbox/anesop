package com.anesop.m4.api.drug;

import com.anesop.contracts.drug.model.CreateDrugRequest;
import com.anesop.contracts.drug.model.Drug;
import com.anesop.contracts.drug.model.DrugList;
import com.anesop.contracts.drug.model.UpdateDrugRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 药品目录服务（契约 drug.yaml 0.1.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>编码、名称、通用名（非空）归一化（trim + 忽略大小写）后院内唯一：重复 409；</li>
 *   <li>别名全局唯一，且名称/通用名不得与其他药品的别名冲突、别名不得与其他药品的
 *       名称/通用名冲突（否则按名称解析麻精属性会歧义）——冲突 409；</li>
 *   <li>编码创建后不可改；更新必须回传 version，过期 409（乐观锁，数据库守卫兜底）；</li>
 *   <li>目录全院可读（不按数据范围过滤）；维护需要 {@code m4:drug:write}；</li>
 *   <li>审计 DRUG_CREATE/DRUG_UPDATE：detail 只含 drugId/code/名称与 narcotic/highRisk
 *       变更摘要，不含备注原文与患者信息；失败路径（400/409/422）写 result=0 审计。</li>
 * </ul>
 */
public interface DrugCatalogService {

    /** 分页查询（keyword 匹配编码/名称/通用名/别名；status 可空）。 */
    DrugList list(String keyword, String status, int page, int size);

    /** 用药录入联想（只返回启用药品；q 为空返回空列表）。 */
    DrugList search(String q, int limit);

    /** 药品详情；不存在 404。 */
    Drug get(String drugId);

    /** 创建药品；编码/名称/通用名/别名冲突 409。 */
    Drug create(CreateDrugRequest request, AuthPrincipal caller, AuthRequestContext context);

    /** 更新药品（编码不可改）；版本过期 409，名称/通用名/别名冲突 409。 */
    Drug update(String drugId, UpdateDrugRequest request, AuthPrincipal caller,
            AuthRequestContext context);
}
