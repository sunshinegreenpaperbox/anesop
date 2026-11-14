package com.anesop.m5.api.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * 看板台次只读端口：由装配层适配器用**单条只读 SELECT**投影 M1 排程台次与 M2 麻醉记录单。
 *
 * <p>为什么是端口而不是直接依赖 m1-api/m2-api：AGENTS「模块边界与依赖禁令」禁止跨模块访问
 * 对方 Mapper/数据表，也禁止模块间循环依赖；m5 只消费“台次/记录单的只读事实”，
 * 由 api-bootstrap 装配层统一投影（与 M4 {@code ReportCaseSource} 同构，ADR-0021 第 8 条）。
 *
 * <p>实现约束（代码即约定）：只执行 SELECT、不写任何模块的表、不开启事务、不复制对方实体、
 * 不返回患者标识。
 */
public interface DashboardCaseSource {

    /**
     * 查询窗口内的台次投影。
     *
     * @param from        窗口起点（含，UTC Instant；本地日归属由调用方换算）
     * @param toExclusive 窗口终点（不含）
     * @param scope       数据范围断言（fail-closed 由调用方判定）
     * @return 台次投影列表；无数据返回空列表，不返回 null
     */
    List<DashboardCaseFact> findCases(Instant from, Instant toExclusive, DashboardScope scope);
}
