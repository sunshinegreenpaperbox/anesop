package com.anesop.m2.api.error;

import java.util.List;

/**
 * 复苏转出条件未满足（M3-M10-01），Web 层映射 409 {@code STATE_CONFLICT}。
 *
 * <p>{@code details[]} 逐项列出未满足条件（field=稳定条件码，reason=中文说明），供前端展示
 * 转出条件清单；不得回显患者标识、SBAR 交接原文、Aldrete 备注或数据库约束名。
 * 条件码：ALDRETE_ASSESSED（存在最近一次评估）、ALDRETE_ITEMS_COMPLETE（五项齐全且合法）、
 * ALDRETE_THRESHOLD_MET（总分 ≥ 配置阈值）。
 */
public class PacuDischargePreconditionException extends RuntimeException {

    /** 未满足条件：稳定条件码 + 可安全展示的中文说明。 */
    public record UnmetCondition(String code, String message) {
    }

    private final transient List<UnmetCondition> unmetConditions;

    public PacuDischargePreconditionException(List<UnmetCondition> unmetConditions) {
        super("复苏转出条件未满足");
        this.unmetConditions = List.copyOf(unmetConditions);
    }

    public List<UnmetCondition> unmetConditions() {
        return unmetConditions;
    }
}
