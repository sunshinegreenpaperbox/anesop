package com.anesop.m1.impl.schedule.rule;

import com.anesop.contracts.scheduling.model.Conflict;
import com.anesop.m1.impl.persistence.entity.ScheduleOverlapRow;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 规则 2：同一术者同一时段不得出现在两台手术（跨术间）。
 *
 * <p>以申请单的术者姓名 + 时段判断；姓名为空时不评估（数据缺失不臆造冲突）。
 */
@Component
@Order(20)
public class SurgeonConflictRule implements SchedulingRule {

    public static final String CODE = "STAFF_CONFLICT";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Conflict> evaluate(SchedulingContext context) {
        String surgeon = context.surgeonName();
        List<Conflict> conflicts = new ArrayList<>();
        if (surgeon == null || surgeon.isBlank()) {
            return conflicts;
        }
        for (ScheduleOverlapRow row : context.overlapping()) {
            if (row.getSurgeonName() == null || !surgeon.trim().equals(row.getSurgeonName().trim())
                    || !ScheduleConflictFactory.overlaps(context, row)) {
                continue;
            }
            conflicts.add(ScheduleConflictFactory.blocking(CODE,
                    "术者同一时段已有台次（" + ScheduleConflictFactory.roomAndSequence(row) + "，"
                            + ScheduleConflictFactory.slot(row.getStartAt(), row.getEndAt()) + "）",
                    row));
        }
        return conflicts;
    }
}
