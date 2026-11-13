package com.anesop.m1.api.schedule;

import com.anesop.contracts.scheduling.model.Conflict;
import java.util.List;

/**
 * 排程冲突（M2-M2-01）：同一术间时段重叠、术者/患者同一时段冲突、术间类型不匹配或
 * 时间窗非法，Web 层映射 409 {@code SCHEDULE_CONFLICT} 并在响应 {@code conflicts[]}
 * 返回结构化冲突明细。
 *
 * <p>异常 message 只用于服务端判断；冲突文案由规则固定生成，不含患者姓名、住院号或 SQL 实参。
 */
public class SchedulingConflictException extends RuntimeException {

    private final transient List<Conflict> conflicts;

    public SchedulingConflictException(List<Conflict> conflicts) {
        super("排程冲突，操作未生效");
        this.conflicts = List.copyOf(conflicts);
    }

    /** 结构化冲突明细（类型/说明/冲突台次），按规则执行顺序返回。 */
    public List<Conflict> conflicts() {
        return conflicts;
    }
}
