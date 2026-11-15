package com.anesop.m4.api.material;

import com.anesop.contracts.material.model.LowStockThresholdRequest;
import com.anesop.contracts.material.model.Material;
import com.anesop.contracts.material.model.MaterialStockAlertList;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 耗材库存预警与阈值维护服务（契约 material.yaml 0.2.0，M8-M4-02）。
 *
 * <p>预警口径（材料 × 科室 粒度，全部为 SYNTHETIC 可验证规则）：
 * <ul>
 *   <li>{@code LOW_STOCK}：已配置 {@code lowStockThreshold} 且可用量**严格低于**阈值
 *       （可用量 = 阈值时不预警）；</li>
 *   <li>{@code ZERO_STOCK}：该科室可用量合计 = 0（只统计已发生过出入库、存在库存行的材料）；</li>
 *   <li>{@code NEAR_EXPIRY}：存在数量 &gt; 0 且效期 ≤ 今天 + **90 天**的批次
 *       （含已过期，{@code daysToExpiry} &lt; 0）；</li>
 *   <li>{@code suggestedQuantity} = max(0, 阈值 − 可用量)；阈值缺失时为 null
 *       （不臆造补货量，阈值来源【待确认】）；临期不产生补货量。</li>
 * </ul>
 *
 * <p>数据范围 fail-closed：ALL/SITE/DEPT；SELF/ROOM/无科室维度 403；科室越界 403。
 * 阈值维护走 {@link #updateThreshold}（乐观锁；审计 {@code MATERIAL_THRESHOLD_UPDATE}），
 * 与耗材主数据更新分离，避免互相覆盖。阈值粒度（全院 vs 按科室）【待确认】。
 */
public interface MaterialAlertService {

    /**
     * 库存预警列表（按零库存优先、材料名称、科室排序）。
     *
     * @param departmentId 科室过滤（可空；必须在数据范围内）
     * @param alertType    预警类型过滤（LOW_STOCK/ZERO_STOCK/NEAR_EXPIRY，可空=全部）
     * @param limit        返回条数（1–200）
     */
    MaterialStockAlertList stockAlerts(String departmentId, String alertType, int limit,
            AuthPrincipal caller);

    /**
     * 维护库存预警阈值（乐观锁；threshold 为 null 表示清除阈值 = 不按阈值预警）。
     *
     * @return 更新后的耗材主数据（含 threshold 与递增后的 version）
     */
    Material updateThreshold(String materialId, LowStockThresholdRequest request,
            AuthPrincipal caller, AuthRequestContext context);
}
