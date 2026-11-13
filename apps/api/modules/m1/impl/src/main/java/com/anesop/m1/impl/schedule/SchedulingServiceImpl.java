package com.anesop.m1.impl.schedule;

import com.anesop.contracts.scheduling.model.CancelScheduleRequest;
import com.anesop.contracts.scheduling.model.Conflict;
import com.anesop.contracts.scheduling.model.ConflictCheckRequest;
import com.anesop.contracts.scheduling.model.ConflictCheckResult;
import com.anesop.contracts.scheduling.model.CreateScheduleRequest;
import com.anesop.contracts.scheduling.model.IdResponse;
import com.anesop.contracts.scheduling.model.OperatingRoom;
import com.anesop.contracts.scheduling.model.OperatingRoomList;
import com.anesop.contracts.scheduling.model.PageMeta;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.contracts.scheduling.model.SchedulePage;
import com.anesop.contracts.scheduling.model.UpdateScheduleRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m1.api.schedule.ScheduleUnprocessableException;
import com.anesop.m1.api.schedule.SchedulingConflictException;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m1.api.schedule.SchedulingValidationException;
import com.anesop.m1.impl.persistence.entity.OperatingRoomEntity;
import com.anesop.m1.impl.persistence.entity.PatientSnapshotEntity;
import com.anesop.m1.impl.persistence.entity.ScheduleEntity;
import com.anesop.m1.impl.persistence.entity.ScheduleOverlapRow;
import com.anesop.m1.impl.persistence.entity.SurgeryApplicationEntity;
import com.anesop.m1.impl.persistence.mapper.OperatingRoomMapper;
import com.anesop.m1.impl.persistence.mapper.PatientSnapshotMapper;
import com.anesop.m1.impl.persistence.mapper.ScheduleMapper;
import com.anesop.m1.impl.persistence.mapper.SurgeryApplicationMapper;
import com.anesop.m1.impl.schedule.rule.SchedulingContext;
import com.anesop.m1.impl.schedule.rule.SchedulingRuleRegistry;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 排程工作台服务实现（M2-M2-01，契约 scheduling.yaml 0.4.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>事务 + 并发：创建/调整先 {@code SELECT ... FOR UPDATE} 锁定目标术间行，再在同一事务内
 *       查询重叠台次并执行规则引擎；同一术间同一时段的并发请求被串行化，后到者复检到冲突，
 *       恰有一个成功。数据库另有 end_at &gt; start_at 与“申请未取消台次唯一”兜底。</li>
 *   <li>冲突不落库：任一 BLOCKING 冲突抛 {@link SchedulingConflictException}，Web 层映射
 *       409 + conflicts[]；不做“先保存后告警”。</li>
 *   <li>数据范围 fail-closed：无范围 403；DEPT/SITE 只见本科室/院区术间，ROOM 只见绑定术间；
 *       单对象越界返回 404，不泄露存在性。</li>
 *   <li>审计：创建/调整/取消分别写 SCHEDULE_CREATE/SCHEDULE_UPDATE/SCHEDULE_CANCEL，
 *       detail 只含标识、时段与原因码，不含患者姓名/住院号/诊断。</li>
 * </ul>
 */
@Service
public class SchedulingServiceImpl implements SchedulingService {

    private static final String STATUS_PLANNED = "PLANNED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String RESOURCE_TYPE = "SCHEDULE";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_REASON = 500;
    /** 停台/改台原因码白名单：大写字母/下划线，2–32 位；非法 400（S-9）。 */
    private static final Pattern REASON_CODE_PATTERN = Pattern.compile("^[A-Z_]{2,32}$");
    /** 单台计划时长上限（分钟），与申请 plannedDurationMin 上限一致。 */
    private static final long MAX_DURATION_MINUTES = 1440L;
    /** 契约 priority 枚举 ↔ smallint 映射（0=ELECTIVE，1=URGENT，2=EMERGENCY）。 */
    private static final List<String> PRIORITY_CODES = List.of("ELECTIVE", "URGENT", "EMERGENCY");

    private static final Set<String> SCHEDULE_STATUSES = Set.of(
            "PLANNED", "PUBLISHED", "IN_PROGRESS", "COMPLETED", "CANCELLED", "DELAYED");
    /** 术间运行状态（契约 OperatingRoom.status），/rooms?status= 过滤用。 */
    private static final Set<String> ROOM_STATUSES = Set.of(
            "IDLE", "PREPARING", "ANESTHESIA", "SURGERY", "PACU", "CLEANING");
    /** 允许调整/取消的台次状态；已开始/已结束不可改，取消为终态。 */
    private static final Set<String> ADJUSTABLE_STATUSES = Set.of("PLANNED", "PUBLISHED", "DELAYED");
    /** 允许排程的申请状态；SCHEDULED 另行返回 409（重复排程）。 */
    private static final Set<String> SCHEDULABLE_APPLICATION_STATUSES = Set.of("SUBMITTED", "ACCEPTED");

