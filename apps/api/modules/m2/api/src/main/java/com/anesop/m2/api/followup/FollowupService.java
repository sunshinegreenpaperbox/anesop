package com.anesop.m2.api.followup;

import com.anesop.contracts.anesthesia.model.CreatePostopFollowupRequest;
import com.anesop.contracts.anesthesia.model.PainManagement;
import com.anesop.contracts.anesthesia.model.PainManagementList;
import com.anesop.contracts.anesthesia.model.PostopFollowup;
import com.anesop.contracts.anesthesia.model.PostopFollowupList;
import com.anesop.contracts.anesthesia.model.RecordPainManagementRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 术后随访与镇痛服务（M3-M11-01，契约 anesthesia.yaml 0.10.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>随访只能录入到**已结束**的麻醉记录单（{@code endedAt} 非空），未结束 409；
 *       随访时间 ≥ 记录结束时间（400）、不得晚于服务端当前时间 + 容差（400）；</li>
 *   <li>同一记录单同一随访类型（H24/H48）允许多次随访，但随访时间不得早于上一条
 *       （400，时间序列只增；同刻允许）；NRS 0–10、PONV 0–3、并发症白名单与文本长度
 *       由服务层校验（越界 400），数据库 CHECK 为第二道防线；</li>
 *   <li>随访与镇痛 **append-only**：数据库触发器拒绝 UPDATE/DELETE/TRUNCATE（55000），
 *       更正只能新增记录；镇痛参数调整同样以新增镇痛记录表达；</li>
 *   <li>镇痛参数只做物理校验（bolus &gt; 0、background ≥ 0、lockout &gt; 0 分钟），
 *       **PCA 默认值与剂量单位未内置【待确认】**，系统不推荐处方、不替代医师判断（H-06）；</li>
 *   <li>数据范围复用记录单/台次可见性（只调 m1-api，不访问 m1 表）：按记录单列表为集合语义
 *       （越权 403），单对象操作越界 404，录入时记录单不可见 422；</li>
 *   <li>并发症说明、患者反馈、调整说明与药物名称在服务端脱敏（遮蔽手机号/身份证号
 *       形似串）后落库；
 *       审计 FOLLOWUP_CREATE/PAIN_MANAGE 与业务同事务，detail 只含标识、评分、并发症码、
 *       镇痛参数、脱敏后的药物名称与计数，**不含任何自由文本原文与患者 PHI**。</li>
 * </ul>
 */
public interface FollowupService {

    /** 按麻醉记录单查询随访（assessedAt 倒序）；记录单不可见 403（集合语义）。 */
    PostopFollowupList listByRecord(String recordId, AuthPrincipal caller);

    /**
     * 录入随访（201，append-only）：记录单不存在/不可见 422、未结束 409、时间窗 400、
     * 评分越界/并发症非法/文本超长 400、同类型时间倒序 400。
     */
    PostopFollowup create(CreatePostopFollowupRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 随访详情；不存在或越界 404。 */
    PostopFollowup get(String followupId, AuthPrincipal caller);

    /** 该随访的镇痛记录（录入时间升序）；随访不存在或越界 404。 */
    PainManagementList listPainManagement(String followupId, AuthPrincipal caller);

    /**
     * 追加镇痛记录（201，append-only）：随访不存在或越界 404；方式/效果缺失 400、
     * 药物缺失/超长 400、bolus ≤ 0 / background &lt; 0 / lockout ≤ 0 400、
     * 调整记录时间或长度非法 400。
     */
    PainManagement recordPainManagement(String followupId, RecordPainManagementRequest request,
            AuthPrincipal caller, AuthRequestContext context);
}
