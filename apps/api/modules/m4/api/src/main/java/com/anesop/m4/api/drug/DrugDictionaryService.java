package com.anesop.m4.api.drug;

import java.util.Optional;

/**
 * 药品字典判定服务（M4-M15-01，收口 M3 T-4 风险）。
 *
 * <p>麻醉记录用药的 {@code narcotic} 由本服务按字典判定：客户端声明只做一致性校验
 * （不一致返回 400，以字典为准），字典未收录同样 400。解析顺序固定：
 * <ol>
 *   <li>按 {@code drugId} 精确解析；</li>
 *   <li>按名称解析：药品名称 → 通用名 → 别名（trim + 忽略大小写），归一化后必须唯一。</li>
 * </ol>
 *
 * <p><b>歧义必须 fail-closed</b>：名称/通用名/别名交叉命中不同药品时抛
 * {@link DrugDictionaryAmbiguousException}，调用方映射 409/422（{@code DRUG_AMBIGUOUS}）；
 * 禁止按优先级静默取第一条——那会让误报 false 绕过双人核对。
 *
 * <p>实现只读本模块 schema（m4_drug），不写库、不缓存跨请求状态；停用（INACTIVE）药品仍可
 * 解析出历史属性，但是否允许新录入由调用方按 {@link DrugDictionaryEntry#active()} 决定。
 */
public interface DrugDictionaryService {

    /**
     * 按药品标识解析（不存在/非法格式返回 empty）。
     *
     * @param drugId 药品标识（UUID 字符串）
     */
    Optional<DrugDictionaryEntry> findById(String drugId);

    /**
     * 按名称/通用名/别名解析（精确匹配，trim + 忽略大小写；未收录返回 empty）。
     *
     * @param name 药品名称（≤200）
     * @throws DrugDictionaryAmbiguousException 归一化后命中多个不同药品
     */
    Optional<DrugDictionaryEntry> findByName(String name);
}
