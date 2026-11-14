package com.anesop.m2.api.error;

import java.util.List;

/**
 * 锁单前置条件未满足（M3-M2-03），Web 层映射 409 {@code STATE_CONFLICT}。
 *
 * <p>{@code details[]} 逐项列出未满足条件（field=条件码，reason=中文说明），前端据此展示
 * 前置清单；不得回显患者标识、临床自由文本或数据库约束名。条件码见契约
 * {@code LockPrecondition.code}。
 */
public class M2LockPreconditionException extends RuntimeException {

    /** 未满足条件：稳定条件码 + 可安全展示的中文说明。 */
    public record UnmetCondition(String code, String message) {
    }

    private final transient List<UnmetCondition> unmetConditions;

    public M2LockPreconditionException(List<UnmetCondition> unmetConditions) {
        super("锁单前置条件未满足");
        this.unmetConditions = List.copyOf(unmetConditions);
    }

    public List<UnmetCondition> unmetConditions() {
        return unmetConditions;
    }
}
