package com.anesop.m2.impl.pacu;

import com.anesop.contracts.anesthesia.model.CreatePacuRecordRequest;
import com.anesop.contracts.anesthesia.model.PacuAssessment;
import com.anesop.contracts.anesthesia.model.PacuAssessmentList;
import com.anesop.contracts.anesthesia.model.PacuDischarge;
import com.anesop.contracts.anesthesia.model.PacuDischargeCriteria;
import com.anesop.contracts.anesthesia.model.PacuDischargeRequest;
import com.anesop.contracts.anesthesia.model.PacuHandover;
import com.anesop.contracts.anesthesia.model.PacuRecord;
import com.anesop.contracts.anesthesia.model.PacuRecordList;
import com.anesop.contracts.anesthesia.model.PacuVitalSigns;
import com.anesop.contracts.anesthesia.model.RecordPacuAssessmentRequest;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.error.PacuDischargePreconditionException;
import com.anesop.m2.api.pacu.PacuService;
import com.anesop.m2.impl.config.PacuProperties;
import com.anesop.m2.impl.config.RecordProperties;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.PacuAssessmentEntity;
import com.anesop.m2.impl.persistence.entity.PacuDischargeEntity;
import com.anesop.m2.impl.persistence.entity.PacuRecordEntity;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.PacuAssessmentMapper;
import com.anesop.m2.impl.persistence.mapper.PacuDischargeMapper;
import com.anesop.m2.impl.persistence.mapper.PacuRecordMapper;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PACU 复苏记录、Aldrete 评估与转出服务实现（M3-M10-01，契约 anesthesia.yaml 0.9.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>入室必须关联“已开始”的麻醉记录单（ONGOING/LOCKED/REVISED 且 startedAt 非空）；
 *       一条记录单同时最多一条未转出记录（服务层先查 + 部分唯一索引兜底 → 409）；
 *       入室时间 ≥ 记录开始时间且 ≤ 当前时间 + 容差（400）；</li>
 *   <li>Aldrete 五项由服务端逐项校验（0–2，缺项/越界 400），总分由服务端计算（0–10），
 *       客户端无法提交总分；评估 append-only（数据库触发器 55000 为第二道防线），
 *       已转出后再评估 409；评估时间不得早于上一条评估（否则 400），latest 统一按
 *       (assessed_at, created_at, id) 判定，状态推进以 latest 为准（READY/MONITORING，
 *       READY→MONITORING 是否允许待临床确认，见 §15.9 Q-8）；</li>
 *   <li>转出前置由服务端统一评估（存在最近一次评估、五项齐全、总分 ≥ 配置阈值），
 *       未满足 409 并逐项列出条件码；criteriaConfirmed 缺省/false 400（不能替代校验）；
 *       转出在同一行锁事务内写 append-only 转出留痕并置 DISCHARGED，转出后不可重复转出/
 *       再评估（409）；</li>
 *   <li>数据范围复用记录单/台次可见性（只调 m1-api，不访问 m1 表）：按记录单列表为集合语义
 *       （越权 403），单对象操作越界 404，入室时记录单不可见 422；</li>
 *   <li>审计 PACU_ADMIT/PACU_ASSESS/PACU_DISCHARGE 与业务同事务；detail 只含标识、状态、
 *       Aldrete 分项/总分、阈值、去向与 SBAR 段落数，不含 SBAR 原文、评估备注原文与患者 PHI；
 *       冲突路径以独立事务写 result=0 审计。</li>
 * </ul>
 */
@Service
public class PacuServiceImpl implements PacuService {

    private static final String STATUS_ADMITTED = "ADMITTED";
    private static final String STATUS_MONITORING = "MONITORING";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_DISCHARGED = "DISCHARGED";
    private static final String RESOURCE_TYPE = "PACU_RECORD";
    private static final String ACTION_ADMIT = "PACU_ADMIT";
    private static final String ACTION_ASSESS = "PACU_ASSESS";
    private static final String ACTION_DISCHARGE = "PACU_DISCHARGE";
    private static final String CODE_ASSESSED = "ALDRETE_ASSESSED";
    private static final String CODE_ITEMS_COMPLETE = "ALDRETE_ITEMS_COMPLETE";
    private static final String CODE_THRESHOLD = "ALDRETE_THRESHOLD_MET";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_NOTES = 500;
    private static final int MAX_SBAR = 2000;
    /** 已开始、可入 PACU 的记录单状态（锁单/修订后仍可入复苏）。 */
    private static final Set<String> PACU_ADMITTABLE_RECORD_STATUSES =
            Set.of("ONGOING", "LOCKED", "REVISED");

