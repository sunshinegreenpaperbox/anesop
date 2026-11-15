package com.anesop.m5.api.dashboard;

/**
 * 风险分层桶（M5-M18-02）：同一 (ASA 分级, 术前评分风险等级) 组合下的台次计数。
 *
 * <p>来源为 M2 术前评估单（M2-M4-01）的**最新一份已最终化**评估，经装配层只读投影聚合；
 * m5-impl 只用它做计数与高危判定，不落库、不回写 M2，也不接触任何患者标识或评分明细。
 *
 * <p>`asaClass`/`riskLevel` 同时为 null 表示该台次**无已最终化术前评估**（未访视/仅草稿），
 * 这类桶只用于计算 `unassessedCaseCount`，不进入 ASA/风险等级分布。
 *
 * @param asaClass   ASA 分级原文（I/II/III/IV/V/VI）；未评估为 null
 * @param riskLevel  术前评估综合风险等级原文（LOW/MODERATE/HIGH）；未评估为 null
 * @param count      该组合的非取消台次数（&gt; 0）
 */
public record DashboardRiskBucket(String asaClass, String riskLevel, int count) {
}
