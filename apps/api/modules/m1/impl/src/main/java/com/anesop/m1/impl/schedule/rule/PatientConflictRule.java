package com.anesop.m1.impl.schedule.rule;

import com.anesop.contracts.scheduling.model.Conflict;
import com.anesop.m1.impl.persistence.entity.ScheduleOverlapRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 规则 3：同一患者同一时段不得安排两台手术。
 *
 * <p>以申请单的 patientId 判断；患者未知（冲突预检未传申请）时不评估。
 */
@Component
@Order(30)
public class PatientConflictRule implements SchedulingRule {

    public static final String CODE = "PATIENT_CONFLICT";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Conflict> evaluate(SchedulingContext context) {
        List<Conflict> conflicts = new ArrayList<>();
        if (context.patientId() == null) {
            return conflicts;
        }
        for (ScheduleOverlapRow row : context.overlapping()) {
            if (!Objects.equals(row.getPatientId(), context.patientId())
                    || !ScheduleConflictFactory.overlaps(context, row)) {
                continue;
            }
            conflicts.add(ScheduleConflictFactory.blocking(CODE,
                    "患者同一时段已有台次（" + ScheduleConflictFactory.roomAndSequence(row) + "，"
                            + ScheduleConflictFactory.slot(row.getStartAt(), row.getEndAt()) + "）",
                    row));
        }
        return conflicts;
    }
}
