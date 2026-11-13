package com.anesop.m2.api.preop;

import com.anesop.contracts.anesthesia.model.FinalizePreopAssessmentRequest;
import com.anesop.contracts.anesthesia.model.PreopAssessment;
import com.anesop.contracts.anesthesia.model.PreopAssessmentCreateRequest;
import com.anesop.contracts.anesthesia.model.PreopAssessmentList;
import com.anesop.contracts.anesthesia.model.PreopAssessmentUpdateRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 术前访视与评估服务（M2-M4-01，契约 anesthesia.yaml 0.3.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>评分由服务端计算：请求只提交量表条目（{@code scoreInput}），RCRI/Apfel/STOP-BANG/
 *       DASI 分数与风险等级由 {@code PreopScoreCalculator} 计算并落库，客户端分数不参与；</li>
 *   <li>数据范围沿用 M1 申请/患者可见性（同步调用 {@code m1-api}，不访问 m1 表）：
 *       按申请查询为集合语义，申请不可见 403；按访视单查询/修改为单对象语义，越界 404；</li>
 *   <li>状态机 DRAFT → FINALIZED：最终化后不可修改（409），只能为同一申请新建版本，
 *       原单保留评分快照与审计；</li>
 *   <li>写操作审计 PREOP_CREATE/PREOP_UPDATE/PREOP_FINALIZE，detail 只含标识、状态、
 *       评分与版本，不含姓名、病史原文、证件号/住院号/手机号。</li>
 * </ul>
 */
public interface PreopAssessmentService {

    /**
     * 按手术申请查询访视单列表（创建时间倒序）与申请上下文。
     *
     * <p>申请不存在或超出调用方数据范围时按集合查询语义返回 403（不返回空列表、
     * 不泄露存在性）；病人快照由申请解析，不使用客户端提交值。
     */
    PreopAssessmentList listByApplication(String applicationId, AuthPrincipal caller);

    /** 访视单详情；不存在或超出数据范围返回 404。 */
    PreopAssessment get(String assessmentId, AuthPrincipal caller);

    /**
     * 创建草稿：校验申请可见/状态/患者一致性，服务端计算评分并写 PREOP_CREATE 审计。
     * 同一申请已有草稿返回 409。
     */
    PreopAssessment create(PreopAssessmentCreateRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 全量保存草稿并重新计算评分；乐观锁版本不一致或已最终化返回 409，
     * 成功写 PREOP_UPDATE 审计。
     */
    PreopAssessment update(String assessmentId, PreopAssessmentUpdateRequest request,
            AuthPrincipal caller, AuthRequestContext context);

    /**
     * 最终化：DRAFT → FINALIZED，记录 finalizedAt/finalizedBy 并写 PREOP_FINALIZE 审计；
     * 已最终化或版本过期返回 409。
     */
    PreopAssessment finalizeAssessment(String assessmentId, FinalizePreopAssessmentRequest request,
            AuthPrincipal caller, AuthRequestContext context);
}
