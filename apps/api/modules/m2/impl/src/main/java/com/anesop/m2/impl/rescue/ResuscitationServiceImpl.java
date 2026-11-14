package com.anesop.m2.impl.rescue;

import com.anesop.contracts.anesthesia.model.CreateResuscitationRequest;
import com.anesop.contracts.anesthesia.model.ResuscitationDrug;
import com.anesop.contracts.anesthesia.model.ResuscitationEventSummary;
import com.anesop.contracts.anesthesia.model.ResuscitationParticipant;
import com.anesop.contracts.anesthesia.model.ResuscitationRecord;
import com.anesop.contracts.anesthesia.model.ResuscitationRecordList;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.rescue.ResuscitationService;
import com.anesop.m2.impl.config.RecordProperties;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.ResuscitationRecordEntity;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.ResuscitationRecordMapper;
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
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 抢救记录服务实现（M3-M2-02，契约 anesthesia.yaml 0.7.0）。
 *
 * <p>锁单后冻结（Q-2）：记录单 locked_at 非空时拒绝新增（409），与“记录内容不可变”一致；
 * 数据库 BEFORE INSERT 触发器（V17）为第二道防线（55000）。如临床确需锁单后补录抢救，
 * 走下一修订版本能力（登记 Q，不绕过冻结）。
 *
 * <p>append-only 留痕：只 INSERT 不 UPDATE/DELETE（数据库触发器兜底 55000）；时间窗必须落在
 * 该记录单的麻醉时间窗内（startedAt ≥ 记录开始；endedAt ≥ startedAt；endedAt ≤ 记录结束或
 * 服务端当前时间 + 容差）。人员留痕（V16/评审 B-4）：负责人 leader 必填、参与人员 rescuers
 * 至少 1 人（结构化 {userId?, name}），人员姓名只落库、不写入日志/审计原文。
 * 用药明细逐条校验（药名/剂量/单位/途径/给药时间），事件摘要/用药/人员以 jsonb 落库，
 * 表内不含患者直接标识。
 *
 * <p>审计 RESCUE_RECORD 只含记录单/抢救记录标识、类型、时间窗、转归与明细条数，
 * 不含事件摘要与药品明细原文。
 */
@Service
public class ResuscitationServiceImpl implements ResuscitationService {

    private static final String RESOURCE_TYPE = "RESUSCITATION_RECORD";
    private static final String ACTION_CREATE = "RESCUE_RECORD";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_SUMMARY = 2000;
    private static final int MAX_INTERVENTIONS = 50;
    private static final int MAX_INTERVENTION = 200;
    private static final int MAX_DRUGS = 50;
    private static final int MAX_PARTICIPANTS = 20;
    private static final int MAX_PERSON_NAME = 100;

    private final AnesthesiaRecordMapper recordMapper;
    private final ResuscitationRecordMapper resuscitationMapper;
    private final SchedulingService schedulingService;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final RecordProperties recordProperties;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public ResuscitationServiceImpl(AnesthesiaRecordMapper recordMapper,
            ResuscitationRecordMapper resuscitationMapper, SchedulingService schedulingService,
            AuditWriter auditWriter, ObjectMapper objectMapper, RecordProperties recordProperties,
            Clock clock) {
        this.recordMapper = recordMapper;
        this.resuscitationMapper = resuscitationMapper;
        this.schedulingService = schedulingService;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.recordProperties = recordProperties;
        this.clock = clock;
    }

