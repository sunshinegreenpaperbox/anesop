package com.anesop.m2.impl.followup;

import com.anesop.contracts.anesthesia.model.CreatePostopFollowupRequest;
import com.anesop.contracts.anesthesia.model.FollowupComplication;
import com.anesop.contracts.anesthesia.model.FollowupType;
import com.anesop.contracts.anesthesia.model.PainAdjustment;
import com.anesop.contracts.anesthesia.model.PainManagement;
import com.anesop.contracts.anesthesia.model.PainManagementEffect;
import com.anesop.contracts.anesthesia.model.PainManagementList;
import com.anesop.contracts.anesthesia.model.PainManagementModality;
import com.anesop.contracts.anesthesia.model.PostopFollowup;
import com.anesop.contracts.anesthesia.model.PostopFollowupList;
import com.anesop.contracts.anesthesia.model.RecordPainManagementRequest;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.followup.FollowupService;
import com.anesop.m2.impl.config.RecordProperties;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.FollowupRecordEntity;
import com.anesop.m2.impl.persistence.entity.PainManagementEntity;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.FollowupRecordMapper;
import com.anesop.m2.impl.persistence.mapper.PainManagementMapper;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
import com.anesop.m2.impl.support.PhiMasker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 术后随访与镇痛服务实现（M3-M11-01，契约 anesthesia.yaml 0.10.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>随访必须在麻醉记录单**结束之后**：录入时在事务内锁定记录单行（
 *       {@code SELECT ... FOR UPDATE}），校验 {@code endedAt} 非空（否则 409）且
 *       {@code assessedAt >= endedAt}（否则 400）；行锁同时把同一记录单的并发随访串行化，
 *       保证“同类型时间单调”检查在同一把锁下成立；</li>
 *   <li>同一记录单同一随访类型允许多次随访，但 assessedAt 不得早于 latest（400，同刻允许）；
 *       latest 按 {@code assessed_at DESC, created_at DESC, id DESC} 判定；随访时间不得晚于
 *       服务端当前时间 + 容差（400）；</li>
 *   <li>NRS 0–10、PONV 0–3、并发症白名单/去重/上限 20、文本 ≤500、随访人 ≤64 由服务层
 *       校验（400）；数据库 CHECK 为第二道防线；</li>
 *   <li>append-only（数据库触发器 55000）：只 INSERT；镇痛参数调整以新增镇痛记录表达；
 *       并发症说明/患者反馈/调整说明/药物名称先经 {@link PhiMasker} 脱敏再落库；</li>
 *   <li>镇痛参数只做物理校验（bolus &gt; 0、background ≥ 0、lockout &gt; 0 分钟），
 *       不内置 PCA 默认值、不设临床阈值【待确认】（H-06）；</li>
 *   <li>数据范围复用记录单/台次可见性（只调 m1-api，不读 m1 表）：按记录单列表越权 403、
 *       单对象越界 404、录入时记录单不可见 422；</li>
 *   <li>审计 FOLLOWUP_CREATE/PAIN_MANAGE 与业务同事务；detail 只含标识、类型、评分、
 *       并发症码、镇痛参数、脱敏后的药物名称与计数，不含自由文本原文与患者 PHI；
 *       记录单未结束的拒绝以独立事务写 result=0 审计（只含拒绝码）。</li>
 * </ul>
 */
@Service
public class FollowupServiceImpl implements FollowupService {

    private static final String RESOURCE_FOLLOWUP = "FOLLOWUP_RECORD";
    private static final String RESOURCE_PAIN = "PAIN_MANAGEMENT";
    private static final String ACTION_FOLLOWUP_CREATE = "FOLLOWUP_CREATE";
    private static final String ACTION_PAIN_MANAGE = "PAIN_MANAGE";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_TEXT = 500;
    private static final int MAX_DRUG = 128;
    private static final int MAX_FOLLOWED_UP_BY = 64;
    private static final int MAX_COMPLICATIONS = 20;
    private static final int MAX_ADJUSTMENTS = 20;
    private static final int MAX_ADJUSTMENT_DESCRIPTION = 200;

