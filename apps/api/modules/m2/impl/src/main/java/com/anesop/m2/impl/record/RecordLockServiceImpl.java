package com.anesop.m2.impl.record;

import com.anesop.contracts.anesthesia.model.ConsentRecord;
import com.anesop.contracts.anesthesia.model.ConsentRecordList;
import com.anesop.contracts.anesthesia.model.CreateRevisionRequest;
import com.anesop.contracts.anesthesia.model.LockPrecondition;
import com.anesop.contracts.anesthesia.model.LockRecordRequest;
import com.anesop.contracts.anesthesia.model.LockRecordResult;
import com.anesop.contracts.anesthesia.model.RecordLockStatus;
import com.anesop.contracts.anesthesia.model.RecordRevision;
import com.anesop.contracts.anesthesia.model.RecordRevisionList;
import com.anesop.contracts.anesthesia.model.RecordEffectiveCorrection;
import com.anesop.contracts.anesthesia.model.RecordEffectiveValues;
import com.anesop.contracts.anesthesia.model.RecordEffectiveView;
import com.anesop.contracts.anesthesia.model.RecordRevisionSummary;
import com.anesop.contracts.anesthesia.model.RevisionDiff;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.consent.ConsentService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2LockPreconditionException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.record.RecordLockService;
import com.anesop.m2.api.threeway.ThreeWayCheckService;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.DrugAdministrationEntity;
import com.anesop.m2.impl.persistence.entity.FluidRecordEntity;
import com.anesop.m2.impl.persistence.entity.RecordEventEntity;
import com.anesop.m2.impl.persistence.entity.RecordRevisionEntity;
import com.anesop.m2.impl.persistence.entity.ResuscitationRecordEntity;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.DrugAdministrationMapper;
import com.anesop.m2.impl.persistence.mapper.FluidRecordMapper;
import com.anesop.m2.impl.persistence.mapper.RecordEventMapper;
import com.anesop.m2.impl.persistence.mapper.RecordRevisionMapper;
import com.anesop.m2.impl.persistence.mapper.ResuscitationRecordMapper;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 麻醉记录单锁单与修订服务实现（M3-M2-03，契约 anesthesia.yaml 0.8.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>锁单前置由服务端统一评估（三方核查完成、当前有效 SIGNED 同意、开始/结束时间完整、
 *       至少 1 个时间轴事件、尚未锁单），不满足返回 409 并逐项列出条件码；前端不得自行推导；</li>
 *   <li>锁单/修订均在记录单行锁（{@code SELECT ... FOR UPDATE}）内执行：重复锁单 409、
 *       version 过期 409、revision_no 并发递增被串行化；数据库触发器（V17）为锁单不可变
 *       与修订 append-only 的第二道防线；</li>
 *   <li>修订只写快照不改原记录行：更正后的麻醉方式仅进入修订快照，首版差异对比
 *       previousRevisionNo=0 的锁单原始内容，历史版本与原值始终可追溯；记录详情/打印读取
 *       GET /records/{recordId}/effective 的 effective 读时投影，原行保持不可变；</li>
 *   <li>快照覆盖抢救记录（含 leader/rescuers），数组按 (时间,id) 稳定排序；设备体征明细
 *       不入快照（采集链路独立留痕），边界见 docs/modules/m2/README.md §14.3；</li>
 *   <li>CA 占位（EXT-05）：signatureRef=SIM-CA-&lt;id&gt;、signatureHash=SHA-256(规范化快照)、
 *       caStatus=SIMULATED；不模拟真实 CA 有效签名，不保存签名原文/证书链；</li>
 *   <li>审计 RECORD_LOCK/RECORD_REVISE/RECORD_SIGN 与业务同事务；失败前置校验以独立事务写
 *       result=0 审计（只含条件码，不含 PHI、原因/变更原文）。</li>
 * </ul>
 */
@Service
public class RecordLockServiceImpl implements RecordLockService {