    @Override
    public ResuscitationRecordList list(String recordId, AuthPrincipal caller) {
        AnesthesiaRecordEntity record = requireVisibleRecord(recordId, caller);
        List<ResuscitationRecord> items = new ArrayList<>();
        for (ResuscitationRecordEntity entity : resuscitationMapper.selectByRecord(record.getId())) {
            items.add(toDto(entity));
        }
        ResuscitationRecordList result = new ResuscitationRecordList();
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResuscitationRecord create(String recordId, CreateResuscitationRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        AnesthesiaRecordEntity record = requireVisibleRecord(recordId, caller);
        // 与并发锁单串行化：先锁记录单行，再判定冻结状态（数据库触发器兜底）。
        AnesthesiaRecordEntity locked = recordMapper.selectByIdForUpdate(record.getId());
        if (locked == null) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        if (locked.getLockedAt() != null) {
            throw new M2ConflictException("记录单已锁单，不可新增抢救记录；如需补录请走下一修订版本（Q-2）");
        }
        record = locked;
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
        String resusType = request.getResusType() == null ? null
                : request.getResusType().getValue();
        if (resusType == null) {
            throw new M2ValidationException("resusType", "抢救类型不能为空");
        }
        String outcome = request.getOutcome() == null ? null : request.getOutcome().getValue();
        if (outcome == null) {
            throw new M2ValidationException("outcome", "转归不能为空");
        }
        Instant startedAt = toInstant(request.getStartedAt());
        Instant endedAt = toInstant(request.getEndedAt());
        if (startedAt == null) {
            throw new M2ValidationException("startedAt", "抢救开始时间不能为空");
        }
        if (endedAt == null) {
            throw new M2ValidationException("endedAt", "抢救结束时间不能为空");
        }
        requireTimeWindow(record, startedAt, endedAt);
        ResuscitationEventSummary summary = requireSummary(request.getEventSummary());
        List<ResuscitationDrug> drugs = requireDrugs(request.getDrugs(), startedAt, endedAt);
        ResuscitationParticipant leader = requireParticipant(request.getLeader(), "leader",
                "抢救负责人不能为空");
        List<ResuscitationParticipant> rescuers = requireRescuers(request.getRescuers());

        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ResuscitationRecordEntity entity = new ResuscitationRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setRecordId(record.getId());
        entity.setResusType(resusType);
        entity.setStartedAt(startedAt);
        entity.setEndedAt(endedAt);
        entity.setEventSummary(toJson(summary));
        entity.setDrugs(toJson(drugs));
        entity.setLeader(toJson(leader));
        entity.setRescuers(toJson(rescuers));
        entity.setOutcome(outcome);
        entity.setCreatedAt(now);
        entity.setCreatedBy(caller == null ? null : caller.userId());
        resuscitationMapper.insertRecord(entity);
        auditCreate(record, entity, summary, drugs, leader, rescuers, caller, context);
        return toDto(entity);
    }

    // ------------------------------------------------------------- helpers --

    private void requireTimeWindow(AnesthesiaRecordEntity record, Instant startedAt,
            Instant endedAt) {
        Instant recordStart = record.getStartedAt() != null ? record.getStartedAt()
                : record.getCreatedAt();
        if (recordStart != null && startedAt.isBefore(recordStart)) {
            throw new M2ValidationException("startedAt", "抢救开始时间不得早于麻醉记录开始时间");
        }
        if (endedAt.isBefore(startedAt)) {
            throw new M2ValidationException("endedAt", "抢救结束时间不得早于开始时间");
        }
        Instant upper = record.getEndedAt() != null ? record.getEndedAt()
                : truncatedNow().plusSeconds(recordProperties.timeToleranceSeconds());
        if (endedAt.isAfter(upper)) {
            throw new M2ValidationException("endedAt", "抢救结束时间超出麻醉记录时间窗");
        }
    }

    private static ResuscitationEventSummary requireSummary(ResuscitationEventSummary summary) {
        if (summary == null || summary.getSummary() == null
                || summary.getSummary().trim().isEmpty()) {
            throw new M2ValidationException("eventSummary.summary", "事件经过摘要不能为空");
        }
        String text = summary.getSummary().trim();
        if (text.length() > MAX_SUMMARY) {
            throw new M2ValidationException("eventSummary.summary", "事件经过摘要长度超出限制");
        }
        List<String> interventions = summary.getInterventions();
        if (interventions != null) {
            if (interventions.size() > MAX_INTERVENTIONS) {
                throw new M2ValidationException("eventSummary.interventions", "关键干预措施过多");
            }
            for (String item : interventions) {
                if (item == null || item.trim().isEmpty() || item.trim().length() > MAX_INTERVENTION) {
                    throw new M2ValidationException("eventSummary.interventions",
                            "关键干预措施为空或长度超出限制");
                }
            }
        }
        ResuscitationEventSummary normalized = new ResuscitationEventSummary();
        normalized.setSummary(text);
        if (interventions != null) {
            normalized.setInterventions(interventions.stream().map(String::trim).toList());
        }
        return normalized;
    }

    private static List<ResuscitationDrug> requireDrugs(List<ResuscitationDrug> drugs,
            Instant startedAt, Instant endedAt) {
        if (drugs == null) {
            return List.of();
        }
        if (drugs.size() > MAX_DRUGS) {
            throw new M2ValidationException("drugs", "抢救用药明细不能超过 50 条");
        }
        for (ResuscitationDrug drug : drugs) {
            if (drug == null || drug.getDrugName() == null || drug.getDrugName().trim().isEmpty()
                    || drug.getDrugName().trim().length() > 200) {
                throw new M2ValidationException("drugs.drugName", "药品名称不能为空且不超过 200 字符");
            }
            BigDecimal dose = drug.getDose();
            if (dose == null || dose.compareTo(BigDecimal.ZERO) <= 0) {
                throw new M2ValidationException("drugs.dose", "剂量必须大于 0");
            }
            if (drug.getUnit() == null || drug.getUnit().trim().isEmpty()
                    || drug.getUnit().trim().length() > 16) {
                throw new M2ValidationException("drugs.unit", "剂量单位不能为空且不超过 16 字符");
            }
            if (drug.getRoute() != null && drug.getRoute().trim().length() > 32) {
                throw new M2ValidationException("drugs.route", "给药途径长度超出限制");
            }
            Instant administeredAt = toInstant(drug.getAdministeredAt());
            if (administeredAt != null
                    && (administeredAt.isBefore(startedAt) || administeredAt.isAfter(endedAt))) {
                throw new M2ValidationException("drugs.administeredAt",
                        "给药时间必须落在抢救时间窗内");
            }
        }
        return drugs;
    }

    private static ResuscitationParticipant requireParticipant(ResuscitationParticipant participant,
            String field, String emptyMessage) {
        if (participant == null || participant.getName() == null
                || participant.getName().trim().isEmpty()) {
            throw new M2ValidationException(field, emptyMessage);
        }
        String name = participant.getName().trim();
        if (name.length() > MAX_PERSON_NAME) {
            throw new M2ValidationException(field, "人员姓名长度超出限制");
        }
        String userId = participant.getUserId();
        if (userId != null && userId.trim().length() > 64) {
            throw new M2ValidationException(field, "人员标识长度超出限制");
        }
        ResuscitationParticipant normalized = new ResuscitationParticipant();
        normalized.setName(name);
        normalized.setUserId(userId == null || userId.trim().isEmpty() ? null : userId.trim());
        return normalized;
    }

    private static List<ResuscitationParticipant> requireRescuers(
            List<ResuscitationParticipant> rescuers) {
        if (rescuers == null || rescuers.isEmpty()) {
            throw new M2ValidationException("rescuers", "抢救参与人员至少 1 人");
        }
        if (rescuers.size() > MAX_PARTICIPANTS) {
            throw new M2ValidationException("rescuers", "抢救参与人员不能超过 20 人");
        }
        List<ResuscitationParticipant> normalized = new ArrayList<>(rescuers.size());
        for (int index = 0; index < rescuers.size(); index++) {
            normalized.add(requireParticipant(rescuers.get(index), "rescuers[" + index + "]",
                    "抢救参与人员姓名不能为空"));
        }
        return normalized;
    }

    private AnesthesiaRecordEntity requireVisibleRecord(String recordId, AuthPrincipal caller) {
        UUID id = parseUuid(recordId);
        AnesthesiaRecordEntity record = id == null ? null : recordMapper.selectById(id);
        if (record == null) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        try {
            Schedule schedule = schedulingService.get(record.getScheduleId().toString(), caller);
            if (schedule == null) {
                throw new NoSuchElementException("麻醉记录单不存在或不可见");
            }
        } catch (NoSuchElementException
                | org.springframework.security.access.AccessDeniedException notVisible) {
            throw new NoSuchElementException("麻醉记录单不存在或不可见");
        }
        return record;
    }

    private void auditCreate(AnesthesiaRecordEntity record, ResuscitationRecordEntity entity,
            ResuscitationEventSummary summary, List<ResuscitationDrug> drugs,
            ResuscitationParticipant leader, List<ResuscitationParticipant> rescuers,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_CREATE);
        detail.put("recordId", record.getId().toString());
        detail.put("resuscitationId", entity.getId().toString());
        detail.put("resusType", entity.getResusType());
        detail.put("startedAt", entity.getStartedAt().toString());
        detail.put("endedAt", entity.getEndedAt().toString());
        detail.put("outcome", entity.getOutcome());
        detail.put("drugCount", drugs.size());
        detail.put("interventionCount",
                summary.getInterventions() == null ? 0 : summary.getInterventions().size());
        // 人员只记标识与人数，姓名不入审计（AGENTS 日志/审计无姓名）。
        detail.put("leaderUserId", leader.getUserId());
        detail.put("rescuerCount", rescuers.size());
        auditWriter.record(ACTION_CREATE, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("抢救记录结构化字段序列化失败", invalid);
        }
    }

