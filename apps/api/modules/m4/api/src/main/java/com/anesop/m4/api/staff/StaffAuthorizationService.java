package com.anesop.m4.api.staff;

import com.anesop.contracts.staff.model.AuthorizationCheckRequest;
import com.anesop.contracts.staff.model.AuthorizationCheckResult;
import com.anesop.contracts.staff.model.StaffAuthorizationHistory;
import com.anesop.contracts.staff.model.UpdateStaffAuthorizationRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 分级授权服务（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>授权历史 **append-only**：每次授予/撤销写入新 version（从 1 递增），历史只增不改
 *       （数据库触发器 55000 兜底）；撤销也写入新版本而不删除旧版本；</li>
 *   <li>PUT 的 {@code version} 必须等于当前版本（无授权传 0），过期 409；</li>
 *   <li>校验口径：级别 R1–R5 + 显式术式范围/年龄段/ASA 上限/有效期；未授权 → NO_AUTHORIZATION，
 *       已撤销 → AUTHORIZATION_REVOKED，未生效 → NOT_YET_VALID，过期 → EXPIRED，
 *       术式不在范围 → SURGERY_SCOPE_MISSING，年龄超范围 → AGE_OUT_OF_RANGE，
 *       ASA 超上限 → ASA_EXCEEDS_LIMIT，人员离职 → STAFF_INACTIVE；多原因同时返回；</li>
 *   <li>fail-closed（B-3）：术式/年龄/ASA 三个维度缺失且未声明 {@code notApplicable}
 *       时返回 INPUT_INCOMPLETE，不再静默跳过；</li>
 *   <li>回溯（B-4）：{@code at} 判定按 approved_at/valid_from/revoked_at 选择当时生效版本，
 *       不把后来扩大的范围套到过去；撤销不追溯既往（Q-21）；</li>
 *   <li>级别默认映射【待确认】：系统不内置 R1–R5 的临床映射；如院方配置了级别策略上限
 *       （{@code anesop.m4.staff.authorization.level-policies}），授予时超限 422、校验时
 *       返回 LEVEL_POLICY_EXCEEDED；</li>
 *   <li>审计 AUTH_GRANT/AUTH_REVOKE：detail 只含标识、级别、版本与范围摘要，不含姓名与
 *       证书号；失败路径写 result=0 审计。</li>
 * </ul>
 */
public interface StaffAuthorizationService {

    /** 当前授权与全部历史版本（按版本倒序）；人员不存在或超出数据范围 404。 */
    StaffAuthorizationHistory getHistory(String staffId, AuthPrincipal caller);

    /** 授予/撤销授权（写入新版本）；版本过期 409，缺少必填范围 400，级别策略超限 422。 */
    StaffAuthorizationHistory update(String staffId, UpdateStaffAuthorizationRequest request,
            AuthPrincipal caller, AuthRequestContext context);

    /** 授权校验（HTTP 集成点，需要 m4:staff:read）；人员不存在或不可见 422。 */
    AuthorizationCheckResult check(AuthorizationCheckRequest request, AuthPrincipal caller);

    /**
     * 进程内授权判定（M1 排程 / M5 麻醉记录集成点）。
     *
     * <p>该重载要求三个维度全部提供；任一缺失即返回 INPUT_INCOMPLETE。确实存在“不适用”
     * 维度（例如术前尚未产生 ASA）时改用
     * {@link #evaluate(UUID, String, Integer, String, Instant, Set)} 显式声明。
     *
     * @param staffId       人员标识
     * @param surgeryTypeId 术式标识/类别编码（不得为 null，除非声明不适用）
     * @param age           患者年龄（不得为 null，除非声明不适用）
     * @param asa           ASA 分级（不得为 null，除非声明不适用）
     * @param at            判定时点（可空表示当前时间）
     * @return 判定结果（含全部原因码）
     * @throws java.util.NoSuchElementException 人员不存在
     */
    AuthorizationDecision evaluate(UUID staffId, String surgeryTypeId, Integer age, String asa,
            Instant at);

    /**
     * 进程内授权判定（显式声明不适用维度，B-3）。
     *
     * @param staffId       人员标识
     * @param surgeryTypeId 术式标识/类别编码（可空，但必须同时在 notApplicable 声明）
     * @param age           患者年龄（可空，但必须同时在 notApplicable 声明）
     * @param asa           ASA 分级（可空，但必须同时在 notApplicable 声明）
     * @param at            判定时点（可空表示当前时间）
     * @param notApplicable 本次不参与校验的维度（null 视为空集合）
     * @return 判定结果（含全部原因码）
     * @throws java.util.NoSuchElementException 人员不存在
     */
    AuthorizationDecision evaluate(UUID staffId, String surgeryTypeId, Integer age, String asa,
            Instant at, Set<AuthorizationDimension> notApplicable);
}
