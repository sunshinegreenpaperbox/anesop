package com.anesop.m4.api.report;

import java.util.List;

/**
 * 报表统计单元的只读投影端口（M4-M16-01 依赖倒置）。
 *
 * <p>工作量与多维报表的统计单元来自 M1 排程台次与 M2 麻醉记录单，但 m4-impl 不得访问
 * 其他模块的数据表或 Mapper（AGENTS「模块边界与依赖禁令」），也不能反向依赖 m1-api/m2-api
 * 形成模块环。因此本接口声明在 m4-api，由**装配层**（api-bootstrap）以只读投影实现，
 * m4-impl 只依赖本接口（与 M4-M14-01 的 {@code RecordVisibilityPort} 同一思路）。
 *
 * <p>实现约定：
 * <ul>
 *   <li><b>只读</b>：只允许 SELECT，不得 INSERT/UPDATE/DELETE，不写其他模块的表；</li>
 *   <li><b>不解释越权语义</b>：按 {@link ReportCaseFilter} 原样拼装条件，范围解析与 fail-closed
 *       判定由服务层负责；</li>
 *   <li><b>不做时区换算</b>：按传入的 Instant 区间过滤 {@code start_at}，本地日期归属由服务层
 *       用 Asia/Shanghai 判定；</li>
 *   <li><b>不返回 PHI</b>：只回传聚合所需维度与时间字段（见 {@link ReportCaseFact}）。</li>
 * </ul>
 */
public interface ReportCaseSource {

    /**
     * 查询统计区间内的台次投影。
     *
     * @param filter 已解析的时间区间与数据范围条件
     * @return 台次投影（一条台次一行；无数据返回空列表）
     */
    List<ReportCaseFact> findCases(ReportCaseFilter filter);
}
