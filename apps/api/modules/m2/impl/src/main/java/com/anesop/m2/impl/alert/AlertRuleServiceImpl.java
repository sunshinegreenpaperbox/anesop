package com.anesop.m2.impl.alert;

import com.anesop.contracts.anesthesia.model.AlertRule;
import com.anesop.contracts.anesthesia.model.AlertRuleList;
import com.anesop.contracts.anesthesia.model.UpdateAlertRuleRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m2.api.alert.AlertRuleService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.persistence.entity.AlertRuleEntity;
import com.anesop.m2.impl.persistence.mapper.AlertRuleMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 预警规则服务实现（M3-M2-02，契约 anesthesia.yaml 0.7.0）。
 *
 * <p>版本化落库：保存规则 = 在同一 code 下插入 version+1 新行，并把旧版本 enabled 置 false。
 * 事务内先 {@code SELECT ... FOR UPDATE} 锁定该 code 的最新版本行，把并发改版串行化；
 * 第二请求拿到新版本行后发现请求的 ruleId 已不是当前版本 → 409（而不是生成 version+2
 * 覆盖别人的配置）。唯一键 (code, version) 与部分唯一索引 (code) WHERE enabled 兜底。
 *
 * <p>规则治理（评审 S-2/S-5）：
 * <ul>
 *   <li>{@code name} 可随新版本更新；阈值/持续时长按指标允许范围校验（SpO2 0–100、
 *       HR 0–300、MAP 0–250、EtCO2 0–150、TEMP 25–45），越界 400；</li>
 *   <li>阈值或持续时长较上一版本变化时置 {@code pendingConfirmation=true}
 *       （临床待确认）；仅名称/严重度/启用状态变化沿用上一版本标记；</li>
 *   <li>提供按 code 的历史版本查询，供告警 {@code ruleVersion} 追溯当时阈值。</li>
 * </ul>
 *
 * <p>审计 ALERT_RULE_UPDATE 只记录规则编码、版本迁移、名称、指标/算子/阈值/时长/严重度、
 * 启用状态与待确认标记，不含患者标识与操作者自由文本。
 */
@Service
public class AlertRuleServiceImpl implements AlertRuleService {

    private static final String RESOURCE_TYPE = "ALERT_RULE";
    private static final String ACTION_UPDATE = "ALERT_RULE_UPDATE";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_DURATION_SECONDS = 3600;
    private static final BigDecimal THRESHOLD_ABS_LIMIT = BigDecimal.valueOf(10000);
    private static final int MAX_NAME = 200;
    private static final Map<String, BigDecimal[]> METRIC_RANGES = Map.of(
            "SPO2", new BigDecimal[] {BigDecimal.ZERO, BigDecimal.valueOf(100)},
            "HR", new BigDecimal[] {BigDecimal.ZERO, BigDecimal.valueOf(300)},
            "MAP", new BigDecimal[] {BigDecimal.ZERO, BigDecimal.valueOf(250)},
            "ETCO2", new BigDecimal[] {BigDecimal.ZERO, BigDecimal.valueOf(150)},
            "TEMP", new BigDecimal[] {BigDecimal.valueOf(25), BigDecimal.valueOf(45)});