    private static final String STATUS_LOCKED = "LOCKED";
    private static final String STATUS_REVISED = "REVISED";
    private static final String CA_SIMULATED = "SIMULATED";
    private static final String SIGNATURE_REF_PREFIX = "SIM-CA-";
    private static final String RESOURCE_TYPE = "ANESTHESIA_RECORD";
    private static final String ACTION_LOCK = "RECORD_LOCK";
    private static final String ACTION_REVISE = "RECORD_REVISE";
    private static final String ACTION_SIGN = "RECORD_SIGN";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_TEXT = 500;

    private static final String CODE_THREE_WAY = "THREE_WAY_CHECK_COMPLETED";
    private static final String CODE_CONSENT = "CONSENT_SIGNED";
    private static final String CODE_STARTED = "RECORD_STARTED";
    private static final String CODE_ENDED = "RECORD_ENDED";
    private static final String CODE_EVENTS = "AT_LEAST_ONE_EVENT";
    private static final String CODE_NOT_LOCKED = "NOT_LOCKED";

    private final AnesthesiaRecordMapper recordMapper;
    private final RecordEventMapper eventMapper;
    private final DrugAdministrationMapper drugMapper;
    private final FluidRecordMapper fluidMapper;
    private final ResuscitationRecordMapper rescueMapper;
    private final RecordRevisionMapper revisionMapper;
    private final SchedulingService schedulingService;
    private final ThreeWayCheckService threeWayCheckService;
    private final ConsentService consentService;
    private final RecordRevisionSnapshotBuilder snapshotBuilder;
    private final AuditWriter auditWriter;
    private final ClinicalFailureAuditor failureAuditor;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public RecordLockServiceImpl(AnesthesiaRecordMapper recordMapper,
            RecordEventMapper eventMapper,
            DrugAdministrationMapper drugMapper,
            FluidRecordMapper fluidMapper,
            ResuscitationRecordMapper rescueMapper,
            RecordRevisionMapper revisionMapper,
            SchedulingService schedulingService,
            ThreeWayCheckService threeWayCheckService,
            ConsentService consentService,
            RecordRevisionSnapshotBuilder snapshotBuilder,
            AuditWriter auditWriter,
            ClinicalFailureAuditor failureAuditor,
            Clock clock) {
        this.recordMapper = recordMapper;
        this.eventMapper = eventMapper;
        this.drugMapper = drugMapper;
        this.fluidMapper = fluidMapper;
        this.rescueMapper = rescueMapper;
        this.revisionMapper = revisionMapper;
        this.schedulingService = schedulingService;
        this.threeWayCheckService = threeWayCheckService;
        this.consentService = consentService;
        this.snapshotBuilder = snapshotBuilder;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.clock = clock;
    }

    @Override
    public RecordLockStatus lockStatus(String recordId, AuthPrincipal caller) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        requireVisibleSchedule(entity, caller);
        int eventCount = eventMapper.countByRecord(entity.getId());
        List<LockPrecondition> preconditions = evaluatePreconditions(entity, eventCount, caller);

