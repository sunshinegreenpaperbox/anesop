package com.anesop.m1.impl.application;

import com.anesop.contracts.scheduling.model.CancelSurgeryApplicationRequest;
import com.anesop.contracts.scheduling.model.IdResponse;
import com.anesop.contracts.scheduling.model.ImportSurgeryApplicationsRequest;
import com.anesop.contracts.scheduling.model.ImportSurgeryApplicationsResult;
import com.anesop.contracts.scheduling.model.PageMeta;
import com.anesop.contracts.scheduling.model.PatientSnapshot;
import com.anesop.contracts.scheduling.model.StatusHistoryEntry;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.contracts.scheduling.model.SurgeryApplicationCreateRequest;
import com.anesop.contracts.scheduling.model.SurgeryApplicationPage;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m1.api.application.PatientNotInIndexException;
import com.anesop.m1.api.application.SurgeryApplicationConflictException;
import com.anesop.m1.api.application.SurgeryApplicationQuery;
import com.anesop.m1.api.application.SurgeryApplicationService;
import com.anesop.m1.api.application.SurgeryApplicationValidationException;
import com.anesop.m1.api.his.HisSurgeryApplicationBatch;
import com.anesop.m1.api.his.HisSurgeryApplicationPort;
import com.anesop.m1.api.his.HisSurgeryApplicationRecord;
import com.anesop.m1.impl.persistence.entity.ApplicationStatusHistoryEntity;
import com.anesop.m1.impl.persistence.entity.PatientSnapshotEntity;
import com.anesop.m1.impl.persistence.entity.SurgeryApplicationEntity;
import com.anesop.m1.impl.persistence.mapper.ApplicationStatusHistoryMapper;
import com.anesop.m1.impl.persistence.mapper.PatientSnapshotMapper;
import com.anesop.m1.impl.persistence.mapper.SurgeryApplicationMapper;
import com.anesop.m1.impl.support.InpatientNoHasher;
import com.anesop.m1.impl.support.InpatientNoMasker;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 手术申请服务实现（M2-M1-01，契约 scheduling.yaml 0.3.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>数据范围 fail-closed：解析器返回 NONE 直接 403；DEPT 按申请/患者所属科室过滤；
 *       SELF 按申请创建人过滤；单对象越界返回 404（不泄露存在性）；</li>
 *   <li>HIS 导入幂等：Mock 适配器固定 seed 生成相同申请号，写入用
 *       {@code ON CONFLICT (his_application_no) DO NOTHING}，重复导入 importedCount=0；</li>
 *   <li>手工创建重复申请号 409；未知患者 422；状态变更写状态历史 + 审计；</li>
 *   <li>住院号明文只在本方法内存中出现，立即脱敏（masked）与哈希后丢弃，不入库/日志/审计。</li>
 * </ul>
 */
@Service
public class SurgeryApplicationServiceImpl implements SurgeryApplicationService {

    private static final Logger LOG = LoggerFactory.getLogger(SurgeryApplicationServiceImpl.class);

    private static final String SOURCE_HIS = "HIS";
    private static final String SOURCE_MANUAL = "MANUAL";
    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final Set<String> STATUSES =
            Set.of("SUBMITTED", "ACCEPTED", "SCHEDULED", "CANCELLED", "REJECTED");
    private static final Set<String> URGENCIES = Set.of("ELECTIVE", "URGENT", "EMERGENCY");
    private static final Set<String> TERMINAL_STATUSES = Set.of("CANCELLED", "REJECTED");

    private static final String RESOURCE_TYPE = "SURGERY_APPLICATION";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_PAGE_SIZE = 200;

