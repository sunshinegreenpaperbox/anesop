package com.anesop.m2.impl.alert;

import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.impl.config.AlertProperties;
import com.anesop.m2.impl.persistence.entity.AlertEntity;
import com.anesop.m2.impl.persistence.entity.AlertRuleEntity;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.DeviceRegistryRow;
import com.anesop.m2.impl.persistence.entity.VitalRow;
import com.anesop.m2.impl.persistence.mapper.AlertMapper;
import com.anesop.m2.impl.persistence.mapper.AlertRuleMapper;
import com.anesop.m2.impl.persistence.mapper.AlertWatermarkMapper;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaVitalMapper;
import com.anesop.m2.impl.persistence.mapper.DeviceRegistryMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 告警增量评估器（M3-M2-02 评审 B-1/B-2/B-3）：m2 模块内部组件，无用户数据范围入参。
 *
 * <p>调用者负责数据范围校验（手动评估/查询）或活动记录匹配（采集落库事件）。
 * 评估只针对 {@code status=ONGOING 且 startedAt != null} 的记录（DRAFT 一律返回空）；
 * 窗口从 {@code startedAt} 起，<b>不使用 createdAt 兜底</b>，避免把同术间上一台次患者的
 * 体征算入本记录。
 *
 * <p>水位增量：
 * <ul>
 *   <li>读取 {@code alert_watermark.evaluated_through}（已评估到的最大 measured_at）；</li>
 *   <li>采样窗口 = {@code max(startedAt, 水位 − 回看窗口) .. end}，回看窗口由
 *       {@link AlertProperties#lookbackSeconds()} 给出（最大规则时长 + 最大指标间隔容忍），
 *       只用于保持跨事件越界窗口连续；</li>
 *   <li>只落库 {@code triggeredAt > 水位} 的判定，不用当前规则回评历史窗口；</li>
 *   <li>评估后把水位推进到本次读到的最大 measured_at（只增）。</li>
 * </ul>
 *
 * <p>审计 {@code ALERT_TRIGGERED}：resourceType=ALERT，resourceId=告警主键（S-1）；
 * 自动评估 actor 为空（actorName=alert-engine），手工评估记录调用人。
 * detail 只含标识、规则编码/版本、指标与数值，不含患者标识与原因原文。
 */
@Component
public class AlertEvaluator {

    private static final Logger LOG = LoggerFactory.getLogger(AlertEvaluator.class);

    private static final String RESOURCE_ALERT = "ALERT";
    private static final String ACTION_TRIGGERED = "ALERT_TRIGGERED";
    private static final String AUDIT_ACTOR_ENGINE = "alert-engine";
    private static final String STATUS_ONGOING = "ONGOING";
    private static final String STATUS_TRIGGERED = "TRIGGERED";

    private final AlertMapper alertMapper;
    private final AlertRuleMapper ruleMapper;
    private final AlertWatermarkMapper watermarkMapper;
    private final AnesthesiaVitalMapper vitalMapper;
    private final DeviceRegistryMapper registryMapper;
    private final AlertRuleEngine engine;
    private final AlertProperties properties;
    private final AuditWriter auditWriter;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/组件由容器管理，评估器不修改其内部状态")
    public AlertEvaluator(AlertMapper alertMapper, AlertRuleMapper ruleMapper,
            AlertWatermarkMapper watermarkMapper, AnesthesiaVitalMapper vitalMapper,
            DeviceRegistryMapper registryMapper, AlertRuleEngine engine,
            AlertProperties properties, AuditWriter auditWriter, Clock clock) {
        this.alertMapper = alertMapper;
        this.ruleMapper = ruleMapper;
        this.watermarkMapper = watermarkMapper;
        this.vitalMapper = vitalMapper;
        this.registryMapper = registryMapper;
        this.engine = engine;
        this.properties = properties;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    /**
     * 对一张记录单做一次增量评估。
     *
     * @param record  记录单（调用者已完成数据范围/活动状态校验）
     * @param trigger 触发来源与审计 actor（自动/手动）
     */
    public EvaluationSummary evaluate(AnesthesiaRecordEntity record, EvaluationTrigger trigger) {
        Instant now = truncatedNow();
        if (record == null || !STATUS_ONGOING.equals(record.getStatus())
                || record.getStartedAt() == null) {
            // DRAFT / 未开始 / 已结束（无 startedAt）一律不评估：返回空，不产生告警。
            return EvaluationSummary.empty(currentWatermark(record));
        }
        Instant startedAt = record.getStartedAt();
        Instant end = record.getEndedAt() != null ? record.getEndedAt() : now;
        if (end.isBefore(startedAt)) {
            return EvaluationSummary.empty(currentWatermark(record));
        }
        Instant watermark = watermarkMapper.selectEvaluatedThrough(record.getId());
        Instant emitAfter = watermark == null ? startedAt
                : (watermark.isAfter(startedAt) ? watermark : startedAt);
        Instant effectiveFrom = emitAfter.minusSeconds(properties.lookbackSeconds());
        if (effectiveFrom.isBefore(startedAt)) {
            effectiveFrom = startedAt;
        }
        List<AlertRuleEntity> rules = ruleMapper.selectEnabledRules();
        if (rules == null) {
            rules = List.of();
        }
        List<AlertSample> samples = samplesOf(record.getRoomId(), effectiveFrom, end);
        List<AlertDecision> decisions = engine.evaluate(record.getId(), rules, samples, emitAfter);
        int created = 0;
        for (AlertDecision decision : decisions) {
            AlertEntity entity = toEntity(record.getId(), decision, now);
            int inserted = alertMapper.insertIgnoreDuplicate(entity);
            if (inserted == 1) {
                created++;
                auditTriggered(record, entity, trigger);
            }
        }
        Instant evaluatedThrough = maxMeasuredAt(samples);
        Instant nextWatermark = evaluatedThrough == null || !evaluatedThrough.isAfter(emitAfter)
                ? watermark
                : evaluatedThrough;
        if (nextWatermark != null) {
            watermarkMapper.advance(record.getId(), nextWatermark, now);
        }
        return new EvaluationSummary(rules.size(), samples.size(), created, nextWatermark);
    }

    /**
     * 读取记录单术间在窗口内的体征采样（m2 自己的 device_registry / anesthesia_vital）。
     *
     * <p>术间为空（台次未排术间 / 记录创建时快照缺失）时没有可评估采样；不跨模块读 m1 表。
     */
    private List<AlertSample> samplesOf(UUID roomId, Instant from, Instant to) {
        if (roomId == null) {
            return List.of();
        }
        List<DeviceRegistryRow> devices = registryMapper.selectDevices(null, roomId);
        List<AlertSample> samples = new ArrayList<>();
        for (DeviceRegistryRow device : devices) {
            List<VitalRow> rows = vitalMapper.selectAlertSamples(device.getGatewayId(),
                    device.getDeviceId(), from, to, properties.maxSamples());
            if (rows.size() >= properties.maxSamples()) {
                LOG.warn("体征采样达到评估上限，已截断为最近 {} 帧：deviceId={}", rows.size(),
                        device.getDeviceId());
            }
            for (VitalRow row : rows) {
                samples.add(AlertSample.from(row));
            }
        }
        return samples;
    }

    private AlertEntity toEntity(UUID recordId, AlertDecision decision, Instant now) {
        AlertEntity entity = new AlertEntity();
        entity.setId(UUID.randomUUID());
        entity.setRecordId(recordId);
        entity.setRuleId(decision.ruleId());
        entity.setRuleCode(decision.ruleCode());
        entity.setRuleVersion(decision.ruleVersion());
        entity.setMetric(decision.metric());
        entity.setValue(decision.value());
        entity.setUnit(decision.unit());
        entity.setSeverity(decision.severity());
        entity.setTriggeredAt(decision.triggeredAt());
        entity.setDedupeKey(decision.dedupeKey());
        entity.setStatus(STATUS_TRIGGERED);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private void auditTriggered(AnesthesiaRecordEntity record, AlertEntity alert,
            EvaluationTrigger trigger) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_TRIGGERED);
        detail.put("alertId", alert.getId().toString());
        detail.put("recordId", record.getId().toString());
        detail.put("ruleCode", alert.getRuleCode());
        detail.put("ruleVersion", alert.getRuleVersion());
        detail.put("metric", alert.getMetric());
        detail.put("value", alert.getValue());
        detail.put("severity", alert.getSeverity());
        detail.put("triggeredAt", alert.getTriggeredAt().toString());
        detail.put("triggerSource", trigger.source());
        auditWriter.record(ACTION_TRIGGERED, RESOURCE_ALERT, alert.getId().toString(), true, detail,
                trigger.actorId(), trigger.actorName(), trigger.context());
    }

    private Instant currentWatermark(AnesthesiaRecordEntity record) {
        return record == null ? null : watermarkMapper.selectEvaluatedThrough(record.getId());
    }

    private static Instant maxMeasuredAt(List<AlertSample> samples) {
        Instant max = null;
        for (AlertSample sample : samples) {
            if (sample.measuredAt() != null
                    && (max == null || sample.measuredAt().isAfter(max))) {
                max = sample.measuredAt();
            }
        }
        return max;
    }

    private Instant truncatedNow() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** 一次增量评估的摘要（规则数、采样帧数、新增告警数、评估后水位）。 */
    public record EvaluationSummary(int rules, int frames, int created, Instant evaluatedThrough) {

        static EvaluationSummary empty(Instant watermark) {
            return new EvaluationSummary(0, 0, 0, watermark);
        }

        /** 兼容旧调用（无水位场景）。 */
        public static EvaluationSummary of(int rules, int frames, int created) {
            return new EvaluationSummary(rules, frames, created, null);
        }
    }

    /** 评估触发来源与审计 actor；自动触发不绑定真人身份。 */
    public record EvaluationTrigger(String source, UUID actorId, String actorName,
            AuthRequestContext context) {

        private static final EvaluationTrigger SYSTEM =
                new EvaluationTrigger("INGEST_EVENT", null, AUDIT_ACTOR_ENGINE, null);

        /** 采集落库后的领域事件（AFTER_COMMIT）触发。 */
        public static EvaluationTrigger system() {
            return SYSTEM;
        }

        /** 手动/补偿评估：记录调用人（S-1）。 */
        public static EvaluationTrigger manual(AuthPrincipal caller, AuthRequestContext context) {
            return new EvaluationTrigger("MANUAL", caller == null ? null : caller.userId(),
                    caller == null ? AUDIT_ACTOR_ENGINE : caller.username(), context);
        }
    }
}
