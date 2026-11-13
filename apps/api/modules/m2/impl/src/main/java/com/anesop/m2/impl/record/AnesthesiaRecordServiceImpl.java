package com.anesop.m2.impl.record;

import com.anesop.contracts.anesthesia.model.AdministerDrugRequest;
import com.anesop.contracts.anesthesia.model.AnesthesiaEvent;
import com.anesop.contracts.anesthesia.model.AnesthesiaRecord;
import com.anesop.contracts.anesthesia.model.AnesthesiaRecordList;
import com.anesop.contracts.anesthesia.model.CreateAnesthesiaRecordRequest;
import com.anesop.contracts.anesthesia.model.CreateRecordEventRequest;
import com.anesop.contracts.anesthesia.model.DrugAdministration;
import com.anesop.contracts.anesthesia.model.DrugAdministrationList;
import com.anesop.contracts.anesthesia.model.FluidRecord;
import com.anesop.contracts.anesthesia.model.FluidRecordList;
import com.anesop.contracts.anesthesia.model.IntakeOutputSummary;
import com.anesop.contracts.anesthesia.model.RecordEvent;
import com.anesop.contracts.anesthesia.model.RecordEventList;
import com.anesop.contracts.anesthesia.model.RecordFluidRequest;
import com.anesop.contracts.anesthesia.model.UpdateAnesthesiaRecordRequest;
import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.UserStatus;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.AuthService;
import com.anesop.m0.api.security.UserAdminService;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.record.AnesthesiaRecordService;
import com.anesop.m2.api.security.M2Permissions;
import com.anesop.m2.api.threeway.ThreeWayCheckService;
import com.anesop.m2.impl.config.RecordProperties;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.DrugAdministrationEntity;
import com.anesop.m2.impl.persistence.entity.FluidRecordEntity;
import com.anesop.m2.impl.persistence.entity.RecordEventEntity;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.DrugAdministrationMapper;
import com.anesop.m2.impl.persistence.mapper.FluidRecordMapper;
import com.anesop.m2.impl.persistence.mapper.RecordEventMapper;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 麻醉记录单服务实现（M3-M2-01，契约 anesthesia.yaml 0.5.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>一个台次唯一记录单：服务层先查 + 唯一索引兜底，重复创建 409；</li>
 *   <li>时间轴 append-only：追加前先 {@code SELECT ... FOR UPDATE} 锁定记录单行，在锁内取
 *       seq + 插入 + 递增 version，同一记录单的并发追加被串行化（不再依赖唯一键冲突重试，
 *       避免 PostgreSQL 事务 aborted 后的 25P02）；occurredAt 不得早于记录开始（400）、
 *       不得晚于「服务端时间 + 容差」（400）、不得晚于 ANESTHESIA_END（409）；DRAFT 下
 *       术中事件必须先记录 ANESTHESIA_START；补录（早于最新事件或早于 now−容差）必须填写
 *       entryReason，否则 400；</li>
 *   <li>麻精双人核对：narcotic=true 必须 secondCheckerId + secondCheckerName，缺失 400；
 *       核对人不得为本人，且必须存在、在职（status=1）并持有 {@code m2:record:write}
 *       （经 m0-api 用户查询/权限快照实时校验，400）；失败路径写 result=0 审计；</li>
 *   <li>数据范围沿台次可见性：按台次列表越权 403（集合语义）、单记录越界 404、创建时台次
 *       不可见 422；不访问 m1 表，只调 m1-api 的 {@link SchedulingService}；</li>
 *   <li>审计 detail 只含标识、事件/药品/出入量的结构与数值，不含患者姓名、院内患者号、
 *       核对人姓名与备注原文；RECORD_UPDATE 记录 before/after 摘要。</li>
 * </ul>
 */
@Service
public class AnesthesiaRecordServiceImpl implements AnesthesiaRecordService {