    private final PacuRecordMapper pacuRecordMapper;
    private final PacuAssessmentMapper assessmentMapper;
    private final PacuDischargeMapper dischargeMapper;
    private final AnesthesiaRecordMapper recordMapper;
    private final SchedulingService schedulingService;
    private final AuditWriter auditWriter;
    private final ClinicalFailureAuditor failureAuditor;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RecordProperties recordProperties;
    private final PacuProperties pacuProperties;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public PacuServiceImpl(PacuRecordMapper pacuRecordMapper,
            PacuAssessmentMapper assessmentMapper,
            PacuDischargeMapper dischargeMapper,
            AnesthesiaRecordMapper recordMapper,
            SchedulingService schedulingService,
            AuditWriter auditWriter,
            ClinicalFailureAuditor failureAuditor,
            ObjectMapper objectMapper,
            Clock clock,
            RecordProperties recordProperties,
            PacuProperties pacuProperties) {
        this.pacuRecordMapper = pacuRecordMapper;
        this.assessmentMapper = assessmentMapper;
        this.dischargeMapper = dischargeMapper;
        this.recordMapper = recordMapper;
        this.schedulingService = schedulingService;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.recordProperties = recordProperties;
        this.pacuProperties = pacuProperties;
    }

    // ------------------------------------------------------------------ 入室 --

