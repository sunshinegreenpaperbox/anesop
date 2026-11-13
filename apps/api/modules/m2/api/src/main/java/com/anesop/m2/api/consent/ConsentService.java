package com.anesop.m2.api.consent;

import com.anesop.contracts.anesthesia.model.ConsentRecord;
import com.anesop.contracts.anesthesia.model.ConsentRecordList;
import com.anesop.contracts.anesthesia.model.ConsentTemplate;
import com.anesop.contracts.anesthesia.model.ConsentTemplateList;
import com.anesop.contracts.anesthesia.model.CreateConsentRequest;
import com.anesop.contracts.anesthesia.model.DeclineConsentRequest;
import com.anesop.contracts.anesthesia.model.RevokeConsentRequest;
import com.anesop.contracts.anesthesia.model.SignConsentRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 知情同意服务（M2-M5-01，契约 anesthesia.yaml 0.4.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>模板版本不可变：签署记录冻结 templateId + templateVersion，模板新增版本不影响已签记录；
 *       模板可停用（ACTIVE → RETIRED 一次），停用后不可用于新签署，历史记录仍引用旧版本；</li>
 *   <li>状态机 WAITING_SIGN → SIGNED | DECLINED；SIGNED → REVOKED（撤销必须填原因）；
 *       已签署记录不可改签字字段，终态不可再流转；同一申请同时最多一条有效 SIGNED；</li>
 *   <li>CA 不可用（EXT-05）：签署只生成占位 signatureRef + SHA-256 signatureHash + signedAt，
 *       caStatus=SIMULATED；不保存签名原文/图片/证书链；</li>
 *   <li>数据范围沿用 M1 申请可见性（只调 m1-api，不访问 m1 表）：按申请列表为集合语义
 *       （越权 403）；按同意记录查询/变更为单对象语义（越界 404）；创建时申请不可见 422；</li>
 *   <li>审计 CONSENT_CREATE/CONSENT_SIGN/CONSENT_DECLINE/CONSENT_REVOKE，detail 只含标识、
 *       状态、模板版本、CA 状态与签名占位标识，不含签署人姓名、拒绝/撤销原因原文。</li>
 * </ul>
 */
public interface ConsentService {

    /**
     * 查询同意模板与版本（templateCode、version 倒序）。
     *
     * @param templateCode  模板编码过滤；null/空表示全部
     * @param effectiveOnly 是否只返回已生效版本；null 按契约默认 true
     * @param includeRetired 是否包含已停用（RETIRED）版本；null/false 只返回 ACTIVE
     */
    ConsentTemplateList listTemplates(String templateCode, Boolean effectiveOnly,
            Boolean includeRetired, AuthPrincipal caller);

    /**
     * 停用模板版本（ACTIVE → RETIRED，只能一次）。不存在 404；已停用 409。
     * 停用后该版本不可用于新签署，历史同意记录仍引用旧版本。
     */
    ConsentTemplate retireTemplate(String templateId, AuthPrincipal caller,
            AuthRequestContext context);

    /** 按手术申请查询同意记录与申请上下文；申请不可见按集合语义 403。 */
    ConsentRecordList listByApplication(String applicationId, AuthPrincipal caller);

    /** 同意记录详情；不存在或超出数据范围 404。 */
    ConsentRecord get(String consentId, AuthPrincipal caller);

    /**
     * 创建同意记录（WAITING_SIGN）：校验申请可见/状态、模板版本存在且已生效，
     * 同一申请 + 模板版本已有有效记录返回 409；申请不可见/模板不匹配 422。
     */
    ConsentRecord create(CreateConsentRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 签署（WAITING_SIGN → SIGNED）：申请须为活动状态；生成 CA 占位 signatureRef/hash 与
     * signedAt，caStatus=SIMULATED；重复签署、已有未撤销 SIGNED、版本过期、已拒绝/已撤销、
     * 申请已取消/驳回 409。
     */
    ConsentRecord sign(String consentId, SignConsentRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 拒绝（WAITING_SIGN → DECLINED）；非待签署状态 409。 */
    ConsentRecord decline(String consentId, DeclineConsentRequest request, AuthPrincipal caller,
            AuthRequestContext context);

    /** 撤销（SIGNED → REVOKED）；非已签署状态 409，撤销后不可再签。 */
    ConsentRecord revoke(String consentId, RevokeConsentRequest request, AuthPrincipal caller,
            AuthRequestContext context);
}