    private static final String STATUS_DRAFT = "DRAFT";
    /** 数据库状态 ONGOING；契约状态映射为 IN_PROGRESS（见 anesthesia.yaml 0.5.0 说明）。 */
    private static final String STATUS_ONGOING = "ONGOING";
    private static final String EVENT_START = "ANESTHESIA_START";
    private static final String EVENT_END = "ANESTHESIA_END";
    private static final String SOURCE_MANUAL = "MANUAL";
    private static final String DIRECTION_INTAKE = "INTAKE";
    private static final String DIRECTION_OUTPUT = "OUTPUT";
    private static final String RESOURCE_TYPE = "ANESTHESIA_RECORD";
    private static final String ACTION_CREATE = "RECORD_CREATE";
    private static final String ACTION_UPDATE = "RECORD_UPDATE";
    private static final String ACTION_EVENT = "EVENT_RECORD";
    private static final String ACTION_DRUG = "DRUG_ADMIN";
    private static final String ACTION_FLUID = "FLUID_RECORD";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_NOTE = 500;
    private static final int MAX_ENTRY_REASON = 200;
    /** 必须先有 ANESTHESIA_START 才能追加的术中事件（CUSTOM 允许在 DRAFT 记录）。 */
    private static final Set<String> POST_START_EVENTS = Set.of(
            "INDUCTION", "INTUBATION", "INCISION", "EXTUBATION");
    private static final Set<String> RECORDABLE_SCHEDULE_STATUSES =
            Set.of("PLANNED", "PUBLISHED", "DELAYED", "IN_PROGRESS");

    private final AnesthesiaRecordMapper recordMapper;
    private final RecordEventMapper eventMapper;
    private final DrugAdministrationMapper drugMapper;
    private final FluidRecordMapper fluidMapper;
    private final SchedulingService schedulingService;
    private final ThreeWayCheckService threeWayCheckService;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AuthService authService;
    private final UserAdminService userAdminService;
    private final ClinicalFailureAuditor failureAuditor;
    private final RecordProperties properties;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public AnesthesiaRecordServiceImpl(AnesthesiaRecordMapper recordMapper,
            RecordEventMapper eventMapper,
            DrugAdministrationMapper drugMapper,
            FluidRecordMapper fluidMapper,
            SchedulingService schedulingService,
            ThreeWayCheckService threeWayCheckService,
            AuditWriter auditWriter,
            ObjectMapper objectMapper,
            Clock clock,
            AuthService authService,
            UserAdminService userAdminService,
            ClinicalFailureAuditor failureAuditor,
            RecordProperties properties) {
        this.recordMapper = recordMapper;
        this.eventMapper = eventMapper;
        this.drugMapper = drugMapper;
        this.fluidMapper = fluidMapper;
        this.schedulingService = schedulingService;
        this.threeWayCheckService = threeWayCheckService;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.authService = authService;
        this.userAdminService = userAdminService;
        this.failureAuditor = failureAuditor;
        this.properties = properties;
    }

