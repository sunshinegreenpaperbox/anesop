package com.anesop.gateway.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 指标目录单测（M2-M0-03）：单位别名、kPa/℉ 换算、未知单位与范围/小数位。
 */
class MetricTest {

    @Test
    void exposesCatalogMetadata() {
        assertThat(Metric.HR.code()).isEqualTo("hr");
        assertThat(Metric.HR.canonicalUnit()).isEqualTo("bpm");
        assertThat(Metric.SPO2.scale()).isZero();
        assertThat(Metric.TEMP.scale()).isEqualTo(1);
        assertThat(Metric.ETCO2.low()).isEqualByComparingTo("0");
        assertThat(Metric.ETCO2.high()).isEqualByComparingTo("150");
        assertThat(Metric.TEMP.canonicalUnit()).isEqualTo("C");
    }

    @Test
    void convertsCanonicalAndAliasUnitsUnchanged() {
        assertThat(Metric.HR.convert(new BigDecimal("70"), null)).contains(new BigDecimal("70"));
        assertThat(Metric.HR.convert(new BigDecimal("70"), " ")).contains(new BigDecimal("70"));
        assertThat(Metric.HR.convert(new BigDecimal("70"), "BPM")).contains(new BigDecimal("70"));
        assertThat(Metric.HR.convert(new BigDecimal("70"), "/min")).contains(new BigDecimal("70"));
        assertThat(Metric.HR.convert(new BigDecimal("70"), "1/min")).contains(new BigDecimal("70"));
        assertThat(Metric.HR.convert(new BigDecimal("70"), "min-1")).contains(new BigDecimal("70"));
        assertThat(Metric.SPO2.convert(new BigDecimal("98"), "pct")).contains(new BigDecimal("98"));
        assertThat(Metric.NIBP_SYS.convert(new BigDecimal("120"), "torr"))
                .contains(new BigDecimal("120"));
        assertThat(Metric.TEMP.convert(new BigDecimal("36.7"), "celsius"))
                .contains(new BigDecimal("36.7"));
        assertThat(Metric.TEMP.convert(new BigDecimal("36.7"), "°c"))
                .contains(new BigDecimal("36.7"));
    }

    @Test
    void convertsKpaOnlyForPressureFamily() {
        assertThat(Metric.NIBP_SYS.convert(new BigDecimal("16"), "kPa"))
                .contains(new BigDecimal("120.00992"));
        assertThat(Metric.ETCO2.convert(new BigDecimal("4.7"), "kpa")).isPresent();
        assertThat(Metric.HR.convert(new BigDecimal("16"), "kPa")).isEmpty();
        assertThat(Metric.TEMP.convert(new BigDecimal("16"), "kPa")).isEmpty();
    }

    @Test
    void convertsFahrenheitOnlyForTemperature() {
        assertThat(Metric.TEMP.convert(new BigDecimal("98.6"), "F"))
                .contains(new BigDecimal("37.0000"));
        assertThat(Metric.TEMP.convert(new BigDecimal("98.6"), "°F")).isPresent();
        assertThat(Metric.TEMP.convert(new BigDecimal("98.6"), "fahrenheit")).isPresent();
        assertThat(Metric.HR.convert(new BigDecimal("98.6"), "F")).isEmpty();
    }

    @Test
    void rejectsUnknownUnitsAndNullValues() {
        assertThat(Metric.HR.convert(new BigDecimal("70"), "furlong")).isEmpty();
        assertThat(Metric.HR.convert(null, "bpm")).isEmpty();
        assertThat(Metric.SPO2.convert(new BigDecimal("98"), "mmol")).isEmpty();
    }

    @Test
    void rangeAndCanonicalRoundingAreInclusive() {
        assertThat(Metric.HR.inRange(new BigDecimal("20"))).isTrue();
        assertThat(Metric.HR.inRange(new BigDecimal("300"))).isTrue();
        assertThat(Metric.HR.inRange(new BigDecimal("19.9"))).isFalse();
        assertThat(Metric.HR.inRange(new BigDecimal("300.1"))).isFalse();
        assertThat(Metric.TEMP.canonical(new BigDecimal("36.74"))).isEqualByComparingTo("36.7");
        assertThat(Metric.TEMP.canonical(new BigDecimal("36.75"))).isEqualByComparingTo("36.8");
    }
}