    private ResuscitationRecord toDto(ResuscitationRecordEntity entity) {
        ResuscitationRecord dto = new ResuscitationRecord();
        dto.setResuscitationId(entity.getId().toString());
        dto.setRecordId(entity.getRecordId().toString());
        dto.setResusType(ResuscitationRecord.ResusTypeEnum.fromValue(entity.getResusType()));
        dto.setStartedAt(offset(entity.getStartedAt()));
        dto.setEndedAt(offset(entity.getEndedAt()));
        dto.setEventSummary(readSummary(entity.getEventSummary()));
        dto.setDrugs(readDrugs(entity.getDrugs()));
        dto.setLeader(readParticipant(entity.getLeader()));
        dto.setRescuers(readParticipants(entity.getRescuers()));
        dto.setOutcome(ResuscitationRecord.OutcomeEnum.fromValue(entity.getOutcome()));
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        return dto;
    }

    private ResuscitationEventSummary readSummary(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ResuscitationEventSummary.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("抢救记录事件摘要解析失败", invalid);
        }
    }

    private List<ResuscitationDrug> readDrugs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ResuscitationDrug>>() {
            });
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("抢救记录用药明细解析失败", invalid);
        }
    }

    private ResuscitationParticipant readParticipant(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ResuscitationParticipant.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("抢救负责人解析失败", invalid);
        }
    }

    private List<ResuscitationParticipant> readParticipants(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<ResuscitationParticipant>>() {
                    });
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("抢救参与人员解析失败", invalid);
        }
    }

    private Instant truncatedNow() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
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
