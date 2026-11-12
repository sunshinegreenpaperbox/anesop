package com.anesop.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link TestDataFactory} 的确定性、SYNTH- 前缀与输入校验。 */
class TestDataFactoryTest {

    @Test
    void userIsDeterministicAndSynthetic() {
        TestDataFactory.SyntheticUser first = TestDataFactory.user("anesthesiologist-1");
        TestDataFactory.SyntheticUser second = TestDataFactory.user("anesthesiologist-1");

        assertThat(first).isEqualTo(second);
        assertThat(first.username()).startsWith(TestDataFactory.SYNTHETIC_PREFIX);
        assertThat(TestDataFactory.isSynthetic(first.username())).isTrue();
        assertThat(first.id()).isNotBlank();
    }

    @Test
    void differentKeysProduceDifferentIds() {
        assertThat(TestDataFactory.user("a").id()).isNotEqualTo(TestDataFactory.user("b").id());
    }

    @Test
    void patientAndCaseCarrySyntheticNumbers() {
        TestDataFactory.SyntheticPatient patient = TestDataFactory.patient("p-001");
        TestDataFactory.SyntheticCase anesthesiaCase = TestDataFactory.anesthesiaCase("c-001", patient);

        assertThat(patient.patientNo()).startsWith(TestDataFactory.SYNTHETIC_PREFIX);
        assertThat(anesthesiaCase.caseNo()).startsWith(TestDataFactory.SYNTHETIC_PREFIX);
        assertThat(anesthesiaCase.patientId()).isEqualTo(patient.id());
        assertThat(anesthesiaCase.scheduledAt().getYear()).isEqualTo(2026);
    }

    @Test
    void rejectsKeysThatCouldLeakUnsafeCharacters() {
        assertThatThrownBy(() -> TestDataFactory.user("张三"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestDataFactory.user("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
