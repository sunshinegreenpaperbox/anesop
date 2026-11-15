package com.anesop.m2.impl.vital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.VitalSignSample;
import com.anesop.m2.impl.persistence.entity.VitalRow;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaVitalMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 记录单体征只读端口单测（M9-01）：空窗口、指标展开、质量标记、上限截断、脏标记降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VitalSignReadAdapterTest {

    private static final UUID ROOM = UUID.fromString("f2000000-0000-4000-8000-0000000000c1");
    private static final Instant FROM = Instant.parse("2026-12-01T08:00:00Z");
    private static final Instant TO = Instant.parse("2026-12-01T09:00:00Z");

    @Mock
    private AnesthesiaVitalMapper vitalMapper;

    private VitalSignReadAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new VitalSignReadAdapter(vitalMapper, new ObjectMapper());
    }

    @Test
    void emptyWindowForMissingArguments() {
        assertThat(adapter.read(null, FROM, TO).samples()).isEmpty();
        assertThat(adapter.read(ROOM, null, TO).samples()).isEmpty();
        assertThat(adapter.read(ROOM, TO, FROM).truncated()).isFalse();
    }

    @Test
    void expandsEachFrameToMetricsWithQualityAndFlags() {
        when(vitalMapper.selectVitalsByRoom(any(), any(), any(), anyInt()))
                .thenReturn(List.of(row(1, FROM.plusSeconds(60), 72, 98, 1,
                        "[\"SIGNAL_LOSS\"]")));

        VitalSignReadPort.Window window = adapter.read(ROOM, FROM, TO);

        assertThat(window.truncated()).isFalse();
        assertThat(window.samples()).hasSize(6);
        assertThat(window.samples()).extracting(VitalSignSample::getMetric)
                .containsExactly(VitalSignSample.MetricEnum.HR, VitalSignSample.MetricEnum.SPO2,
                        VitalSignSample.MetricEnum.NIBP_SYS, VitalSignSample.MetricEnum.NIBP_DIA,
                        VitalSignSample.MetricEnum.ETCO2, VitalSignSample.MetricEnum.TEMP);
        VitalSignSample hr = window.samples().get(0);
        assertThat(hr.getValue()).isEqualByComparingTo(BigDecimal.valueOf(72));
        assertThat(hr.getUnit()).isEqualTo("bpm");
        assertThat(hr.getQuality()).isEqualTo(1);
        assertThat(hr.getQualityFlags()).containsExactly("SIGNAL_LOSS");
        assertThat(hr.getHeartRate()).isEqualTo(72);
    }

    @Test
    void malformedFlagsDegradeToEmptyWithoutFailing() {
        when(vitalMapper.selectVitalsByRoom(any(), any(), any(), anyInt()))
                .thenReturn(List.of(row(1, FROM, 70, 98, 0, "not-json")));

        assertThat(adapter.read(ROOM, FROM, TO).samples().get(0).getQualityFlags()).isEmpty();
    }

    @Test
    void truncatesAtTwoThousandSamples() {
        List<VitalRow> rows = new ArrayList<>();
        for (int index = 0; index < 2001; index++) {
            rows.add(row(index, FROM.plusSeconds(index), 70, 98, 0, "[]"));
        }
        when(vitalMapper.selectVitalsByRoom(any(), any(), any(), anyInt())).thenReturn(rows);

        VitalSignReadPort.Window window = adapter.read(ROOM, FROM, TO);

        assertThat(window.truncated()).isTrue();
        assertThat(window.samples()).hasSize(2000);
    }

    private static VitalRow row(long seq, Instant measuredAt, int hr, int spo2, int quality,
            String flags) {
        VitalRow row = new VitalRow();
        row.setSeq(seq);
        row.setMeasuredAt(measuredAt);
        row.setHr(hr);
        row.setSpo2(spo2);
        row.setNibpSys(120);
        row.setNibpDia(80);
        row.setEtco2(new BigDecimal("35.0"));
        row.setTemp(new BigDecimal("36.7"));
        row.setQuality(quality);
        row.setQualityFlags(flags);
        return row;
    }
}
