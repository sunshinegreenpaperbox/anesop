package com.anesop.m1.impl.schedule.rule;

import com.anesop.contracts.scheduling.model.Conflict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 规则注册表：按 {@code @Order} 顺序执行全部 {@link SchedulingRule} Bean，汇总冲突。
 *
 * <p>规则可配置体现在“新增/停用规则只增减 Bean”，服务与控制器不感知具体规则；
 * 测试可注入固定规则列表直接构造注册表。
 */
@Component
public class SchedulingRuleRegistry {

    private final List<SchedulingRule> rules;

    public SchedulingRuleRegistry(List<SchedulingRule> rules) {
        List<SchedulingRule> ordered = new ArrayList<>(rules);
        ordered.sort(Comparator.comparingInt(this::orderOf));
        this.rules = List.copyOf(ordered);
    }

    /** 执行全部规则并汇总冲突；无冲突返回空列表。 */
    public List<Conflict> evaluate(SchedulingContext context) {
        List<Conflict> conflicts = new ArrayList<>();
        for (SchedulingRule rule : rules) {
            List<Conflict> found = rule.evaluate(context);
            if (found != null && !found.isEmpty()) {
                conflicts.addAll(found);
            }
        }
        return conflicts;
    }

    /** 已注册规则编码（文档/诊断用）。 */
    public List<String> ruleCodes() {
        return rules.stream().map(SchedulingRule::code).toList();
    }

    private int orderOf(SchedulingRule rule) {
        org.springframework.core.annotation.Order order =
                org.springframework.core.annotation.AnnotationUtils.findAnnotation(rule.getClass(),
                        org.springframework.core.annotation.Order.class);
        return order == null ? Integer.MAX_VALUE : order.value();
    }
}
