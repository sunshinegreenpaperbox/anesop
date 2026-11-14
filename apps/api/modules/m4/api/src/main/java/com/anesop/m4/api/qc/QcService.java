package com.anesop.m4.api.qc;

import com.anesop.contracts.qc.model.AdverseEvent;
import com.anesop.contracts.qc.model.AdverseEventList;
import com.anesop.contracts.qc.model.AdverseRca;
import com.anesop.contracts.qc.model.ChartReview;
import com.anesop.contracts.qc.model.ChartReviewList;
import com.anesop.contracts.qc.model.CloseAdverseRcaRequest;
import com.anesop.contracts.qc.model.CloseRectificationRequest;
import com.anesop.contracts.qc.model.CreateAdverseEventRequest;
import com.anesop.contracts.qc.model.CreateAdverseRcaRequest;
import com.anesop.contracts.qc.model.CreateChartReviewRequest;
import com.anesop.contracts.qc.model.CreateRectificationRequest;
import com.anesop.contracts.qc.model.QcIndicatorList;
import com.anesop.contracts.qc.model.QcIndicatorResultList;
import com.anesop.contracts.qc.model.Rectification;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * 质控指标、不良事件 RCA、病历质量抽查与上报导出服务（M4-M17-01，契约 qc.yaml 0.1.0）。
 *
 * <p>口径（qc-1.0.0，时区 Asia/Shanghai）：
 * <ul>
 *   <li>指标：结构/过程/结果三类，按 {@code code + version} 版本化；结果按周期 + 科室 +
 *       版本留存（重算覆盖同一快照）；分子/分母由服务端从声明的数据源计算，分母为 0 时
 *       {@code value=0} 且不判达标；指标清单与阈值【待确认】（ADR-0022）。</li>
 *   <li>不良事件：主体 append-only + 状态机 {@code REPORTED→RCA→RECTIFYING→CLOSED}，
 *       越级/重复闭环 409；RCA append-only，复核与闭环字段一次性写入且须独立复核人。</li>
 *   <li>病历抽查：评分项白名单四项，总分/满分/等级/缺陷由服务端计算；整改 append-only，
 *       闭环须独立复核人。</li>
 *   <li>导出：UTF-8 BOM + 防公式注入 + 不含患者标识与自由文本原文；上报平台格式【待确认】（EXT 占位）。</li>
 * </ul>
 *
 * <p>数据范围 fail-closed：ALL 全院；SITE/DEPT 按科室；ROOM/SELF/无范围 403；
 * {@code deptId} 越界 403；单对象越界与不存在统一 404。
 * 审计（detail 均不含 PHI）：{@code QC_INDICATOR_QUERY}/{@code QC_INDICATOR_COMPUTE}/
 * {@code ADVERSE_EVENT_CREATE}/{@code ADVERSE_EVENT_QUERY}/{@code ADVERSE_EVENT_STATUS}/
 * {@code RCA_CREATE}/{@code RCA_REVIEW}/{@code RCA_CLOSE}/{@code CHART_REVIEW_CREATE}/
 * {@code CHART_REVIEW_QUERY}/{@code CHART_REVIEW_STATUS}/{@code RECTIFICATION_CREATE}/
 * {@code RECTIFICATION_CLOSE}/{@code QC_EXPORT}。
 */
public interface QcService {

    /** 指标定义列表（生效版本）。 */
    QcIndicatorList indicators(AuthPrincipal caller, AuthRequestContext context);

    /** 周期指标结果：计算、留存快照并返回。 */
    QcIndicatorResultList indicatorResults(QcIndicatorResultQuery query, AuthPrincipal caller,
            AuthRequestContext context);

    /** 登记不良事件（REPORTED）。 */
    AdverseEvent createAdverseEvent(CreateAdverseEventRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 不良事件台账（分页）。 */
    AdverseEventList listAdverseEvents(AdverseEventQuery query, AuthPrincipal caller,
            AuthRequestContext context);

    /** 提交 RCA（事件 REPORTED → RCA）。 */
    AdverseRca createRca(String eventId, CreateAdverseRcaRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** RCA 复核（事件 RCA → RECTIFYING）或整改闭环（事件 RECTIFYING → CLOSED）。 */
    AdverseRca closeRca(String rcaId, CloseAdverseRcaRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 创建病历质量抽查（OPEN）。 */
    ChartReview createChartReview(CreateChartReviewRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 病历抽查列表（分页）。 */
    ChartReviewList listChartReviews(ChartReviewQuery query, AuthPrincipal caller,
            AuthRequestContext context);

    /** 提交整改（抽查 OPEN → RECTIFYING）。 */
    Rectification createRectification(String reviewId, CreateRectificationRequest request,
            AuthPrincipal caller, AuthRequestContext context);

    /** 整改闭环（整改 CLOSED，抽查 → CLOSED）。 */
    Rectification closeRectification(String rectificationId, CloseRectificationRequest request,
            AuthPrincipal caller, AuthRequestContext context);

    /** 准备上报导出：完成参数/数据范围校验并生成 CSV 行，返回可写出的计划。 */
    ExportPlan prepareExport(QcExportQuery query, AuthPrincipal caller,
            AuthRequestContext context);

    /** 导出计划：文件名、数据行数（不含表头）与流式写出动作。 */
    record ExportPlan(String fileName, int dataRows, Consumer<OutputStream> writer) {
    }
}
