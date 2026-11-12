package com.anesop.gateway.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 协议 v1 解析器：合法帧、字段缺失、时间格式与乱序、数值/质量码边界。
 *
 * <p>每条断言都对应一个可独立失败的输入，不做"只跑快乐路径"的测试。
 */
class FrameParserTest {

    private static final Instant RECEIVED = Instant.parse("2026-11-12T08:00:00Z");

    private final FrameParser parser = new FrameParser();

    @Test
    void parsesValidFrame() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|42|2026-11-12T07:59:59Z|76|98|120|80|35.0|36.7|0", RECEIVED);

        assertThat(frame.deviceId()).isEqualTo("SIM-MON-01");
        assertThat(frame.seq()).isEqualTo(42L);
        assertThat(frame.measuredAt()).isEqualTo(Instant.parse("2026-11-12T07:59:59Z"));
        assertThat(frame.receivedAt()).isEqualTo(RECEIVED);
        assertThat(frame.hr().value()).isEqualByComparingTo("76");
        assertThat(frame.spo2().value()).isEqualByComparingTo("98");
        assertThat(frame.nibpSys().value()).isEqualByComparingTo("120");
        assertThat(frame.nibpDia().value()).isEqualByComparingTo("80");
        assertThat(frame.etco2().value()).isEqualByComparingTo("35.0");
        assertThat(frame.temp().value()).isEqualByComparingTo("36.7");
        assertThat(frame.quality()).isZero();
    }

    @Test
    void parsesIso8601WithOffset() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T15:59:59+08:00|76|98|120|80|35.0|36.7|0", RECEIVED);

        assertThat(frame.measuredAt()).isEqualTo(Instant.parse("2026-11-12T07:59:59Z"));
    }

    @Test
    void parsesUnitSuffixAndAbsentValue() {
        DeviceFrame frame = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:59Z|76bpm|-|16.0kPa|98.6F|35|36.7|0", RECEIVED);

        assertThat(frame.hr().value()).isEqualByComparingTo("76");
        assertThat(frame.hr().unit()).isEqualTo("bpm");
        assertThat(frame.spo2().absent()).isTrue();
        assertThat(frame.nibpSys().value()).isEqualByComparingTo("16.0");
        assertThat(frame.nibpSys().unit()).isEqualTo("kPa");
        assertThat(frame.nibpDia().value()).isEqualByComparingTo("98.6");
    }

    @Test
    void rejectsFrameWithMissingField() {
        assertThatThrownBy(() -> parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:59Z|76|98|120|80|35.0|0", RECEIVED))
                .isInstanceOfSatisfying(FrameParseException.class,
                        error -> assertThat(error.error()).isEqualTo(FrameError.FIELD_COUNT));
    }

    @Test
    void rejectsForeignPrefix() {
        assertThatThrownBy(() -> parser.parse(
                "HL7|SIM-MON-01|1|2026-11-12T07:59:59Z|76|98|120|80|35.0|36.7|0", RECEIVED))
                .isInstanceOfSatisfying(FrameParseException.class,
                        error -> assertThat(error.error()).isEqualTo(FrameError.BAD_PREFIX));
    }

    @Test
    void rejectsNonNumericVital() {
        assertThatThrownBy(() -> parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:59Z|abc|98|120|80|35.0|36.7|0", RECEIVED))
                .isInstanceOfSatisfying(FrameParseException.class,
                        error -> assertThat(error.error()).isEqualTo(FrameError.BAD_NUMBER));
    }

    @Test
    void rejectsNegativeSequence() {
        assertThatThrownBy(() -> parser.parse(
                "DEV|SIM-MON-01|-5|2026-11-12T07:59:59Z|76|98|120|80|35.0|36.7|0", RECEIVED))
                .isInstanceOfSatisfying(FrameParseException.class,
                        error -> assertThat(error.error()).isEqualTo(FrameError.BAD_SEQ));
    }

    @Test
    void rejectsQualityOutsideDeviceRange() {
        assertThatThrownBy(() -> parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:59Z|76|98|120|80|35.0|36.7|5", RECEIVED))
                .isInstanceOfSatisfying(FrameParseException.class,
                        error -> assertThat(error.error()).isEqualTo(FrameError.BAD_QUALITY));
    }

    @Test
    void rejectsUnparseableMeasuredAt() {
        assertThatThrownBy(() -> parser.parse(
                "DEV|SIM-MON-01|1|昨天下午|76|98|120|80|35.0|36.7|0", RECEIVED))
                .isInstanceOfSatisfying(FrameParseException.class,
                        error -> assertThat(error.error()).isEqualTo(FrameError.BAD_MEASURED_AT));
    }

    @Test
    void rejectsIllegalDeviceId() {
        assertThatThrownBy(() -> parser.parse(
                "DEV|../../etc/passwd|1|2026-11-12T07:59:59Z|76|98|120|80|35.0|36.7|0", RECEIVED))
                .isInstanceOfSatisfying(FrameParseException.class,
                        error -> assertThat(error.error()).isEqualTo(FrameError.BAD_DEVICE_ID));
    }

    @Test
    void keepsOutOfOrderTimestampsAsGiven() {
        DeviceFrame later = parser.parse(
                "DEV|SIM-MON-01|2|2026-11-12T07:59:59Z|76|98|120|80|35.0|36.7|0", RECEIVED);
        DeviceFrame earlier = parser.parse(
                "DEV|SIM-MON-01|1|2026-11-12T07:59:50Z|76|98|120|80|35.0|36.7|0", RECEIVED);

        assertThat(later.measuredAt()).isAfter(earlier.measuredAt());
        assertThat(later.measuredAt()).isEqualTo(Instant.parse("2026-11-12T07:59:59Z"));
        assertThat(earlier.measuredAt()).isEqualTo(Instant.parse("2026-11-12T07:59:50Z"));
    }
}
