package com.anesop.m1.api.analytics;

import com.anesop.contracts.scheduling.model.CancellationReport;
import com.anesop.contracts.scheduling.model.OnTimeReport;
import com.anesop.contracts.scheduling.model.UtilizationReport;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * 排程分析只读服务（M2-M6-01，契约 scheduling.yaml 0.6.0）。
 *
 * <p>口径（D-21：系统出数、绩效规则院方另定）：
 * <ul>
 *   <li>时区固定 {@code Asia/Shanghai}，日期范围为本地日期闭区间，跨度 ≤ 92 天；</li>
 *   <li>利用率 = 非取消台次与区间重叠的计划分钟数 /（可见术间数 × 天数 × 每间每日可用分钟）；</li>
 *   <li>准点率 = 首台准点台次 / 已评估首台台次，阈值默认 ±15 分钟；MVP 以台次
 *       {@code updated_at} 近似实际开台（HIS 未回写，【待确认】）；</li>
 *   <li>取消率 = CANCELLED 台次 / 区间总台次，按最近 STOP 原因码分组。</li>
 * </ul>
 *
 * <p>数据范围 fail-closed：DEPT 只看本科室术间、ROOM 只看绑定术间，未绑定/无可见术间 403；
 * 查询与导出均写审计（{@code ANALYTICS_QUERY}/{@code ANALYTICS_EXPORT}），detail 不含患者标识。
 * 空数据集返回 0 计数与 0 比率，不做除零。
 */
public interface SchedulingAnalyticsService {

    /** 术间利用率（汇总 + 按术间）。 */
    UtilizationReport utilization(AnalyticsQuery query, AuthPrincipal caller, AuthRequestContext context);

    /** 首台准点开台率（汇总 + 按术间）。 */
    OnTimeReport onTime(AnalyticsQuery query, AuthPrincipal caller, AuthRequestContext context);

    /** 取消率（汇总 + 原因码分组 + 按术间）。 */
    CancellationReport cancellations(AnalyticsQuery query, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 准备导出：完成 metric/format/日期/数据范围校验并生成 CSV 行，返回可写出的计划。
     *
     * <p>Web 层在拿到计划后才设置 {@code text/csv} 响应头并写出，保证非法参数仍返回
     * 400 JSON（而不是在已提交的 CSV 响应中抛错）。
     *
     * @return 导出计划（文件名、数据行数与写出动作）；写出内容只含聚合值
     */
    ExportPlan prepareExport(AnalyticsExportQuery query, AuthPrincipal caller,
            AuthRequestContext context);

    /** 导出计划：文件名、数据行数（不含表头）与流式写出动作。 */
    record ExportPlan(String fileName, int dataRows, Consumer<OutputStream> writer) {
    }
}