    private final PatientSnapshotMapper patientSnapshotMapper;
    private final SurgeryApplicationMapper applicationMapper;
    private final ApplicationStatusHistoryMapper historyMapper;
    private final DataScopeResolver dataScopeResolver;
    private final AuditWriter auditWriter;
    private final HisSurgeryApplicationPort hisPort;
    private final InpatientNoHasher inpatientNoHasher;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/Clock 由容器管理，服务不修改其内部状态")
    public SurgeryApplicationServiceImpl(PatientSnapshotMapper patientSnapshotMapper,
            SurgeryApplicationMapper applicationMapper,
            ApplicationStatusHistoryMapper historyMapper,
            DataScopeResolver dataScopeResolver,
            AuditWriter auditWriter,
            HisSurgeryApplicationPort hisPort,
            InpatientNoHasher inpatientNoHasher,
            Clock clock) {
        this.patientSnapshotMapper = patientSnapshotMapper;
        this.applicationMapper = applicationMapper;
        this.historyMapper = historyMapper;
        this.dataScopeResolver = dataScopeResolver;
        this.auditWriter = auditWriter;
        this.hisPort = hisPort;
        this.inpatientNoHasher = inpatientNoHasher;
        this.clock = clock;
    }

    @Override
    public SurgeryApplicationPage list(SurgeryApplicationQuery query, AuthPrincipal caller) {
        int page = Math.max(1, query.page());
        int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
        String status = normalizeCode(query.status(), "status");
        String urgency = normalizeCode(query.urgency(), "urgency");
        if (status != null && !STATUSES.contains(status)) {
            throw new SurgeryApplicationValidationException("status", "状态取值不在允许范围内");
        }
        if (urgency != null && !URGENCIES.contains(urgency)) {
            throw new SurgeryApplicationValidationException("urgency", "紧急度取值不在允许范围内");
        }

        DataScopeAssignments scope = scope(caller);
        requireScope(scope);

        QueryWrapper<SurgeryApplicationEntity> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        if (urgency != null) {
            wrapper.eq("urgency", urgency);
        }
        if (query.departmentName() != null && !query.departmentName().isBlank()) {
            wrapper.eq("dept_name", query.departmentName().trim());
        }
        applyScope(wrapper, scope, caller);

        Long counted = applicationMapper.selectCount(wrapper);
        long total = counted == null ? 0 : counted;
        long offset = (long) (page - 1) * size;
        List<SurgeryApplication> items = new ArrayList<>();
        if (total > offset) {
            List<SurgeryApplicationEntity> rows = applicationMapper.selectList(wrapper
                    .orderByDesc("submitted_at")
                    .orderByAsc("id")
                    .last("LIMIT " + size + " OFFSET " + offset));
            Map<UUID, PatientSnapshotEntity> patients = loadPatients(rows);
            for (SurgeryApplicationEntity row : rows) {
                items.add(toDto(row, patients.get(row.getPatientId()), List.of()));
            }
        }
        PageMeta meta = new PageMeta();
        meta.setPage(page);
        meta.setSize(size);
        meta.setTotal((int) Math.min(Integer.MAX_VALUE, total));
        meta.setTotalPages(total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size));
        SurgeryApplicationPage result = new SurgeryApplicationPage();
        result.setItems(items);
        result.setMeta(meta);
        return result;
    }

    @Override
    public SurgeryApplication get(String applicationId, AuthPrincipal caller) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        SurgeryApplicationEntity application = requireVisibleApplication(applicationId, scope, caller);
        PatientSnapshotEntity patient = patientSnapshotMapper.selectById(application.getPatientId());
        return toDto(application, patient, history(application.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SurgeryApplication submit(SurgeryApplicationCreateRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        String hisApplicationNo = requireText(request.getHisApplicationNo(), "hisApplicationNo", 64);
        String surgeryName = requireText(request.getSurgeryName(), "surgeryName", 200);
        Integer duration = request.getPlannedDurationMin();
        if (duration == null || duration <= 0 || duration > 1440) {
            throw new SurgeryApplicationValidationException("plannedDurationMin", "预计手术时长必须为 1–1440 分钟");
        }
        String urgency = request.getUrgency() == null ? null : request.getUrgency().getValue();
        if (urgency == null || !URGENCIES.contains(urgency)) {
            throw new SurgeryApplicationValidationException("urgency", "紧急度取值不在允许范围内");
        }
        UUID patientId = parseUuid(request.getPatientId());
        if (patientId == null) {
            throw new SurgeryApplicationValidationException("patientId", "患者标识格式不正确");
        }
        PatientSnapshotEntity patient = patientSnapshotMapper.selectById(patientId);
        if (patient == null) {
            throw new PatientNotInIndexException("患者不在本系统患者快照内");
        }
        if (scope.requiresObjectFilter() && !scope.isSelfOnly() && !inScope(scope, patient.getDeptId(),
                patient.getSiteId())) {
            throw new AccessDeniedException("患者超出当前用户数据范围");
        }
        if (applicationMapper.selectCount(new QueryWrapper<SurgeryApplicationEntity>()
                .eq("his_application_no", hisApplicationNo)) > 0) {
            throw new SurgeryApplicationConflictException("HIS 申请号已存在");
        }

        Instant now = Instant.now(clock);
        SurgeryApplicationEntity application = new SurgeryApplicationEntity();
        application.setId(UUID.randomUUID());
        application.setHisApplicationNo(hisApplicationNo);
        application.setPatientId(patient.getId());
        application.setSurgeryName(surgeryName);
        application.setRequiredRoomType(request.getRequiredRoomType() == null ? null
                : request.getRequiredRoomType().getValue());
        application.setSurgeryCode(optionalText(request.getSurgeryCode(), "surgeryCode", 64));
        application.setUrgency(urgency);
        application.setPlannedDurationMin(duration);
        application.setDeptName(patient.getDeptName());
        application.setDeptId(patient.getDeptId());
        application.setSiteId(patient.getSiteId());
        application.setSurgeonName(optionalText(request.getSurgeonName(), "surgeonName", 100));
        application.setPreopDiagnosis(optionalText(request.getPreopDiagnosis(), "preopDiagnosis", 500));
        application.setStatus(STATUS_SUBMITTED);
        application.setSubmittedAt(now);
        application.setSource(SOURCE_MANUAL);
        application.setRemark(optionalText(request.getRemark(), "remark", 500));
        application.setCreatedAt(now);
        application.setCreatedBy(actorId(caller));
        application.setUpdatedAt(now);
        application.setUpdatedBy(actorId(caller));
        application.setVersion(1L);
        application.setDeleted(false);
        try {
            applicationMapper.insert(application);
        } catch (DuplicateKeyException e) {
            throw new SurgeryApplicationConflictException("HIS 申请号已存在");
        }

        ApplicationStatusHistoryEntity history =
                historyEntry(application, null, STATUS_SUBMITTED, null, "手工创建", now);
        historyMapper.insert(history);
        audit("SURGERY_APPLICATION_CREATE", application.getId().toString(), caller, context,
                Map.of("operation", "submit", "status", STATUS_SUBMITTED, "source", SOURCE_MANUAL));
        return toDto(application, patient, List.of(history));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportSurgeryApplicationsResult importFromHis(ImportSurgeryApplicationsRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        Integer requested = request.getCount();
        if (requested == null || requested < 1 || requested > 5) {
            throw new SurgeryApplicationValidationException("count", "拉取条数必须为 1–5");
        }
        HisSurgeryApplicationBatch batch = hisPort.pull(requested,
                request.getSeed() == null ? null : request.getSeed().longValue());
        List<SurgeryApplication> imported = new ArrayList<>();
        int skipped = 0;
        Instant now = Instant.now(clock);
        for (HisSurgeryApplicationRecord record : batch.applications()) {
            if (!inScope(scope, record.deptId(), record.siteId())) {
                // 超出数据范围的 HIS 记录不落库、不泄露；计入跳过条数。
                skipped++;
                continue;
            }
            PatientSnapshotEntity patient = upsertPatient(record, caller, now);
            SurgeryApplicationEntity application = buildFromHis(record, patient, caller, now);
            int inserted = applicationMapper.insertIfAbsent(application);
            if (inserted == 0) {
                skipped++;
                continue;
            }
            ApplicationStatusHistoryEntity history =
                    historyEntry(application, null, STATUS_SUBMITTED, null, "HIS 导入", now);
            historyMapper.insert(history);
            imported.add(toDto(application, patient, List.of(history)));
        }
        audit("SURGERY_APPLICATION_IMPORT", null, caller, context, Map.of(
                "operation", "importFromHis",
                "adapter", batch.adapter(),
                "simulated", batch.simulated(),
                "importedCount", imported.size(),
                "skippedCount", skipped));

        ImportSurgeryApplicationsResult result = new ImportSurgeryApplicationsResult();
        result.setSimulated(batch.simulated());
        result.setAdapter(ImportSurgeryApplicationsResult.AdapterEnum.fromValue(batch.adapter()));
        result.setRequestedCount(requested);
        result.setImportedCount(imported.size());
        result.setSkippedCount(skipped);
        result.setApplications(imported);
        String message = "本次为模拟 HIS 导入（SYNTHETIC 合成数据），重复导入按 HIS 申请号幂等跳过";
        if (batch.applications().size() < requested) {
            message += "；HIS 本次仅提供 " + batch.applications().size() + " 条可用申请，已按可用数量返回";
        }
        result.setMessage(message);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IdResponse cancel(String applicationId, CancelSurgeryApplicationRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        SurgeryApplicationEntity application = requireVisibleApplication(applicationId, scope, caller);
        if (TERMINAL_STATUSES.contains(application.getStatus())) {
            throw new SurgeryApplicationConflictException("申请已处于终态，不允许取消");
        }
        if (request.getVersion() == null
                || !request.getVersion().equals(application.getVersion().intValue())) {
            throw new SurgeryApplicationConflictException("申请版本已过期");
        }
        String reason = requireText(request.getReason(), "reason", 500);
        String reasonCode = requireText(request.getReasonCode(), "reasonCode", 64);
        Instant now = Instant.now(clock);
        String fromStatus = application.getStatus();
        application.setStatus(STATUS_CANCELLED);
        application.setUpdatedAt(now);
        application.setUpdatedBy(actorId(caller));
        if (applicationMapper.updateById(application) == 0) {
            throw new SurgeryApplicationConflictException("申请版本已过期");
        }
        ApplicationStatusHistoryEntity history = historyEntry(
                application, fromStatus, STATUS_CANCELLED, reasonCode, reason, now);
        historyMapper.insert(history);
        audit("SURGERY_APPLICATION_CANCEL", application.getId().toString(), caller, context, Map.of(
                "operation", "cancel",
                "reasonCode", reasonCode,
                "fromStatus", fromStatus,
                "toStatus", STATUS_CANCELLED));
        IdResponse response = new IdResponse();
        response.setId(application.getId().toString());
        return response;
    }

    private PatientSnapshotEntity upsertPatient(HisSurgeryApplicationRecord record, AuthPrincipal caller,
            Instant now) {
        String hisPatientNo = requireText(record.hisPatientNo(), "hisPatientNo", 64);
        String inpatientNo = requireText(record.inpatientNo(), "inpatientNo", 64);
        if (record.birthDate() == null) {
            throw new SurgeryApplicationValidationException("birthDate",
                    "出生日期缺失，拒绝创建/导入（不得用当前日期伪造）");
        }
        PatientSnapshotEntity patient = new PatientSnapshotEntity();
        patient.setId(UUID.randomUUID());
        patient.setHisPatientNo(hisPatientNo);
        patient.setName(requireText(record.patientName(), "patientName", 100));
        patient.setGender(requireText(record.gender(), "gender", 16));
        patient.setBirthDate(record.birthDate());
        // 年龄只取 HIS 提供的值；缺失保持 null，不由 birthDate/now() 伪造。
        patient.setAge(record.age());
        patient.setInpatientNoMasked(InpatientNoMasker.mask(inpatientNo));
        patient.setInpatientNoHash(inpatientNoHasher.hash(inpatientNo));
        patient.setDeptName(requireText(record.deptName(), "deptName", 100));
        patient.setDeptId(record.deptId());
        patient.setSiteId(record.siteId());
        patient.setWard(optionalText(record.ward(), "ward", 100));
        patient.setBedNo(optionalText(record.bedNo(), "bedNo", 32));
        patient.setCreatedAt(now);
        patient.setCreatedBy(actorId(caller));
        patient.setUpdatedAt(now);
        patient.setUpdatedBy(actorId(caller));
        patient.setVersion(1L);
        patient.setDeleted(false);
        UUID persistedId = patientSnapshotMapper.upsertReturningId(patient);
        if (persistedId == null) {
            // 单条 upsert 必须返回行 ID；不在此回查（避免在已中止事务内继续执行 SQL）。
            throw new IllegalStateException("患者快照 upsert 未返回行标识");
        }
        patient.setId(persistedId);
        return patient;
    }

    private SurgeryApplicationEntity buildFromHis(HisSurgeryApplicationRecord record,
            PatientSnapshotEntity patient, AuthPrincipal caller, Instant now) {
        SurgeryApplicationEntity application = new SurgeryApplicationEntity();
        application.setId(UUID.randomUUID());
        application.setHisApplicationNo(requireText(record.hisApplicationNo(), "hisApplicationNo", 64));
        application.setPatientId(patient.getId());
        application.setSurgeryName(requireText(record.surgeryName(), "surgeryName", 200));
        application.setSurgeryCode(optionalText(record.surgeryCode(), "surgeryCode", 64));
        String urgency = requireText(record.urgency(), "urgency", 16);
        if (!URGENCIES.contains(urgency)) {
            throw new SurgeryApplicationValidationException("urgency", "紧急度取值不在允许范围内");
        }
        application.setUrgency(urgency);
        Integer duration = record.plannedDurationMin();
        if (duration == null || duration <= 0 || duration > 1440) {
            throw new SurgeryApplicationValidationException("plannedDurationMin", "预计手术时长必须为 1–1440 分钟");
        }
        application.setPlannedDurationMin(duration);
        application.setDeptName(patient.getDeptName());
        application.setDeptId(patient.getDeptId());
        application.setSiteId(patient.getSiteId());
        application.setSurgeonName(optionalText(record.surgeonName(), "surgeonName", 100));
        application.setPreopDiagnosis(optionalText(record.preopDiagnosis(), "preopDiagnosis", 500));
        application.setStatus(STATUS_SUBMITTED);
        application.setSubmittedAt(now);
        application.setSource(SOURCE_HIS);
        application.setRemark(optionalText(record.remark(), "remark", 500));
        application.setCreatedAt(now);
        application.setCreatedBy(actorId(caller));
        application.setUpdatedAt(now);
        application.setUpdatedBy(actorId(caller));
        application.setVersion(1L);
        application.setDeleted(false);
        return application;
    }

    private SurgeryApplicationEntity requireVisibleApplication(String applicationId,
            DataScopeAssignments scope, AuthPrincipal caller) {
        UUID id = parseUuid(applicationId);
        if (id == null) {
            throw new NoSuchElementException("申请不存在");
        }
        SurgeryApplicationEntity application = applicationMapper.selectById(id);
        if (application == null) {
            throw new NoSuchElementException("申请不存在或不可见");
        }
        if (scope.isSelfOnly() && !java.util.Objects.equals(application.getCreatedBy(), caller.userId())) {
            throw new NoSuchElementException("申请不存在或不可见");
        }
        if (!scope.isSelfOnly() && scope.requiresObjectFilter()
                && !inScope(scope, application.getDeptId(), application.getSiteId())) {
            throw new NoSuchElementException("申请不存在或不可见");
        }
        return application;
    }

    private DataScopeAssignments scope(AuthPrincipal caller) {
        return dataScopeResolver.resolve(caller == null ? null : caller.userId());
    }

    private static void requireScope(DataScopeAssignments scope) {
        if (scope == null || scope.isDenied()) {
            throw new AccessDeniedException("当前用户无数据范围");
        }
    }

    private static void applyScope(QueryWrapper<SurgeryApplicationEntity> wrapper,
            DataScopeAssignments scope, AuthPrincipal caller) {
        if (!scope.requiresObjectFilter()) {
            return;
        }
        if (scope.isSelfOnly()) {
            wrapper.eq("created_by", caller.userId());
            return;
        }
        List<UUID> orgIds = scope.allowedOrgIds().stream()
                .map(SurgeryApplicationServiceImpl::parseUuid)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (orgIds.isEmpty()) {
            throw new AccessDeniedException("当前用户无可用组织数据范围");
        }
        wrapper.and(condition -> condition
                .in("dept_id", orgIds)
                .or()
                .in("site_id", orgIds));
    }

    private static boolean inScope(DataScopeAssignments scope, UUID deptId, UUID siteId) {
        if (!scope.requiresObjectFilter() || scope.isUnrestricted()) {
            return true;
        }
        if (scope.isSelfOnly()) {
            return true;
        }
        Set<UUID> ids = scope.allowedOrgIds().stream()
                .map(SurgeryApplicationServiceImpl::parseUuid)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return (deptId != null && ids.contains(deptId)) || (siteId != null && ids.contains(siteId));
    }

    private Map<UUID, PatientSnapshotEntity> loadPatients(List<SurgeryApplicationEntity> rows) {
        List<UUID> patientIds = rows.stream()
                .map(SurgeryApplicationEntity::getPatientId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (patientIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PatientSnapshotEntity> patients = new HashMap<>();
        for (PatientSnapshotEntity patient : patientSnapshotMapper.selectBatchIds(patientIds)) {
            patients.put(patient.getId(), patient);
        }
        return patients;
    }

    private List<ApplicationStatusHistoryEntity> history(UUID applicationId) {
        return historyMapper.selectList(new QueryWrapper<ApplicationStatusHistoryEntity>()
                .eq("application_id", applicationId)
                .orderByAsc("occurred_at")
                .orderByAsc("id"));
    }

    private ApplicationStatusHistoryEntity historyEntry(SurgeryApplicationEntity application,
            String fromStatus, String toStatus, String reasonCode, String reason, Instant occurredAt) {
        ApplicationStatusHistoryEntity entity = new ApplicationStatusHistoryEntity();
        entity.setId(UUID.randomUUID());
        entity.setApplicationId(application.getId());
        entity.setFromStatus(fromStatus);
        entity.setToStatus(toStatus);
        entity.setReasonCode(reasonCode);
        entity.setReason(reason);
        entity.setOperatorId(application.getUpdatedBy());
        entity.setOccurredAt(occurredAt);
        return entity;
    }

    private SurgeryApplication toDto(SurgeryApplicationEntity application, PatientSnapshotEntity patient,
            List<ApplicationStatusHistoryEntity> history) {
        SurgeryApplication dto = new SurgeryApplication();
        dto.setApplicationId(application.getId().toString());
        dto.setHisApplicationNo(application.getHisApplicationNo());
        dto.setPatient(toPatientSnapshot(patient));
        dto.setSurgeryName(application.getSurgeryName());
        if (application.getRequiredRoomType() != null) {
            dto.setRequiredRoomType(
                    SurgeryApplication.RequiredRoomTypeEnum.fromValue(application.getRequiredRoomType()));
        }
        dto.setSurgeryCode(application.getSurgeryCode());
        dto.setUrgency(SurgeryApplication.UrgencyEnum.fromValue(application.getUrgency()));
        dto.setPlannedDurationMin(application.getPlannedDurationMin());
        dto.setDepartmentName(application.getDeptName());
        dto.setSurgeonName(application.getSurgeonName());
        dto.setPreopDiagnosis(application.getPreopDiagnosis());
        dto.setStatus(SurgeryApplication.StatusEnum.fromValue(application.getStatus()));
        dto.setSubmittedAt(offset(application.getSubmittedAt()));
        dto.setSource(SurgeryApplication.SourceEnum.fromValue(application.getSource()));
        dto.setRemark(application.getRemark());
        List<StatusHistoryEntry> entries = new ArrayList<>();
        for (ApplicationStatusHistoryEntity row : history) {
            entries.add(toHistoryEntry(row));
        }
        dto.setStatusHistory(entries);
        dto.setCreatedAt(offset(application.getCreatedAt()));
        dto.setCreatedBy(string(application.getCreatedBy()));
        dto.setUpdatedAt(offset(application.getUpdatedAt()));
        dto.setUpdatedBy(string(application.getUpdatedBy()));
        dto.setVersion(application.getVersion() == null ? 0 : application.getVersion().intValue());
        return dto;
    }

    private PatientSnapshot toPatientSnapshot(PatientSnapshotEntity patient) {
        PatientSnapshot dto = new PatientSnapshot();
        if (patient == null) {
            return dto;
        }
        dto.setPatientId(patient.getId().toString());
        // 0.3.0 兼容：patientNo 回填 hisPatientNo，标 deprecated，1.0.0 删除。
        dto.setPatientNo(patient.getHisPatientNo());
        dto.setHisPatientNo(patient.getHisPatientNo());
        dto.setName(patient.getName());
        dto.setGender(PatientSnapshot.GenderEnum.fromValue(patient.getGender()));
        dto.setBirthDate(patient.getBirthDate());
        dto.setAge(patient.getAge());
        dto.setInpatientNoMasked(patient.getInpatientNoMasked());
        dto.setDepartmentId(string(patient.getDeptId()));
        dto.setDepartmentName(patient.getDeptName());
        dto.setWard(patient.getWard());
        dto.setBedNo(patient.getBedNo());
        dto.setSiteId(string(patient.getSiteId()));
        return dto;
    }

    private StatusHistoryEntry toHistoryEntry(ApplicationStatusHistoryEntity row) {
        StatusHistoryEntry entry = new StatusHistoryEntry();
        entry.setFromStatus(row.getFromStatus() == null
                ? null
                : StatusHistoryEntry.FromStatusEnum.fromValue(row.getFromStatus()));
        entry.setToStatus(StatusHistoryEntry.ToStatusEnum.fromValue(row.getToStatus()));
        entry.setReasonCode(row.getReasonCode());
        entry.setReason(row.getReason());
        entry.setOperatorId(string(row.getOperatorId()));
        entry.setOccurredAt(offset(row.getOccurredAt()));
        return entry;
    }

    private void audit(String action, String resourceId, AuthPrincipal caller, AuthRequestContext context,
            Map<String, Object> detail) {
        Map<String, Object> safeDetail = new LinkedHashMap<>(detail);
        safeDetail.put("operation", action);
        auditWriter.record(action, RESOURCE_TYPE, resourceId, true, safeDetail,
                actorId(caller), caller == null ? null : caller.username(), context);
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
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normalizeCode(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.length() > 32) {
            throw new SurgeryApplicationValidationException(field, "取值长度超出限制");
        }
        return normalized;
    }

    private static String requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new SurgeryApplicationValidationException(field, "字段不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new SurgeryApplicationValidationException(field, "字段长度超出限制");
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new SurgeryApplicationValidationException(field, "字段长度超出限制");
        }
        return trimmed;
    }
}
