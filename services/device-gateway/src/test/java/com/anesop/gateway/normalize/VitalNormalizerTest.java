package com.anesop.gateway.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.gateway.model.VitalSample;
import com.anesop.gateway.protocol.DeviceFrame;
import com.anesop.gateway.protocol.FrameParser;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 标准化器：单位换算、物理范围、质量标记、时钟偏差三条分支。
 */
class VitalNormalizerTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-12T08:00:00Z");

    private final FrameParser parser = new FrameParser();
    private final VitalNormalizer normalizer = new VitalNormalizer(5000L, 120_000L);

    @Test
    void keepsNormalFrameUntouched() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:59Z|76|98|120|80|35.0|36.7|0", RECEIVED);

        VitalSample sample = normalizer.normalize(frame, "AUTO");

        assertThat(sample.measuredAt()).isEqualTo(frame.measuredAt());
        assertThat(sample.measuredAtCorrected()).isEqualTo(frame.measuredAt());
        assertThat(sample.hr()).isEqualTo(76);
        assertThat(sample.spo2()).isEqualTo(98);
        assertThat(sample.nibpSys()).isEqualTo(120);
        assertThat(sample.etco2()).isEqualByComparingTo("35.0");
        assertThat(sample.temp()).isEqualByComparingTo("36.7");
        assertThat(sample.quality()).isZero();
        assertThat(sample.qualityFlags()).isEmpty();
    }

    @Test
    void convertsKilopascalToMillimetreOfMercury() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:59Z|76|98|16.0kPa|10.7kPa|35|36.7|0", RECEIVED);

        VitalSample sample = normalizer.normalize(frame, "AUTO");

        assertThat(sample.nibpSys()).isEqualTo(120);
        assertThat(sample.nibpDia()).isEqualTo(80);
        assertThat(sample.quality()).isZero();
        assertThat(sample.qualityFlags()).isEmpty();
    }

    @Test
    void convertsFahrenheitToCelsius() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:59Z|76|98|120|80|35|98.6F|0", RECEIVED);

        VitalSample sample = normalizer.normalize(frame, "AUTO");

        assertThat(sample.temp()).isEqualByComparingTo("37.0");
        assertThat(sample.quality()).isZero();
    }

    @Test
    void marksOutOfRangeValueSuspectButKeepsIt() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:59Z|400|98|120|80|35|36.7|0", RECEIVED);

        VitalSample sample = normalizer.normalize(frame, "AUTO");

        assertThat(sample.hr()).isEqualTo(400);
        assertThat(sample.quality()).isEqualTo(1);
        assertThat(sample.qualityFlags()).contains("hr:RANGE");
    }

    @Test
    void marksUnsupportedUnitSuspectAndDropsValue() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:59Z|76|98|120|80|35mmol|36.7|0", RECEIVED);

        VitalSample sample = normalizer.normalize(frame, "AUTO");

        assertThat(sample.etco2()).isNull();
        assertThat(sample.quality()).isEqualTo(1);
        assertThat(sample.qualityFlags()).contains("etco2:UNIT");
    }

    @Test
    void neverUpgradesDeviceReportedMissingQuality() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:59Z|-|-|-|-|-|-|2", RECEIVED);

        VitalSample sample = normalizer.normalize(frame, "AUTO");

        assertThat(sample.quality()).isEqualTo(2);
        assertThat(sample.qualityFlags()).contains("hr:MISSING", "spo2:MISSING", "etco2:MISSING");
    }

    @Test
    void correctsFutureClockIntoCorrectedTimestampOnly() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T08:00:30Z|76|98|120|80|35|36.7|0", RECEIVED);

        VitalSample sample = normalizer.normalize(frame, "AUTO");

        assertThat(sample.measuredAt()).as("幂等键必须保留设备原值").isEqualTo(frame.measuredAt());
        assertThat(sample.measuredAtCorrected()).isEqualTo(RECEIVED);
        assertThat(sample.quality()).isEqualTo(1);
        assertThat(sample.qualityFlags()).contains("ts:FUTURE:30000");
    }

    @Test
    void correctsSmallPastSkewIntoCorrectedTimestampOnly() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:30Z|76|98|120|80|35|36.7|0", RECEIVED);

        VitalSample sample = normalizer.normalize(frame, "AUTO");

        assertThat(sample.measuredAt()).isEqualTo(frame.measuredAt());
        assertThat(sample.measuredAtCorrected()).isEqualTo(RECEIVED);
        assertThat(sample.qualityFlags()).contains("ts:SKEW:-30000");
    }

    @Test
    void keepsBackfillTimestampAndFlagsIt() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:50:00Z|76|98|120|80|35|36.7|0", RECEIVED);

        VitalSample sample = normalizer.normalize(frame, "AUTO");

        assertThat(sample.measuredAt()).isEqualTo(Instant.parse("2026-11-12T07:50:00Z"));
        assertThat(sample.measuredAtCorrected()).isEqualTo(sample.measuredAt());
        assertThat(sample.quality()).isEqualTo(1);
        assertThat(sample.qualityFlags()).contains("ts:BACKFILL:-600000");
    }

    @Test
    void rejectsInvalidSkewConfiguration() {
        assertThatThrownBy(() -> new VitalNormalizer(10_000L, 5_000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
