package com.anesop.m2.api.record;

import com.anesop.contracts.anesthesia.model.CreateRevisionRequest;
import com.anesop.contracts.anesthesia.model.LockRecordRequest;
import com.anesop.contracts.anesthesia.model.LockRecordResult;
import com.anesop.contracts.anesthesia.model.RecordEffectiveView;
import com.anesop.contracts.anesthesia.model.RecordLockStatus;
import com.anesop.contracts.anesthesia.model.RecordRevision;
import com.anesop.contracts.anesthesia.model.RecordRevisionList;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 麻醉记录单锁单与修订服务（M3-M2-03，契约 anesthesia.yaml 0.8.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>锁单前置（服务端统一评估，前端只展示）：三方核查完成
 *       （{@code ThreeWayCheckService.isThreeWayCheckCompleted}）、申请存在当前有效 SIGNED
 *       同意、记录开始/结束时间完整（ANESTHESIA_START/ANESTHESIA_END）、至少 1 个时间轴事件、
 *       尚未锁单；未满足返回 409 并逐项列出（{@link com.anesop.m2.api.error
 *       .M2LockPreconditionException}），version 过期或重复锁单 409；</li>
 *   <li>锁单后记录内容不可改（服务层 409 + 数据库触发器 55000 双保险）；事件/用药/出入量
 *       追加一律 409，修正只能新增不可变修订版本；</li>
 *   <li>修订：必须已锁单、原因码白名单、原因/变更摘要必填；在记录单行锁内递增
 *       revision_no（从 1 起），写入全量快照（含全部子记录与锁单/上一版签名摘要）与
 *       CA 占位签名；原记录与历史版本不可改；</li>
 *   <li>CA 不可用（EXT-05）：锁单/修订只生成 signatureRef（SIM-CA-&lt;id&gt;）与
 *       signatureHash（规范化记录 JSON 的 SHA-256，排除患者标识/审计时间/签名字段），
 *       caStatus=SIMULATED；不模拟真实 CA 有效签名；</li>
 *   <li>数据范围沿台次/申请可见性：单记录越界 404；审计 RECORD_LOCK/RECORD_REVISE/
 *       RECORD_SIGN（detail 无 PHI，signatureRef 可留）；SYS_ADMIN 走 break-glass 时在 detail
 *       标注 administratorOperation=true（是否仅麻醉医师待临床确认，Q 台账）。</li>
 *   <li>记录详情/打印读取 {@link #effective}：锁单原始值 + 最新修订更正字段的读时投影，
 *       原记录行与历史修订始终不可变。</li>
 * </ul>
 */
public interface RecordLockService {

    /** 锁单状态与前置条件清单（只读，不改变记录状态）；记录不存在/越界 404。 */
    RecordLockStatus lockStatus(String recordId, AuthPrincipal caller);

    /**
     * 锁单：必须回传当前 version 并确认前置清单（confirmChecklist=true）。
     * 前置条件未满足 409（details 逐项列出）；重复锁单/version 过期 409；未确认 400。
     */
    LockRecordResult lock(String recordId, LockRecordRequest request, AuthPrincipal caller,
            AuthRequestContext context, boolean administrator);

    /** 兼容入口：非管理员调用（管理员标记由 Web 适配层判定后调用 5 参方法）。 */
    default LockRecordResult lock(String recordId, LockRecordRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        return lock(recordId, request, caller, context, false);
    }

    /**
     * 当前有效记录值（只读投影）：original=锁单原始行字段，effective=叠加最新修订更正后的值。
     * 记录不存在/越界 404；未锁单或未修订时 effective 与 original 相同。
     */
    RecordEffectiveView effective(String recordId, AuthPrincipal caller);

    /** 修订版本列表（revisionNo 升序）；未锁单或无修订返回空列表；越界 404。 */
    RecordRevisionList listRevisions(String recordId, AuthPrincipal caller);

    /** 修订版本详情（快照 + 签名 + 相对上一版差异）；不存在/越界 404。 */
    RecordRevision getRevision(String recordId, String revisionNo, AuthPrincipal caller);

    /**
     * 创建修订版本（锁单后修正唯一入口）：未锁单 409、原因码非法/摘要缺失 400、
     * version 过期或并发版本冲突 409；原记录与历史版本不可改。
     */
    RecordRevision createRevision(String recordId, CreateRevisionRequest request,
            AuthPrincipal caller, AuthRequestContext context, boolean administrator);

    /** 兼容入口：非管理员调用。 */
    default RecordRevision createRevision(String recordId, CreateRevisionRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        return createRevision(recordId, request, caller, context, false);
    }
}
