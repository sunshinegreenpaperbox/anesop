package com.anesop.m2.impl.record;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.contracts.anesthesia.model.RevisionDiff;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.DrugAdministrationEntity;
import com.anesop.m2.impl.persistence.entity.FluidRecordEntity;
import com.anesop.m2.impl.persistence.entity.RecordEventEntity;
import com.anesop.m2.impl.persistence.entity.ResuscitationRecordEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 修订快照规范化与摘要单测（M3-M2-03）。
 *
 * <p>断言可观察行为：字段排序/null 省略/数字归一化保证“同内容同摘要、不同内容不同摘要”；
 * 快照排除患者标识与审计时间；差异按叶子路径稳定输出。全部数据为 SYNTHETIC。
 */
class RecordRevisionSnapshotBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordRevisionSnapshotBuilder builder =
            new RecordRevisionSnapshotBuilder(objectMapper);

    @Test
    void canonicalJsonSortsKeysAndOmitsNullsSoSameContentHasSameHash() {
        Map<String, Object> first = builder.build(record(), events(), List.of(), List.of(), List.of(),
                Map.of("intake", 500), "GENERAL", "a".repeat(64), null);
        Map<String, Object> second = builder.build(record(), events(), List.of(), List.of(), List.of(),
                Map.of("intake", 500), "GENERAL", "a".repeat(64), null);

        String canonical = builder.canonicalJson(objectMapper.valueToTree(first));
        assertThat(canonical).startsWith("{\"drugs\":[]");
        assertThat(canonical).contains("\"events\":[{\"entryReason\":\"补录原因（演示）\"");
        assertThat(canonical).doesNotContain("note\":null");
        assertThat(builder.hash(first)).isEqualTo(builder.hash(second));
        assertThat(builder.hash(first)).matches("^[0-9a-f]{64}$");
    }

    @Test
    void differentClinicalContentProducesDifferentHashAndChainChangesHash() {
        Map<String, Object> base = builder.build(record(), events(), List.of(), List.of(), List.of(),
                Map.of("intake", 500), "GENERAL", "a".repeat(64), null);
        Map<String, Object> corrected = builder.build(record(), events(), List.of(), List.of(), List.of(),
                Map.of("intake", 500), "COMBINED", "a".repeat(64), null);
        Map<String, Object> chained = builder.build(record(), events(), List.of(), List.of(), List.of(),
                Map.of("intake", 500), "GENERAL", "a".repeat(64), "b".repeat(64));

        assertThat(builder.hash(corrected)).isNotEqualTo(builder.hash(base));
        assertThat(builder.hash(chained)).isNotEqualTo(builder.hash(base));
        assertThat(builder.hash(base)).isEqualTo(builder.hash(base));
    }

    @Test
    void snapshotExcludesPatientIdentifiersAndAuditTimestamps() {
        Map<String, Object> snapshot = builder.build(record(), events(), List.of(), List.of(), List.of(),
                Map.of("intake", 500), "GENERAL", "a".repeat(64), null);
        String json = builder.toJson(snapshot);

        UUID patientId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        assertThat(json).doesNotContain(patientId.toString());
        assertThat(json).doesNotContain("演示患者");
        assertThat(json).doesNotContain("createdAt");
        assertThat(json).doesNotContain("updatedBy");
        assertThat(json).contains("\"record\"", "\"events\"", "\"drugs\"", "\"fluids\"",
                "\"ioSummary\"", "\"signatures\"");
        assertThat(snapshot.get("record")).isInstanceOf(Map.class);
    }

    @Test
    void diffComparesLeafPathsAndReportsNoChangeForIdenticalContent() {
        Map<String, Object> base = builder.build(record(), events(), List.of(), List.of(), List.of(),
                Map.of("intake", 500, "output", 100), "GENERAL", "a".repeat(64), null);
        Map<String, Object> corrected = builder.build(record(), events(), List.of(), List.of(), List.of(),
                Map.of("intake", 500, "output", 100), "COMBINED", "a".repeat(64), null);

        RevisionDiff diff = builder.diff(base, corrected);
        assertThat(diff.getPreviousRevisionNo()).isZero();
        assertThat(diff.getSummary()).isEqualTo("共 1 项字段变更");
        assertThat(diff.getChangedFields()).hasSize(1);
        assertThat(diff.getChangedFields().get(0).getField()).isEqualTo("record.anesthesiaType");
        assertThat(diff.getChangedFields().get(0).getPreviousValue()).isEqualTo("GENERAL");
        assertThat(diff.getChangedFields().get(0).getNewValue()).isEqualTo("COMBINED");

        RevisionDiff unchanged = builder.diff(base, base);
        assertThat(unchanged.getChangedFields()).isEmpty();
        assertThat(unchanged.getSummary()).isEqualTo("无内容差异（仅修订说明）");
    }

    @Test
    void numericValuesAreNormalizedAcrossParsedAndInMemorySnapshots() {
        DrugAdministrationEntity drug = new DrugAdministrationEntity();
        drug.setId(UUID.randomUUID());
        drug.setDrugName("丙泊酚（演示）");
        drug.setDose(new BigDecimal("10.000"));
        drug.setUnit("mg");
        drug.setRoute("IV");
        drug.setAdministeredAt(Instant.now());
        drug.setNarcotic(false);
        Map<String, Object> inMemory = builder.build(record(), List.of(), List.of(drug),
                List.of(), List.of(), Map.of(), "GENERAL", "a".repeat(64), null);
        Map<String, Object> parsed = builder.parse(builder.toJson(inMemory));

        RevisionDiff diff = builder.diff(inMemory, parsed);
        assertThat(diff.getChangedFields()).isEmpty();
        assertThat(builder.hash(inMemory)).isEqualTo(builder.hash(parsed));
    }

    @Test
    void canonicalJsonHandlesArraysEscapesAndRemovedFields() {
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("note", "旧备注：含\"引号\"与\\反斜杠\n换行（演示）");
        previous.put("values", List.of(1, 2.5, true));
        previous.put("removed", "仅上一版存在（演示）");

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("note", null);
        current.put("values", List.of(1, 2.5, true));

        String canonical = builder.canonicalJson(objectMapper.valueToTree(previous));
        assertThat(canonical).contains("\\\"引号\\\"");
        assertThat(canonical).contains("[1,2.5,true]");
        assertThat(canonical).doesNotContain("removed\":null");

        RevisionDiff diff = builder.diff(previous, current);
        assertThat(diff.getChangedFields()).extracting(
                com.anesop.contracts.anesthesia.model.RevisionFieldChange::getField)
                .contains("note", "removed");
        assertThat(diff.getSummary()).contains("项字段变更");
    }

    @Test
    void parseFallsBackForEmptyAndNonObjectJson() {
        assertThat(builder.parse(null)).isEmpty();
        assertThat(builder.parse("  ")).isEmpty();
        assertThat(builder.parse("[1,2]")).isEmpty();
    }

    @Test
    void rescueContentIsIncludedInHashAndContentHashRestoresWhenOldContentReturns() {
        ResuscitationRecordEntity rescue = rescue("演示：胸外按压后恢复自主循环（合成）");
        Map<String, Object> withRescue = builder.build(record(), events(), List.of(), List.of(),
                List.of(rescue), Map.of("intake", 500), "GENERAL", "a".repeat(64), null);
        Map<String, Object> noRescue = builder.build(record(), events(), List.of(), List.of(),
                List.of(), Map.of("intake", 500), "GENERAL", "a".repeat(64), null);
        Map<String, Object> changedRescue = builder.build(record(), events(), List.of(), List.of(),
                List.of(rescue("演示：胸外按压并电除颤（合成）")), Map.of("intake", 500),
                "GENERAL", "a".repeat(64), null);
        Map<String, Object> restoredRescue = builder.build(record(), events(), List.of(), List.of(),
                List.of(rescue("演示：胸外按压后恢复自主循环（合成）")), Map.of("intake", 500),
                "GENERAL", "a".repeat(64), null);

        // 签名摘要覆盖抢救内容：有/无抢救、抢救内容变化都会改变摘要。
        assertThat(builder.hash(withRescue)).isNotEqualTo(builder.hash(noRescue));
        assertThat(builder.hash(changedRescue)).isNotEqualTo(builder.hash(withRescue));
        // 恢复旧内容 → 内容摘要复原（签名摘要另含版本链，故用 contentHash 验证纯内容）。
        assertThat(builder.contentHash(restoredRescue)).isEqualTo(builder.contentHash(withRescue));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rescues = (List<Map<String, Object>>) withRescue.get("rescues");
        assertThat(rescues).hasSize(1);
        assertThat(rescues.get(0)).containsKeys("resusType", "eventSummary", "drugs", "leader",
                "rescuers", "outcome");
        assertThat(rescues.get(0)).doesNotContainKeys("createdAt", "createdBy");
        assertThat(String.valueOf(rescues.get(0).get("rescuers")))
                .contains("演示护士（合成）").doesNotContain("createdAt");
    }

    @Test
    void arraysAreSortedByTimeThenIdSoSameMillisecondOrderDoesNotChangeHash() {
        RecordEventEntity first = event("13000000-0000-4000-8000-000000000002", 2,
                Instant.parse("2026-12-01T00:41:00Z"));
        RecordEventEntity second = event("13000000-0000-4000-8000-000000000001", 1,
                Instant.parse("2026-12-01T00:41:00Z"));
        DrugAdministrationEntity drugA = drug("14000000-0000-4000-8000-000000000001",
                Instant.parse("2026-12-01T00:42:00Z"));
        DrugAdministrationEntity drugB = drug("14000000-0000-4000-8000-000000000002",
                Instant.parse("2026-12-01T00:42:00Z"));
        FluidRecordEntity fluidA = fluid("15000000-0000-4000-8000-000000000001",
                Instant.parse("2026-12-01T00:43:00Z"));
        FluidRecordEntity fluidB = fluid("15000000-0000-4000-8000-000000000002",
                Instant.parse("2026-12-01T00:43:00Z"));

        Map<String, Object> forward = builder.build(record(), List.of(first, second),
                List.of(drugA, drugB), List.of(fluidA, fluidB), List.of(), Map.of(), "GENERAL",
                null, null);
        Map<String, Object> shuffled = builder.build(record(), List.of(second, first),
                List.of(drugB, drugA), List.of(fluidB, fluidA), List.of(), Map.of(), "GENERAL",
                null, null);

        assertThat(builder.hash(shuffled)).isEqualTo(builder.hash(forward));
        assertThat(builder.contentHash(shuffled)).isEqualTo(builder.contentHash(forward));
    }

    private AnesthesiaRecordEntity record() {
        AnesthesiaRecordEntity record = new AnesthesiaRecordEntity();
        record.setId(UUID.fromString("12000000-0000-4000-8000-000000000001"));
        record.setScheduleId(UUID.fromString("80000000-0000-4000-8000-000000000001"));
        record.setApplicationId(UUID.fromString("70000000-0000-4000-8000-000000000001"));
        record.setPatientId(UUID.fromString("50000000-0000-4000-8000-000000000001"));
        record.setStatus("ONGOING");
        record.setAnesthesiaType("GENERAL");
        Instant start = Instant.parse("2026-12-01T00:40:00Z");
        record.setStartedAt(start);
        record.setEndedAt(start.plus(2, ChronoUnit.HOURS));
        record.setCreatedAt(Instant.parse("2026-12-01T00:35:00Z"));
        record.setVersion(5L);
        record.setCurrentRevision(0);
        return record;
    }

    private static ResuscitationRecordEntity rescue(String summary) {
        ResuscitationRecordEntity rescue = new ResuscitationRecordEntity();
        rescue.setId(UUID.fromString("16000000-0000-4000-8000-000000000001"));
        rescue.setRecordId(UUID.fromString("12000000-0000-4000-8000-000000000001"));
        rescue.setResusType("CPR");
        rescue.setStartedAt(Instant.parse("2026-12-01T01:30:00Z"));
        rescue.setEndedAt(Instant.parse("2026-12-01T01:45:00Z"));
        rescue.setEventSummary("{\"summary\":\"" + summary
                + "\",\"interventions\":[\"胸外按压\"]}");
        rescue.setDrugs("[{\"drugName\":\"肾上腺素（演示）\",\"dose\":1,"
                + "\"unit\":\"mg\",\"route\":\"IV\","
                + "\"administeredAt\":\"2026-12-01T09:31:00+08:00\"}]");
        rescue.setLeader("{\"userId\":\"10000000-0000-4000-8000-000000000002\","
                + "\"name\":\"演示负责人（合成）\"}");
        rescue.setRescuers("[{\"name\":\"演示护士（合成）\"}]");
        rescue.setOutcome("SUCCESS");
        rescue.setCreatedAt(Instant.parse("2026-12-01T01:46:00Z"));
        rescue.setCreatedBy(UUID.fromString("10000000-0000-4000-8000-000000000002"));
        return rescue;
    }

    private static RecordEventEntity event(String id, int seq, Instant occurredAt) {
        RecordEventEntity event = new RecordEventEntity();
        event.setId(UUID.fromString(id));
        event.setRecordId(UUID.fromString("12000000-0000-4000-8000-000000000001"));
        event.setSeq(seq);
        event.setEventType("CUSTOM");
        event.setOccurredAt(occurredAt);
        event.setSource("MANUAL");
        event.setPayload("{}");
        event.setCreatedAt(occurredAt.plusSeconds(1));
        return event;
    }

    private static DrugAdministrationEntity drug(String id, Instant administeredAt) {
        DrugAdministrationEntity drug = new DrugAdministrationEntity();
        drug.setId(UUID.fromString(id));
        drug.setDrugName("丙泊酚（演示）");
        drug.setDose(new BigDecimal("10"));
        drug.setUnit("mg");
        drug.setRoute("IV");
        drug.setAdministeredAt(administeredAt);
        drug.setNarcotic(false);
        return drug;
    }

    private static FluidRecordEntity fluid(String id, Instant recordedAt) {
        FluidRecordEntity fluid = new FluidRecordEntity();
        fluid.setId(UUID.fromString(id));
        fluid.setDirection("INTAKE");
        fluid.setFluidType("复方氯化钠（演示）");
        fluid.setAmountMl(500);
        fluid.setRecordedAt(recordedAt);
        return fluid;
    }

    private List<RecordEventEntity> events() {
        RecordEventEntity event = new RecordEventEntity();
        event.setId(UUID.fromString("13000000-0000-4000-8000-000000000001"));
        event.setRecordId(UUID.fromString("12000000-0000-4000-8000-000000000001"));
        event.setSeq(1);
        event.setEventType("CUSTOM");
        event.setOccurredAt(Instant.parse("2026-12-01T00:41:00Z"));
        event.setSource("MANUAL");
        event.setPayload("{\"note\":\"演示事件（合成）\",\"entryReason\":\"补录原因（演示）\"}");
        event.setCreatedAt(Instant.parse("2026-12-01T00:42:00Z"));
        return List.of(event);
    }
}