    @Override
    public AnesthesiaRecordList listBySchedule(String scheduleId, AuthPrincipal caller) {
        UUID id = parseUuid(scheduleId);
        if (id == null) {
            throw new M2ValidationException("scheduleId", "台次标识格式不正确");
        }
        Schedule schedule;
        try {
            schedule = schedulingService.get(id.toString(), caller);
        } catch (NoSuchElementException notVisible) {
            // 集合查询越权语义：台次不存在/不可见统一 403，不返回空列表（不泄露存在性）。
            throw new AccessDeniedException("排程台次不存在或超出数据范围");
        }
        List<AnesthesiaRecordEntity> rows = recordMapper.selectList(
                new QueryWrapper<AnesthesiaRecordEntity>()
                        .eq("schedule_id", id)
                        .orderByDesc("created_at"));
        AnesthesiaRecordList result = new AnesthesiaRecordList();
        List<AnesthesiaRecord> items = new ArrayList<>();
        for (AnesthesiaRecordEntity row : rows) {
            items.add(toDto(row, schedule, eventsOf(row.getId())));
        }
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AnesthesiaRecord create(CreateAnesthesiaRecordRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        UUID scheduleId = parseUuid(request == null ? null : request.getScheduleId());
        if (scheduleId == null) {
            throw new M2ValidationException("scheduleId", "台次标识格式不正确");
        }
        String anesthesiaType = requireEnum(request.getAnesthesiaType(), "anesthesiaType");
        Schedule schedule;
        try {
            schedule = schedulingService.get(scheduleId.toString(), caller);
        } catch (NoSuchElementException notVisible) {
            // 创建接口无资源路径参数，按契约语义 422（不回显台次是否存在）。
            throw new M2UnprocessableException("scheduleId", "排程台次不存在或不可见");
        }
        String scheduleStatus = schedule.getStatus() == null ? null : schedule.getStatus().getValue();
        if (scheduleStatus == null || !RECORDABLE_SCHEDULE_STATUSES.contains(scheduleStatus)) {
            throw new M2ConflictException("台次状态不允许创建麻醉记录单");
        }
        Long existing = recordMapper.selectCount(new QueryWrapper<AnesthesiaRecordEntity>()
                .eq("schedule_id", scheduleId));
        if (existing != null && existing > 0) {
            throw new M2ConflictException("该台次已有麻醉记录单，一个台次只允许一张记录单");
        }

        Instant now = truncatedNow();
        AnesthesiaRecordEntity entity = new AnesthesiaRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setScheduleId(scheduleId);
        entity.setApplicationId(UUID.fromString(schedule.getApplicationId()));
        entity.setPatientId(UUID.fromString(schedule.getPatientId()));
        entity.setStatus(STATUS_DRAFT);
        entity.setAnesthesiaType(anesthesiaType);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(1L);
        try {
            recordMapper.insertRecord(entity);
        } catch (DuplicateKeyException concurrentCreate) {
            // 并发重复创建：唯一索引 uk_anesthesia_record_schedule 兜底。
            throw new M2ConflictException("该台次已有麻醉记录单，一个台次只允许一张记录单");
        }
        audit(ACTION_CREATE, entity, caller, context);
        return toDto(entity, schedule, List.of());
    }

    @Override
    public AnesthesiaRecord get(String recordId, AuthPrincipal caller) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        Schedule schedule = requireVisibleSchedule(entity, caller);
        return toDto(entity, schedule, eventsOf(entity.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AnesthesiaRecord update(String recordId, UpdateAnesthesiaRecordRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        Schedule schedule = requireVisibleSchedule(entity, caller);
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        if (request.getEvents() != null && !request.getEvents().isEmpty()) {
            // 0.5.0：事件 append-only，PUT 不再接受事件（契约描述已声明）。
            throw new M2ValidationException("events",
                    "事件为 append-only，请调用 POST /records/{recordId}/events 追加");
        }
        requireVersion(entity, request.getVersion());
        Map<String, Object> before = auditSnapshot(entity);
        String anesthesiaType = entity.getAnesthesiaType();
        if (request.getAnesthesiaType() != null) {
            anesthesiaType = requireEnum(request.getAnesthesiaType(), "anesthesiaType");
        }
        Instant now = truncatedNow();
        int updated = recordMapper.updateRecord(entity.getId(), entity.getVersion(), anesthesiaType,
                entity.getStatus(), entity.getStartedAt(), entity.getEndedAt(), now,
                actorId(caller));
        if (updated == 0) {
            throw new M2ConflictException("麻醉记录单已被修改，请刷新后重试");
        }
        entity.setAnesthesiaType(anesthesiaType);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(entity.getVersion() + 1);
        auditUpdate(entity, before, caller, context);
        return toDto(entity, schedule, eventsOf(entity.getId()));
    }

    @Override
    public RecordEventList listEvents(String recordId, AuthPrincipal caller) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        requireVisibleSchedule(entity, caller);
        RecordEventList result = new RecordEventList();
        List<RecordEvent> items = new ArrayList<>();
        for (RecordEventEntity row : eventMapper.selectByRecord(entity.getId())) {
            items.add(toEventDto(row));
        }
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RecordEvent appendEvent(String recordId, CreateRecordEventRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        AnesthesiaRecordEntity visible = requireRecord(recordId);
        requireVisibleSchedule(visible, caller);
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        String eventType = requireEnum(request.getEventType(), "eventType");
        Instant occurredAt = request.getOccurredAt() == null ? null
                : request.getOccurredAt().toInstant().truncatedTo(ChronoUnit.MILLIS);
        if (occurredAt == null) {
            throw new M2ValidationException("occurredAt", "事件发生时间不能为空");
        }
        String source = request.getSource() == null ? SOURCE_MANUAL
                : requireEnum(request.getSource(), "source");
        String entryReason = optionalText(request.getEntryReason(), "entryReason", MAX_ENTRY_REASON);
        Instant now = truncatedNow();
        Instant futureLimit = now.plusSeconds(properties.timeToleranceSeconds());
        if (occurredAt.isAfter(futureLimit)) {
            // 未来时间只容忍服务端时钟偏差窗口，防止录入"尚未发生"的临床事件。
            throw new M2ValidationException("occurredAt", "事件发生时间不得晚于服务端当前时间");
        }

        // 并发正确性：先锁记录单行，再在同一把锁内取 seq、插入事件与递增 version。
        // 唯一键冲突兜底只用于异常场景（如绕过服务层写入），命中即 409，不再重试查询
        // （PostgreSQL 事务 aborted 后任何语句都会报 25P02，重试没有意义）。
        AnesthesiaRecordEntity entity = recordMapper.selectByIdForUpdate(visible.getId());
        if (entity == null) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        Instant floor = entity.getStartedAt() != null ? entity.getStartedAt() : entity.getCreatedAt();
        if (floor != null && occurredAt.isBefore(floor)) {
            throw new M2ValidationException("occurredAt", "事件时间不得早于记录开始时间");
        }
        if (entity.getEndedAt() != null) {
            if (occurredAt.isAfter(entity.getEndedAt()) || EVENT_END.equals(eventType)) {
                throw new M2ConflictException("麻醉已结束，不可再追加事件");
            }
        }
        if (EVENT_END.equals(eventType)) {
            if (!STATUS_ONGOING.equals(entity.getStatus())) {
                throw new M2ConflictException("麻醉尚未开始，不可记录麻醉结束事件");
            }
            if (entity.getEndedAt() != null) {
                throw new M2ConflictException("麻醉结束事件已记录，不可重复记录");
            }
        }
        if (EVENT_START.equals(eventType) && STATUS_ONGOING.equals(entity.getStatus())) {
            throw new M2ConflictException("麻醉已开始，不可重复记录麻醉开始事件");
        }
        if (STATUS_DRAFT.equals(entity.getStatus()) && POST_START_EVENTS.contains(eventType)) {
            // DRAFT 下没有 ANESTHESIA_START，术中事件无从"不早于开始"；取严格口径：
            // 必须先记录 ANESTHESIA_START（若后续临床确认允许缺失，改为按 occurredAt 顺序校验）。
            throw new M2ConflictException("麻醉尚未开始，请先记录 ANESTHESIA_START 事件");
        }

        OffsetDateTime latest = eventMapper.selectLatestOccurredAt(entity.getId());
        Instant latestOccurredAt = latest == null ? null : latest.toInstant();
        boolean backfilled = (latestOccurredAt != null && occurredAt.isBefore(latestOccurredAt))
                || occurredAt.isBefore(now.minusSeconds(properties.timeToleranceSeconds()));
        if (backfilled && entryReason == null) {
            throw new M2ValidationException("entryReason",
                    "补录事件（早于最新事件或早于当前时间窗口）必须填写补录原因");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "note", optionalText(request.getNote(), "note", MAX_NOTE));
        putIfPresent(payload, "entryReason", entryReason);

        RecordEventEntity event = new RecordEventEntity();
        event.setId(UUID.randomUUID());
        event.setRecordId(entity.getId());
        event.setEventType(eventType);
        event.setOccurredAt(occurredAt);
        event.setSource(source);
        event.setPayload(toJson(payload));
        event.setCreatedAt(now);
        event.setCreatedBy(actorId(caller));
        event.setSeq(eventMapper.selectNextSeq(entity.getId()));
        try {
            eventMapper.insertEvent(event);
        } catch (DuplicateKeyException concurrentSeq) {
            // 锁行路径下不应发生；兜底映射 409，避免把唯一键冲突暴露为 500。
            throw new M2ConflictException("时间轴事件写入冲突，请刷新后重试");
        }

        String nextStatus = EVENT_START.equals(eventType) ? STATUS_ONGOING : entity.getStatus();
        Instant nextStartedAt = EVENT_START.equals(eventType) ? occurredAt : entity.getStartedAt();
        Instant nextEndedAt = EVENT_END.equals(eventType) ? occurredAt : entity.getEndedAt();
        int updated = recordMapper.updateRecord(entity.getId(), entity.getVersion(),
                entity.getAnesthesiaType(), nextStatus, nextStartedAt, nextEndedAt, now,
                actorId(caller));
        if (updated == 0) {
            throw new M2ConflictException("麻醉记录单已被修改，请刷新后重试");
        }
        entity.setStatus(nextStatus);
        entity.setStartedAt(nextStartedAt);
        entity.setEndedAt(nextEndedAt);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(entity.getVersion() + 1);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_EVENT);
        detail.put("recordId", entity.getId().toString());
        detail.put("scheduleId", entity.getScheduleId().toString());
        detail.put("eventId", event.getId().toString());
        detail.put("seq", event.getSeq());
        detail.put("eventType", eventType);
        detail.put("occurredAt", occurredAt.toString());
        detail.put("source", source);
        detail.put("status", entity.getStatus());
        // 只记录是否补录，不把 entryReason 原文（可能含临床自由文本）写进审计。
        detail.put("backfilled", backfilled);
        auditWriter.record(ACTION_EVENT, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);
        return toEventDto(event);
    }

    @Override
    public DrugAdministrationList listDrugs(String recordId, AuthPrincipal caller) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        requireVisibleSchedule(entity, caller);
        DrugAdministrationList result = new DrugAdministrationList();
        List<DrugAdministration> items = new ArrayList<>();
        for (DrugAdministrationEntity row : drugMapper.selectByRecord(entity.getId())) {
            items.add(toDrugDto(row));
        }
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DrugAdministration recordDrug(String recordId, AdministerDrugRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        requireVisibleSchedule(entity, caller);
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        String drugName = requireText(request.getDrugName(), "drugName", 200);
        BigDecimal dose = request.getDose();
        if (dose == null || dose.signum() <= 0) {
            throw new M2ValidationException("dose", "剂量必须大于 0");
        }
        String unit = requireText(request.getUnit(), "unit", 16);
        String route = requireText(request.getRoute(), "route", 32);
        if (request.getAdministeredAt() == null) {
            throw new M2ValidationException("administeredAt", "给药时间不能为空");
        }
        if (request.getNarcotic() == null) {
            throw new M2ValidationException("narcotic", "是否麻精类药品不能为空");
        }
        // 注意：narcotic 当前由客户端声明（药品字典 I-07 未落地，舒芬太尼等误报 false 可绕过
        // 双人核对，风险登记见 docs/modules/m2/README.md §10）；M5 药品字典上线后改为服务端
        // 按药品编码判定，客户端声明仅作兼容输入。
        boolean narcotic = request.getNarcotic();
        UUID secondCheckerId = null;
        String secondCheckerName = null;
        if (narcotic) {
            secondCheckerId = parseUuid(request.getSecondCheckerId());
            if (secondCheckerId == null) {
                throw drugRejected(entity, caller, context, "secondCheckerId",
                        "麻精药品必须填写第二核对人", "CHECKER_REQUIRED");
            }
            secondCheckerName = requireText(request.getSecondCheckerName(), "secondCheckerName", 64);
            if (caller != null && secondCheckerId.equals(caller.userId())) {
                throw drugRejected(entity, caller, context, "secondCheckerId",
                        "核对人不得与给药人为同一人", "CHECKER_SAME_PERSON");
            }
            requireEligibleChecker(entity, secondCheckerId, caller, context);
        } else if (request.getSecondCheckerId() != null || request.getSecondCheckerName() != null) {
            throw new M2ValidationException("secondCheckerId", "非麻精药品不得填写双人核对信息");
        }

        Instant now = truncatedNow();
        DrugAdministrationEntity drug = new DrugAdministrationEntity();
        drug.setId(UUID.randomUUID());
        drug.setRecordId(entity.getId());
        drug.setDrugName(drugName);
        drug.setDose(dose);
        drug.setUnit(unit);
        drug.setRoute(route);
        drug.setAdministeredAt(request.getAdministeredAt().toInstant()
                .truncatedTo(ChronoUnit.MILLIS));
        drug.setNarcotic(narcotic);
        drug.setSecondCheckerId(secondCheckerId);
        drug.setSecondCheckerName(secondCheckerName);
        drug.setNote(optionalText(request.getNote(), "note", MAX_NOTE));
        drug.setCreatedAt(now);
        drug.setCreatedBy(actorId(caller));
        try {
            drugMapper.insertDrug(drug);
        } catch (DuplicateKeyException duplicate) {
            throw new M2ConflictException("用药记录写入冲突，请重试");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_DRUG);
        detail.put("recordId", entity.getId().toString());
        detail.put("drugId", drug.getId().toString());
        detail.put("drugName", drugName);
        detail.put("dose", dose.toPlainString());
        detail.put("unit", unit);
        detail.put("route", route);
        detail.put("narcotic", narcotic);
        if (secondCheckerId != null) {
            detail.put("secondCheckerId", secondCheckerId.toString());
        }
        auditWriter.record(ACTION_DRUG, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);
        return toDrugDto(drug);
    }

    @Override
    public FluidRecordList listFluids(String recordId, AuthPrincipal caller) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        requireVisibleSchedule(entity, caller);
        FluidRecordList result = new FluidRecordList();
        List<FluidRecord> items = new ArrayList<>();
        for (FluidRecordEntity row : fluidMapper.selectByRecord(entity.getId())) {
            items.add(toFluidDto(row));
        }
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FluidRecord recordFluid(String recordId, RecordFluidRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        requireVisibleSchedule(entity, caller);
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        String direction = requireEnum(request.getDirection(), "direction");
        String fluidType = requireText(request.getFluidType(), "fluidType", 64);
        if (request.getAmountMl() == null || request.getAmountMl() <= 0) {
            throw new M2ValidationException("amountMl", "量必须大于 0 毫升");
        }
        Instant now = truncatedNow();
        Instant recordedAt = request.getRecordedAt() == null ? now
                : request.getRecordedAt().toInstant().truncatedTo(ChronoUnit.MILLIS);

        FluidRecordEntity fluid = new FluidRecordEntity();
        fluid.setId(UUID.randomUUID());
        fluid.setRecordId(entity.getId());
        fluid.setDirection(direction);
        fluid.setFluidType(fluidType);
        fluid.setAmountMl(request.getAmountMl());
        fluid.setRecordedAt(recordedAt);
        fluid.setNote(optionalText(request.getNote(), "note", 200));
        fluid.setCreatedAt(now);
        fluid.setCreatedBy(actorId(caller));
        try {
            fluidMapper.insertFluid(fluid);
        } catch (DuplicateKeyException duplicate) {
            throw new M2ConflictException("出入量明细写入冲突，请重试");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_FLUID);
        detail.put("recordId", entity.getId().toString());
        detail.put("fluidId", fluid.getId().toString());
        detail.put("direction", direction);
        detail.put("amountMl", fluid.getAmountMl());
        auditWriter.record(ACTION_FLUID, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);
        return toFluidDto(fluid);
    }

    @Override
    public IntakeOutputSummary ioSummary(String recordId, AuthPrincipal caller) {
        AnesthesiaRecordEntity entity = requireRecord(recordId);
        requireVisibleSchedule(entity, caller);
        Map<String, Object> totals = fluidMapper.selectTotals(entity.getId());
        int intake = intOf(totals, "intake");
        int output = intOf(totals, "output");
        int count = intOf(totals, "fluid_count");
        IntakeOutputSummary summary = new IntakeOutputSummary();
        summary.setRecordId(entity.getId().toString());
        summary.setIntakeMl(intake);
        summary.setOutputMl(output);
        summary.setBalanceMl(intake - output);
        summary.setFluidCount(count);
        summary.setComputedAt(offset(truncatedNow()));
        return summary;
    }

    // ------------------------------------------------------------- helpers --

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
            // 单对象语义：记录单越界/台次不可见统一 404（不泄露存在性）。
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
    }

    /**
     * 麻精核对人资格：必须存在、在职（status=1）且持有 {@code m2:record:write}。
     *
     * <p>通过 m0-api 查询（{@link UserAdminService#get} 取状态、{@link AuthService#currentUser}
     * 取实时权限快照），不访问 m0 表/Mapper。权限快照对停用/不存在用户抛
     * {@link BadCredentialsException}，按 fail-closed 处理为 400。
     */
    private void requireEligibleChecker(AnesthesiaRecordEntity entity, UUID checkerId,
            AuthPrincipal caller, AuthRequestContext context) {
        UserSummary checker;
        try {
            checker = userAdminService.get(checkerId);
        } catch (NoSuchElementException missing) {
            throw drugRejected(entity, caller, context, "secondCheckerId",
                    "第二核对人不存在或已停用", "CHECKER_NOT_FOUND");
        }
        if (checker.getStatus() != UserStatus.ACTIVE) {
            throw drugRejected(entity, caller, context, "secondCheckerId",
                    "第二核对人不存在或已停用", "CHECKER_DISABLED");
        }
        CurrentUser snapshot;
        try {
            snapshot = authService.currentUser(checkerId);
        } catch (BadCredentialsException denied) {
            throw drugRejected(entity, caller, context, "secondCheckerId",
                    "第二核对人不存在或已停用", "CHECKER_DISABLED");
        }
        List<String> permissions = snapshot.getPermissions();
        if (permissions == null || !permissions.contains(M2Permissions.RECORD_WRITE)) {
            throw drugRejected(entity, caller, context, "secondCheckerId",
                    "第二核对人无麻醉记录书写权限", "CHECKER_NO_PERMISSION");
        }
    }

    /**
     * 麻精核对失败：先以独立事务写 {@code result=0} 审计（detail 只含记录标识、麻精标记与
     * 失败原因码，不含核对人姓名），再返回 400 供调用方抛出。
     */
    private M2ValidationException drugRejected(AnesthesiaRecordEntity entity, AuthPrincipal caller,
            AuthRequestContext context, String field, String message, String reasonCode) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_DRUG);
        detail.put("recordId", entity.getId().toString());
        detail.put("narcotic", true);
        detail.put("rejection", reasonCode);
        failureAuditor.recordFailure(ACTION_DRUG, RESOURCE_TYPE, entity.getId().toString(), detail,
                caller, context);
        return new M2ValidationException(field, message);
    }

    private static Map<String, Object> auditSnapshot(AnesthesiaRecordEntity entity) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", entity.getStatus());
        snapshot.put("anesthesiaType", entity.getAnesthesiaType());
        snapshot.put("startedAt", entity.getStartedAt() == null ? null
                : entity.getStartedAt().toString());
        snapshot.put("endedAt", entity.getEndedAt() == null ? null : entity.getEndedAt().toString());
        snapshot.put("version", entity.getVersion());
        return snapshot;
    }

    private void auditUpdate(AnesthesiaRecordEntity entity, Map<String, Object> before,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_UPDATE);
        detail.put("recordId", entity.getId().toString());
        detail.put("scheduleId", entity.getScheduleId().toString());
        detail.put("applicationId", entity.getApplicationId().toString());
        detail.put("before", before);
        detail.put("after", auditSnapshot(entity));
        auditWriter.record(ACTION_UPDATE, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);
    }

    private List<RecordEventEntity> eventsOf(UUID recordId) {
        List<RecordEventEntity> rows = eventMapper.selectByRecord(recordId);
        return rows == null ? List.of() : rows;
    }

    private static void requireVersion(AnesthesiaRecordEntity entity, Integer requestVersion) {
        if (requestVersion == null || entity.getVersion() == null
                || !entity.getVersion().equals(requestVersion.longValue())) {
            throw new M2ConflictException("麻醉记录单版本已过期，请刷新后重试");
        }
    }

    private AnesthesiaRecord toDto(AnesthesiaRecordEntity entity, Schedule schedule,
            List<RecordEventEntity> events) {
        AnesthesiaRecord dto = new AnesthesiaRecord();
        dto.setRecordId(entity.getId().toString());
        dto.setScheduleId(entity.getScheduleId().toString());
        dto.setPatientId(entity.getPatientId().toString());
        dto.setPatientNo(schedule == null ? null : schedule.getPatientNo());
        dto.setPatientName(schedule == null ? null : schedule.getPatientName());
        dto.setStatus(AnesthesiaRecord.StatusEnum.fromValue(
                STATUS_ONGOING.equals(entity.getStatus()) ? "IN_PROGRESS" : entity.getStatus()));
        dto.setAnesthesiaType(AnesthesiaRecord.AnesthesiaTypeEnum.fromValue(
                entity.getAnesthesiaType()));
        dto.setStartedAt(offset(entity.getStartedAt()));
        dto.setEndedAt(offset(entity.getEndedAt()));
        List<AnesthesiaEvent> legacyEvents = new ArrayList<>();
        for (RecordEventEntity event : events) {
            AnesthesiaEvent mapped = toLegacyEvent(event);
            if (mapped != null) {
                legacyEvents.add(mapped);
            }
        }
        dto.setEvents(legacyEvents);
        dto.setVitalSigns(List.of());
        dto.setThreeWayCheckCompleted(
                threeWayCheckService.isThreeWayCheckCompleted(entity.getApplicationId().toString()));
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        dto.setUpdatedAt(offset(entity.getUpdatedAt()));
        dto.setUpdatedBy(string(entity.getUpdatedBy()));
        dto.setVersion(entity.getVersion() == null ? 0 : entity.getVersion().intValue());
        return dto;
    }

    /**
     * 0.4.0 兼容字段映射：只保留经典事件类型；CUSTOM 归入 OTHER，
     * ANESTHESIA_START/ANESTHESIA_END 只在完整时间轴（/events）中出现。
     */
    private AnesthesiaEvent toLegacyEvent(RecordEventEntity event) {
        String type = switch (event.getEventType()) {
            case "INDUCTION" -> "INDUCTION";
            case "INTUBATION" -> "INTUBATION";
            case "INCISION" -> "INCISION";
            case "EXTUBATION" -> "EXTUBATION";
            case "CUSTOM" -> "OTHER";
            default -> null;
        };
        if (type == null) {
            return null;
        }
        AnesthesiaEvent dto = new AnesthesiaEvent();
        dto.setEventId(event.getId().toString());
        dto.setEventType(AnesthesiaEvent.EventTypeEnum.fromValue(type));
        dto.setOccurredAt(offset(event.getOccurredAt()));
        dto.setSource(AnesthesiaEvent.SourceEnum.fromValue(event.getSource()));
        dto.setNote(payloadText(event, "note"));
        dto.setEntryReason(payloadText(event, "entryReason"));
        dto.setRecordedAt(offset(event.getCreatedAt()));
        dto.setRecordedBy(string(event.getCreatedBy()));
        return dto;
    }

    private RecordEvent toEventDto(RecordEventEntity entity) {
        RecordEvent dto = new RecordEvent();
        dto.setEventId(entity.getId().toString());
        dto.setRecordId(entity.getRecordId().toString());
        dto.setSeq(entity.getSeq());
        dto.setEventType(RecordEvent.EventTypeEnum.fromValue(entity.getEventType()));
        dto.setOccurredAt(offset(entity.getOccurredAt()));
        dto.setSource(RecordEvent.SourceEnum.fromValue(entity.getSource()));
        dto.setNote(payloadText(entity, "note"));
        dto.setEntryReason(payloadText(entity, "entryReason"));
        dto.setRecordedAt(offset(entity.getCreatedAt()));
        dto.setRecordedBy(string(entity.getCreatedBy()));
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        return dto;
    }

    private DrugAdministration toDrugDto(DrugAdministrationEntity entity) {
        DrugAdministration dto = new DrugAdministration();
        dto.setDrugId(entity.getId().toString());
        dto.setRecordId(entity.getRecordId().toString());
        dto.setDrugName(entity.getDrugName());
        dto.setDose(entity.getDose());
        dto.setUnit(entity.getUnit());
        dto.setRoute(entity.getRoute());
        dto.setAdministeredAt(offset(entity.getAdministeredAt()));
        dto.setNarcotic(entity.getNarcotic());
        dto.setSecondCheckerId(string(entity.getSecondCheckerId()));
        dto.setSecondCheckerName(entity.getSecondCheckerName());
        dto.setNote(entity.getNote());
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        return dto;
    }

    private FluidRecord toFluidDto(FluidRecordEntity entity) {
        FluidRecord dto = new FluidRecord();
        dto.setFluidId(entity.getId().toString());
        dto.setRecordId(entity.getRecordId().toString());
        dto.setDirection(FluidRecord.DirectionEnum.fromValue(entity.getDirection()));
        dto.setFluidType(entity.getFluidType());
        dto.setAmountMl(entity.getAmountMl());
        dto.setRecordedAt(offset(entity.getRecordedAt()));
        dto.setNote(entity.getNote());
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        return dto;
    }

    private void audit(String action, AnesthesiaRecordEntity entity, AuthPrincipal caller,
            AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", action);
        detail.put("recordId", entity.getId().toString());
        detail.put("scheduleId", entity.getScheduleId().toString());
        detail.put("applicationId", entity.getApplicationId().toString());
        detail.put("status", entity.getStatus());
        detail.put("anesthesiaType", entity.getAnesthesiaType());
        detail.put("version", entity.getVersion());
        auditWriter.record(action, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);
    }

    private String payloadText(RecordEventEntity event, String field) {
        String payload = event.getPayload();
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payload).get(field);
            return node == null || node.isNull() ? null : node.asText();
        } catch (JsonProcessingException invalidPayload) {
            return null;
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException invalidPayload) {
            throw new IllegalStateException("事件明细序列化失败", invalidPayload);
        }
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private static String requireEnum(Enum<?> value, String field) {
        if (value == null) {
            throw new M2ValidationException(field, "字段不能为空");
        }
        return value.toString();
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

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requireText(value, field, maxLength);
    }

    private static int intOf(Map<String, Object> row, String key) {
        if (row == null) {
            return 0;
        }
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Instant truncatedNow() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
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
