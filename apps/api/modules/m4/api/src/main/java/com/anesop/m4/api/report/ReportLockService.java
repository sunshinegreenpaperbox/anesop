package com.anesop.m4.api.report;

import com.anesop.contracts.report.model.ReportLock;
import com.anesop.contracts.report.model.ReportLockList;
import com.anesop.contracts.report.model.ReportLockRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 报表数据锁定与快照服务（契约 report.yaml 0.2.0，M8-M4-02）。
 *
 * <p>语义：
 * <ul>
 *   <li>锁定 = 把「报表类型 + 期间 + 归一化参数（含数据范围指纹）」对应的聚合结果写入
 *       append-only 快照 `m4_report.report_snapshot`，**之后同参数查询/导出默认读快照**
 *       （{@code source=SNAPSHOT}），源数据变化不影响已锁定值；</li>
 *   <li>同一 (report, period, params_hash) 重新锁定 409（快照不可覆盖；解锁/重出数流程
 *       【待确认】，本期不提供解锁端点）；</li>
 *   <li>期间支持 {@code YYYY-MM}（整月）或 {@code YYYY-MM-DD~YYYY-MM-DD}（闭区间），
 *       也可用 from+to；跨度（含首尾）≤ 366 天；</li>
 *   <li>锁定写审计 {@code REPORT_LOCK}（detail: report/period/paramsHash/payloadHash/
 *       rowCount/dataScope），不含患者标识；</li>
 *   <li>权限 {@code m4:report:lock}（V28 授予 SYS_ADMIN/SYS_OR_MANAGER/SYS_QC_MANAGER）；
 *       列表/详情复用 {@code m4:report:read}，只返回元数据（不含 payload）。</li>
 * </ul>
 */
public interface ReportLockService {

    /** 生成快照并锁定；同参数已锁定 409。 */
    ReportLock lock(ReportLockRequest request, AuthPrincipal caller, AuthRequestContext context);

    /** 锁定列表（按锁定时间倒序；report 可空；limit 1–200）。 */
    ReportLockList list(String report, int limit, AuthPrincipal caller);

    /** 锁定详情；不存在 404。 */
    ReportLock get(String lockId, AuthPrincipal caller);
}