        RecordLockStatus status = new RecordLockStatus();
        status.setRecordId(entity.getId().toString());
        status.setLocked(entity.getLockedAt() != null);
        status.setLockedAt(offset(entity.getLockedAt()));
        status.setLockedBy(string(entity.getLockedBy()));
        status.setStatus(RecordLockStatus.StatusEnum.fromValue(
                contractStatus(entity.getStatus())));
        Integer currentRevision = entity.getCurrentRevision();
        status.setCurrentRevision(currentRevision == null ? Integer.valueOf(0) : currentRevision);
        status.setCanLock(entity.getLockedAt() == null
                && preconditions.stream().allMatch(LockPrecondition::getSatisfied));
        status.setSignatureRef(entity.getLockSignatureRef());
        status.setSignatureHash(entity.getLockSignatureHash());
        status.setCaStatus(entity.getLockCaStatus() == null ? null
                : RecordLockStatus.CaStatusEnum.fromValue(entity.getLockCaStatus()));
        status.setPreconditions(preconditions);
        return status;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LockRecordResult lock(String recordId, LockRecordRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        return lock(recordId, request, caller, context, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LockRecordResult lock(String recordId, LockRecordRequest request, AuthPrincipal caller,
            AuthRequestContext context, boolean administrator) {
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        if (!Boolean.TRUE.equals(request.getConfirmChecklist())) {
            throw new M2ValidationException("confirmChecklist",
                    "请先逐项确认锁单前置条件清单后再锁单");
        }
        if (request.getVersion() == null) {
            throw new M2ValidationException("version", "版本号不能为空");
        }
        UUID id = parseUuid(recordId);
        AnesthesiaRecordEntity visible = id == null ? null : recordMapper.selectById(id);
        if (visible == null) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        requireVisibleSchedule(visible, caller);

        AnesthesiaRecordEntity entity = recordMapper.selectByIdForUpdate(id);
        if (entity == null) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        if (entity.getLockedAt() != null) {
            auditRejected(ACTION_LOCK, entity, "ALREADY_LOCKED", caller, context);
            throw new M2ConflictException("记录单已锁单，不可重复锁单；修正请新增修订版本");
        }
        if (entity.getVersion() == null
                || !entity.getVersion().equals(request.getVersion().longValue())) {
            auditRejected(ACTION_LOCK, entity, "VERSION_CONFLICT", caller, context);
            throw new M2ConflictException("麻醉记录单版本已过期，请刷新后重试");
        }
        int eventCount = eventMapper.countByRecord(entity.getId());
        List<LockPrecondition> preconditions = evaluatePreconditions(entity, eventCount, caller);
        List<M2LockPreconditionException.UnmetCondition> unmet = new ArrayList<>();
        for (LockPrecondition precondition : preconditions) {
            if (!precondition.getSatisfied()) {
                unmet.add(new M2LockPreconditionException.UnmetCondition(
                        precondition.getCode().getValue(), precondition.getDetail()));
            }
        }
        if (!unmet.isEmpty()) {
            auditLockRejected(entity, unmet, caller, context);
            throw new M2LockPreconditionException(unmet);
        }

        Instant now = truncatedNow();
        UUID actorId = actorId(caller);
        String signatureRef = SIGNATURE_REF_PREFIX + entity.getId();
        Map<String, Object> snapshot = snapshotBuilder.build(entity,
                eventMapper.selectByRecord(entity.getId()), drugMapper.selectByRecord(entity.getId()),
                fluidMapper.selectByRecord(entity.getId()),
                rescueMapper.selectByRecord(entity.getId()),
                fluidMapper.selectTotals(entity.getId()), entity.getAnesthesiaType(), null, null);
        String signatureHash = snapshotBuilder.hash(snapshot);

        int updated = recordMapper.lockRecord(entity.getId(), entity.getVersion(), now, actorId,
                signatureRef, signatureHash, CA_SIMULATED, now, actorId);
        if (updated == 0) {
            throw new M2ConflictException("记录单已被修改或已锁单，请刷新后重试");
        }
        entity.setLockedAt(now);
        entity.setLockedBy(actorId);
        entity.setLockSignatureRef(signatureRef);
        entity.setLockSignatureHash(signatureHash);
        entity.setLockCaStatus(CA_SIMULATED);
        entity.setStatus(STATUS_LOCKED);
        entity.setCurrentRevision(0);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId);
        entity.setVersion(entity.getVersion() + 1);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_LOCK);
        detail.put("recordId", entity.getId().toString());
        detail.put("scheduleId", entity.getScheduleId().toString());
        detail.put("status", entity.getStatus());
        detail.put("lockedAt", now.toString());
        detail.put("currentRevision", 0);
        detail.put("caStatus", CA_SIMULATED);
        detail.put("signatureRef", signatureRef);
        detail.put("preconditionsSatisfied", preconditions.size());
        detail.put("administratorOperation", administrator);
        auditWriter.record(ACTION_LOCK, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId, caller == null ? null : caller.username(), context);
        auditSignature(ACTION_LOCK, "LOCK", entity.getId(), signatureRef, signatureHash,
                CA_SIMULATED, null, caller, context);

        LockRecordResult result = new LockRecordResult();
        result.setId(entity.getId().toString());
        result.setStatus(LockRecordResult.StatusEnum.LOCKED);
        result.setLockedAt(offset(now));
        result.setLockedBy(string(actorId));
        result.setSignatureStatus(LockRecordResult.SignatureStatusEnum.SIGNED);
        result.setSignatureRef(signatureRef);
        result.setSignatureHash(signatureHash);
        result.setCaStatus(LockRecordResult.CaStatusEnum.SIMULATED);
        result.setCurrentRevision(0);
        return result;
    }

