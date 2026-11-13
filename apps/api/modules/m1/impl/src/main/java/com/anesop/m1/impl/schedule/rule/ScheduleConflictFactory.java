package com.anesop.m1.impl.schedule.rule;

import com.anesop.contracts.scheduling.model.Conflict;
import com.anesop.m1.impl.persistence.entity.ScheduleOverlapRow;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** 冲突 DTO 构造与半开区间判断（规则共用，避免各规则重复文案/时区逻辑）。 */
final class ScheduleConflictFactory {

    static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(DISPLAY_OFFSET);

    private ScheduleConflictFactory() {
    }

    /** 半开区间重叠：候选 [start,end) 与既有 [row.start,row.end) 有交集。 */
    static boolean overlaps(SchedulingContext context, ScheduleOverlapRow row) {
        return row.getStartAt() != null && row.getEndAt() != null
                && row.getStartAt().isBefore(context.endAt())
                && row.getEndAt().isAfter(context.startAt());
    }

    static Conflict blocking(String type, String message, ScheduleOverlapRow conflicting) {
        Conflict conflict = new Conflict();
        conflict.setConflictType(type);
        conflict.setSeverity(Conflict.SeverityEnum.BLOCKING);
        conflict.setMessage(message);
        conflict.setConflictingScheduleId(conflicting == null ? null : conflicting.getId().toString());
        return conflict;
    }

    static String slot(Instant start, Instant end) {
        return TIME.format(start) + "–" + TIME.format(end);
    }

    static String roomAndSequence(ScheduleOverlapRow row) {
        return "术间 " + row.getRoomNo() + " 第 " + row.getSequence() + " 台";
    }
}
