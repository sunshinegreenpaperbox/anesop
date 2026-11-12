package com.anesop.m1.api.patient;

import com.anesop.contracts.patient.model.Patient;
import com.anesop.contracts.patient.model.PatientPage;
import com.anesop.m0.api.security.AuthPrincipal;

/**
 * 患者摘要查询（M2-M1-01）：返回排程/申请所需的最小必要字段。
 *
 * <p>响应**不含**证件号与手机号（含脱敏值），住院号只返回脱敏值；列表/详情均按调用者
 * 数据范围过滤，集合越权 403、单对象越界/不存在 404（不泄露存在性）。
 */
public interface PatientQueryService {

    /** 分页查询患者摘要。 */
    PatientPage list(PatientQuery query, AuthPrincipal caller);

    /** 查询单个患者摘要。 */
    Patient get(String patientId, AuthPrincipal caller);
}
