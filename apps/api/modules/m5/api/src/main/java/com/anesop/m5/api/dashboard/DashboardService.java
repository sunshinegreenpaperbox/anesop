package com.anesop.m5.api.dashboard;

import com.anesop.contracts.dashboard.model.DashboardKpi;
import com.anesop.contracts.dashboard.model.RealtimeDashboard;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 实时态势与 KPI 看板服务（M5-M18-01，契约 dashboard.yaml 0.1.0，口径 dashboard-1.0.0）。
 *
 * <p>只读：驾驶舱不产生业务数据，所有数值来自 M1/M2 既有数据的一次只读投影 + 应用内聚合。
 * 数据范围由服务层按 ALL/SITE/DEPT/ROOM/SELF 解析（fail-closed），无可用范围 403。
 * 查询写审计 {@code DASHBOARD_VIEW}（按用户采样，避免 10s 轮询刷屏）。
 */
public interface DashboardService {

    /**
     * 实时态势快照（术间/台次/告警/设备 + 数据延迟）。
     *
     * @param caller  当前调用者（用户标识用于 SELF 范围与审计；可为 null 视为无范围）
     * @param context 请求上下文（审计用）
     * @return 契约 {@code RealtimeDashboard}
     */
    RealtimeDashboard realtime(AuthPrincipal caller, AuthRequestContext context);

    /**
     * 当日运营 KPI。
     *
     * @param date    统计自然日（YYYY-MM-DD，Asia/Shanghai）；缺省为服务端当日
     * @param caller  当前调用者
     * @param context 请求上下文（审计用）
     * @return 契约 {@code DashboardKpi}
     */
    DashboardKpi kpi(String date, AuthPrincipal caller, AuthRequestContext context);
}
