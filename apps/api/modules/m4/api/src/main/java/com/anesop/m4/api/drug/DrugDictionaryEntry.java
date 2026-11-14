package com.anesop.m4.api.drug;

/**
 * 药品字典条目（跨模块只读视图）：麻精判定的唯一权威来源（M3 T-4 收口）。
 *
 * <p>只暴露判定所需的稳定字段，不含价格、供应商等主数据细节；不实现 {@code equals}
 * 语义依赖，调用方只按字段读取。
 *
 * @param drugId   药品标识
 * @param code     院内药品编码
 * @param name     药品名称（字典名）
 * @param narcotic 是否麻精药品
 * @param highRisk 是否高危药品
 * @param active   是否启用（ACTIVE）
 */
public record DrugDictionaryEntry(String drugId, String code, String name, boolean narcotic,
        boolean highRisk, boolean active) {
}
