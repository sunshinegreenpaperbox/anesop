package com.anesop.m2.api.pacu;

import com.anesop.contracts.anesthesia.model.CreatePacuRecordRequest;
import com.anesop.contracts.anesthesia.model.PacuAssessment;
import com.anesop.contracts.anesthesia.model.PacuAssessmentList;
import com.anesop.contracts.anesthesia.model.PacuDischarge;
import com.anesop.contracts.anesthesia.model.PacuDischargeRequest;
import com.anesop.contracts.anesthesia.model.PacuRecord;
import com.anesop.contracts.anesthesia.model.PacuRecordList;
import com.anesop.contracts.anesthesia.model.RecordPacuAssessmentRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * PACU 复苏记录、Aldrete 评估与转出服务（M3-M10-01，契约 anesthesia.yaml 0.9.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>入室：必须关联已开始（ONGOING/LOCKED/REVISED 且 startedAt 非空）的麻醉记录单；
 *       一条记录单同时最多一条未转出记录（重复入室 409）；入室时间 ≥ 记录开始时间，
 *       且 ≤ 服务端当前时间 + 容差（400）；患者标识由记录单解析，不信任客户端；</li>
 *   <li>Aldrete 评估：五项 activity/respiration/circulation/consciousness/oxygenation 各
 *       0–2（缺项/越界 400），总分由**服务端**计算（0–10）；append-only 时间序列（数据库
 *       触发器拒绝 UPDATE/DELETE/TRUNCATE，55000），更正只能新增评估；已转出后再评估 409；</li>
 *   <li>转出：去向 + SBAR 交接（四段必填）+ 显式确认转出条件（criteriaConfirmed，
 *       缺省/false 400）；服务端校验存在最近一次评估、五项齐全、总分 ≥ 配置阈值
 *       （{@code anesop.m2.pacu.aldrete-threshold}，默认 9，**院方阈值【待确认】**），
 *       未满足 409 并逐项列出条件码；转出后状态 DISCHARGED、不可重复转出/再评估（409），
 *       转出留痕 append-only；</li>
 *   <li>数据范围复用记录单/台次可见性（只调 m1-api，不访问 m1 表）：按记录单列表为集合语义
 *       （越权 403），单对象操作越界 404，入室时记录单不可见 422（请求体参数）；</li>
 *   <li>审计 PACU_ADMIT/PACU_ASSESS/PACU_DISCHARGE 与业务同事务；detail 只含标识、状态、
 *       Aldrete 各项分值与总分、阈值、去向与交接段落数，**不含 SBAR 交接原文、评估备注原文、
 *       患者姓名/证件号/住院号/手机号**；冲突失败以独立事务写 result=0 审计。</li>
 * </ul>
 */
public interface PacuService {

    /** 按麻醉记录单查询复苏记录（创建时间倒序）；记录单不可见 403（集合语义）。 */
    PacuRecordList listByRecord(String recordId, AuthPrincipal caller);

    /**
     * 入室（创建复苏记录，201）：记录单未开始 409、不可见 422、重复未转出记录 409、
     * 入室时间早于记录开始或晚于当前时间+容差 400。
     */
    PacuRecord admit(CreatePacuRecordRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 复苏记录详情；不存在或越界 404。 */
    PacuRecord get(String pacuId, AuthPrincipal caller);

    /** Aldrete 评估时间序列（assessedAt 升序）；不存在或越界 404。 */
    PacuAssessmentList listAssessments(String pacuId, AuthPrincipal caller);

    /**
     * 记录评估（201，append-only）：单项越界/缺项 400、评估时间早于入室或晚于当前时间+容差
     * 400、已转出 409；总分由服务端计算并推进状态（READY/MONITORING）。
     */
    PacuAssessment recordAssessment(String pacuId, RecordPacuAssessmentRequest request,
            AuthPrincipal caller, AuthRequestContext context);

    /**
     * 转出（201）：criteriaConfirmed 未确认 400、SBAR/去向缺失 400、Aldrete 未评估/未达阈值
     * 409（details 逐项列出条件码）、已转出 409。
     */
    PacuDischarge discharge(String pacuId, PacuDischargeRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 转出记录；未转出或越界 404。 */
    PacuDischarge getDischarge(String pacuId, AuthPrincipal caller);
}
