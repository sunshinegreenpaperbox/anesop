package com.anesop.m0.api.platform;

import com.anesop.contracts.platform.model.MessageType;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 站内消息模板注册表（M8-M0-04 评审 S-4）。
 *
 * <p>消息标题与参数白名单由服务端注册表决定：调用方只能提交<b>已登记</b>的模板编码与
 * 该模板允许的参数键，标题由 {@link MessagePublisher} 从注册表渲染。这样即使调用方
 * 误把患者姓名/住院号拼进「标题」，也不会被持久化或展示，避免 M0 成为任意文案/PHI 通道。
 *
 * <p>新增模板的流程：在本表登记（编码、类型、标题、允许参数）→ 补
 * {@code PlatformMessageServiceTest} 用例 → 在调用模块的领域事件处使用编码。**不得**
 * 为图方便放开任意 title 或任意 params。
 *
 * <p>参数值仍需调用方保证非 PHI（键白名单只约束语义位置）；服务端做长度/字符集兜底。
 */
public final class MessageTemplates {

    /**
     * 一个消息模板。
     *
     * @param code          模板编码（大写字母/数字/下划线，≤64）
     * @param type          消息类型（发布时校验与模板一致）
     * @param title         服务端渲染的固定标题（不含占位符，杜绝任意文案）
     * @param allowedParams 允许的参数键（发布时严格子集校验）
     */
    public record Template(String code, MessageType type, String title, Set<String> allowedParams) {

        public Template {
            allowedParams = allowedParams == null ? Set.of() : Set.copyOf(allowedParams);
        }
    }

    /** 系统广播/维护通知。 */
    public static final String SYSTEM_BROADCAST = "SYSTEM_BROADCAST";
    /** 台次时间调整。 */
    public static final String SCHEDULE_CHANGED = "SCHEDULE_CHANGED";
    /** 台次停台/取消。 */
    public static final String SCHEDULE_STOPPED = "SCHEDULE_STOPPED";
    /** 严重预警提醒。 */
    public static final String ALERT_CRITICAL = "ALERT_CRITICAL";
    /** 病历抽查待办。 */
    public static final String QC_REVIEW_DUE = "QC_REVIEW_DUE";
    /** 待办任务提醒。 */
    public static final String TASK_ASSIGNED = "TASK_ASSIGNED";
    /** 耗材低库存提醒（M8-M4-01 预留）。 */
    public static final String MATERIAL_LOW_STOCK = "MATERIAL_LOW_STOCK";

    /**
     * 业务投递模板（M9-01，默认通知矩阵）：改台/急诊加台/停台/严重预警。
     *
     * <p>参数只允许标识、术间、时间与原因码（无 PHI）。编码与 m1 NotificationPort 的
     * 事件映射一一对应；频率/免打扰/升级路径待院方确认（见 docs/modules/m1/README.md）。
     */
    public static final String SCHEDULE_CHANGE = "SCHEDULE_CHANGE";
    /** 急诊加台通知。 */
    public static final String EMERGENCY = "EMERGENCY";
    /** 台次停台通知。 */
    public static final String SCHEDULE_STOP = "SCHEDULE_STOP";
    /** 严重预警提醒（含未确认）。 */
    public static final String CRITICAL_ALERT = "CRITICAL_ALERT";

    private static final Map<String, Template> REGISTRY = Map.ofEntries(
            Map.entry(SYSTEM_BROADCAST, new Template(SYSTEM_BROADCAST, MessageType.SYSTEM,
                    "系统维护通知", Set.of("deptName"))),
            Map.entry(SCHEDULE_CHANGED, new Template(SCHEDULE_CHANGED, MessageType.SCHEDULE,
                    "台次时间已调整", Set.of("roomNo", "startAt"))),
            Map.entry(SCHEDULE_STOPPED, new Template(SCHEDULE_STOPPED, MessageType.SCHEDULE,
                    "台次已停台", Set.of("roomNo"))),
            Map.entry(ALERT_CRITICAL, new Template(ALERT_CRITICAL, MessageType.ALERT,
                    "严重预警提醒", Set.of("count"))),
            Map.entry(QC_REVIEW_DUE, new Template(QC_REVIEW_DUE, MessageType.QC,
                    "病历抽查待办", Set.of())),
            Map.entry(TASK_ASSIGNED, new Template(TASK_ASSIGNED, MessageType.TASK,
                    "待办任务提醒", Set.of("count"))),
            Map.entry(MATERIAL_LOW_STOCK, new Template(MATERIAL_LOW_STOCK, MessageType.ALERT,
                    "耗材库存不足", Set.of("deptName", "count"))),
            Map.entry(SCHEDULE_CHANGE, new Template(SCHEDULE_CHANGE, MessageType.SCHEDULE,
                    "台次时间已调整", Set.of("scheduleId", "roomId", "startAt", "reasonCode"))),
            Map.entry(EMERGENCY, new Template(EMERGENCY, MessageType.SCHEDULE,
                    "急诊加台通知", Set.of("scheduleId", "roomId", "startAt", "reasonCode"))),
            Map.entry(SCHEDULE_STOP, new Template(SCHEDULE_STOP, MessageType.SCHEDULE,
                    "台次已停台", Set.of("scheduleId", "roomId", "reasonCode"))),
            Map.entry(CRITICAL_ALERT, new Template(CRITICAL_ALERT, MessageType.ALERT,
                    "严重预警提醒（待处理）", Set.of("scheduleId", "roomId", "alertId", "metric",
                            "severity", "triggeredAt"))));

    private MessageTemplates() {
    }

    /** 按编码查找模板；未登记返回空。 */
    public static Optional<Template> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(REGISTRY.get(code));
    }

    /** 所有已登记模板（只读快照，供文档/自检使用）。 */
    public static Map<String, Template> all() {
        // 防御性不可变副本：避免 SpotBugs MS_EXPOSE_REP 与调用方修改内部注册表。
        return Map.copyOf(REGISTRY);
    }
}
