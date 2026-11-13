package com.anesop.m2.api.alert;

import com.anesop.contracts.anesthesia.model.AlertRule;
import com.anesop.contracts.anesthesia.model.AlertRuleList;
import com.anesop.contracts.anesthesia.model.UpdateAlertRuleRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 术中预警规则服务（M3-M2-02，契约 anesthesia.yaml 0.7.0）。
 *
 * <p>边界与约束：
 * <ul>
 *   <li>规则版本化、不可原地修改：{@link #updateRule} 在同一 code 下生成 version+1 的新行，
 *       并把旧版本 enabled 置 false；历史版本与阈值永不被改写（数据库触发器兜底），
 *       保证告警可追溯“触发时依据的规则版本”；</li>
 *   <li>同一 code 最多一个启用版本（部分唯一索引 uk_alert_rule_enabled_code 兜底），
 *       并发改版第二请求命中唯一键 → 409；</li>
 *   <li>阈值口径【待确认】：LT=低于下限、GT=高于上限、OUT_OF_RANGE=超出区间，
 *       等于阈值不触发；算子与阈值必须自洽（缺失 400），阈值按指标允许范围校验
 *       （SpO2 0–100、HR 0–300、MAP 0–250、EtCO2 0–150、TEMP 25–45），越界 400；</li>
 *   <li>规则治理（S-2）：{@code name} 可随新版本更新；阈值/持续时长变化置
 *       {@code pendingConfirmation=true}（临床待确认）；历史版本可按 code 查询，
 *       供告警 {@code ruleVersion} 追溯当时阈值；</li>
 *   <li>权限：列表需要 {@code m2:alert:read}，配置需要 {@code m2:alert:configure}
 *       （V15 仅授予 SYS_ADMIN）；审计 ALERT_RULE_UPDATE 只记录规则编码/版本与变更后的
 *       阈值结构，不含操作者输入的备注或患者标识。</li>
 * </ul>
 */
public interface AlertRuleService {

    /**
     * 每个规则编码（code）的当前版本列表（按 code 升序）；停用的最新版本同样返回，
     * 前端据此展示启用状态。
     */
    AlertRuleList listRules();

    /**
     * 同一规则编码的历史版本（按 version 倒序，additive：供 ruleVersion 追溯）。
     *
     * @param ruleId 任一版本行标识；不存在 404
     */
    AlertRuleList listVersions(String ruleId);

    /**
     * 保存规则（生成新版本）。
     *
     * @param ruleId  当前版本行标识；不存在 404，非当前版本或 version 过期 409
     * @param request 阈值/时长/严重度/启用状态 + 当前 version
     * @throws com.anesop.m2.api.error.M2ValidationException 算子与阈值不自洽、字段越界（400）
     * @throws com.anesop.m2.api.error.M2ConflictException   版本过期/并发改版（409）
     * @throws java.util.NoSuchElementException              规则不存在（404）
     */
    AlertRule updateRule(String ruleId, UpdateAlertRuleRequest request, AuthPrincipal caller,
            AuthRequestContext context);
}
