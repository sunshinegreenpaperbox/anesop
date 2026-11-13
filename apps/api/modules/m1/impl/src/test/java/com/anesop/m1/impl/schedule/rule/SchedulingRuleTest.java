package com.anesop.m1.impl.schedule.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.contracts.scheduling.model.Conflict;
import com.anesop.m1.impl.persistence.entity.ScheduleOverlapRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 排程规则单测（M2-M2-01）：覆盖时间重叠、术者/患者冲突、术间类型不匹配、时长/时间窗与
 * 边界相接（半开区间不算冲突）。全部为 SYNTHETIC 标识。
 */
class SchedulingRuleTest {

    private static final UUID ROOM_1 = UUID.fromString("f2000000-0000-4000-8000-0000000000c1");
    private static final UUID ROOM_2 = UUID.fromString("f2000000-0000-4000-8000-0000000000c2");
    private static final UUID PATIENT_1 = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID PATIENT_2 = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID SCHEDULE_1 = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final Instant START = Instant.parse("2026-12-01T00:30:00Z");
    private static final Instant END = Instant.parse("2026-12-01T03:00:00Z");

    private final RoomOverlapRule roomOverlap = new RoomOverlapRule();
    private final SurgeonConflictRule surgeonConflict = new SurgeonConflictRule();
    private final PatientConflictRule patientConflict = new PatientConflictRule();
    private final RoomTypeMatchRule roomTypeMatch = new RoomTypeMatchRule();
    private final TimeWindowRule timeWindow = new TimeWindowRule();

    private SchedulingContext context(String requiredRoomType, List<ScheduleOverlapRow> rows) {
        return new SchedulingContext(UUID.fromString("60000000-0000-4000-8000-000000000001"),
                PATIENT_1, "演示术者", ROOM_1, "OR-01", "GENERAL", requiredRoomType,
                START, END, null, rows);
    }

    private ScheduleOverlapRow row(UUID roomId, UUID patientId, String surgeon, Instant start, Instant end) {
        ScheduleOverlapRow row = new ScheduleOverlapRow();
        row.setId(SCHEDULE_1);
        row.setRoomId(roomId);
        row.setRoomNo("OR-01");
        row.setRoomType("GENERAL");
        row.setStartAt(start);
        row.setEndAt(end);
        row.setSequence(1);
        row.setStatus("PLANNED");
        row.setPatientId(patientId);
        row.setSurgeonName(surgeon);
        return row;
    }