    private final OperatingRoomMapper roomMapper;
    private final ScheduleMapper scheduleMapper;
    private final SurgeryApplicationMapper applicationMapper;
    private final PatientSnapshotMapper patientSnapshotMapper;
    private final SchedulingRuleRegistry ruleRegistry;
    private final DataScopeResolver dataScopeResolver;
    private final AuditWriter auditWriter;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/Clock 由容器管理，服务不修改其内部状态")
    public SchedulingServiceImpl(OperatingRoomMapper roomMapper,
            ScheduleMapper scheduleMapper,
            SurgeryApplicationMapper applicationMapper,
            PatientSnapshotMapper patientSnapshotMapper,
            SchedulingRuleRegistry ruleRegistry,
            DataScopeResolver dataScopeResolver,
            AuditWriter auditWriter,
            Clock clock) {
        this.roomMapper = roomMapper;
        this.scheduleMapper = scheduleMapper;
        this.applicationMapper = applicationMapper;
        this.patientSnapshotMapper = patientSnapshotMapper;
        this.ruleRegistry = ruleRegistry;
        this.dataScopeResolver = dataScopeResolver;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    @Override
    public OperatingRoomList listRooms(AuthPrincipal caller, String siteId, String status) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        UUID siteFilter = parseOptionalUuid(siteId, "siteId");
        String statusFilter = normalizeRoomStatus(status);
        QueryWrapper<OperatingRoomEntity> wrapper = new QueryWrapper<>();
        Set<UUID> roomIds = visibleRoomIds(scope);
        if (roomIds != null) {
            if (roomIds.isEmpty()) {
                throw new AccessDeniedException("当前用户无可用术间数据范围");
            }
            wrapper.in("id", roomIds);
        }
        if (siteFilter != null) {
            wrapper.eq("site_id", siteFilter);
        }
        if (statusFilter != null) {
            wrapper.eq("status", statusFilter);
        }
        List<OperatingRoom> items = new ArrayList<>();
        for (OperatingRoomEntity room : roomMapper.selectList(wrapper.orderByAsc("room_no"))) {
            items.add(toRoomDto(room));
        }
        OperatingRoomList result = new OperatingRoomList();
        result.setItems(items);
        return result;
    }

    /** 兼容无过滤参数的调用（单测/内部）；等价于 {@code siteId=null,status=null}。 */
    public OperatingRoomList listRooms(AuthPrincipal caller) {
        return listRooms(caller, null, null);
    }

