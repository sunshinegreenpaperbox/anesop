package com.anesop.m1.impl.schedule.rule;

import com.anesop.contracts.scheduling.model.Conflict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 规则 5：时长与时间窗合法（结束晚于开始，单台 1–1440 分钟）。
 *
 * <p>不合法为 BLOCKING：服务端在事务内同样执行本规则，不能靠前端规避。
 */
@Component
@Order(50)
public class TimeWindowRule implements SchedulingRule {

    public static final String CODE = "TIME_WINDOW_INVALID";

    /** 单台计划时长上限（分钟），与申请 plannedDurationMin 的上限一致。 */
    public static final long MAX_DURATION_MINUTES = 1440L;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public List<Conflict> evaluate(SchedulingContext context) {
        List<Conflict> conflicts = new ArrayList<>();
        if (context.startAt() == null || context.endAt() == null) {
            return conflicts;
        }
        if (!context.endAt().isAfter(context.startAt())) {
            conflicts.add(ScheduleConflictFactory.blocking(CODE, "计划结束时间必须晚于开始时间", null));
            return conflicts;
        }
        long minutes = Duration.between(context.startAt(), context.endAt()).toMinutes();
        if (minutes < 1 || minutes > MAX_DURATION_MINUTES) {
            conflicts.add(ScheduleConflictFactory.blocking(CODE,
                    "单台计划时长必须在 1–" + MAX_DURATION_MINUTES + " 分钟之间", null));
        }
        return conflicts;
    }
}
