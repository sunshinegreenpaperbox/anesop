package com.anesop.m1.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class InpatientNoHasherTest {

    /** Base64("synthetic-pepper-0123456789-anesop-m1-test")，44 字节 SYNTHETIC 测试值。 */
    private static final String PEPPER =
            "c3ludGhldGljLXBlcHBlci0wMTIzNDU2Nzg5LWFuZXNvcC1tMS10ZXN0";

    @Test
    void hashesDeterministicallyWithPepper() {
        InpatientNoHasher hasher = new InpatientNoHasher(PEPPER, false);
        String first = hasher.hash("SYNTH-ZY-0001");
        String second = hasher.hash("SYNTH-ZY-0001");
        assertThat(first).hasSize(64).isEqualTo(second);
        assertThat(first).isNotEqualTo(hasher.hash("SYNTH-ZY-0002"));
        assertThat(first).doesNotContain("SYNTH");
    }

    @Test
    void rejectsMissingPepperByDefault() {
        assertThatThrownBy(() -> new InpatientNoHasher("", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANESOP_INPATIENT_PEPPER");
    }

    @Test
    void rejectsInvalidBase64Pepper() {
        assertThatThrownBy(() -> new InpatientNoHasher("not-base64-%%%", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void rejectsShortPepper() {
        String shortPepper = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new InpatientNoHasher(shortPepper, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void generatesRandomPepperOnlyWhenExplicitlyAllowed() {
        InpatientNoHasher hasher = new InpatientNoHasher("", true);
        assertThat(hasher.hash("SYNTH-ZY-0001")).hasSize(64);
    }

    @Test
    void rejectsBlankInpatientNo() {
        InpatientNoHasher hasher = new InpatientNoHasher(PEPPER, false);
        assertThatThrownBy(() -> hasher.hash(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applicationContextFailsFastWhenPepperMissingInProdSemantics() {
        new ApplicationContextRunner()
                .withUserConfiguration(HasherConfiguration.class)
                .withPropertyValues("anesop.m1.inpatient-pepper.allow-dev-generated=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void applicationContextStartsWithGeneratedPepperInDevMode() {
        new ApplicationContextRunner()
                .withUserConfiguration(HasherConfiguration.class)
                .withPropertyValues("anesop.m1.inpatient-pepper.allow-dev-generated=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    @Import(InpatientNoHasher.class)
    static class HasherConfiguration {
    }
}
