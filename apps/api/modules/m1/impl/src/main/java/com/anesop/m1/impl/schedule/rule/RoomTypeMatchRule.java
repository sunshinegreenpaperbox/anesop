package com.anesop.m1.impl.schedule.rule;

import com.anesop.contracts.scheduling.model.Conflict;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 规则 4：申请要求的术间类型必须与候选术间一致（GENERAL/HYBRID/INTERVENTION）。
 *
 * <p>申请未填写 requiredRoomType（null）时视为不限，不评估；类型不匹配为 BLOCKING，
 * 无对应冲突台次（conflictingScheduleId=null）。
 */
@Component
@Order(40)
public class RoomTypeMatchRule implements SchedulingRule {

    public static final String CODE = "ROOM_TYPE_MISMATCH";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Conflict> evaluate(SchedulingContext context) {
        List<Conflict> conflicts = new ArrayList<>();
        String required = context.requiredRoomType();
        if (required == null || required.isBlank() || required.equals(context.roomType())) {
            return conflicts;
        }
        conflicts.add(ScheduleConflictFactory.blocking(CODE,
                "申请要求 " + required + " 术间，候选术间 " + context.roomNo() + " 为 "
                        + context.roomType(),
                null));
        return conflicts;
    }
}
