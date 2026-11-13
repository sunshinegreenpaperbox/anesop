package com.anesop.m2.impl.alert;

import com.anesop.contracts.anesthesia.model.AcknowledgeAlertRequest;
import com.anesop.contracts.anesthesia.model.Alert;
import com.anesop.contracts.anesthesia.model.AlertEvaluationResult;
import com.anesop.contracts.anesthesia.model.AlertList;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.alert.AlertService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.persistence.entity.AlertEntity;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.mapper.AlertMapper;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 告警服务实现（M3-M2-02，契约 anesthesia.yaml 0.7.0）。
 *
 * <p>触发入口（评审 B-1）：采集 ingest 成功提交后由 {@code @TransactionalEventListener
 * (AFTER_COMMIT)} 调用 {@link AlertEvaluator} 对 {@code status=ONGOING 且 startedAt != null}
 * 的记录做增量评估；记录页 5–10s 轮询告警列表作为兜底。读取告警列表<b>不再评估</b>，
 * 避免“读旧记录时用当前规则重评历史窗口”；{@link #evaluate} 仅用于调试/补偿，同样按水位
 * 只处理新采样。
 *
 * <p>关键约束：
 * <ul>
 *   <li>窗口 = 记录单 startedAt..(endedAt 或 now)，不使用 createdAt 兜底；DRAFT 返回空；</li>
 *   <li>数据源 = 记录单创建时快照的 roomId（V16）绑定的设备；不跨模块读 m1 表；</li>
 *   <li>去重键 recordId:ruleCode:窗口起始秒，唯一索引兜底；重复评估不重复插入；</li>
 *   <li>告警状态机只允许一次流转，条件更新（version + status）失败即 409；</li>
 *   <li>数据范围：记录单/台次不可见统一 404；审计不含 PHI 与处理原因原文。</li>
 * </ul>
 */
@Service
public class AlertServiceImpl implements AlertService {

    private static final String ACTION_ACK = "ALERT_ACK";
    private static final String RESOURCE_ALERT = "ALERT";
    private static final String STATUS_TRIGGERED = "TRIGGERED";
    private static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    private static final String STATUS_IGNORED = "IGNORED";
    private static final String DECISION_CONFIRMED = "CONFIRMED";
    private static final int MAX_REASON = 500;
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);

    private final AlertMapper alertMapper;
    private final AnesthesiaRecordMapper recordMapper;
    private final SchedulingService schedulingService;
    private final AlertEvaluator evaluator;
    private final AuditWriter auditWriter;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public AlertServiceImpl(AlertMapper alertMapper, AnesthesiaRecordMapper recordMapper,
            SchedulingService schedulingService, AlertEvaluator evaluator, AuditWriter auditWriter,
            Clock clock) {
        this.alertMapper = alertMapper;
        this.recordMapper = recordMapper;
        this.schedulingService = schedulingService;
        this.evaluator = evaluator;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    @Override
    public AlertList listAlerts(String recordId, AuthPrincipal caller) {
        AnesthesiaRecordEntity record = requireVisibleRecord(recordId, caller);
        List<Alert> items = new ArrayList<>();
        for (AlertEntity entity : alertMapper.selectByRecord(record.getId())) {
            items.add(toDto(entity));
        }
        AlertList result = new AlertList();
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlertEvaluationResult evaluate(String recordId, AuthPrincipal caller,
            AuthRequestContext context) {
        AnesthesiaRecordEntity record = requireVisibleRecord(recordId, caller);
        AlertEvaluator.EvaluationSummary summary = evaluator.evaluate(record,
                AlertEvaluator.EvaluationTrigger.manual(caller, context));
        AlertEvaluationResult result = new AlertEvaluationResult();
        result.setRecordId(record.getId().toString());
        result.setEvaluatedRules(summary.rules());
        result.setSampledFrames(summary.frames());
        result.setCreatedAlerts(summary.created());
        result.setEvaluatedThrough(offset(summary.evaluatedThrough()));
        result.setEvaluatedAt(offset(truncatedNow()));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Alert acknowledge(String alertId, AcknowledgeAlertRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        UUID id = parseUuid(alertId);
        AlertEntity alert = id == null ? null : alertMapper.selectById(id);
        if (alert == null) {
            throw new NoSuchElementException("告警不存在或不可见");
        }
        requireVisibleRecord(alert.getRecordId().toString(), caller);
        if (request == null || request.getDecision() == null) {
            throw new M2ValidationException("decision", "处理决定不能为空");
        }
        String decision = request.getDecision().getValue();
        String reason = requireReason(request.getReason());
        if (request.getVersion() == null) {
            throw new M2ValidationException("version", "版本不能为空");
        }
        if (!STATUS_TRIGGERED.equals(alert.getStatus())) {
            throw new M2ConflictException("告警已处理，不能重复处理");
        }
        if (!request.getVersion().equals(alert.getVersion() == null ? null
                : alert.getVersion().intValue())) {
            throw new M2ConflictException("告警版本已过期，请刷新后重试");
        }
        String nextStatus = DECISION_CONFIRMED.equals(decision) ? STATUS_ACKNOWLEDGED
                : STATUS_IGNORED;
        Instant now = truncatedNow();
        int updated = alertMapper.acknowledge(alert.getId(), alert.getVersion(), nextStatus,
                caller == null ? null : caller.userId(), now, reason);
        if (updated == 0) {
            throw new M2ConflictException("告警已处理或版本已过期，请刷新后重试");
        }
        auditAck(alert, nextStatus, decision, caller, context);
        return toDto(alertMapper.selectById(alert.getId()));
    }

    // ------------------------------------------------------------- helpers --

    private AnesthesiaRecordEntity requireVisibleRecord(String recordId, AuthPrincipal caller) {
        UUID id = parseUuid(recordId);
        AnesthesiaRecordEntity record = id == null ? null : recordMapper.selectById(id);
        if (record == null) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        try {
            schedulingService.get(record.getScheduleId().toString(), caller);
        } catch (NoSuchElementException | org.springframework.security.access.AccessDeniedException
                notVisible) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        return record;
    }

    private void auditAck(AlertEntity alert, String status, String decision, AuthPrincipal caller,
            AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_ACK);
        detail.put("alertId", alert.getId().toString());
        detail.put("recordId", alert.getRecordId().toString());
        detail.put("ruleCode", alert.getRuleCode());
        detail.put("ruleVersion", alert.getRuleVersion());
        detail.put("metric", alert.getMetric());
        detail.put("severity", alert.getSeverity());
        detail.put("decision", decision);
        detail.put("status", status);
        auditWriter.record(ACTION_ACK, RESOURCE_ALERT, alert.getId().toString(), true, detail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
    }

    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new M2ValidationException("reason", "处理原因不能为空");
        }
        if (trimmed.length() > MAX_REASON) {
            throw new M2ValidationException("reason", "处理原因长度超出限制");
        }
        return trimmed;
    }

    private Alert toDto(AlertEntity entity) {
        Alert dto = new Alert();
        dto.setAlertId(entity.getId().toString());
        dto.setRecordId(entity.getRecordId().toString());
        dto.setRuleId(entity.getRuleId().toString());
        dto.setRuleCode(entity.getRuleCode());
        dto.setRuleVersion(entity.getRuleVersion());
        dto.setMetric(Alert.MetricEnum.fromValue(entity.getMetric()));
        dto.setValue(entity.getValue());
        dto.setUnit(entity.getUnit());
        dto.setSeverity(Alert.SeverityEnum.fromValue(entity.getSeverity()));
        dto.setTriggeredAt(offset(entity.getTriggeredAt()));
        dto.setStatus(Alert.StatusEnum.fromValue(entity.getStatus()));
        dto.setAcknowledgedBy(string(entity.getAcknowledgedBy()));
        dto.setAcknowledgedAt(offset(entity.getAcknowledgedAt()));
        dto.setAcknowledgeReason(entity.getAcknowledgeReason());
        dto.setDedupeKey(entity.getDedupeKey());
        dto.setVersion(entity.getVersion() == null ? null : entity.getVersion().intValue());
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        dto.setUpdatedAt(offset(entity.getUpdatedAt()));
        dto.setUpdatedBy(string(entity.getUpdatedBy()));
        return dto;
    }

    private Instant truncatedNow() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null
                : instant.atOffset(DISPLAY_OFFSET).truncatedTo(ChronoUnit.MILLIS);
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalidUuid) {
            return null;
        }
    }
}
