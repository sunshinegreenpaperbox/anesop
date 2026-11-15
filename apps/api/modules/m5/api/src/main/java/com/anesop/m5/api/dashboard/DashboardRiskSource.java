package com.anesop.m5.api.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * 风险分层只读端口（M5-M18-02）：由装配层适配器用**单条只读 SELECT**聚合 M2 术前评估单。
 *
 * <p>为什么是聚合桶而不是台次明细：驾驶舱只需要“每个 ASA/风险等级组合有多少台”，
 * 返回逐台明细会把患者维度数据带进看板链路（AGENTS 患者数据红线），也放大了响应体积。
 * 适配器按 (asa_class, risk_level) 分组，未最终化评估归入两个 null 的桶。
 *
 * <p>实现约束（代码即约定）：只执行 SELECT、不写任何模块的表、不开启事务、
 * 不返回患者/申请/台次标识与评分明细。
 */
public interface DashboardRiskSource {

    /**
     * 统计窗口内非取消台次的风险分层。
     *
     * @param from        窗口起点（含，UTC Instant）
     * @param toExclusive 窗口终点（不含）
     * @param scope       数据范围断言（fail-closed 由调用方判定）
     * @return 风险分层桶列表（顺序不保证，impl 自行排序）；无数据返回空列表，不返回 null
     */
    List<DashboardRiskBucket> findRiskBuckets(Instant from, Instant toExclusive,
            DashboardScope scope);
}