    @Test
    void 同术间时间重叠为阻断冲突() {
        List<Conflict> conflicts = roomOverlap.evaluate(context(null,
                List.of(row(ROOM_1, PATIENT_2, "其他术者", START.plusSeconds(600), END))));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getConflictType()).isEqualTo("ROOM_OVERLAP");
        assertThat(conflicts.get(0).getSeverity()).isEqualTo(Conflict.SeverityEnum.BLOCKING);
        assertThat(conflicts.get(0).getMessage()).contains("OR-01").contains("第 1 台");
        assertThat(conflicts.get(0).getConflictingScheduleId()).isEqualTo(SCHEDULE_1.toString());
    }

    @Test
    void 边界相接不算冲突() {
        // 既有台次恰好结束于候选开始：半开区间 [start,end) 无交集。
        List<Conflict> conflicts = roomOverlap.evaluate(context(null,
                List.of(row(ROOM_1, PATIENT_2, "其他术者", START.minusSeconds(3600), START))));

        assertThat(conflicts).isEmpty();
    }

    @Test
    void 不同术间不算术间冲突() {
        List<Conflict> conflicts = roomOverlap.evaluate(context(null,
                List.of(row(ROOM_2, PATIENT_2, "其他术者", START, END))));

        assertThat(conflicts).isEmpty();
    }

    @Test
    void 同一术者同一时段跨术间冲突() {
        List<Conflict> conflicts = surgeonConflict.evaluate(context(null,
                List.of(row(ROOM_2, PATIENT_2, "演示术者", START, END))));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getConflictType()).isEqualTo("STAFF_CONFLICT");
        assertThat(conflicts.get(0).getMessage()).contains("术者");
    }

    @Test
    void 术者缺失不臆造冲突() {
        SchedulingContext context = new SchedulingContext(null, PATIENT_1, " ", ROOM_1, "OR-01",
                "GENERAL", null, START, END, null,
                List.of(row(ROOM_2, PATIENT_2, "演示术者", START, END)));

        assertThat(surgeonConflict.evaluate(context)).isEmpty();
    }

    @Test
    void 同一患者同一时段冲突() {
        List<Conflict> conflicts = patientConflict.evaluate(context(null,
                List.of(row(ROOM_2, PATIENT_1, "其他术者", START, END))));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getConflictType()).isEqualTo("PATIENT_CONFLICT");
        assertThat(conflicts.get(0).getMessage()).contains("患者");
    }

    @Test
    void 术间类型不匹配为阻断冲突() {
        List<Conflict> conflicts = roomTypeMatch.evaluate(context("HYBRID", List.of()));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getConflictType()).isEqualTo("ROOM_TYPE_MISMATCH");
        assertThat(conflicts.get(0).getMessage()).contains("HYBRID").contains("GENERAL");
        assertThat(conflicts.get(0).getConflictingScheduleId()).isNull();
    }

    @Test
    void 术间类型匹配或未要求时无冲突() {
        assertThat(roomTypeMatch.evaluate(context("GENERAL", List.of()))).isEmpty();
        assertThat(roomTypeMatch.evaluate(context(null, List.of()))).isEmpty();
    }

    @Test
    void 结束不晚于开始为时间窗冲突() {
        SchedulingContext context = new SchedulingContext(null, PATIENT_1, null, ROOM_1, "OR-01",
                "GENERAL", null, END, START, null, List.of());

        List<Conflict> conflicts = timeWindow.evaluate(context);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getConflictType()).isEqualTo("TIME_WINDOW_INVALID");
    }

    @Test
    void 时长超过上限为时间窗冲突() {
        SchedulingContext context = new SchedulingContext(null, PATIENT_1, null, ROOM_1, "OR-01",
                "GENERAL", null, START, START.plusSeconds(25 * 3600), null, List.of());

        assertThat(timeWindow.evaluate(context)).hasSize(1);
    }

    @Test
    void 正常时长无冲突() {
        assertThat(timeWindow.evaluate(context(null, List.of()))).isEmpty();
    }

    @Test
    void 上下文重叠集合为空或含空值时安全处理() {
        SchedulingContext nullRows = new SchedulingContext(null, null, null, ROOM_1, "OR-01",
                "GENERAL", null, START, END, null, null);
        SchedulingContext nullTimes = new SchedulingContext(null, PATIENT_1, null, ROOM_1, "OR-01",
                "GENERAL", null, null, null, null, List.of());

        assertThat(nullRows.overlapping()).isEmpty();
        assertThat(new RoomOverlapRule().evaluate(nullRows)).isEmpty();
        assertThat(timeWindow.evaluate(nullTimes)).isEmpty();
        assertThat(patientConflict.evaluate(nullRows)).isEmpty();
        assertThat(roomTypeMatch.evaluate(new SchedulingContext(null, null, null, ROOM_1, "OR-01",
                "GENERAL", "  ", START, END, null, null))).isEmpty();
    }

    @Test
    void 不足一分钟与空术者值不产生误报() {
        SchedulingContext shortSlot = new SchedulingContext(null, PATIENT_1, null, ROOM_1, "OR-01",
                "GENERAL", null, START, START.plusSeconds(30), null, List.of());
        assertThat(timeWindow.evaluate(shortSlot)).hasSize(1);

        ScheduleOverlapRow missingSurgeon = row(ROOM_2, PATIENT_1, null, START, END);
        ScheduleOverlapRow missingTimes = row(ROOM_2, PATIENT_2, "演示术者", null, null);
        assertThat(surgeonConflict.evaluate(context(null, List.of(missingSurgeon)))).isEmpty();
        assertThat(roomOverlap.evaluate(context(null, List.of(missingTimes)))).isEmpty();
    }

    @Test
    void 注册表忽略空结果并支持扩展规则() {
        SchedulingRule empty = new SchedulingRule() {
            @Override
            public String code() {
                return "EMPTY_RULE";
            }

            @Override
            public List<Conflict> evaluate(SchedulingContext context) {
                return List.of();
            }
        };
        SchedulingRule nullResult = new SchedulingRule() {
            @Override
            public String code() {
                return "NULL_RULE";
            }

            @Override
            public List<Conflict> evaluate(SchedulingContext context) {
                return null;
            }
        };
        SchedulingRuleRegistry registry = new SchedulingRuleRegistry(List.of(empty, nullResult));

        assertThat(registry.evaluate(context(null, List.of()))).isEmpty();
        assertThat(registry.ruleCodes()).containsExactly("EMPTY_RULE", "NULL_RULE");
    }

    @Test
    void 注册表按顺序汇总全部规则且编码稳定() {
        SchedulingRuleRegistry registry = new SchedulingRuleRegistry(List.of(
                timeWindow, roomTypeMatch, patientConflict, surgeonConflict, roomOverlap));
        SchedulingContext context = context("HYBRID", List.of(
                row(ROOM_1, PATIENT_1, "演示术者", START.plusSeconds(600), END)));

        List<Conflict> conflicts = registry.evaluate(context);

        assertThat(conflicts).extracting(Conflict::getConflictType)
                .containsExactly("ROOM_OVERLAP", "STAFF_CONFLICT", "PATIENT_CONFLICT",
                        "ROOM_TYPE_MISMATCH");
        assertThat(registry.ruleCodes()).containsExactly("ROOM_OVERLAP", "STAFF_CONFLICT",
                "PATIENT_CONFLICT", "ROOM_TYPE_MISMATCH", "TIME_WINDOW_INVALID");
    }
}