    private final FollowupRecordMapper followupMapper;
    private final PainManagementMapper painManagementMapper;
    private final AnesthesiaRecordMapper recordMapper;
    private final SchedulingService schedulingService;
    private final AuditWriter auditWriter;
    private final ClinicalFailureAuditor failureAuditor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RecordProperties recordProperties;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public FollowupServiceImpl(FollowupRecordMapper followupMapper,
            PainManagementMapper painManagementMapper,
            AnesthesiaRecordMapper recordMapper,
            SchedulingService schedulingService,
            AuditWriter auditWriter,
            ClinicalFailureAuditor failureAuditor,
            ObjectMapper objectMapper,
            Clock clock,
            RecordProperties recordProperties) {
        this.followupMapper = followupMapper;
        this.painManagementMapper = painManagementMapper;
        this.recordMapper = recordMapper;
        this.schedulingService = schedulingService;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.recordProperties = recordProperties;
    }

    // ------------------------------------------------------------------ 查询 --

    @Override
    public PostopFollowupList listByRecord(String recordId, AuthPrincipal caller) {
        UUID id = parseUuid(recordId);
        if (id == null) {
            throw new M2ValidationException("recordId", "麻醉记录单标识格式不正确");
        }
        AnesthesiaRecordEntity record = recordMapper.selectById(id);
        if (record == null) {
            throw new AccessDeniedException("麻醉记录单不存在或超出数据范围");
        }
        try {
            requireVisibleSchedule(record, caller, false);
        } catch (NoSuchElementException notVisible) {
            // 集合查询越权语义：记录单/台次不可见统一 403，不返回空列表（不泄露存在性）。
            throw new AccessDeniedException("麻醉记录单不存在或超出数据范围");
        }
        PostopFollowupList result = new PostopFollowupList();
        List<PostopFollowup> items = new ArrayList<>();
        for (FollowupRecordEntity row : followupMapper.selectByRecord(id)) {
            items.add(toFollowupDto(row));
        }
        result.setItems(items);
        return result;
    }

    @Override
    public PostopFollowup get(String followupId, AuthPrincipal caller) {
        return toFollowupDto(requireFollowup(followupId, caller));
    }

    @Override
    public PainManagementList listPainManagement(String followupId, AuthPrincipal caller) {
        FollowupRecordEntity followup = requireFollowup(followupId, caller);
        PainManagementList result = new PainManagementList();
        List<PainManagement> items = new ArrayList<>();
        for (PainManagementEntity row : painManagementMapper.selectByFollowup(followup.getId())) {
            items.add(toPainDto(row, followup));
        }
        result.setItems(items);
        return result;
    }

    // ------------------------------------------------------------------ 随访 --

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostopFollowup create(CreatePostopFollowupRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        UUID recordId = parseUuid(request.getAnesthesiaRecordId());
        if (recordId == null) {
            throw new M2ValidationException("anesthesiaRecordId", "麻醉记录单标识格式不正确");
        }
        AnesthesiaRecordEntity record = recordMapper.selectByIdForUpdate(recordId);
        if (record == null) {
            throw new M2UnprocessableException("anesthesiaRecordId",
                    "麻醉记录单不存在或不可见");
        }
        requireVisibleSchedule(record, caller, true);
        if (record.getEndedAt() == null) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("operation", ACTION_FOLLOWUP_CREATE);
            detail.put("recordId", recordId.toString());
            detail.put("rejection", "RECORD_NOT_ENDED");
            failureAuditor.recordFailure(ACTION_FOLLOWUP_CREATE, RESOURCE_FOLLOWUP,
                    recordId.toString(), detail, caller, context);
            throw new M2ConflictException("麻醉记录单尚未结束，不能录入术后随访");
        }

        FollowupType type = request.getFollowupType();
        if (type == null) {
            throw new M2ValidationException("followupType", "随访类型不能为空（H24/H48）");
        }
        Instant assessedAt = request.getAssessedAt() == null ? null
                : request.getAssessedAt().toInstant();
        if (assessedAt == null) {
            throw new M2ValidationException("assessedAt", "随访时间不能为空");
        }
        if (assessedAt.isBefore(record.getEndedAt())) {
            throw new M2ValidationException("assessedAt", "随访时间不得早于麻醉结束时间");
        }
        requireNotFuture(assessedAt, "assessedAt");
        FollowupRecordEntity previous = followupMapper.selectLatestByRecordAndType(recordId,
                type.getValue());
        if (previous != null && assessedAt.isBefore(previous.getAssessedAt())) {
            throw new M2ValidationException("assessedAt",
                    "随访时间不得早于上一条同类型随访（时间序列只增）");
        }

