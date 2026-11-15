package com.anesop.m4.api.report;

import com.anesop.contracts.report.model.MultiReport;
import com.anesop.contracts.report.model.WorkloadReport;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * 工作量统计、多维报表与导出只读服务（M4-M16-01，契约 report.yaml 0.1.0）。
 *
 * <p>口径（口径版本 report-1.0.0；D-21：系统出数、绩效规则院方另定）：
 * <ul>
 *   <li>时区固定 {@code Asia/Shanghai}，日期范围为本地日期闭区间，跨度 ≤ 366 天；</li>
 *   <li>台次：{@code m1_sched.schedule} 中未删除、状态非 {@code CANCELLED}，按 {@code start_at}
 *       的本地日期归集；跨日台次只计首日；</li>
 *   <li>麻醉时长：麻醉记录单 {@code started_at}..{@code ended_at} 之差向下取整到分钟之和；
 *       无记录单/未结束计 0，并单列 {@code unrecordedCaseCount}；</li>
 *   <li>加权台次：Σ 台次权重，默认 1.0，可按术式编码配置；绩效口径【待确认】；</li>
 *   <li>维度/指标白名单校验（非法 400）；空数据返回 0 与空数组，不做除零。</li>
 * </ul>
 *
 * <p>数据范围 fail-closed：ALL 全院；SITE/DEPT 按申请科室、ROOM 按术间、SELF 按本人创建
 * 麻醉记录单过滤；无可用范围/科室越界 403。查询写审计 {@code REPORT_QUERY}、导出写
 * {@code REPORT_EXPORT}，detail 不含患者标识。
 *
 * <p>0.2.0（M8-M4-02）：锁定后同参数查询/导出默认读快照（{@code source=SNAPSHOT}），
 * 快照数据来自 {@code m4_report.report_snapshot}（append-only），源数据变化不影响已锁定值；
 * 未锁定参数仍为实时聚合（{@code source=LIVE}）。锁定端点见 {@link ReportLockService}。
 */
public interface ReportService {

    /** 工作量统计（汇总 + 维度桶 + 术式分布）。 */
    WorkloadReport workload(ReportQuery query, AuthPrincipal caller, AuthRequestContext context);

    /** 多维报表（组合维度 × 指标，稳定排序 + 分页 + 全量合计）。 */
    MultiReport multi(MultiReportQuery query, AuthPrincipal caller, AuthRequestContext context);

    /**
     * 准备导出：完成 report/format/维度/指标/日期/数据范围校验并生成 CSV 行，
     * 返回可写出的计划。
     *
     * <p>Web 层在拿到计划后才设置 {@code text/csv} 响应头并写出，保证非法参数仍返回
     * 400 JSON（而不是在已提交的 CSV 响应中抛错）。
     *
     * @return 导出计划（文件名、数据行数与写出动作）；内容只含聚合值与维度标签
     */
    ExportPlan prepareExport(ReportExportQuery query, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 导出计划：文件名、数据行数（不含表头）、流式写出动作与数据来源。
     *
     * <p>{@code source} 为 {@code LIVE}（实时聚合）或 {@code SNAPSHOT}（命中报表锁定快照，
     * report.yaml 0.2.0）；Web 层据此设置 {@code X-Report-Source} 响应头。
     */
    record ExportPlan(String fileName, int dataRows, Consumer<OutputStream> writer,
            String source) {
    }
}
