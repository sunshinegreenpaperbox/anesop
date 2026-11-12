package com.anesop.m1.impl.his;

import static org.assertj.core.api.Assertions.assertThat;

import com.anesop.m1.api.his.HisSurgeryApplicationBatch;
import com.anesop.m1.api.his.HisSurgeryApplicationRecord;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MockHisApplicationAdapterTest {

    private static final UUID DEPT_ID = UUID.fromString("f1000000-0000-4000-8000-000000000002");

    @Test
    void generatesThreeToFiveSyntheticRecordsDeterministically() {
        MockHisApplicationAdapter adapter = new MockHisApplicationAdapter(
                20260101L, "演示外科", DEPT_ID.toString(), "");

        HisSurgeryApplicationBatch first = adapter.pull(3, 42L);
        HisSurgeryApplicationBatch second = adapter.pull(3, 42L);

        assertThat(first.simulated()).isTrue();
        assertThat(first.adapter()).isEqualTo("MOCK_HIS");
        assertThat(first.applications()).hasSize(3);
        assertThat(second.applications()).extracting(HisSurgeryApplicationRecord::hisApplicationNo)
                .containsExactlyElementsOf(first.applications().stream()
                        .map(HisSurgeryApplicationRecord::hisApplicationNo).toList());
        assertThat(first.applications()).allSatisfy(record -> {
            assertThat(record.hisApplicationNo()).startsWith("SYNTH-HIS-APP-");
            assertThat(record.hisPatientNo()).startsWith("SYNTH-P-");
            assertThat(record.inpatientNo()).startsWith("SYNTH-ZY-");
            assertThat(record.patientName()).startsWith("演示患者");
            assertThat(record.deptId()).isEqualTo(DEPT_ID);
            assertThat(record.plannedDurationMin()).isPositive();
            assertThat(record.urgency()).isIn("ELECTIVE", "URGENT", "EMERGENCY");
        });
    }

    @Test
    void returnsExactlyRequestedCountWithinOneToFive() {
        MockHisApplicationAdapter adapter = new MockHisApplicationAdapter(1L, "演示外科", "", "");

        assertThat(adapter.pull(1, null).applications()).hasSize(1);
        assertThat(adapter.pull(4, null).applications()).hasSize(4);
        assertThat(adapter.pull(5, null).applications()).hasSize(5);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.pull(0, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.pull(6, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidConfiguredDepartmentId() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new MockHisApplicationAdapter(1L, "演示外科", "not-a-uuid", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsBlankOptionalIds() {
        MockHisApplicationAdapter adapter = new MockHisApplicationAdapter(1L, "演示外科", " ", " ");
        List<HisSurgeryApplicationRecord> records = adapter.pull(3, null).applications();
        assertThat(records).allSatisfy(record -> assertThat(record.deptId()).isNull());
    }
}
