package com.anesop.m1.impl.schedule.rule;

import com.anesop.m1.impl.persistence.entity.ScheduleOverlapRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 规则引擎输入：候选台次 + 同窗口既有台次（已由事务内查询/锁保证一致性）。
 *
 * @param applicationId     候选申请；冲突预检未传申请时为 null
 * @param patientId         候选患者；未知时为 null
 * @param surgeonName       候选术者姓名；未知时为 null
 * @param roomId            候选术间
 * @param roomNo            候选术间编号（冲突文案用）
 * @param roomType          候选术间类型
 * @param requiredRoomType  申请要求的术间类型；null=不限
 * @param startAt           候选开始
 * @param endAt             候选结束
 * @param excludeScheduleId 调整时排除的自身台次
 * @param overlapping       与候选窗口重叠的未取消台次
 */
public record SchedulingContext(
        UUID applicationId,
        UUID patientId,
        String surgeonName,
        UUID roomId,
        String roomNo,
        String roomType,
        String requiredRoomType,
        Instant startAt,
        Instant endAt,
        UUID excludeScheduleId,
        List<ScheduleOverlapRow> overlapping) {

    public SchedulingContext {
        overlapping = overlapping == null ? List.of() : List.copyOf(overlapping);
    }
}
