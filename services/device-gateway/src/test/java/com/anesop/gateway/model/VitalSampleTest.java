package com.anesop.gateway.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 体征样本单测（M2-M0-03）：必填校验、质量码边界与不可变质量标记。
 */
class VitalSampleTest {

    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");

    private static VitalSample sample(String deviceId, Instant measuredAt, int quality,
            List<String> flags, String source) {
        return new VitalSample(deviceId, 1L, measuredAt, measuredAt, NOW,
                76, 98, 120, 80, null, null, quality, flags, source);
    }

    @Test
    void validatesRequiredFields() {
        assertThatThrownBy(() -> sample("", NOW, 0, List.of(), "AUTO"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deviceId");
        assertThatThrownBy(() -> sample(null, NOW, 0, List.of(), "AUTO"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sample("SIM-1", null, 0, List.of(), "AUTO"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("时间戳");
        assertThatThrownBy(() -> sample("SIM-1", NOW, -1, List.of(), "AUTO"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quality");
        assertThatThrownBy(() -> sample("SIM-1", NOW, 4, List.of(), "AUTO"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sample("SIM-1", NOW, 0, List.of(), ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
        assertThatThrownBy(() -> sample("SIM-1", NOW, 0, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsBoundaryQualityAndCopiesFlagsDefensively() {
        List<String> flags = new ArrayList<>(List.of("hr:RANGE"));
        VitalSample sample = sample("SIM-1", NOW, 3, flags, "REPLAY");
        flags.add("mutated");

        assertThat(sample.quality()).isEqualTo(3);
        assertThat(sample.qualityFlags()).containsExactly("hr:RANGE");
        assertThat(sample.qualityFlagsText()).isEqualTo("hr:RANGE");
        assertThatThrownBy(() -> sample.qualityFlags().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(sample("SIM-1", NOW, 0, null, "AUTO").qualityFlags()).isEmpty();
    }
}