    private final AlertRuleMapper ruleMapper;
    private final AuditWriter auditWriter;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/组件由容器管理，服务不修改其内部状态")
    public AlertRuleServiceImpl(AlertRuleMapper ruleMapper, AuditWriter auditWriter, Clock clock) {
        this.ruleMapper = ruleMapper;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    @Override
    public AlertRuleList listVersions(String ruleId) {
        UUID id = parseUuid(ruleId);
        AlertRuleEntity current = id == null ? null : ruleMapper.selectById(id);
        if (current == null) {
            throw new NoSuchElementException("预警规则不存在");
        }
        List<AlertRule> items = new ArrayList<>();
        for (AlertRuleEntity entity : ruleMapper.selectVersionsByCode(current.getCode())) {
            items.add(toDto(entity));
        }
        AlertRuleList result = new AlertRuleList();
        result.setItems(items);
        return result;
    }

    @Override
    public AlertRuleList listRules() {
        List<AlertRule> items = new ArrayList<>();
        for (AlertRuleEntity entity : ruleMapper.selectCurrentRules()) {
            items.add(toDto(entity));
        }
        AlertRuleList result = new AlertRuleList();
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlertRule updateRule(String ruleId, UpdateAlertRuleRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        UUID id = parseUuid(ruleId);
        AlertRuleEntity current = id == null ? null : ruleMapper.selectById(id);
        if (current == null) {
            throw new NoSuchElementException("预警规则不存在");
        }
        AlertRuleEntity latest = ruleMapper.selectLatestByCodeForUpdate(current.getCode());
        if (latest == null || !latest.getId().equals(current.getId())) {
            // 请求的不是当前版本：并发改版已生成新版本，或请求历史版本 → 409，不静默覆盖。
            throw new M2ConflictException("预警规则已更新，请刷新后重试");
        }
        if (request == null || request.getVersion() == null
                || !request.getVersion().equals(current.getVersion())) {
            throw new M2ConflictException("预警规则版本已过期，请刷新后重试");
        }
        String severity = request.getSeverity() == null ? null : request.getSeverity().getValue();
        if (severity == null) {
            throw new M2ValidationException("severity", "严重度不能为空");
        }
        if (request.getEnabled() == null) {
            throw new M2ValidationException("enabled", "启用状态不能为空");
        }
        int duration = request.getDurationSeconds() == null ? -1 : request.getDurationSeconds();
        if (duration < 0 || duration > MAX_DURATION_SECONDS) {
            throw new M2ValidationException("durationSeconds", "持续时长必须在 0–3600 秒之间");
        }
        Thresholds thresholds = thresholdsOf(current.getMetric(), current.getOperator(), request);
        String name = request.getName() == null ? current.getName() : requireName(request.getName());
        boolean thresholdsChanged = !sameNumber(current.getThresholdLow(), thresholds.low())
                || !sameNumber(current.getThresholdHigh(), thresholds.high())
                || !Integer.valueOf(duration).equals(current.getDurationSeconds());
        boolean pending = Boolean.TRUE.equals(current.getPendingConfirmation()) || thresholdsChanged;

        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        UUID actor = caller == null ? null : caller.userId();
        ruleMapper.disableVersion(current.getId(), now, actor);

        AlertRuleEntity next = new AlertRuleEntity();
        next.setId(UUID.randomUUID());
        next.setCode(current.getCode());
        next.setName(name);
        next.setMetric(current.getMetric());
        next.setOperator(current.getOperator());
        next.setThresholdLow(thresholds.low());
        next.setThresholdHigh(thresholds.high());
        next.setDurationSeconds(duration);
        next.setSeverity(severity);
        next.setVersion(current.getVersion() + 1);
        next.setEnabled(request.getEnabled());
        next.setPendingConfirmation(pending);
        next.setCreatedAt(now);
        next.setCreatedBy(actor);
        next.setUpdatedAt(now);
        next.setUpdatedBy(actor);
        try {
            ruleMapper.insertVersion(next);
        } catch (DuplicateKeyException duplicate) {
            // 唯一键 (code, version) / 部分唯一索引 (code) WHERE enabled 兜底：并发改版 409。
            throw new M2ConflictException("预警规则已更新，请刷新后重试");
        }
        auditUpdate(current, next, caller, context);
        return toDto(next);
    }

    private static Thresholds thresholdsOf(String metric, String operator,
            UpdateAlertRuleRequest request) {
        BigDecimal low = request.getThresholdLow();
        BigDecimal high = request.getThresholdHigh();
        requireThresholdRange(metric, low, "thresholdLow");
        requireThresholdRange(metric, high, "thresholdHigh");
        return switch (operator) {
            case "LT" -> {
                if (low == null) {
                    throw new M2ValidationException("thresholdLow", "LT 规则必须提供下限阈值");
                }
                yield new Thresholds(low, null);
            }
            case "GT" -> {
                if (high == null) {
                    throw new M2ValidationException("thresholdHigh", "GT 规则必须提供上限阈值");
                }
                yield new Thresholds(null, high);
            }
            case "OUT_OF_RANGE" -> {
                if (low == null || high == null) {
                    throw new M2ValidationException("thresholdHigh",
                            "OUT_OF_RANGE 规则必须同时提供上下限阈值");
                }
                if (low.compareTo(high) >= 0) {
                    throw new M2ValidationException("thresholdHigh", "上限阈值必须大于下限阈值");
                }
                yield new Thresholds(low, high);
            }
            default -> throw new M2ValidationException("thresholdLow", "规则算子不受支持");
        };
    }

    private static void requireThresholdRange(String metric, BigDecimal value, String field) {
        if (value == null) {
            return;
        }
        if (value.abs().compareTo(THRESHOLD_ABS_LIMIT) > 0) {
            throw new M2ValidationException(field, "阈值超出允许范围（±10000）");
        }
        BigDecimal[] range = METRIC_RANGES.get(metric);
        if (range == null) {
            return;
        }
        if (value.compareTo(range[0]) < 0 || value.compareTo(range[1]) > 0) {
            throw new M2ValidationException(field,
                    "阈值超出该指标允许范围（" + range[0].stripTrailingZeros().toPlainString()
                            + "–" + range[1].stripTrailingZeros().toPlainString() + "）");
        }
    }

    private static String requireName(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new M2ValidationException("name", "规则名称不能为空");
        }
        if (trimmed.length() > MAX_NAME) {
            throw new M2ValidationException("name", "规则名称长度超出限制");
        }
        return trimmed;
    }

    private static boolean sameNumber(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    private void auditUpdate(AlertRuleEntity before, AlertRuleEntity after, AuthPrincipal caller,
            AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_UPDATE);
        detail.put("ruleId", after.getId().toString());
        detail.put("code", after.getCode());
        detail.put("fromVersion", before.getVersion());
        detail.put("toVersion", after.getVersion());
        detail.put("name", after.getName());
        detail.put("metric", after.getMetric());
        detail.put("operator", after.getOperator());
        detail.put("thresholdLow", after.getThresholdLow());
        detail.put("thresholdHigh", after.getThresholdHigh());
        detail.put("durationSeconds", after.getDurationSeconds());
        detail.put("severity", after.getSeverity());
        detail.put("enabled", after.getEnabled());
        detail.put("pendingConfirmation", after.getPendingConfirmation());
        auditWriter.record(ACTION_UPDATE, RESOURCE_TYPE, after.getId().toString(), true, detail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
    }

    private static AlertRule toDto(AlertRuleEntity entity) {
        AlertRule dto = new AlertRule();
        dto.setRuleId(entity.getId().toString());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setMetric(AlertRule.MetricEnum.fromValue(entity.getMetric()));
        dto.setOperator(AlertRule.OperatorEnum.fromValue(entity.getOperator()));
        dto.setThresholdLow(entity.getThresholdLow());
        dto.setThresholdHigh(entity.getThresholdHigh());
        dto.setDurationSeconds(entity.getDurationSeconds());
        dto.setSeverity(AlertRule.SeverityEnum.fromValue(entity.getSeverity()));
        dto.setVersion(entity.getVersion());
        dto.setEnabled(entity.getEnabled());
        dto.setPendingConfirmation(entity.getPendingConfirmation());
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        dto.setUpdatedAt(offset(entity.getUpdatedAt()));
        dto.setUpdatedBy(string(entity.getUpdatedBy()));
        return dto;
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

    private record Thresholds(BigDecimal low, BigDecimal high) {
    }
}