    @Override
    public SchedulePage listSchedules(String date, String roomId, String status, int page, int size,
            AuthPrincipal caller) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        LocalDate day = parseDate(date);
        String normalizedStatus = normalizeStatus(status);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));

        QueryWrapper<ScheduleEntity> wrapper = new QueryWrapper<>();
        wrapper.ge("start_at", day.atStartOfDay(ZONE).toInstant())
                .lt("start_at", day.plusDays(1).atStartOfDay(ZONE).toInstant());
        if (normalizedStatus != null) {
            wrapper.eq("status", normalizedStatus);
        }
        if (roomId != null && !roomId.isBlank()) {
            OperatingRoomEntity room = requireRoom(roomId);
            if (!roomVisible(scope, room)) {
                throw new AccessDeniedException("术间超出当前用户数据范围");
            }
            wrapper.eq("room_id", room.getId());
        } else {
            Set<UUID> roomIds = visibleRoomIds(scope);
            if (roomIds != null) {
                if (roomIds.isEmpty()) {
                    throw new AccessDeniedException("当前用户无可用术间数据范围");
                }
                wrapper.in("room_id", roomIds);
            }
        }

        Long counted = scheduleMapper.selectCount(wrapper);
        long total = counted == null ? 0 : counted;
        long offset = (long) (safePage - 1) * safeSize;
        List<Schedule> items = new ArrayList<>();
        if (total > offset) {
            List<ScheduleEntity> rows = scheduleMapper.selectList(wrapper
                    .orderByAsc("start_at")
                    .orderByAsc("room_id")
                    .orderByAsc("sequence")
                    .last("LIMIT " + safeSize + " OFFSET " + offset));
            items = toScheduleDtos(rows, scope, caller);
        }
        PageMeta meta = new PageMeta();
        meta.setPage(safePage);
        meta.setSize(safeSize);
        meta.setTotal((int) Math.min(Integer.MAX_VALUE, total));
        meta.setTotalPages(total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + safeSize - 1) / safeSize));
        SchedulePage result = new SchedulePage();
        result.setItems(items);
        result.setMeta(meta);
        return result;
    }

    @Override
    public Schedule get(String scheduleId, AuthPrincipal caller) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        ScheduleEntity schedule = requireVisibleSchedule(scheduleId, scope);
        return toScheduleDtos(List.of(schedule), scope, caller).get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Schedule create(CreateScheduleRequest request, AuthPrincipal caller, AuthRequestContext context) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        UUID applicationId = parseUuid(request.getApplicationId());
        if (applicationId == null) {
            throw new SchedulingValidationException("applicationId", "手术申请标识格式不正确");
        }
        // 数据范围：申请/患者必须对调用方可见（DEPT/SITE 其他科室、ROOM 无有效组织上下文 → 404，
        // 不泄露存在性与患者标识）；403/404 响应体不含 patientName/hisPatientNo。
        SurgeryApplicationEntity application =
                requireVisibleApplication(applicationId, scope, caller);
        if (!SCHEDULABLE_APPLICATION_STATUSES.contains(application.getStatus())) {
            if ("SCHEDULED".equals(application.getStatus())) {
                throw new SchedulingConflictException(List.of(conflict("APPLICATION_ALREADY_SCHEDULED",
                        "该申请已有生效台次，请先取消或调整原台次", null)));
            }
            throw new ScheduleUnprocessableException("applicationId", "申请状态不允许排程");
        }
        if (request.getSequenceNo() == null || request.getSequenceNo() < 1) {
            throw new SchedulingValidationException("sequenceNo", "台次序号必须从 1 开始");
        }
        Instant startAt = toInstant(request.getPlannedStart(), "plannedStart");
        Instant endAt = toInstant(request.getPlannedEnd(), "plannedEnd");
        validateTimeWindow(startAt, endAt);
        UUID roomId = parseUuid(request.getRoomId());
        if (roomId == null) {
            throw new SchedulingValidationException("roomId", "术间标识格式不正确");
        }
        OperatingRoomEntity room = requireRoomForWrite(roomId);
        if (!roomVisible(scope, room)) {
            throw new NoSuchElementException("术间不存在或不可见");
        }

        // 锁顺序（全局统一，避免死锁）：先人员/患者 advisory 锁（字典序），再术间行锁。
        acquirePersonLocks(application);
        OperatingRoomEntity locked = roomMapper.selectByIdForUpdate(room.getId());
        if (locked == null) {
            throw new NoSuchElementException("术间不存在或不可见");
        }
        List<ScheduleOverlapRow> overlapping = scheduleMapper.selectOverlaps(startAt, endAt, null);
        PatientSnapshotEntity patient = loadVisiblePatient(application, scope, caller);
        SchedulingContext ruleContext = new SchedulingContext(application.getId(),
                application.getPatientId(), application.getSurgeonName(), locked.getId(),
                locked.getRoomNo(), locked.getRoomType(), application.getRequiredRoomType(),
                startAt, endAt, null, overlapping);
        List<Conflict> conflicts = ruleRegistry.evaluate(ruleContext);
        if (!conflicts.isEmpty()) {
            throw new SchedulingConflictException(conflicts);
        }

        Instant now = Instant.now(clock);
        ScheduleEntity schedule = new ScheduleEntity();
        schedule.setId(UUID.randomUUID());
        schedule.setApplicationId(application.getId());
        schedule.setRoomId(locked.getId());
        schedule.setStartAt(startAt);
        schedule.setEndAt(endAt);
        schedule.setSequence(request.getSequenceNo());
        schedule.setPriority(priorityCode(request.getPriority()));
        schedule.setStatus(STATUS_PLANNED);
        schedule.setCreatedAt(now);
        schedule.setCreatedBy(actorId(caller));
        schedule.setUpdatedAt(now);
        schedule.setUpdatedBy(actorId(caller));
        schedule.setVersion(1L);
        schedule.setDeleted(false);
        try {
            scheduleMapper.insert(schedule);
        } catch (DuplicateKeyException e) {
            // 并发重复排程同一申请：部分唯一索引兜底，统一 409。
            throw new SchedulingConflictException(List.of(conflict("APPLICATION_ALREADY_SCHEDULED",
                    "该申请已有生效台次，请先取消或调整原台次", null)));
        }
        audit("SCHEDULE_CREATE", schedule.getId(), caller, context, Map.of(
                "operation", "create",
                "applicationId", application.getId().toString(),
                "roomId", locked.getId().toString(),
                "startAt", startAt.toString(),
                "endAt", endAt.toString(),
                "sequence", schedule.getSequence()));
        return toScheduleDto(schedule, application, patient, locked);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Schedule reschedule(String scheduleId, UpdateScheduleRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        ScheduleEntity schedule = requireVisibleSchedule(scheduleId, scope);
        if (!ADJUSTABLE_STATUSES.contains(schedule.getStatus())) {
            throw new SchedulingConflictException(List.of(conflict("STATE_CONFLICT",
                    "当前台次状态不允许调整（已开始/已结束/已取消）", null)));
        }
        if (request.getVersion() == null
                || !request.getVersion().equals(schedule.getVersion().intValue())) {
            throw new SchedulingConflictException(List.of(conflict("VERSION_CONFLICT",
                    "台次版本已过期，请刷新后重试", null)));
        }
        if (request.getSequenceNo() == null || request.getSequenceNo() < 1) {
            throw new SchedulingValidationException("sequenceNo", "台次序号必须从 1 开始");
        }
        Instant startAt = toInstant(request.getPlannedStart(), "plannedStart");
        Instant endAt = toInstant(request.getPlannedEnd(), "plannedEnd");
        validateTimeWindow(startAt, endAt);

        UUID targetRoomId = request.getRoomId() == null ? schedule.getRoomId()
                : parseUuid(request.getRoomId());
        if (targetRoomId == null) {
            throw new SchedulingValidationException("roomId", "术间标识格式不正确");
        }
        OperatingRoomEntity room = requireRoomForWrite(targetRoomId);
        if (!roomVisible(scope, room)) {
            throw new NoSuchElementException("术间不存在或不可见");
        }
        SurgeryApplicationEntity application = requireVisibleApplication(
                schedule.getApplicationId(), scope, caller);
        acquirePersonLocks(application);
        OperatingRoomEntity locked = roomMapper.selectByIdForUpdate(room.getId());
        if (locked == null) {
            throw new NoSuchElementException("术间不存在或不可见");
        }
        List<ScheduleOverlapRow> overlapping =
                scheduleMapper.selectOverlaps(startAt, endAt, schedule.getId());
        SchedulingContext ruleContext = new SchedulingContext(application.getId(),
                application.getPatientId(), application.getSurgeonName(), locked.getId(),
                locked.getRoomNo(), locked.getRoomType(), application.getRequiredRoomType(),
                startAt, endAt, schedule.getId(), overlapping);
        List<Conflict> conflicts = ruleRegistry.evaluate(ruleContext);
        if (!conflicts.isEmpty()) {
            throw new SchedulingConflictException(conflicts);
        }

        String reason = optionalText(request.getReason(), "reason", MAX_REASON);
        schedule.setRoomId(locked.getId());
        schedule.setStartAt(startAt);
        schedule.setEndAt(endAt);
        schedule.setSequence(request.getSequenceNo());
        if (reason != null) {
            schedule.setReason(reason);
        }
        schedule.setUpdatedAt(Instant.now(clock));
        schedule.setUpdatedBy(actorId(caller));
        if (scheduleMapper.updateById(schedule) == 0) {
            throw new SchedulingConflictException(List.of(conflict("VERSION_CONFLICT",
                    "台次版本已过期，请刷新后重试", null)));
        }
        audit("SCHEDULE_UPDATE", schedule.getId(), caller, context, Map.of(
                "operation", "reschedule",
                "applicationId", schedule.getApplicationId().toString(),
                "roomId", locked.getId().toString(),
                "startAt", startAt.toString(),
                "endAt", endAt.toString(),
                "sequence", schedule.getSequence()));
        PatientSnapshotEntity patient = loadVisiblePatient(application, scope, caller);
        return toScheduleDto(schedule, application, patient, locked);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IdResponse cancel(String scheduleId, CancelScheduleRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        ScheduleEntity schedule = requireVisibleSchedule(scheduleId, scope);
        if (!ADJUSTABLE_STATUSES.contains(schedule.getStatus())) {
            throw new SchedulingConflictException(List.of(conflict("STATE_CONFLICT",
                    "当前台次状态不允许取消（已开始/已结束/已取消）", null)));
        }
        if (request.getVersion() == null
                || !request.getVersion().equals(schedule.getVersion().intValue())) {
            throw new SchedulingConflictException(List.of(conflict("VERSION_CONFLICT",
                    "台次版本已过期，请刷新后重试", null)));
        }
        String reasonCode = requireText(request.getReasonCode(), "reasonCode", 64);
        if (!REASON_CODE_PATTERN.matcher(reasonCode).matches()) {
            throw new SchedulingValidationException("reasonCode",
                    "停台原因码必须为 2–32 位大写字母或下划线");
        }
        String reason = requireText(request.getReason(), "reason", MAX_REASON);
        schedule.setStatus(STATUS_CANCELLED);
        schedule.setReason(reason);
        schedule.setUpdatedAt(Instant.now(clock));
        schedule.setUpdatedBy(actorId(caller));
        if (scheduleMapper.updateById(schedule) == 0) {
            throw new SchedulingConflictException(List.of(conflict("VERSION_CONFLICT",
                    "台次版本已过期，请刷新后重试", null)));
        }
        audit("SCHEDULE_CANCEL", schedule.getId(), caller, context, Map.of(
                "operation", "cancel",
                "applicationId", schedule.getApplicationId().toString(),
                "roomId", schedule.getRoomId().toString(),
                "reasonCode", reasonCode));
        IdResponse response = new IdResponse();
        response.setId(schedule.getId().toString());
        return response;
    }

    @Override
    public ConflictCheckResult checkConflicts(ConflictCheckRequest request, AuthPrincipal caller) {
        DataScopeAssignments scope = scope(caller);
        requireScope(scope);
        UUID roomId = parseUuid(request.getRoomId());
        if (roomId == null) {
            throw new SchedulingValidationException("roomId", "术间标识格式不正确");
        }
        OperatingRoomEntity room = requireRoomForWrite(roomId);
        if (!roomVisible(scope, room)) {
            throw new AccessDeniedException("术间超出当前用户数据范围");
        }
        Instant startAt = toInstant(request.getPlannedStart(), "plannedStart");
        Instant endAt = toInstant(request.getPlannedEnd(), "plannedEnd");
        validateTimeWindow(startAt, endAt);
        UUID exclude = parseUuid(request.getExcludeScheduleId());
        SurgeryApplicationEntity application = null;
        if (request.getApplicationId() != null && !request.getApplicationId().isBlank()) {
            UUID applicationId = parseUuid(request.getApplicationId());
            if (applicationId == null) {
                throw new SchedulingValidationException("applicationId", "手术申请标识格式不正确");
            }
            application = requireVisibleApplication(applicationId, scope, caller);
        }
        List<ScheduleOverlapRow> overlapping = scheduleMapper.selectOverlaps(startAt, endAt, exclude);
        SchedulingContext context = new SchedulingContext(
                application == null ? null : application.getId(),
                application == null ? null : application.getPatientId(),
                application == null ? null : application.getSurgeonName(),
                room.getId(), room.getRoomNo(), room.getRoomType(),
                application == null ? null : application.getRequiredRoomType(),
                startAt, endAt, exclude, overlapping);
        List<Conflict> conflicts = ruleRegistry.evaluate(context);
        ConflictCheckResult result = new ConflictCheckResult();
        result.setHasConflict(!conflicts.isEmpty());
        result.setConflicts(conflicts);
        return result;
    }

    /**
     * 申请可见性（与 application 侧 {@code requireVisibleApplication} 等价）：不存在或越界统一 404，
     * 不泄露存在性与患者标识。ROOM 范围的文档例外：ROOM 用户没有组织绑定，申请可见性按其绑定
     * 术间的 dept_id/site_id 推导（术间所在科室/院区），否则 ROOM 用户无法为任何申请排程。
     */
    private SurgeryApplicationEntity requireVisibleApplication(UUID applicationId,
            DataScopeAssignments scope, AuthPrincipal caller) {
        SurgeryApplicationEntity application = applicationId == null ? null
                : applicationMapper.selectById(applicationId);
        if (application == null || !applicationVisible(scope, caller, application)) {
            throw new NoSuchElementException("手术申请不存在或不可见");
        }
        return application;
    }

    private boolean applicationVisible(DataScopeAssignments scope, AuthPrincipal caller,
            SurgeryApplicationEntity application) {
        return applicationVisible(scope, caller, application,
                scope.requiresObjectFilter() && !scope.isUnrestricted() && !scope.isSelfOnly()
                        ? effectiveOrgIds(scope) : Set.of());
    }

    /** 预计算组织范围的重载：列表逐行复用时避免每行重复查询绑定术间。 */
    private boolean applicationVisible(DataScopeAssignments scope, AuthPrincipal caller,
            SurgeryApplicationEntity application, Set<UUID> orgIds) {
        if (!scope.requiresObjectFilter() || scope.isUnrestricted()) {
            return true;
        }
        if (scope.isSelfOnly()) {
            return caller != null
                    && java.util.Objects.equals(application.getCreatedBy(), caller.userId());
        }
        return inScope(application.getDeptId(), application.getSiteId(), orgIds);
    }

    private boolean inScope(DataScopeAssignments scope, UUID deptId, UUID siteId) {
        if (!scope.requiresObjectFilter() || scope.isUnrestricted()) {
            return true;
        }
        return inScope(deptId, siteId, effectiveOrgIds(scope));
    }

    private static boolean inScope(UUID deptId, UUID siteId, Set<UUID> orgIds) {
        return (deptId != null && orgIds.contains(deptId))
                || (siteId != null && orgIds.contains(siteId));
    }

    /**
     * 有效组织范围：SITE/DEPT 绑定 ∪ 绑定术间所属 dept_id/site_id（ROOM 范围的文档例外）。
     */
    private Set<UUID> effectiveOrgIds(DataScopeAssignments scope) {
        Set<UUID> ids = new HashSet<>(parseUuids(scope.allowedOrgIds()));
        List<UUID> roomIds = parseUuids(scope.allowedRoomIds());
        if (!roomIds.isEmpty()) {
            for (OperatingRoomEntity room : roomMapper.selectBatchIds(roomIds)) {
                if (room.getDeptId() != null) {
                    ids.add(room.getDeptId());
                }
                if (room.getSiteId() != null) {
                    ids.add(room.getSiteId());
                }
            }
        }
        return ids;
    }

    /** 患者同样做可见性复核；越界患者不参与响应组装（字段留空），避免泄露标识。 */
    private PatientSnapshotEntity loadVisiblePatient(SurgeryApplicationEntity application,
            DataScopeAssignments scope, AuthPrincipal caller) {
        if (application.getPatientId() == null) {
            return null;
        }
        PatientSnapshotEntity patient = patientSnapshotMapper.selectById(application.getPatientId());
        if (patient == null) {
            return null;
        }
        if (scope.requiresObjectFilter() && !scope.isUnrestricted() && !scope.isSelfOnly()
                && !inScope(scope, patient.getDeptId(), patient.getSiteId())) {
            return null;
        }
        return patient;
    }

    /**
     * 跨术间的术者/患者维度串行化：事务级 advisory lock，按 key 字典序获取（全局唯一顺序），
     * 再进入术间行锁，避免两事务交叉加锁造成死锁。锁随事务提交/回滚自动释放。
     */
    private void acquirePersonLocks(SurgeryApplicationEntity application) {
        Set<String> keys = new TreeSet<>();
        if (application.getSurgeonName() != null && !application.getSurgeonName().isBlank()) {
            keys.add("schedule:surgeon:" + application.getSurgeonName().trim());
        }
        if (application.getPatientId() != null) {
            keys.add("schedule:patient:" + application.getPatientId());
        }
        for (String key : keys) {
            scheduleMapper.lockAdvisoryXact(key);
        }
    }

    /** 时间窗非法统一 400（S-9）：结束必须晚于开始，时长 1–1440 分钟。 */
    private static void validateTimeWindow(Instant startAt, Instant endAt) {
        if (!endAt.isAfter(startAt)) {
            throw new SchedulingValidationException("plannedEnd", "计划结束时间必须晚于开始时间");
        }
        long minutes = java.time.Duration.between(startAt, endAt).toMinutes();
        if (minutes < 1 || minutes > MAX_DURATION_MINUTES) {
            throw new SchedulingValidationException("plannedEnd",
                    "单台计划时长必须在 1–" + MAX_DURATION_MINUTES + " 分钟之间");
        }
    }

    private static Integer priorityCode(CreateScheduleRequest.PriorityEnum priority) {
        return priority == null ? 0 : Math.max(0, PRIORITY_CODES.indexOf(priority.getValue()));
    }

    private static UUID parseOptionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        UUID parsed = parseUuid(value);
        if (parsed == null) {
            throw new SchedulingValidationException(field, "标识格式不正确");
        }
        return parsed;
    }

    private static String normalizeRoomStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ROOM_STATUSES.contains(normalized)) {
            throw new SchedulingValidationException("status", "术间状态取值不在允许范围内");
        }
        return normalized;
    }

    private List<Schedule> toScheduleDtos(List<ScheduleEntity> rows, DataScopeAssignments scope,
            AuthPrincipal caller) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> applicationIds = rows.stream().map(ScheduleEntity::getApplicationId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        List<UUID> roomIds = rows.stream().map(ScheduleEntity::getRoomId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, SurgeryApplicationEntity> applications = new HashMap<>();
        for (SurgeryApplicationEntity application : applicationMapper.selectBatchIds(applicationIds)) {
            applications.put(application.getId(), application);
        }
        Map<UUID, PatientSnapshotEntity> patients = new HashMap<>();
        List<UUID> patientIds = applications.values().stream()
                .map(SurgeryApplicationEntity::getPatientId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!patientIds.isEmpty()) {
            for (PatientSnapshotEntity patient : patientSnapshotMapper.selectBatchIds(patientIds)) {
                patients.put(patient.getId(), patient);
            }
        }
        Map<UUID, OperatingRoomEntity> rooms = new HashMap<>();
        for (OperatingRoomEntity room : roomMapper.selectBatchIds(roomIds)) {
            rooms.put(room.getId(), room);
        }
        Set<UUID> rowScopeOrgIds = scope.requiresObjectFilter() && !scope.isUnrestricted()
                && !scope.isSelfOnly() ? effectiveOrgIds(scope) : Set.of();
        List<Schedule> items = new ArrayList<>();
        for (ScheduleEntity row : rows) {
            SurgeryApplicationEntity application = applications.get(row.getApplicationId());
            // 逐行复核申请可见性：不可见申请的 patientName/hisPatientNo 一律不回显（fail-closed）。
            PatientSnapshotEntity patient = application == null
                    || !applicationVisible(scope, caller, application, rowScopeOrgIds) ? null
                    : patients.get(application.getPatientId());
            items.add(toScheduleDto(row, application, patient, rooms.get(row.getRoomId())));
        }
        return items;
    }

    private Schedule toScheduleDto(ScheduleEntity schedule, SurgeryApplicationEntity application,
            PatientSnapshotEntity patient, OperatingRoomEntity room) {
        Schedule dto = new Schedule();
        dto.setScheduleId(schedule.getId().toString());
        dto.setApplicationId(schedule.getApplicationId() == null ? null
                : schedule.getApplicationId().toString());
        dto.setPatientId(patient == null || patient.getId() == null ? null : patient.getId().toString());
        dto.setPatientNo(patient == null ? "" : patient.getHisPatientNo());
        dto.setPatientName(patient == null ? "" : patient.getName());
        dto.setRoomId(schedule.getRoomId() == null ? null : schedule.getRoomId().toString());
        dto.setRoomNo(room == null ? "" : room.getRoomNo());
        dto.setSequenceNo(schedule.getSequence());
        dto.setPlannedStart(offset(schedule.getStartAt()));
        dto.setPlannedEnd(offset(schedule.getEndAt()));
        dto.setActualStart(null);
        dto.setActualEnd(null);
        dto.setStatus(Schedule.StatusEnum.fromValue(schedule.getStatus()));
        if (schedule.getPriority() != null && schedule.getPriority() >= 0
                && schedule.getPriority() < PRIORITY_CODES.size()) {
            dto.setPriority(Schedule.PriorityEnum.fromValue(PRIORITY_CODES.get(schedule.getPriority())));
        } else if (application != null && application.getUrgency() != null) {
            dto.setPriority(Schedule.PriorityEnum.fromValue(application.getUrgency()));
        }
        dto.setChangeReason(schedule.getReason());
        dto.setCreatedAt(offset(schedule.getCreatedAt()));
        dto.setCreatedBy(string(schedule.getCreatedBy()));
        dto.setUpdatedAt(offset(schedule.getUpdatedAt()));
        dto.setUpdatedBy(string(schedule.getUpdatedBy()));
        dto.setVersion(schedule.getVersion() == null ? 1 : schedule.getVersion().intValue());
        return dto;
    }

    private OperatingRoom toRoomDto(OperatingRoomEntity room) {
        OperatingRoom dto = new OperatingRoom();
        dto.setRoomId(room.getId().toString());
        dto.setRoomNo(room.getRoomNo());
        dto.setRoomName(room.getName());
        if (room.getRoomType() != null) {
            dto.setRoomType(OperatingRoom.RoomTypeEnum.fromValue(room.getRoomType()));
        }
        dto.setSiteId(room.getSiteId() == null ? "" : room.getSiteId().toString());
        dto.setStatus(OperatingRoom.StatusEnum.fromValue(room.getStatus()));
        dto.setIsolationRequired(false);
        dto.setEquipmentTags(List.of());
        dto.setCreatedAt(offset(room.getCreatedAt()));
        dto.setCreatedBy(string(room.getCreatedBy()));
        dto.setUpdatedAt(offset(room.getUpdatedAt()));
        dto.setUpdatedBy(string(room.getUpdatedBy()));
        dto.setVersion(room.getVersion() == null ? 1 : Math.max(1, room.getVersion().intValue()));
        return dto;
    }

    private OperatingRoomEntity requireRoom(String roomId) {
        UUID id = parseUuid(roomId);
        if (id == null) {
            throw new SchedulingValidationException("roomId", "术间标识格式不正确");
        }
        return requireRoomForWrite(id);
    }

    private OperatingRoomEntity requireRoomForWrite(UUID roomId) {
        OperatingRoomEntity room = roomMapper.selectById(roomId);
        if (room == null) {
            throw new NoSuchElementException("术间不存在或不可见");
        }
        return room;
    }

    private ScheduleEntity requireVisibleSchedule(String scheduleId, DataScopeAssignments scope) {
        UUID id = parseUuid(scheduleId);
        if (id == null) {
            throw new NoSuchElementException("排程不存在或不可见");
        }
        ScheduleEntity schedule = scheduleMapper.selectById(id);
        if (schedule == null) {
            throw new NoSuchElementException("排程不存在或不可见");
        }
        OperatingRoomEntity room = roomMapper.selectById(schedule.getRoomId());
        if (room == null || !roomVisible(scope, room)) {
            throw new NoSuchElementException("排程不存在或不可见");
        }
        return schedule;
    }

    private DataScopeAssignments scope(AuthPrincipal caller) {
        return dataScopeResolver.resolve(caller == null ? null : caller.userId());
    }

    private static void requireScope(DataScopeAssignments scope) {
        if (scope == null || scope.isDenied()) {
            throw new AccessDeniedException("当前用户无数据范围");
        }
    }

    /** null=不过滤（ALL）；空集合=无可见术间（调用方拒绝）。 */
    private Set<UUID> visibleRoomIds(DataScopeAssignments scope) {
        if (!scope.requiresObjectFilter()) {
            return null;
        }
        Set<UUID> ids = new LinkedHashSet<>(parseUuids(scope.allowedRoomIds()));
        if (scope.isSelfOnly()) {
            // SELF 用户没有术间对象范围；不能退化为全院可见（fail-closed）。
            return Set.of();
        }
        List<UUID> orgIds = parseUuids(scope.allowedOrgIds());
        if (!orgIds.isEmpty()) {
            List<OperatingRoomEntity> rooms = roomMapper.selectList(new QueryWrapper<OperatingRoomEntity>()
                    .and(condition -> condition
                            .in("dept_id", orgIds)
                            .or()
                            .in("site_id", orgIds)));
            for (OperatingRoomEntity room : rooms) {
                ids.add(room.getId());
            }
        }
        return ids;
    }

    private static boolean roomVisible(DataScopeAssignments scope, OperatingRoomEntity room) {
        if (!scope.requiresObjectFilter()) {
            return true;
        }
        if (scope.isSelfOnly()) {
            return false;
        }
        Set<UUID> roomIds = new HashSet<>(parseUuids(scope.allowedRoomIds()));
        if (roomIds.contains(room.getId())) {
            return true;
        }
        Set<UUID> orgIds = new HashSet<>(parseUuids(scope.allowedOrgIds()));
        return (room.getDeptId() != null && orgIds.contains(room.getDeptId()))
                || (room.getSiteId() != null && orgIds.contains(room.getSiteId()));
    }

    private static List<UUID> parseUuids(List<String> values) {
        return values.stream().map(SchedulingServiceImpl::parseUuid)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    private static Conflict conflict(String type, String message, ScheduleOverlapRow conflicting) {
        Conflict conflict = new Conflict();
        conflict.setConflictType(type);
        conflict.setSeverity(Conflict.SeverityEnum.BLOCKING);
        conflict.setMessage(message);
        conflict.setConflictingScheduleId(conflicting == null ? null : conflicting.getId().toString());
        return conflict;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new SchedulingValidationException("date", "排程日期不能为空");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new SchedulingValidationException("date", "排程日期格式必须为 YYYY-MM-DD");
        }
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SCHEDULE_STATUSES.contains(normalized)) {
            throw new SchedulingValidationException("status", "排程状态取值不在允许范围内");
        }
        return normalized;
    }

    private static Instant toInstant(OffsetDateTime value, String field) {
        if (value == null) {
            throw new SchedulingValidationException(field, "时间不能为空");
        }
        return value.toInstant();
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

    private static String requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new SchedulingValidationException(field, "字段不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new SchedulingValidationException(field, "字段长度超出限制");
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new SchedulingValidationException(field, "字段长度超出限制");
        }
        return trimmed;
    }

    private void audit(String action, UUID resourceId, AuthPrincipal caller, AuthRequestContext context,
            Map<String, Object> detail) {
        Map<String, Object> safeDetail = new LinkedHashMap<>(detail);
        auditWriter.record(action, RESOURCE_TYPE, resourceId == null ? null : resourceId.toString(),
                true, safeDetail, actorId(caller), caller == null ? null : caller.username(), context);
    }
}