    @Override
    public PacuRecordList listByRecord(String recordId, AuthPrincipal caller) {
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
        PacuRecordList result = new PacuRecordList();
        List<PacuRecord> items = new ArrayList<>();
        for (PacuRecordEntity row : pacuRecordMapper.selectList(
                new QueryWrapper<PacuRecordEntity>()
                        .eq("anesthesia_record_id", id)
                        .orderByDesc("created_at"))) {
            items.add(toRecordDto(row, assessmentMapper.selectLatestByPacu(row.getId())));
        }
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PacuRecord admit(CreatePacuRecordRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        UUID recordId = parseUuid(request == null ? null : request.getRecordId());
        if (recordId == null) {
            throw new M2ValidationException("recordId", "麻醉记录单标识格式不正确");
        }
        AnesthesiaRecordEntity record = recordMapper.selectById(recordId);
        if (record == null) {
            throw new M2UnprocessableException("recordId", "麻醉记录单不存在或不可见");
        }
        requireVisibleSchedule(record, caller, true);
        if (record.getStartedAt() == null
                || !PACU_ADMITTABLE_RECORD_STATUSES.contains(record.getStatus())) {
            throw new M2ConflictException("麻醉记录单尚未记录麻醉开始，不能入 PACU");
        }
        Instant admittedAt = request.getAdmittedAt() == null ? null
                : request.getAdmittedAt().toInstant();
        if (admittedAt == null) {
            throw new M2ValidationException("admittedAt", "入室时间不能为空");
        }
        if (admittedAt.isBefore(record.getStartedAt())) {
            throw new M2ValidationException("admittedAt", "入室时间不得早于麻醉记录开始时间");
        }
        requireNotFuture(admittedAt, "admittedAt");
        validateAdmission(request);

        if (pacuRecordMapper.selectActiveByRecord(recordId) != null) {
            auditRejected(ACTION_ADMIT, recordId.toString(), "ACTIVE_PACU_EXISTS", caller, context);
            throw new M2ConflictException("该麻醉记录单已有未转出的复苏记录");
        }

        Instant now = truncatedNow();
        PacuRecordEntity entity = new PacuRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setAnesthesiaRecordId(recordId);
        entity.setPatientId(record.getPatientId());
        entity.setAdmittedAt(admittedAt);
        entity.setStatus(STATUS_ADMITTED);
        entity.setAdmittedVitalSigns(vitalSignsJson(request.getVitalSigns()));
        entity.setConsciousness(request.getConsciousness().getValue());
        entity.setPainScore(request.getPainScore());
        entity.setNausea(request.getNausea());
        entity.setAirway(request.getAirway().getValue());
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        try {
            pacuRecordMapper.insertRecord(entity);
        } catch (DuplicateKeyException concurrentAdmission) {
            auditRejected(ACTION_ADMIT, recordId.toString(), "ACTIVE_PACU_EXISTS", caller, context);
            throw new M2ConflictException("该麻醉记录单已有未转出的复苏记录");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_ADMIT);
        detail.put("pacuId", entity.getId().toString());
        detail.put("recordId", recordId.toString());
        detail.put("status", STATUS_ADMITTED);
        detail.put("admittedAt", format(admittedAt));
        detail.put("aldreteThreshold", pacuProperties.aldreteThreshold());
        auditWriter.record(ACTION_ADMIT, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);

        return toRecordDto(entity, null);
    }

    // -------------------------------------------------------------- 查询详情 --

    @Override
    public PacuRecord get(String pacuId, AuthPrincipal caller) {
        PacuRecordEntity entity = requirePacu(pacuId, caller);
        return toRecordDto(entity, assessmentMapper.selectLatestByPacu(entity.getId()));
    }

    @Override
    public PacuAssessmentList listAssessments(String pacuId, AuthPrincipal caller) {
        PacuRecordEntity entity = requirePacu(pacuId, caller);
        PacuAssessmentList result = new PacuAssessmentList();
        List<PacuAssessment> items = new ArrayList<>();
        for (PacuAssessmentEntity row : assessmentMapper.selectByPacu(entity.getId())) {
            items.add(toAssessmentDto(row));
        }
        result.setItems(items);
        return result;
    }

    // -------------------------------------------------------------- 评估 --

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PacuAssessment recordAssessment(String pacuId, RecordPacuAssessmentRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        PacuRecordEntity visible = requirePacu(pacuId, caller);
        PacuRecordEntity entity = pacuRecordMapper.selectByIdForUpdate(visible.getId());
        if (entity == null) {
            throw new NoSuchElementException("复苏记录不存在或不可见");
        }
        if (STATUS_DISCHARGED.equals(entity.getStatus())) {
            auditRejected(ACTION_ASSESS, entity.getId().toString(), "ALREADY_DISCHARGED",
                    caller, context);
            throw new M2ConflictException("已转出的复苏记录不可再记录评估");
        }
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        Instant assessedAt = request.getAssessedAt() == null ? null
                : request.getAssessedAt().toInstant();
        if (assessedAt == null) {
            throw new M2ValidationException("assessedAt", "评估时间不能为空");
        }
        if (assessedAt.isBefore(entity.getAdmittedAt())) {
            throw new M2ValidationException("assessedAt", "评估时间不得早于入室时间");
        }
        requireNotFuture(assessedAt, "assessedAt");
        PacuAssessmentEntity previous = assessmentMapper.selectLatestByPacu(entity.getId());
        if (previous != null && assessedAt.isBefore(previous.getAssessedAt())) {
            throw new M2ValidationException("assessedAt",
                    "评估时间不得早于上一条评估（时间序列只增，latest 按 assessed_at/created_at/id 判定）");
        }

        int activity = requireItem(request.getActivity(), "activity");
        int respiration = requireItem(request.getRespiration(), "respiration");
        int circulation = requireItem(request.getCirculation(), "circulation");
        int consciousness = requireItem(request.getConsciousness(), "consciousness");
        int oxygenation = requireItem(request.getOxygenation(), "oxygenation");
        String notes = optionalText(request.getNotes(), "notes", MAX_NOTES);
        int total = AldreteCalculator.total(activity, respiration, circulation, consciousness,
                oxygenation);

        Instant now = truncatedNow();
        PacuAssessmentEntity assessment = new PacuAssessmentEntity();
        assessment.setId(UUID.randomUUID());
        assessment.setPacuId(entity.getId());
        assessment.setAssessedAt(assessedAt);
        assessment.setAldreteActivity(activity);
        assessment.setAldreteRespiration(respiration);
        assessment.setAldreteCirculation(circulation);
        assessment.setAldreteConsciousness(consciousness);
        assessment.setAldreteOxygenation(oxygenation);
        assessment.setAldreteTotal(total);
        assessment.setNotes(notes);
        assessment.setCreatedAt(now);
        assessment.setCreatedBy(actorId(caller));
        assessmentMapper.insertAssessment(assessment);

        // 状态推进以数据库口径的最新评估为准（assessed_at, created_at, id）；不直接使用本次
        // 提交分值，避免同刻评估等边界下状态与 PacuRecord.latest* 投影不一致。
        PacuAssessmentEntity latest = assessmentMapper.selectLatestByPacu(entity.getId());
        int latestTotal = latest == null ? total : latest.getAldreteTotal();
        boolean ready = AldreteCalculator.meetsThreshold(latestTotal,
                pacuProperties.aldreteThreshold());
        String nextStatus = ready ? STATUS_READY : STATUS_MONITORING;

        int updated = pacuRecordMapper.markAssessed(entity.getId(), entity.getVersion(),
                nextStatus, now, actorId(caller));
        if (updated == 0) {
            auditRejected(ACTION_ASSESS, entity.getId().toString(), "CONCURRENT_MODIFICATION",
                    caller, context);
            throw new M2ConflictException("复苏记录已被修改或已转出，请刷新后重试");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_ASSESS);
        detail.put("pacuId", entity.getId().toString());
        detail.put("recordId", entity.getAnesthesiaRecordId().toString());
        detail.put("assessmentId", assessment.getId().toString());
        detail.put("activity", activity);
        detail.put("respiration", respiration);
        detail.put("circulation", circulation);
        detail.put("consciousness", consciousness);
        detail.put("oxygenation", oxygenation);
        detail.put("aldreteTotal", total);
        detail.put("aldreteThreshold", pacuProperties.aldreteThreshold());
        detail.put("status", nextStatus);
        auditWriter.record(ACTION_ASSESS, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);

        return toAssessmentDto(assessment);
    }

    // -------------------------------------------------------------- 转出 --

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PacuDischarge discharge(String pacuId, PacuDischargeRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        PacuRecordEntity visible = requirePacu(pacuId, caller);
        PacuRecordEntity entity = pacuRecordMapper.selectByIdForUpdate(visible.getId());
        if (entity == null) {
            throw new NoSuchElementException("复苏记录不存在或不可见");
        }
        if (STATUS_DISCHARGED.equals(entity.getStatus())) {
            auditRejected(ACTION_DISCHARGE, entity.getId().toString(), "ALREADY_DISCHARGED",
                    caller, context);
            throw new M2ConflictException("复苏记录已转出，不可重复转出");
        }
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        if (!Boolean.TRUE.equals(request.getCriteriaConfirmed())) {
            throw new M2ValidationException("criteriaConfirmed",
                    "请先逐项确认转出条件后再转出");
        }
        if (request.getDestination() == null) {
            throw new M2ValidationException("destination", "转出去向不能为空");
        }
        String situation = requireText(handoverField(request, "situation"), "handover.situation",
                MAX_SBAR);
        String background = requireText(handoverField(request, "background"), "handover.background",
                MAX_SBAR);
        String assessmentText = requireText(handoverField(request, "assessment"),
                "handover.assessment", MAX_SBAR);
        String recommendation = requireText(handoverField(request, "recommendation"),
                "handover.recommendation", MAX_SBAR);

        PacuAssessmentEntity latest = assessmentMapper.selectLatestByPacu(entity.getId());
        int threshold = pacuProperties.aldreteThreshold();
        boolean itemsComplete = latest != null
                && AldreteCalculator.isItemValid(latest.getAldreteActivity())
                && AldreteCalculator.isItemValid(latest.getAldreteRespiration())
                && AldreteCalculator.isItemValid(latest.getAldreteCirculation())
                && AldreteCalculator.isItemValid(latest.getAldreteConsciousness())
                && AldreteCalculator.isItemValid(latest.getAldreteOxygenation());
        boolean meetsThreshold = itemsComplete && latest != null
                && AldreteCalculator.meetsThreshold(latest.getAldreteTotal(), threshold);

        List<PacuDischargePreconditionException.UnmetCondition> unmet = new ArrayList<>();
        if (latest == null) {
            unmet.add(new PacuDischargePreconditionException.UnmetCondition(CODE_ASSESSED,
                    "尚未记录任何 Aldrete 评估"));
        }
        if (latest != null && !itemsComplete) {
            unmet.add(new PacuDischargePreconditionException.UnmetCondition(CODE_ITEMS_COMPLETE,
                    "Aldrete 五项评分不齐全或存在越界分值"));
        }
        if (latest != null && itemsComplete && !meetsThreshold) {
            unmet.add(new PacuDischargePreconditionException.UnmetCondition(CODE_THRESHOLD,
                    "Aldrete 总分未达到转出阈值 " + threshold));
        }
        if (!unmet.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("operation", ACTION_DISCHARGE);
            detail.put("pacuId", entity.getId().toString());
            detail.put("recordId", entity.getAnesthesiaRecordId().toString());
            detail.put("rejection", "DISCHARGE_PRECONDITION_UNMET");
            detail.put("unmetConditions", unmet.stream()
                    .map(PacuDischargePreconditionException.UnmetCondition::code).toList());
            failureAuditor.recordFailure(ACTION_DISCHARGE, RESOURCE_TYPE,
                    entity.getId().toString(), detail, caller, context);
            throw new PacuDischargePreconditionException(unmet);
        }

        Instant now = truncatedNow();
        UUID actorId = actorId(caller);
        Map<String, Object> handover = new LinkedHashMap<>();
        handover.put("situation", situation);
        handover.put("background", background);
        handover.put("assessment", assessmentText);
        handover.put("recommendation", recommendation);
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("aldreteThreshold", threshold);
        criteria.put("aldreteTotal", latest.getAldreteTotal());
        criteria.put("meetsThreshold", true);
        criteria.put("itemsComplete", true);
        criteria.put("latestAssessmentId", latest.getId().toString());
        criteria.put("latestAssessmentAt", format(latest.getAssessedAt()));

        PacuDischargeEntity discharge = new PacuDischargeEntity();
        discharge.setId(UUID.randomUUID());
        discharge.setPacuId(entity.getId());
        discharge.setDischargedAt(now);
        discharge.setDestination(request.getDestination().getValue());
        discharge.setHandover(toJson(handover));
        discharge.setCriteriaSnapshot(toJson(criteria));
        discharge.setCreatedAt(now);
        discharge.setCreatedBy(actorId);
        try {
            dischargeMapper.insertDischarge(discharge);
        } catch (DuplicateKeyException concurrentDischarge) {
            auditRejected(ACTION_DISCHARGE, entity.getId().toString(), "ALREADY_DISCHARGED",
                    caller, context);
            throw new M2ConflictException("复苏记录已转出，不可重复转出");
        }
        int updated = pacuRecordMapper.markDischarged(entity.getId(), entity.getVersion(), now,
                actorId);
        if (updated == 0) {
            auditRejected(ACTION_DISCHARGE, entity.getId().toString(), "CONCURRENT_MODIFICATION",
                    caller, context);
            throw new M2ConflictException("复苏记录已被修改或已转出，请刷新后重试");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_DISCHARGE);
        detail.put("pacuId", entity.getId().toString());
        detail.put("recordId", entity.getAnesthesiaRecordId().toString());
        detail.put("dischargeId", discharge.getId().toString());
        detail.put("destination", discharge.getDestination());
        detail.put("aldreteTotal", latest.getAldreteTotal());
        detail.put("aldreteThreshold", threshold);
        detail.put("latestAssessmentId", latest.getId().toString());
        detail.put("handoverSections", handover.size());
        auditWriter.record(ACTION_DISCHARGE, RESOURCE_TYPE, entity.getId().toString(), true,
                detail, actorId, caller == null ? null : caller.username(), context);

        return toDischargeDto(discharge);
    }

    @Override
    public PacuDischarge getDischarge(String pacuId, AuthPrincipal caller) {
        PacuRecordEntity entity = requirePacu(pacuId, caller);
        PacuDischargeEntity discharge = dischargeMapper.selectByPacu(entity.getId());
        if (discharge == null) {
            throw new NoSuchElementException("转出记录不存在或不可见");
        }
        return toDischargeDto(discharge);
    }

    // ------------------------------------------------------------- helpers --

    private PacuRecordEntity requirePacu(String pacuId, AuthPrincipal caller) {
        UUID id = parseUuid(pacuId);
        PacuRecordEntity entity = id == null ? null : pacuRecordMapper.selectById(id);
        if (entity == null) {
            throw new NoSuchElementException("复苏记录不存在或不可见");
        }
        AnesthesiaRecordEntity record = recordMapper.selectById(entity.getAnesthesiaRecordId());
        if (record == null) {
            throw new NoSuchElementException("复苏记录不存在或不可见");
        }
        requireVisibleSchedule(record, caller, false);
        return entity;
    }

    /**
     * 数据范围沿台次/申请可见性（只调 m1-api，不读 m1 表）。
     *
     * @param unprocessable true=请求体参数（入室时记录单不可见映射 422）；false=单对象/集合语义
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
                throw new M2UnprocessableException("recordId", "麻醉记录单不存在或不可见");
            }
            throw new NoSuchElementException("复苏记录不存在或不可见");
        }
    }

    private void validateAdmission(CreatePacuRecordRequest request) {
        PacuVitalSigns vitals = request.getVitalSigns();
        if (vitals == null) {
            throw new M2ValidationException("vitalSigns", "入室生命体征不能为空");
        }
        requireRange(vitals.getSystolicBp(), 40, 300, "vitalSigns.systolicBp");
        requireRange(vitals.getDiastolicBp(), 20, 200, "vitalSigns.diastolicBp");
        requireRange(vitals.getHeartRate(), 20, 250, "vitalSigns.heartRate");
        requireRange(vitals.getSpo2(), 50, 100, "vitalSigns.spo2");
        requireRange(vitals.getRespiratoryRate(), 4, 60, "vitalSigns.respiratoryRate");
        if (vitals.getTemperature() != null
                && (vitals.getTemperature().doubleValue() < 30
                        || vitals.getTemperature().doubleValue() > 45)) {
            throw new M2ValidationException("vitalSigns.temperature", "体温超出物理范围（30–45 ℃）");
        }
        if (request.getConsciousness() == null) {
            throw new M2ValidationException("consciousness", "入室意识状态不能为空");
        }
        if (request.getPainScore() == null) {
            throw new M2ValidationException("painScore", "疼痛评分不能为空");
        }
        if (request.getPainScore() < 0 || request.getPainScore() > 10) {
            throw new M2ValidationException("painScore", "疼痛评分必须在 0–10 之间");
        }
        if (request.getNausea() == null) {
            throw new M2ValidationException("nausea", "恶心/呕吐标记不能为空");
        }
        if (request.getAirway() == null) {
            throw new M2ValidationException("airway", "气道方式不能为空");
        }
    }

    private static void requireRange(Integer value, int min, int max, String field) {
        if (value != null && (value < min || value > max)) {
            throw new M2ValidationException(field, "生命体征超出物理范围（" + min + "–" + max + "）");
        }
    }

    private static int requireItem(Integer value, String field) {
        if (value == null) {
            throw new M2ValidationException(field, "Aldrete 分项不能为空（0–2）");
        }
        if (value < AldreteCalculator.ITEM_MIN || value > AldreteCalculator.ITEM_MAX) {
            throw new M2ValidationException(field, "Aldrete 分项必须在 0–2 之间");
        }
        return value;
    }

    private void requireNotFuture(Instant time, String field) {
        Instant limit = truncatedNow().plusSeconds(recordProperties.timeToleranceSeconds());
        if (time.isAfter(limit)) {
            throw new M2ValidationException(field, "时间不得晚于服务端当前时间（含容差）");
        }
    }

    private static String handoverField(PacuDischargeRequest request, String name) {
        PacuHandover handover = request.getHandover();
        if (handover == null) {
            return null;
        }
        return switch (name) {
            case "situation" -> handover.getSituation();
            case "background" -> handover.getBackground();
            case "assessment" -> handover.getAssessment();
            case "recommendation" -> handover.getRecommendation();
            default -> null;
        };
    }

    private static String requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new M2ValidationException(field, "SBAR 交接段落不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new M2ValidationException(field, "SBAR 交接段落长度超出限制（≤" + maxLength + "）");
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new M2ValidationException(field, "字段长度超出限制（≤" + maxLength + "）");
        }
        return trimmed;
    }

    private String vitalSignsJson(PacuVitalSigns vitals) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (vitals.getSystolicBp() != null) {
            map.put("systolicBp", vitals.getSystolicBp());
        }
        if (vitals.getDiastolicBp() != null) {
            map.put("diastolicBp", vitals.getDiastolicBp());
        }
        if (vitals.getHeartRate() != null) {
            map.put("heartRate", vitals.getHeartRate());
        }
        if (vitals.getSpo2() != null) {
            map.put("spo2", vitals.getSpo2());
        }
        if (vitals.getRespiratoryRate() != null) {
            map.put("respiratoryRate", vitals.getRespiratoryRate());
        }
        if (vitals.getTemperature() != null) {
            map.put("temperature", vitals.getTemperature());
        }
        return toJson(map);
    }

    private PacuRecord toRecordDto(PacuRecordEntity entity, PacuAssessmentEntity latest) {
        PacuRecord dto = new PacuRecord();
        dto.setPacuId(entity.getId().toString());
        dto.setRecordId(entity.getAnesthesiaRecordId().toString());
        dto.setPatientId(entity.getPatientId().toString());
        dto.setAdmittedAt(offset(entity.getAdmittedAt()));
        dto.setDischargedAt(offset(entity.getDischargedAt()));
        dto.setStatus(PacuRecord.StatusEnum.fromValue(entity.getStatus()));
        dto.setVitalSigns(readVitalSigns(entity.getAdmittedVitalSigns()));
        dto.setConsciousness(PacuRecord.ConsciousnessEnum.fromValue(entity.getConsciousness()));
        dto.setPainScore(entity.getPainScore());
        dto.setNausea(entity.getNausea());
        dto.setAirway(PacuRecord.AirwayEnum.fromValue(entity.getAirway()));
        if (latest != null) {
            dto.setLatestAssessmentId(latest.getId().toString());
            dto.setLatestAssessmentAt(offset(latest.getAssessedAt()));
            dto.setLatestAldreteTotal(latest.getAldreteTotal());
        }
        dto.setAldreteThreshold(pacuProperties.aldreteThreshold());
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        dto.setUpdatedAt(offset(entity.getUpdatedAt()));
        dto.setUpdatedBy(string(entity.getUpdatedBy()));
        dto.setVersion(entity.getVersion() == null ? 1 : entity.getVersion().intValue());
        return dto;
    }

    private PacuVitalSigns readVitalSigns(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return new PacuVitalSigns();
        }
        try {
            return objectMapper.readValue(json, PacuVitalSigns.class);
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalStateException("入室生命体征 JSON 解析失败", invalidJson);
        }
    }

    private static PacuAssessment toAssessmentDto(PacuAssessmentEntity entity) {
        PacuAssessment dto = new PacuAssessment();
        dto.setAssessmentId(entity.getId().toString());
        dto.setPacuId(entity.getPacuId().toString());
        dto.setAssessedAt(offset(entity.getAssessedAt()));
        dto.setActivity(entity.getAldreteActivity());
        dto.setRespiration(entity.getAldreteRespiration());
        dto.setCirculation(entity.getAldreteCirculation());
        dto.setConsciousness(entity.getAldreteConsciousness());
        dto.setOxygenation(entity.getAldreteOxygenation());
        dto.setAldreteTotal(entity.getAldreteTotal());
        dto.setNotes(entity.getNotes());
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        return dto;
    }

    private PacuDischarge toDischargeDto(PacuDischargeEntity entity) {
        PacuDischarge dto = new PacuDischarge();
        dto.setDischargeId(entity.getId().toString());
        dto.setPacuId(entity.getPacuId().toString());
        dto.setDischargedAt(offset(entity.getDischargedAt()));
        dto.setDestination(PacuDischarge.DestinationEnum.fromValue(entity.getDestination()));
        dto.setHandover(readJson(entity.getHandover(), PacuHandover.class));
        dto.setCriteria(readJson(entity.getCriteriaSnapshot(), PacuDischargeCriteria.class));
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        return dto;
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException invalidJson) {
            throw new IllegalStateException("转出留痕 JSON 解析失败", invalidJson);
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("转出留痕 JSON 序列化失败", serializationFailure);
        }
    }

    /** 冲突/拒绝失败留痕（独立事务 result=0；只含标识与拒绝码，不含 PHI/文本原文）。 */
    private void auditRejected(String action, String resourceId, String rejection,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", action);
        detail.put("rejection", rejection);
        failureAuditor.recordFailure(action, RESOURCE_TYPE, resourceId, detail, caller, context);
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
