package com.anesop.m2.api.alert;

import com.anesop.contracts.anesthesia.model.AcknowledgeAlertRequest;
import com.anesop.contracts.anesthesia.model.Alert;
import com.anesop.contracts.anesthesia.model.AlertEvaluationResult;
import com.anesop.contracts.anesthesia.model.AlertList;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;

/**
 * 术中告警服务（M3-M2-02，契约 anesthesia.yaml 0.7.0）。
 *
 * <p>触发入口（评审 B-1，ADR-0017）：采集 ingest 成功提交后发布领域事件，
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 监听器按记录单创建时快照的 roomId（V16）
 * 匹配 {@code status=ONGOING 且 startedAt != null} 的活动记录并增量评估；记录页 5–10s 轮询
 * 告警列表兜底。{@link #listAlerts} 只读、不再评估（避免读旧记录时用当前规则重评历史窗口）；
 * {@link #evaluate} 仅用于调试/采集补偿，同样按“已评估 measured_at 水位”只处理新采样。
 * 跨模块只经 m1-api 解析数据范围，不读 m1 表。
 *
 * <p>关键约束：
 * <ul>
 *   <li>评估范围：记录单 startedAt..(endedAt 或当前时间)，仅取记录单快照 roomId 绑定设备的
 *       体征；DRAFT/未开始一律返回空，不使用 createdAt 兜底；质量码 ≥2（缺失/超时）不参与
 *       判定，空帧不重置窗口（按指标间隔容忍切窗）；</li>
 *   <li>幂等去重：dedupe_key = recordId:ruleCode:越界窗口起始秒，唯一索引兜底，
 *       重复评估同一窗口不重复插入；处置后复发（新窗口）生成新告警；</li>
 *   <li>规则版本：告警行快照 rule_id/rule_code/rule_version，规则升级后新告警使用新版本；</li>
 *   <li>数据范围：记录单不存在/超出数据范围统一 404（不泄露存在性）；</li>
 *   <li>告警状态机：只允许 TRIGGERED → ACKNOWLEDGED/IGNORED 一次流转，重复处理/版本过期 409；
 *       reason 必填并落库留痕，审计 detail 不含 reason 原文；</li>
 *   <li>审计 ALERT_TRIGGERED（resourceType=ALERT、resourceId=告警主键；自动评估 actor 为空，
 *       手工评估记录调用人）与 ALERT_ACK，detail 只含告警/记录单/规则标识、指标与数值，
 *       不含患者标识与处理原因原文。</li>
 * </ul>
 */
public interface AlertService {

    /**
     * 记录单告警列表（只读，按 triggeredAt 倒序返回；实时告警由 ingest 事件评估落库）。
     *
     * @throws java.util.NoSuchElementException 记录单不存在或超出数据范围（404）
     */
    AlertList listAlerts(String recordId, AuthPrincipal caller);

    /**
     * 手动/补偿评估（调试/测试用；权限 {@code m2:record:write}，由控制器声明）。
     *
     * <p>与实时评估共用同一引擎与水位：只处理水位之后的新采样，不重评历史窗口；
     * DRAFT/未开始记录返回空结果。
     *
     * @return 本次评估的规则数、采样帧数、新增告警数与评估后水位
     * @throws java.util.NoSuchElementException 记录单不存在或超出数据范围（404）
     */
    AlertEvaluationResult evaluate(String recordId, AuthPrincipal caller,
            AuthRequestContext context);

    /**
     * 确认或忽略告警。
     *
     * @throws com.anesop.m2.api.error.M2ValidationException decision/reason 非法（400）
     * @throws com.anesop.m2.api.error.M2ConflictException   已处理或 version 过期（409）
     * @throws java.util.NoSuchElementException              告警不存在或超出数据范围（404）
     */
    Alert acknowledge(String alertId, AcknowledgeAlertRequest request, AuthPrincipal caller,
            AuthRequestContext context);
}