    @Override
    public RecordRevisionList listRevisions(String recordId, AuthPrincipal caller) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        requireVisibleSchedule(entity, caller);
        RecordRevisionList list = new RecordRevisionList();
        List<RecordRevisionSummary> items = new ArrayList<>();
        for (RecordRevisionEntity revision : revisionMapper.selectByRecord(entity.getId())) {
            items.add(toSummary(revision));
        }
        list.setItems(items);
        return list;
    }

    @Override
    public RecordRevision getRevision(String recordId, String revisionNo, AuthPrincipal caller) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        requireVisibleSchedule(entity, caller);
        Integer no = parseRevisionNo(revisionNo);
        RecordRevisionEntity revision = no == null ? null
                : revisionMapper.selectByRecordAndNo(entity.getId(), no);
        if (revision == null) {
            throw new NoSuchElementException("修订版本不存在或不可见");
        }
        return toDetail(entity, revision);
    }

    @Override
    public RecordEffectiveView effective(String recordId, AuthPrincipal caller) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        requireVisibleSchedule(entity, caller);
        int currentRevision = entity.getCurrentRevision() == null
                ? 0 : entity.getCurrentRevision();
        RecordEffectiveValues original = effectiveValues(entity.getAnesthesiaType(),
                entity.getStartedAt(), entity.getEndedAt());
        RecordEffectiveValues effective = effectiveValues(entity.getAnesthesiaType(),
                entity.getStartedAt(), entity.getEndedAt());
        List<RecordEffectiveCorrection> corrections = new ArrayList<>();
        if (currentRevision >= 1) {
            // corrections 是累积的：按修订链找出真正引入当前麻醉方式的版本与原因码，
            // 而不是简单归因到最后一版（后续仅补充说明的修订不应成为更正来源）。
            List<RecordRevisionEntity> revisions = revisionMapper.selectByRecord(entity.getId());
            if (revisions == null || revisions.isEmpty()) {
                throw new NoSuchElementException("当前修订版本不存在或不可见");
            }
            String previousType = entity.getAnesthesiaType();
            int sourceRevisionNo = 0;
            String sourceReasonCode = null;
            for (RecordRevisionEntity revision : revisions) {
                if (revision.getRevisionNo() != null
                        && revision.getRevisionNo() > currentRevision) {
                    break;
                }
                String type = snapshotAnesthesiaType(
                        snapshotBuilder.parse(revision.getSnapshot()), previousType);
                if (type != null && !type.equals(previousType)) {
                    previousType = type;
                    sourceRevisionNo = revision.getRevisionNo() == null
                            ? 0 : revision.getRevisionNo();
                    sourceReasonCode = revision.getReasonCode();
                }
            }
            if (sourceRevisionNo > 0 && !previousType.equals(entity.getAnesthesiaType())) {
                effective.setAnesthesiaType(
                        RecordEffectiveValues.AnesthesiaTypeEnum.fromValue(previousType));
                RecordEffectiveCorrection correction = new RecordEffectiveCorrection();
                correction.setField(RecordEffectiveCorrection.FieldEnum.ANESTHESIA_TYPE);
                correction.setOriginalValue(entity.getAnesthesiaType());
                correction.setEffectiveValue(previousType);
                correction.setRevisionNo(sourceRevisionNo);
                correction.setReasonCode(
                        RecordEffectiveCorrection.ReasonCodeEnum.fromValue(sourceReasonCode));
                corrections.add(correction);
            }
        }
        RecordEffectiveView view = new RecordEffectiveView();
        view.setRecordId(entity.getId().toString());
        view.setLocked(entity.getLockedAt() != null);
        view.setLockedAt(offset(entity.getLockedAt()));
        view.setLockedBy(string(entity.getLockedBy()));
        view.setStatus(RecordEffectiveView.StatusEnum.fromValue(contractStatus(entity.getStatus())));
        view.setCurrentRevision(currentRevision);
        view.setOriginal(original);
        view.setEffective(effective);
        view.setCorrections(corrections);
        view.setSignatureRef(entity.getLockSignatureRef());
        view.setSignatureHash(entity.getLockSignatureHash());
        view.setCaStatus(entity.getLockCaStatus() == null ? null
                : RecordEffectiveView.CaStatusEnum.fromValue(entity.getLockCaStatus()));
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RecordRevision createRevision(String recordId, CreateRevisionRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        return createRevision(recordId, request, caller, context, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RecordRevision createRevision(String recordId, CreateRevisionRequest request,
            AuthPrincipal caller, AuthRequestContext context, boolean administrator) {
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        if (request.getReasonCode() == null) {
            throw new M2ValidationException("reasonCode", "修订原因码不能为空");
        }
        String reasonCode = request.getReasonCode().getValue();
        String reason = requireText(request.getReason(), "reason", MAX_TEXT);
        String changes = requireText(request.getChanges(), "changes", MAX_TEXT);

        UUID id = parseUuid(recordId);
        AnesthesiaRecordEntity visible = id == null ? null : recordMapper.selectById(id);
        if (visible == null) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        requireVisibleSchedule(visible, caller);

        AnesthesiaRecordEntity entity = recordMapper.selectByIdForUpdate(id);
        if (entity == null) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        if (entity.getLockedAt() == null) {
            auditRejected(ACTION_REVISE, entity, "RECORD_NOT_LOCKED", caller, context);
            throw new M2ConflictException("记录单未锁单，不能新增修订版本；请先完成锁单");
        }
        if (request.getVersion() != null
                && !entity.getVersion().equals(request.getVersion().longValue())) {
            auditRejected(ACTION_REVISE, entity, "VERSION_CONFLICT", caller, context);
            throw new M2ConflictException("麻醉记录单版本已过期，请刷新后重试");
        }
        int previousRevisionNo = entity.getCurrentRevision() == null
                ? 0 : entity.getCurrentRevision();
        RecordRevisionEntity previous = previousRevisionNo == 0 ? null
                : revisionMapper.selectByRecordAndNo(entity.getId(), previousRevisionNo);
        // 修订内容基线：首版为锁单原始值；后续版本沿用上一版快照的麻醉方式（更正是累积的，
        // 未提交 corrections 时不会把上一版更正“改回”锁单原值）。
        String anesthesiaType = entity.getAnesthesiaType();
        if (previous != null) {
            Map<String, Object> previousSnapshot = snapshotBuilder.parse(previous.getSnapshot());
            anesthesiaType = snapshotAnesthesiaType(previousSnapshot, anesthesiaType);
        }
        if (request.getCorrections() != null
                && request.getCorrections().getAnesthesiaType() != null) {
            anesthesiaType = request.getCorrections().getAnesthesiaType().getValue();
        }

        List<RecordEventEntity> events = eventMapper.selectByRecord(entity.getId());
        List<DrugAdministrationEntity> drugs = drugMapper.selectByRecord(entity.getId());
        List<FluidRecordEntity> fluids = fluidMapper.selectByRecord(entity.getId());
        List<ResuscitationRecordEntity> rescues = rescueMapper.selectByRecord(entity.getId());
        Map<String, Object> totals = fluidMapper.selectTotals(entity.getId());
        String previousHash = previous == null ? null : previous.getSignatureHash();
        Map<String, Object> snapshot = snapshotBuilder.build(entity, events, drugs, fluids, rescues,
                totals, anesthesiaType, entity.getLockSignatureHash(), previousHash);
        String signatureHash = snapshotBuilder.hash(snapshot);
        UUID revisionId = UUID.randomUUID();
        String signatureRef = SIGNATURE_REF_PREFIX + revisionId;
        Instant now = truncatedNow();
        int revisionNo = previousRevisionNo + 1;

        RecordRevisionEntity revision = new RecordRevisionEntity();
        revision.setId(revisionId);
        revision.setRecordId(entity.getId());
        revision.setRevisionNo(revisionNo);
        revision.setReasonCode(reasonCode);
        revision.setReason(reason);
        revision.setChanges(changes);
        revision.setSnapshot(snapshotBuilder.toJson(snapshot));
        revision.setPreviousRevisionNo(previous == null ? null : previousRevisionNo);
        revision.setPreviousSignatureHash(previousHash);
        revision.setSignatureRef(signatureRef);
        revision.setSignatureHash(signatureHash);
        revision.setCaStatus(CA_SIMULATED);
        revision.setCreatedAt(now);
        revision.setCreatedBy(actorId(caller));
        try {
            revisionMapper.insertRevision(revision);
        } catch (DuplicateKeyException concurrentRevision) {
            auditRejected(ACTION_REVISE, entity, "REVISION_CONFLICT", caller, context);
            throw new M2ConflictException("修订版本号冲突，请刷新后重试");
        }
        int updated = recordMapper.markRevision(entity.getId(), entity.getVersion(),
                previousRevisionNo, revisionNo, now, actorId(caller));
        if (updated == 0) {
            auditRejected(ACTION_REVISE, entity, "CONCURRENT_MODIFICATION", caller, context);
            throw new M2ConflictException("记录单已被修改，请刷新后重试");
        }
        entity.setStatus(STATUS_REVISED);
        entity.setCurrentRevision(revisionNo);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(entity.getVersion() + 1);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_REVISE);
        detail.put("recordId", entity.getId().toString());
        detail.put("scheduleId", entity.getScheduleId().toString());
        detail.put("revisionNo", revisionNo);
        detail.put("reasonCode", reasonCode);
        detail.put("caStatus", CA_SIMULATED);
        detail.put("signatureRef", signatureRef);
        detail.put("currentRevision", revisionNo);
        detail.put("status", entity.getStatus());
        detail.put("administratorOperation", administrator);
        auditWriter.record(ACTION_REVISE, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);
        auditSignature(ACTION_REVISE, "REVISION", entity.getId(), signatureRef, signatureHash,
                CA_SIMULATED, revisionNo, caller, context);

        RecordRevision response = toRecordRevision(revision);
        response.setSnapshot(snapshot);
        Map<String, Object> baseline = snapshotBuilder.build(entity, events, drugs, fluids, rescues,
                totals, entity.getAnesthesiaType(), entity.getLockSignatureHash(), null);
        RevisionDiff diff = snapshotBuilder.diff(
                previous == null ? baseline : snapshotBuilder.parse(previous.getSnapshot()),
                snapshot);
        diff.setPreviousRevisionNo(previous == null ? 0 : previousRevisionNo);
        response.setDiff(diff);
        return response;
    }

    // ------------------------------------------------------------- helpers --

    private List<LockPrecondition> evaluatePreconditions(AnesthesiaRecordEntity entity,
            int eventCount, AuthPrincipal caller) {
        List<LockPrecondition> preconditions = new ArrayList<>();
        // 集成点本身 fail-safe：非法标识/无记录返回 false（不读 m2 表）；这里是同步调用
        // m2-api，不捕获异常，读库故障按 5xx 暴露而不是静默放行锁单。
        boolean threeWayDone = threeWayCheckService.isThreeWayCheckCompleted(
                entity.getApplicationId().toString());
        preconditions.add(precondition(CODE_THREE_WAY, "三方核查完成", threeWayDone,
                threeWayDone ? "Time-out 与 Sign-out 均已记录"
                        : "三方核查未完成（需同时记录 Time-out 与 Sign-out）"));

        ConsentState consent = consentState(entity, caller);
        preconditions.add(precondition(CODE_CONSENT, "当前有效知情同意已签署", consent.signed(),
                consent.detail()));

        preconditions.add(precondition(CODE_STARTED, "已记录麻醉开始",
                entity.getStartedAt() != null,
                entity.getStartedAt() == null ? "未记录麻醉开始（ANESTHESIA_START）"
                        : format(entity.getStartedAt())));
        preconditions.add(precondition(CODE_ENDED, "已记录麻醉结束",
                entity.getEndedAt() != null,
                entity.getEndedAt() == null ? "未记录麻醉结束（ANESTHESIA_END）"
                        : format(entity.getEndedAt())));
        preconditions.add(precondition(CODE_EVENTS, "至少 1 个时间轴事件", eventCount >= 1,
                eventCount >= 1 ? "事件数 " + eventCount : "未记录任何时间轴事件（至少 1 个）"));
        preconditions.add(precondition(CODE_NOT_LOCKED, "尚未锁单",
                entity.getLockedAt() == null,
                entity.getLockedAt() == null ? "记录单未锁定"
                        : "记录单已锁单，不能重复锁单"));
        return preconditions;
    }

    private ConsentState consentState(AnesthesiaRecordEntity entity, AuthPrincipal caller) {
        try {
            ConsentRecordList list = consentService.listByApplication(
                    entity.getApplicationId().toString(), caller);
            List<ConsentRecord> items = list == null ? List.of()
                    : (list.getItems() == null ? List.of() : list.getItems());
            for (ConsentRecord consent : items) {
                if (consent.getStatus() == ConsentRecord.StatusEnum.SIGNED) {
                    return new ConsentState(true, "存在 SIGNED 同意记录");
                }
            }
            return new ConsentState(false,
                    "未完成：未找到当前有效的已签署知情同意（待签署/已拒绝/已撤销均不算）");
        } catch (NoSuchElementException | AccessDeniedException notVisible) {
            return new ConsentState(false, "未完成：无法读取同意记录（不可见或不存在）");
        }
    }

    private static LockPrecondition precondition(String code, String label, boolean satisfied,
            String detail) {
        LockPrecondition item = new LockPrecondition();
        item.setCode(LockPrecondition.CodeEnum.fromValue(code));
        item.setLabel(label);
        item.setSatisfied(satisfied);
        item.setDetail(detail);
        return item;
    }

    private void auditLockRejected(AnesthesiaRecordEntity entity,
            List<M2LockPreconditionException.UnmetCondition> unmet, AuthPrincipal caller,
            AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_LOCK);
        detail.put("recordId", entity.getId().toString());
        detail.put("rejection", "LOCK_PRECONDITION_UNMET");
        detail.put("unmetConditions", unmet.stream().map(
                M2LockPreconditionException.UnmetCondition::code).toList());
        failureAuditor.recordFailure(ACTION_LOCK, RESOURCE_TYPE, entity.getId().toString(), detail,
                caller, context);
    }

    /** 修订/重复锁单等冲突失败留痕（独立事务 result=0；只含标识与拒绝码，不含原因原文）。 */
    private void auditRejected(String action, AnesthesiaRecordEntity entity, String rejection,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", action);
        detail.put("recordId", entity.getId().toString());
        detail.put("rejection", rejection);
        failureAuditor.recordFailure(action, RESOURCE_TYPE, entity.getId().toString(), detail,
                caller, context);
    }

    private void auditSignature(String operation, String scope, UUID recordId,
            String signatureRef, String signatureHash, String caStatus, Integer revisionNo,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_SIGN);
        detail.put("scope", scope);
        detail.put("recordId", recordId.toString());
        detail.put("signatureRef", signatureRef);
        detail.put("signatureHash", signatureHash);
        detail.put("caStatus", caStatus);
        if (revisionNo != null) {
            detail.put("revisionNo", revisionNo);
        }
        auditWriter.record(ACTION_SIGN, RESOURCE_TYPE, recordId.toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);
    }

    private AnesthesiaRecordEntity requireRecord(String recordId) {
        UUID id = parseUuid(recordId);
        AnesthesiaRecordEntity entity = id == null ? null : recordMapper.selectById(id);
        if (entity == null) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        return entity;
    }

    private Schedule requireVisibleSchedule(AnesthesiaRecordEntity entity, AuthPrincipal caller) {
        try {
            return schedulingService.get(entity.getScheduleId().toString(), caller);
        } catch (NoSuchElementException notVisible) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
    }

    private RecordRevisionSummary toSummary(RecordRevisionEntity entity) {
        RecordRevisionSummary summary = new RecordRevisionSummary();
        summary.setRevisionNo(entity.getRevisionNo());
        summary.setRecordId(entity.getRecordId().toString());
        summary.setReasonCode(RecordRevisionSummary.ReasonCodeEnum.fromValue(
                entity.getReasonCode()));
        summary.setReason(entity.getReason());
        summary.setChanges(entity.getChanges());
        summary.setSignatureRef(entity.getSignatureRef());
        summary.setSignatureHash(entity.getSignatureHash());
        summary.setCaStatus(RecordRevisionSummary.CaStatusEnum.fromValue(entity.getCaStatus()));
        summary.setCreatedBy(string(entity.getCreatedBy()));
        summary.setCreatedAt(offset(entity.getCreatedAt()));
        return summary;
    }

    /** 修订版本公共字段（详情/创建响应）；快照与差异由调用方补充。 */
    private RecordRevision toRecordRevision(RecordRevisionEntity revision) {
        RecordRevision detail = new RecordRevision();
        detail.setRevisionNo(revision.getRevisionNo());
        detail.setRecordId(revision.getRecordId().toString());
        detail.setReasonCode(RecordRevision.ReasonCodeEnum.fromValue(revision.getReasonCode()));
        detail.setReason(revision.getReason());
        detail.setChanges(revision.getChanges());
        detail.setSignatureRef(revision.getSignatureRef());
        detail.setSignatureHash(revision.getSignatureHash());
        detail.setCaStatus(RecordRevision.CaStatusEnum.fromValue(revision.getCaStatus()));
        detail.setCreatedBy(string(revision.getCreatedBy()));
        detail.setCreatedAt(offset(revision.getCreatedAt()));
        return detail;
    }

    private RecordRevision toDetail(AnesthesiaRecordEntity record,
            RecordRevisionEntity revision) {
        RecordRevision detail = toRecordRevision(revision);
        Map<String, Object> snapshot = snapshotBuilder.parse(revision.getSnapshot());
        detail.setSnapshot(snapshot);

        Map<String, Object> previousSnapshot;
        int previousRevisionNo = revision.getRevisionNo() - 1;
        if (previousRevisionNo >= 1) {
            RecordRevisionEntity previous = revisionMapper.selectByRecordAndNo(
                    record.getId(), previousRevisionNo);
            if (previous == null) {
                throw new NoSuchElementException("上一修订版本不存在或不可见");
            }
            previousSnapshot = snapshotBuilder.parse(previous.getSnapshot());
        } else {
            previousRevisionNo = 0;
            previousSnapshot = snapshotBuilder.build(record,
                    eventMapper.selectByRecord(record.getId()),
                    drugMapper.selectByRecord(record.getId()),
                    fluidMapper.selectByRecord(record.getId()),
                    rescueMapper.selectByRecord(record.getId()),
                    fluidMapper.selectTotals(record.getId()), record.getAnesthesiaType(),
                    record.getLockSignatureHash(), null);
        }
        RevisionDiff diff = snapshotBuilder.diff(previousSnapshot, snapshot);
        diff.setPreviousRevisionNo(previousRevisionNo);
        detail.setDiff(diff);
        return detail;
    }

    private static RecordEffectiveValues effectiveValues(String anesthesiaType, Instant startedAt,
            Instant endedAt) {
        RecordEffectiveValues values = new RecordEffectiveValues();
        values.setAnesthesiaType(
                RecordEffectiveValues.AnesthesiaTypeEnum.fromValue(anesthesiaType));
        values.setStartedAt(startedAt == null ? null
                : startedAt.atOffset(DISPLAY_OFFSET).truncatedTo(ChronoUnit.MILLIS));
        values.setEndedAt(endedAt == null ? null
                : endedAt.atOffset(DISPLAY_OFFSET).truncatedTo(ChronoUnit.MILLIS));
        return values;
    }

    private static String snapshotAnesthesiaType(Map<String, Object> snapshot, String fallback) {
        Object recordPart = snapshot == null ? null : snapshot.get("record");
        if (recordPart instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("anesthesiaType".equals(entry.getKey())
                        && entry.getValue() instanceof String text && !text.isBlank()) {
                    return text;
                }
            }
        }
        return fallback;
    }

    private static String requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new M2ValidationException(field, "字段不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new M2ValidationException(field, "字段长度超出限制");
        }
        return trimmed;
    }

    private static String contractStatus(String status) {
        return "ONGOING".equals(status) ? "IN_PROGRESS" : status;
    }

    private static Integer parseRevisionNo(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 1 ? null : parsed;
        } catch (NumberFormatException invalid) {
            return null;
        }
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

    /** 当前有效同意状态（内部记录）。 */
    private record ConsentState(boolean signed, String detail) {
    }
}
