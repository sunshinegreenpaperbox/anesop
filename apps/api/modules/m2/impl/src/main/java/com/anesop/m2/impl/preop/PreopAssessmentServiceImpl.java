package com.anesop.m2.impl.preop;

import com.anesop.contracts.anesthesia.model.FinalizePreopAssessmentRequest;
import com.anesop.contracts.anesthesia.model.PreopAirwayAssessment;
import com.anesop.contracts.anesthesia.model.PreopApplicationSummary;
import com.anesop.contracts.anesthesia.model.PreopAssessment;
import com.anesop.contracts.anesthesia.model.PreopAssessmentCreateRequest;
import com.anesop.contracts.anesthesia.model.PreopAssessmentList;
import com.anesop.contracts.anesthesia.model.PreopAssessmentUpdateRequest;
import com.anesop.contracts.anesthesia.model.PreopPatientSnapshot;
import com.anesop.contracts.anesthesia.model.PreopPlan;
import com.anesop.contracts.anesthesia.model.PreopScoreInput;
import com.anesop.contracts.anesthesia.model.PreopScores;
import com.anesop.contracts.scheduling.model.PatientSnapshot;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.application.SurgeryApplicationService;
import com.anesop.m2.api.preop.PreopAssessmentService;
import com.anesop.m2.api.preop.PreopConflictException;
import com.anesop.m2.api.preop.PreopUnprocessableException;
import com.anesop.m2.api.preop.PreopValidationException;
import com.anesop.m2.impl.persistence.entity.PreopAssessmentEntity;
import com.anesop.m2.impl.persistence.mapper.PreopAssessmentMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 术前访视与评估服务实现（M2-M4-01，契约 anesthesia.yaml 0.3.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>评分只由服务端计算：请求只带量表条目，{@link PreopScoreCalculator} 输出分数与
 *       风险分级；客户端分数不落库。</li>
 *   <li>数据范围复用 M1 申请/患者可见性：同步调用 {@link SurgeryApplicationService#get}
 *       （只依赖 m1-api，不访问 m1 表/Mapper）。按申请列表为集合语义（越权 403）；按访视单
 *       查询/修改为单对象语义（越界/不存在 404）；创建时申请不可见按 422（请求体参数）。</li>
 *   <li>状态机 DRAFT → FINALIZED：最终化后修改/再次最终化 409；只能新建版本。
 *       更新与最终化都用 {@code version} 条件更新，返回 0 行即 409（乐观锁）。</li>
 *   <li>审计 PREOP_CREATE/PREOP_UPDATE/PREOP_FINALIZE 与业务同事务；detail 只含标识、
 *       状态、评分与版本，不含姓名、病史原文、证件号/住院号/手机号（AGENTS 数据红线）。</li>
 * </ul>
 */
@Service
public class PreopAssessmentServiceImpl implements PreopAssessmentService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_FINALIZED = "FINALIZED";
    private static final String RESOURCE_TYPE = "PREOP_ASSESSMENT";
    private static final String ACTION_CREATE = "PREOP_CREATE";
    private static final String ACTION_UPDATE = "PREOP_UPDATE";
    private static final String ACTION_FINALIZE = "PREOP_FINALIZE";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_HISTORY = 4000;
    private static final int MAX_AIRWAY_PLAN = 500;
    private static final int MAX_AIRWAY_NOTES = 500;
    private static final int MAX_PLAN_NOTES = 2000;
    private static final int MAX_FINALIZE_NOTE = 500;

    /** 允许创建/更新/最终化访视的申请状态；取消/驳回为终态，拒绝继续访视（409）。 */
    private static final Set<String> ACTIVE_APPLICATION_STATUSES =
            Set.of("SUBMITTED", "ACCEPTED", "SCHEDULED");

    private final PreopAssessmentMapper assessmentMapper;
    private final SurgeryApplicationService applicationService;
    private final PreopScoreCalculator scoreCalculator;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务/序列化器由容器管理，服务不修改其内部状态")
    public PreopAssessmentServiceImpl(PreopAssessmentMapper assessmentMapper,
            SurgeryApplicationService applicationService,
            PreopScoreCalculator scoreCalculator,
            AuditWriter auditWriter,
            ObjectMapper objectMapper,
            Clock clock) {
        this.assessmentMapper = assessmentMapper;
        this.applicationService = applicationService;
        this.scoreCalculator = scoreCalculator;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public PreopAssessmentList listByApplication(String applicationId, AuthPrincipal caller) {
        UUID id = parseUuid(applicationId);
        if (id == null) {
            throw new PreopValidationException("applicationId", "手术申请标识格式不正确");
        }
        SurgeryApplication application;
        try {
            application = applicationService.get(id.toString(), caller);
        } catch (NoSuchElementException notVisible) {
            // 集合查询越权语义：申请不可见/不存在统一 403，不返回空列表（不泄露存在性）。
            throw new AccessDeniedException("手术申请不存在或超出数据范围");
        }
        List<PreopAssessmentEntity> rows = assessmentMapper.selectList(
                new QueryWrapper<PreopAssessmentEntity>()
                        .eq("application_id", id)
                        .orderByDesc("created_at")
                        .orderByDesc("version"));
        List<PreopAssessment> items = new ArrayList<>();
        for (PreopAssessmentEntity row : rows) {
            items.add(toDto(row));
        }
        PreopAssessmentList result = new PreopAssessmentList();
        result.setApplication(toApplicationSummary(application));
        result.setItems(items);
        return result;
    }

    @Override
    public PreopAssessment get(String assessmentId, AuthPrincipal caller) {
        PreopAssessmentEntity entity = requireEntity(assessmentId);
        // 复用 M1 申请可见性：申请不存在或越界 → NoSuchElementException → 404。
        applicationService.get(entity.getApplicationId().toString(), caller);
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PreopAssessment create(PreopAssessmentCreateRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        UUID applicationId = parseUuid(request == null ? null : request.getApplicationId());
        if (applicationId == null) {
            throw new PreopValidationException("applicationId", "手术申请标识格式不正确");
        }
        SurgeryApplication application = loadApplicationForWrite(applicationId, caller);
        requireActiveApplication(application);
        // 患者快照只做一致性校验，内容一律由 M1 申请快照服务端回填（不信任客户端）。
        PreopPatientSnapshot patientSnapshot =
                resolvePatientSnapshot(request.getPatientSnapshot(), application);
        scoreCalculator.validateClinicalEnums(
                enumValue(request.getAsaClass()), enumValue(request.getAirwayAssessment().getMallampati()));
        PreopScores scores = scoreCalculator.calculate(request.getScoreInput());
        if (hasDraft(applicationId)) {
            throw new PreopConflictException("该申请已有草稿，请直接编辑或先最终化");
        }

        Instant now = clock.instant();
        PreopAssessmentEntity entity = new PreopAssessmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setApplicationId(applicationId);
        entity.setPatientId(UUID.fromString(application.getPatient().getPatientId()));
        entity.setPatientSnapshotJson(writeJson(patientSnapshot, "patientSnapshot"));
        entity.setHistorySummary(requireText(request.getHistorySummary(), "historySummary", MAX_HISTORY));
        entity.setAsaClass(enumValue(request.getAsaClass()));
        entity.setMallampati(enumValue(request.getAirwayAssessment().getMallampati()));
        entity.setAirwayDetailsJson(airwayJson(request.getAirwayAssessment()));
        entity.setScoreInputJson(writeJson(request.getScoreInput(), "scoreInput"));
        applyScores(entity, scores);
        applyPlan(entity, request.getPlan());
        entity.setStatus(STATUS_DRAFT);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(1L);
        try {
            assessmentMapper.insertDraft(entity);
        } catch (DuplicateKeyException duplicateDraft) {
            // 并发重复创建：部分唯一索引 uk_preop_assessment_application_draft 兜底。
            throw new PreopConflictException("该申请已有草稿，请直接编辑或先最终化");
        }
        audit(ACTION_CREATE, entity, caller, context);
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PreopAssessment update(String assessmentId, PreopAssessmentUpdateRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        PreopAssessmentEntity entity = requireEntity(assessmentId);
        SurgeryApplication application =
                applicationService.get(entity.getApplicationId().toString(), caller);
        requireActiveApplication(application);
        requireDraft(entity);
        requireVersion(entity, request.getVersion());
        scoreCalculator.validateClinicalEnums(
                enumValue(request.getAsaClass()), enumValue(request.getAirwayAssessment().getMallampati()));
        PreopScores scores = scoreCalculator.calculate(request.getScoreInput());

        Instant now = clock.instant();
        entity.setHistorySummary(requireText(request.getHistorySummary(), "historySummary", MAX_HISTORY));
        entity.setAsaClass(enumValue(request.getAsaClass()));
        entity.setMallampati(enumValue(request.getAirwayAssessment().getMallampati()));
        entity.setAirwayDetailsJson(airwayJson(request.getAirwayAssessment()));
        entity.setScoreInputJson(writeJson(request.getScoreInput(), "scoreInput"));
        applyScores(entity, scores);
        applyPlan(entity, request.getPlan());
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));

        int updated = assessmentMapper.updateDraft(entity);
        if (updated == 0) {
            throw new PreopConflictException("访视单已被修改或已最终化，请刷新后重试");
        }
        entity.setVersion(entity.getVersion() + 1);
        audit(ACTION_UPDATE, entity, caller, context);
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PreopAssessment finalizeAssessment(String assessmentId, FinalizePreopAssessmentRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        PreopAssessmentEntity entity = requireEntity(assessmentId);
        SurgeryApplication application =
                applicationService.get(entity.getApplicationId().toString(), caller);
        requireActiveApplication(application);
        requireDraft(entity);
        requireVersion(entity, request.getVersion());
        String finalizeNote = optionalText(request.getNote(), "note", MAX_FINALIZE_NOTE);

        Instant now = clock.instant();
        int updated = assessmentMapper.finalizeDraft(entity.getId(), entity.getVersion(), now,
                actorId(caller), finalizeNote);
        if (updated == 0) {
            throw new PreopConflictException("访视单已被修改或已最终化，请刷新后重试");
        }
        entity.setStatus(STATUS_FINALIZED);
        entity.setFinalizeNote(finalizeNote);
        entity.setFinalizedAt(now);
        entity.setFinalizedBy(actorId(caller));
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(entity.getVersion() + 1);
        audit(ACTION_FINALIZE, entity, caller, context);
        return toDto(entity);
    }

    private SurgeryApplication loadApplicationForWrite(UUID applicationId, AuthPrincipal caller) {
        try {
            return applicationService.get(applicationId.toString(), caller);
        } catch (NoSuchElementException notVisible) {
            // 创建接口无资源路径参数，按契约语义 422（不回显申请是否存在）。
            throw new PreopUnprocessableException("applicationId", "手术申请不存在或不可见");
        }
    }

    private static void requireActiveApplication(SurgeryApplication application) {
        String status = application.getStatus() == null ? null : application.getStatus().getValue();
        if (status == null || !ACTIVE_APPLICATION_STATUSES.contains(status)) {
            throw new PreopConflictException("手术申请状态不允许创建/修改术前访视");
        }
    }

    /**
     * 服务端回填患者快照：内容（姓名/性别/年龄/住院号脱敏值/科室病区床号）一律取 M1 申请
     * 快照，客户端提交值不落库。仅校验客户端携带的 patientId/hisPatientNo 是否指向同一患者，
     * 不一致按 400 拒绝，防止把访视单写到错误患者上。
     */
    private static PreopPatientSnapshot resolvePatientSnapshot(PreopPatientSnapshot requested,
            SurgeryApplication application) {
        PatientSnapshot patient = application.getPatient();
        if (patient == null) {
            throw new PreopUnprocessableException("applicationId", "手术申请缺少患者快照");
        }
        if (requested != null) {
            if (requested.getPatientId() != null
                    && !Objects.equals(requested.getPatientId(), patient.getPatientId())) {
                throw new PreopValidationException("patientSnapshot.patientId",
                        "患者标识与手术申请不一致");
            }
            if (requested.getHisPatientNo() != null
                    && !Objects.equals(requested.getHisPatientNo(), patient.getHisPatientNo())) {
                throw new PreopValidationException("patientSnapshot.hisPatientNo",
                        "HIS 患者号与手术申请不一致");
            }
        }
        return toPreopSnapshot(patient);
    }

    private boolean hasDraft(UUID applicationId) {
        Long count = assessmentMapper.selectCount(new QueryWrapper<PreopAssessmentEntity>()
                .eq("application_id", applicationId)
                .eq("status", STATUS_DRAFT));
        return count != null && count > 0;
    }

    private void requireDraft(PreopAssessmentEntity entity) {
        if (STATUS_FINALIZED.equals(entity.getStatus())) {
            throw new PreopConflictException("访视单已最终化，不可修改；请为同一申请新建版本");
        }
        if (!STATUS_DRAFT.equals(entity.getStatus())) {
            throw new PreopConflictException("访视单状态不允许该操作");
        }
    }

    private static void requireVersion(PreopAssessmentEntity entity, Integer requestVersion) {
        if (requestVersion == null || entity.getVersion() == null
                || !entity.getVersion().equals(requestVersion.longValue())) {
            throw new PreopConflictException("访视单版本已过期，请刷新后重试");
        }
    }

    private PreopAssessmentEntity requireEntity(String assessmentId) {
        UUID id = parseUuid(assessmentId);
        PreopAssessmentEntity entity = id == null ? null : assessmentMapper.selectById(id);
        if (entity == null) {
            throw new NoSuchElementException("术前访视不存在或不可见");
        }
        return entity;
    }

    private void applyScores(PreopAssessmentEntity entity, PreopScores scores) {
        entity.setRcriScore(scores.getRcriScore());
        entity.setRcriRisk(scores.getRcriRisk().getValue());
        entity.setApfelScore(scores.getApfelScore());
        entity.setApfelRisk(scores.getApfelRisk().getValue());
        entity.setStopbangScore(scores.getStopBangScore());
        entity.setStopbangRisk(scores.getStopBangRisk().getValue());
        entity.setDasiScore(scores.getDasiScore().doubleValue());
        entity.setFunctionalCapacityLow(scores.getFunctionalCapacityLow());
        entity.setRiskLevel(scores.getRiskLevel().getValue());
        entity.setCalculatorVersion(scores.getCalculatorVersion());
        entity.setScoresJson(writeJson(scores, "scores"));
    }

    private void applyPlan(PreopAssessmentEntity entity, PreopPlan plan) {
        if (plan == null || plan.getPlanType() == null) {
            throw new PreopValidationException("plan.planType", "麻醉计划类型不能为空");
        }
        entity.setPlanType(plan.getPlanType().getValue());
        entity.setAirwayPlan(optionalText(plan.getAirwayPlan(), "plan.airwayPlan", MAX_AIRWAY_PLAN));
        entity.setPlanNotes(optionalText(plan.getNotes(), "plan.notes", MAX_PLAN_NOTES));
    }

    private String airwayJson(PreopAirwayAssessment airway) {
        if (airway == null) {
            throw new PreopValidationException("airwayAssessment", "气道评估不能为空");
        }
        if (airway.getMouthOpeningCm() == null
                || airway.getMouthOpeningCm().compareTo(BigDecimal.ZERO) < 0
                || airway.getMouthOpeningCm().compareTo(BigDecimal.TEN) > 0) {
            throw new PreopValidationException("airwayAssessment.mouthOpeningCm",
                    "张口度必须在 0–10 cm 之间");
        }
        // 备注长度做服务端兜底（契约已限制，防止绕过 Web 层直接调用）。
        optionalText(airway.getNotes(), "airwayAssessment.notes", MAX_AIRWAY_NOTES);
        return writeJson(airway, "airwayAssessment");
    }

    private PreopAssessment toDto(PreopAssessmentEntity entity) {
        PreopAssessment dto = new PreopAssessment();
        dto.setAssessmentId(entity.getId().toString());
        dto.setApplicationId(entity.getApplicationId().toString());
        dto.setPatientId(entity.getPatientId().toString());
        dto.setPatientSnapshot(readJson(entity.getPatientSnapshotJson(), PreopPatientSnapshot.class));
        dto.setHistorySummary(entity.getHistorySummary());
        dto.setAsaClass(PreopAssessment.AsaClassEnum.fromValue(entity.getAsaClass()));
        dto.setAirwayAssessment(readJson(entity.getAirwayDetailsJson(), PreopAirwayAssessment.class));
        dto.setScoreInput(readJson(entity.getScoreInputJson(), PreopScoreInput.class));
        dto.setScores(readJson(entity.getScoresJson(), PreopScores.class));
        PreopPlan plan = new PreopPlan();
        plan.setPlanType(PreopPlan.PlanTypeEnum.fromValue(entity.getPlanType()));
        plan.setAirwayPlan(entity.getAirwayPlan());
        plan.setNotes(entity.getPlanNotes());
        dto.setPlan(plan);
        dto.setStatus(PreopAssessment.StatusEnum.fromValue(entity.getStatus()));
        dto.setFinalizeNote(entity.getFinalizeNote());
        dto.setFinalizedAt(offset(entity.getFinalizedAt()));
        dto.setFinalizedBy(string(entity.getFinalizedBy()));
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        dto.setUpdatedAt(offset(entity.getUpdatedAt()));
        dto.setUpdatedBy(string(entity.getUpdatedBy()));
        dto.setVersion(entity.getVersion() == null ? 0 : entity.getVersion().intValue());
        return dto;
    }

    private PreopApplicationSummary toApplicationSummary(SurgeryApplication application) {
        PatientSnapshot patient = application.getPatient();
        if (patient == null) {
            throw new PreopUnprocessableException("applicationId", "手术申请缺少患者快照");
        }
        PreopApplicationSummary summary = new PreopApplicationSummary();
        summary.setApplicationId(application.getApplicationId());
        summary.setPatientId(patient.getPatientId());
        summary.setPatientSnapshot(toPreopSnapshot(patient));
        summary.setSurgeryName(application.getSurgeryName());
        summary.setDepartmentName(application.getDepartmentName());
        summary.setStatus(PreopApplicationSummary.StatusEnum.fromValue(
                application.getStatus().getValue()));
        return summary;
    }

    private static PreopPatientSnapshot toPreopSnapshot(PatientSnapshot patient) {
        PreopPatientSnapshot snapshot = new PreopPatientSnapshot();
        snapshot.setPatientId(patient.getPatientId());
        snapshot.setHisPatientNo(patient.getHisPatientNo());
        snapshot.setInpatientNoMasked(patient.getInpatientNoMasked());
        snapshot.setPatientName(patient.getName());
        snapshot.setGender(patient.getGender() == null ? PreopPatientSnapshot.GenderEnum.UNKNOWN
                : PreopPatientSnapshot.GenderEnum.fromValue(patient.getGender().getValue()));
        snapshot.setBirthDate(patient.getBirthDate());
        snapshot.setAge(patient.getAge());
        snapshot.setDepartmentName(patient.getDepartmentName());
        snapshot.setWard(patient.getWard());
        snapshot.setBedNo(patient.getBedNo());
        return snapshot;
    }

    private void audit(String action, PreopAssessmentEntity entity, AuthPrincipal caller,
            AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", action);
        detail.put("assessmentId", entity.getId().toString());
        detail.put("applicationId", entity.getApplicationId().toString());
        detail.put("status", entity.getStatus());
        detail.put("version", entity.getVersion());
        detail.put("riskLevel", entity.getRiskLevel());
        detail.put("rcriScore", entity.getRcriScore());
        detail.put("apfelScore", entity.getApfelScore());
        detail.put("stopbangScore", entity.getStopbangScore());
        detail.put("dasiScore", entity.getDasiScore());
        detail.put("calculatorVersion", entity.getCalculatorVersion());
        auditWriter.record(action, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);
    }

    private String writeJson(Object value, String field) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException serializationFailure) {
            // 契约模型序列化失败属服务端错误，不回显字段值（可能含临床文本）。
            throw new IllegalStateException("术前访视 " + field + " 序列化失败", serializationFailure);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException parseFailure) {
            throw new IllegalStateException("术前访视数据损坏（" + type.getSimpleName() + "）", parseFailure);
        }
    }

    /**
     * 契约生成枚举的 {@code toString()} 即契约字面量（如 GENERAL、II）；null 原样返回，
     * 由 {@link PreopScoreCalculator#validateClinicalEnums} 负责拒绝缺项。
     */
    private static String enumValue(Enum<?> value) {
        return value == null ? null : value.toString();
    }

    private static String requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new PreopValidationException(field, "字段不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new PreopValidationException(field, "字段长度超出限制");
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new PreopValidationException(field, "字段长度超出限制");
        }
        return trimmed;
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(DISPLAY_OFFSET).truncatedTo(ChronoUnit.MILLIS);
    }

    private static String string(UUID value) {
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