        int nrsScore = requireScore(request.getNrsScore(), 0, 10, "nrsScore");
        int ponvScore = requireScore(request.getPonvScore(), 0, 3, "ponvScore");
        List<FollowupComplication> complications = normalizeComplications(
                request.getComplications());
        String complicationNotes = optionalMaskedText(request.getComplicationNotes(),
                "complicationNotes");
        String patientFeedback = optionalMaskedText(request.getPatientFeedback(),
                "patientFeedback");
        String followedUpBy = requireText(request.getFollowedUpBy(), "followedUpBy",
                MAX_FOLLOWED_UP_BY, "随访人不能为空");

        Instant now = truncatedNow();
        UUID actorId = actorId(caller);
        FollowupRecordEntity entity = new FollowupRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setAnesthesiaRecordId(recordId);
        entity.setPatientId(record.getPatientId());
        entity.setFollowupType(type.getValue());
        entity.setAssessedAt(assessedAt);
        entity.setNrsScore(nrsScore);
        entity.setPonvScore(ponvScore);
        entity.setComplications(complicationsJson(complications));
        entity.setComplicationNotes(complicationNotes);
        entity.setPatientFeedback(patientFeedback);
        entity.setFollowedUpBy(followedUpBy);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId);
        entity.setVersion(1L);
        followupMapper.insertFollowup(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_FOLLOWUP_CREATE);
        detail.put("followupId", entity.getId().toString());
        detail.put("recordId", recordId.toString());
        detail.put("followupType", type.getValue());
        detail.put("assessedAt", format(assessedAt));
        detail.put("nrsScore", nrsScore);
        detail.put("ponvScore", ponvScore);
        detail.put("complicationCodes", complications.stream()
                .map(FollowupComplication::getValue).toList());
        detail.put("hasComplicationNotes", complicationNotes != null);
        detail.put("hasPatientFeedback", patientFeedback != null);
        auditWriter.record(ACTION_FOLLOWUP_CREATE, RESOURCE_FOLLOWUP, entity.getId().toString(),
                true, detail, actorId, caller == null ? null : caller.username(), context);

        return toFollowupDto(entity);
    }

    // -------------------------------------------------------------- 镇痛 --

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PainManagement recordPainManagement(String followupId,
            RecordPainManagementRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        FollowupRecordEntity followup = requireFollowup(followupId, caller);
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        if (request.getModality() == null) {
            throw new M2ValidationException("modality", "镇痛方式不能为空");
        }
        if (request.getEffect() == null) {
            throw new M2ValidationException("effect", "镇痛效果不能为空");
        }
        // 药物名称是自由文本，可能误填患者标识；落库与审计前统一脱敏（评审 S-1）。
        String drug = PhiMasker.mask(
                requireText(request.getDrug(), "drug", MAX_DRUG, "药物名称不能为空"));
        BigDecimal bolusDose = requirePositive(request.getBolusDose(), "bolusDose",
                "bolus 剂量必须大于 0");
        BigDecimal backgroundDose = requireNonNegative(request.getBackgroundDose(),
                "backgroundDose", "background 剂量不能为空且不得小于 0");
        Integer lockoutMinutes = request.getLockoutMinutes();
        if (lockoutMinutes == null || lockoutMinutes <= 0) {
            throw new M2ValidationException("lockoutMinutes", "lockout 时间必须大于 0 分钟");
        }
        List<PainAdjustment> adjustments = normalizeAdjustments(request.getAdjustments());

        Instant now = truncatedNow();
        UUID actorId = actorId(caller);
        PainManagementEntity entity = new PainManagementEntity();
        entity.setId(UUID.randomUUID());
        entity.setFollowupId(followup.getId());
        entity.setModality(request.getModality().getValue());
        entity.setDrug(drug);
        entity.setBolusDose(bolusDose);
        entity.setBackgroundDose(backgroundDose);
        entity.setLockoutMinutes(lockoutMinutes);
        entity.setEffect(request.getEffect().getValue());
        entity.setAdjustments(adjustmentsJson(adjustments));
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId);
        entity.setVersion(1L);
        painManagementMapper.insertPainManagement(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_PAIN_MANAGE);
        detail.put("painManagementId", entity.getId().toString());
        detail.put("followupId", followup.getId().toString());
        detail.put("recordId", followup.getAnesthesiaRecordId().toString());
        detail.put("modality", entity.getModality());
        detail.put("drug", entity.getDrug());
        detail.put("bolusDose", bolusDose.toPlainString());
        detail.put("backgroundDose", backgroundDose.toPlainString());
        detail.put("lockoutMinutes", lockoutMinutes);
        detail.put("effect", entity.getEffect());
        detail.put("adjustmentCount", adjustments.size());
        auditWriter.record(ACTION_PAIN_MANAGE, RESOURCE_PAIN, entity.getId().toString(), true,
                detail, actorId, caller == null ? null : caller.username(), context);

        return toPainDto(entity, followup);
    }

    // ------------------------------------------------------------- helpers --

    private FollowupRecordEntity requireFollowup(String followupId, AuthPrincipal caller) {
        UUID id = parseUuid(followupId);
        FollowupRecordEntity entity = id == null ? null : followupMapper.selectById(id);
        if (entity == null) {
            throw new NoSuchElementException("随访记录不存在或不可见");
        }
        AnesthesiaRecordEntity record = recordMapper.selectById(entity.getAnesthesiaRecordId());
        if (record == null) {
            throw new NoSuchElementException("随访记录不存在或不可见");
        }
        requireVisibleSchedule(record, caller, false);
        return entity;
    }

    /**
     * 数据范围沿台次/申请可见性（只调 m1-api，不读 m1 表）。
     *
     * @param unprocessable true=请求体参数（录入时记录单不可见映射 422）；false=单对象/集合语义
     *                      （404/403 由调用方决定：不可见按不存在处理，不泄露存在性）
     */
    private void requireVisibleSchedule(AnesthesiaRecordEntity record, AuthPrincipal caller,
            boolean unprocessable) {
        try {
            Schedule ignored = schedulingService.get(record.getScheduleId().toString(), caller);
            if (ignored == null) {
                throw new NoSuchElementException("排程台次不存在或不可见");
            }
        } catch (NoSuchElementException notVisible) {
            if (unprocessable) {
                throw new M2UnprocessableException("anesthesiaRecordId",
                        "麻醉记录单不存在或不可见");
            }
            throw new NoSuchElementException("随访记录不存在或不可见");
        }
    }

    private List<FollowupComplication> normalizeComplications(
            List<FollowupComplication> complications) {
        if (complications == null) {
            return List.of();
        }
        if (complications.size() > MAX_COMPLICATIONS) {
            throw new M2ValidationException("complications",
                    "并发症最多选择 " + MAX_COMPLICATIONS + " 项");
        }
        Set<FollowupComplication> unique = new LinkedHashSet<>();
        for (FollowupComplication complication : complications) {
            if (complication == null) {
                throw new M2ValidationException("complications", "并发症取值不能为空");
            }
            unique.add(complication);
        }
        return List.copyOf(unique);
    }

    private List<PainAdjustment> normalizeAdjustments(List<PainAdjustment> adjustments) {
        if (adjustments == null) {
            return List.of();
        }
        if (adjustments.size() > MAX_ADJUSTMENTS) {
            throw new M2ValidationException("adjustments",
                    "调整记录最多 " + MAX_ADJUSTMENTS + " 条");
        }
        List<PainAdjustment> normalized = new ArrayList<>();
        for (PainAdjustment adjustment : adjustments) {
            if (adjustment == null) {
                throw new M2ValidationException("adjustments", "调整记录不能为空");
            }
            Instant adjustedAt = adjustment.getAdjustedAt() == null ? null
                    : adjustment.getAdjustedAt().toInstant();
            if (adjustedAt == null) {
                throw new M2ValidationException("adjustments.adjustedAt", "调整时间不能为空");
            }
            requireNotFuture(adjustedAt, "adjustments.adjustedAt");
            String description = requireText(adjustment.getDescription(),
                    "adjustments.description", MAX_ADJUSTMENT_DESCRIPTION, "调整说明不能为空");
            PainAdjustment safe = new PainAdjustment();
            safe.setAdjustedAt(offset(adjustedAt));
            safe.setDescription(PhiMasker.mask(description));
            normalized.add(safe);
        }
        return List.copyOf(normalized);
    }

    private static int requireScore(Integer value, int min, int max, String field) {
        if (value == null) {
            throw new M2ValidationException(field, "评分不能为空");
        }
        if (value < min || value > max) {
            throw new M2ValidationException(field, "评分必须在 " + min + "–" + max + " 之间");
        }
        return value;
    }

    private static String requireText(String value, String field, int maxLength,
            String emptyMessage) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new M2ValidationException(field, emptyMessage);
        }
        if (trimmed.length() > maxLength) {
            throw new M2ValidationException(field, "字段长度超出限制（≤" + maxLength + "）");
        }
        return trimmed;
    }

    /** 自由文本：trim → 长度校验（按原文）→ 脱敏；空白返回 null。 */
    private static String optionalMaskedText(String value, String field) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_TEXT) {
            throw new M2ValidationException(field, "字段长度超出限制（≤" + MAX_TEXT + "）");
        }
        return PhiMasker.mask(trimmed);
    }

    private static BigDecimal requirePositive(Double value, String field, String message) {
        if (value == null || !Double.isFinite(value) || value <= 0) {
            throw new M2ValidationException(field, message);
        }
        return BigDecimal.valueOf(value);
    }

    private static BigDecimal requireNonNegative(Double value, String field, String message) {
        if (value == null || !Double.isFinite(value) || value < 0) {
            throw new M2ValidationException(field, message);
        }
        return BigDecimal.valueOf(value);
    }

    private void requireNotFuture(Instant time, String field) {
        Instant limit = truncatedNow().plusSeconds(recordProperties.timeToleranceSeconds());
        if (time.isAfter(limit)) {
            throw new M2ValidationException(field, "时间不得晚于服务端当前时间（含容差）");
        }
    }

    private PostopFollowup toFollowupDto(FollowupRecordEntity entity) {
        PostopFollowup dto = new PostopFollowup();
        dto.setFollowupId(entity.getId().toString());
        dto.setRecordId(entity.getAnesthesiaRecordId().toString());
        dto.setPatientId(entity.getPatientId().toString());
        dto.setFollowupType(FollowupType.fromValue(entity.getFollowupType()));
        dto.setAssessedAt(offset(entity.getAssessedAt()));
        dto.setNrsScore(entity.getNrsScore());
        dto.setPonvScore(entity.getPonvScore());
        dto.setComplications(readComplications(entity.getComplications()));
        dto.setComplicationNotes(entity.getComplicationNotes());
        dto.setPatientFeedback(entity.getPatientFeedback());
        dto.setFollowedUpBy(entity.getFollowedUpBy());
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        dto.setVersion(entity.getVersion() == null ? 1 : entity.getVersion().intValue());
        return dto;
    }

    private PainManagement toPainDto(PainManagementEntity entity, FollowupRecordEntity followup) {
        PainManagement dto = new PainManagement();
        dto.setPainManagementId(entity.getId().toString());
        dto.setFollowupId(entity.getFollowupId().toString());
        dto.setRecordId(followup.getAnesthesiaRecordId().toString());
        dto.setModality(PainManagementModality.fromValue(entity.getModality()));
        dto.setDrug(entity.getDrug());
        dto.setBolusDose(entity.getBolusDose() == null ? null
                : entity.getBolusDose().doubleValue());
        dto.setBackgroundDose(entity.getBackgroundDose() == null ? null
                : entity.getBackgroundDose().doubleValue());
        dto.setLockoutMinutes(entity.getLockoutMinutes());
        dto.setEffect(PainManagementEffect.fromValue(entity.getEffect()));
        dto.setAdjustments(readAdjustments(entity.getAdjustments()));
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        return dto;
    }

    private List<FollowupComplication> readComplications(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<FollowupComplication>>() { });
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalStateException("并发症 JSON 解析失败", invalidJson);
        }
    }

    private List<PainAdjustment> readAdjustments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<PainAdjustment>>() { });
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalStateException("镇痛调整记录 JSON 解析失败", invalidJson);
        }
    }

    private String complicationsJson(List<FollowupComplication> complications) {
        return toJson(complications.stream().map(FollowupComplication::getValue).toList());
    }

    private String adjustmentsJson(List<PainAdjustment> adjustments) {
        return toJson(adjustments);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("随访/镇痛 JSON 序列化失败", serializationFailure);
        }
    }

    private Instant truncatedNow() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null
                : instant.atOffset(DISPLAY_OFFSET).truncatedTo(ChronoUnit.MILLIS);
    }

    private static String format(Instant instant) {
        OffsetDateTime value = offset(instant);
        return value == null ? null : value.toString();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static UUID actorId(AuthPrincipal caller) {
        return caller == null ? null : caller.userId();
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
