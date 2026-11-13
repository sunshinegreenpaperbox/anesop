package com.anesop.m1.impl.schedule.rule;

import com.anesop.contracts.scheduling.model.Conflict;
import java.util.List;

/**
 * 排程规则（M2-M2-01，可配置规则引擎的扩展点）。
 *
 * <p>实现类注册为 Spring Bean，由 {@link SchedulingRuleRegistry} 按顺序执行；每条规则只读取
 * {@link SchedulingContext}，返回自己负责的冲突（可为空列表）。规则不得修改上下文或落库，
 * 便于单测与按需启停（新增规则只需新增 Bean，不改服务代码）。
 *
 * <p>冲突文案固定生成，不得包含患者姓名、住院号、证件号或 SQL 实参。
 */
public interface SchedulingRule {

    /** 规则编码（日志/文档/审计使用，如 ROOM_OVERLAP）。 */
    String code();

    /** 评估候选台次，返回冲突明细；无冲突返回空列表。 */
    List<Conflict> evaluate(SchedulingContext context);
}
