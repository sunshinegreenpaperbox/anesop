package com.anesop.m5.api.dashboard;

import java.time.Instant;

/**
 * 看板台次投影（只读，无患者标识）。
 *
 * <p>来源为 M1 排程台次与 M2 麻醉记录单的**单条只读 SELECT** 投影（装配层适配器实现）；
 * m5-impl 只用它做计数、状态分布、准点/利用率与时长聚合，不落库、不回写任何模块。
 *
 * @param scheduleId      台次标识
 * @param roomId          术间标识（用于按术间聚合与数据范围过滤）
 * @param deptId          申请科室标识
 * @param siteId          申请院区标识
 * @param status          台次状态（PLANNED/PUBLISHED/IN_PROGRESS/COMPLETED/CANCELLED/DELAYED）
 * @param startAt         计划开始时间
 * @param endAt           计划结束时间
 * @param updatedAt       台次最后更新时间（准点回退近似实际开始时间；数据延迟依据）
 * @param recordStartedAt 麻醉记录单开始时间；无记录单为 null
 * @param recordEndedAt   麻醉记录单结束时间；无记录单/未结束为 null
 * @param recordCreatedBy 麻醉记录单创建人用户标识；SELF 范围与去重依据
 */
public record DashboardCaseFact(
        String scheduleId,
        String roomId,
        String deptId,
        String siteId,
        String status,
        Instant startAt,
        Instant endAt,
        Instant updatedAt,
        Instant recordStartedAt,
        Instant recordEndedAt,
        String recordCreatedBy) {
}
