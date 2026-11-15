package com.anesop.m2.impl.vital;

import com.anesop.contracts.anesthesia.model.VitalSignSample;
import com.anesop.m2.impl.persistence.entity.VitalRow;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaVitalMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记录单体征只读端口实现（M9-01）：把采集落库的一帧展开为按指标的多条采样点。
 *
 * <p>口径（与采集域 device.yaml 一致）：
 * <ul>
 *   <li>指标编码/单位：HR(bpm)/SPO2(%)/NIBP_SYS(mmHg)/NIBP_DIA(mmHg)/ETCO2(mmHg)/TEMP(℃)；</li>
 *   <li>质量码原样透传（0 正常/1 可疑/2 缺失/3 超时）与质量标记明细，前端以质量标记展示，
 *       不静默丢弃可疑/缺失帧；</li>
 *   <li>时间窗口 {@code [from, to]} 含端点，按 measuredAt 升序；最多 2000 条，超出截断并
 *       返回 truncated=true（不静默截断）；</li>
 *   <li>兼容 0.10.0 整帧字段：HR 同时填 heartRate、NIBP_SYS/DIA 填 systolicBp/diastolicBp、
 *       SPO2 填 spo2、ETCO2 填 etco2、TEMP 填 temperature。</li>
 * </ul>
 * 只读、无 PHI（只有设备数值与时间），不修改采集链路。
 */
@Component
public class VitalSignReadAdapter implements VitalSignReadPort {

    private static final Logger LOG = LoggerFactory.getLogger(VitalSignReadAdapter.class);
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final TypeReference<List<String>> FLAGS_TYPE = new TypeReference<>() {
    };

    private final AnesthesiaVitalMapper vitalMapper;
    private final ObjectMapper objectMapper;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper 由容器管理；ObjectMapper 防御性拷贝")
    public VitalSignReadAdapter(AnesthesiaVitalMapper vitalMapper, ObjectMapper objectMapper) {
        this.vitalMapper = vitalMapper;
        this.objectMapper = objectMapper.copy();
    }

    @Override
    @Transactional(readOnly = true)
    public Window read(UUID roomId, Instant from, Instant to) {
        if (roomId == null || from == null || to == null || to.isBefore(from)) {
            return Window.empty();
        }
        // 多取 1 帧用于判定截断，避免额外的 COUNT 查询。
        List<VitalRow> rows = vitalMapper.selectVitalsByRoom(roomId, from, to, MAX_SAMPLES + 1);
        if (rows == null || rows.isEmpty()) {
            return Window.empty();
        }
        List<VitalSignSample> samples = new ArrayList<>();
        boolean truncated = rows.size() > MAX_SAMPLES;
        outer:
        for (VitalRow row : rows) {
            if (row == null || row.getMeasuredAt() == null) {
                continue;
            }
            for (VitalSignSample sample : expand(row)) {
                if (samples.size() >= MAX_SAMPLES) {
                    truncated = true;
                    break outer;
                }
                samples.add(sample);
            }
        }
        if (truncated) {
            LOG.info("记录单体征采样超过上限，已截断为前 {} 条：roomId={}", MAX_SAMPLES, roomId);
        }
        return new Window(samples, truncated);
    }

    /** 一帧展开为若干指标采样点（只输出非空指标）。 */
    private List<VitalSignSample> expand(VitalRow row) {
        List<VitalSignSample> samples = new ArrayList<>(6);
        if (row.getHr() != null) {
            VitalSignSample sample = base(row, VitalSignSample.MetricEnum.HR, "bpm",
                    BigDecimal.valueOf(row.getHr()));
            sample.setHeartRate(row.getHr());
            samples.add(sample);
        }
        if (row.getSpo2() != null) {
            VitalSignSample sample = base(row, VitalSignSample.MetricEnum.SPO2, "%",
                    BigDecimal.valueOf(row.getSpo2()));
            sample.setSpo2(row.getSpo2());
            samples.add(sample);
        }
        if (row.getNibpSys() != null) {
            VitalSignSample sample = base(row, VitalSignSample.MetricEnum.NIBP_SYS, "mmHg",
                    BigDecimal.valueOf(row.getNibpSys()));
            sample.setSystolicBp(row.getNibpSys());
            samples.add(sample);
        }
        if (row.getNibpDia() != null) {
            VitalSignSample sample = base(row, VitalSignSample.MetricEnum.NIBP_DIA, "mmHg",
                    BigDecimal.valueOf(row.getNibpDia()));
            sample.setDiastolicBp(row.getNibpDia());
            samples.add(sample);
        }
        if (row.getEtco2() != null) {
            VitalSignSample sample = base(row, VitalSignSample.MetricEnum.ETCO2, "mmHg",
                    row.getEtco2());
            sample.setEtco2(row.getEtco2().intValue());
            samples.add(sample);
        }
        if (row.getTemp() != null) {
            VitalSignSample sample = base(row, VitalSignSample.MetricEnum.TEMP, "℃", row.getTemp());
            sample.setTemperature(row.getTemp());
            samples.add(sample);
        }
        return samples;
    }

    private VitalSignSample base(VitalRow row, VitalSignSample.MetricEnum metric, String unit,
            BigDecimal value) {
        VitalSignSample sample = new VitalSignSample();
        sample.setMeasuredAt(row.getMeasuredAt().atOffset(DISPLAY_OFFSET)
                .truncatedTo(ChronoUnit.MILLIS));
        sample.setMetric(metric);
        sample.setValue(value);
        sample.setUnit(unit);
        Integer quality = row.getQuality();
        sample.setQuality(quality == null ? Integer.valueOf(0) : quality);
        sample.setQualityFlags(readFlags(row.getQualityFlags()));
        return sample;
    }

    /** 质量标记为 jsonb 数组文本；脏数据按空数组处理（不影响体征数值展示）。 */
    private List<String> readFlags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> flags = objectMapper.readValue(json, FLAGS_TYPE);
            return flags == null ? List.of() : flags;
        } catch (JsonProcessingException invalid) {
            LOG.warn("体征质量标记解析失败，按空标记返回");
            return List.of();
        }
    }
}
