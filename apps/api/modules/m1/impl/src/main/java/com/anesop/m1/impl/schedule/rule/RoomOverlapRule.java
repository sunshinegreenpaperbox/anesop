package com.anesop.m1.impl.schedule.rule;

import com.anesop.contracts.scheduling.model.Conflict;
import com.anesop.m1.impl.persistence.entity.ScheduleOverlapRow;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 规则 1：同一术间时段不得重叠（半开区间，相邻相接不算冲突）。
 *
 * <p>BLOCKING；冲突文案给出术间编号、台次序号与时段，不含患者标识。
 */
@Component
@Order(10)
public class RoomOverlapRule implements SchedulingRule {

    public static final String CODE = "ROOM_OVERLAP";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Conflict> evaluate(SchedulingContext context) {
        List<Conflict> conflicts = new ArrayList<>();
        for (ScheduleOverlapRow row : context.overlapping()) {
            if (!java.util.Objects.equals(row.getRoomId(), context.roomId())
                    || !ScheduleConflictFactory.overlaps(context, row)) {
                continue;
            }
            conflicts.add(ScheduleConflictFactory.blocking(CODE,
                    "与" + ScheduleConflictFactory.roomAndSequence(row) + "（"
                            + ScheduleConflictFactory.slot(row.getStartAt(), row.getEndAt())
                            + "）时段重叠",
                    row));
        }
        return conflicts;
    }
}
