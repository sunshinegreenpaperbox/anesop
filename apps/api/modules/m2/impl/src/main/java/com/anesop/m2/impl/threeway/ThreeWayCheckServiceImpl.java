package com.anesop.m2.impl.threeway;

import com.anesop.contracts.anesthesia.model.ApplicationThreeWayCheck;
import com.anesop.contracts.anesthesia.model.ApplicationThreeWayCheckList;
import com.anesop.contracts.anesthesia.model.RecordApplicationThreeWayCheckRequest;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.application.SurgeryApplicationService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.threeway.ThreeWayCheckService;
import com.anesop.m2.impl.persistence.entity.ThreeWayCheckEntity;
import com.anesop.m2.impl.persistence.mapper.ThreeWayCheckMapper;
import com.anesop.m2.impl.support.AnesthesiaApplicationSummaries;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 三方核查服务实现（M2-M5-01，契约 anesthesia.yaml 0.4.0，手术申请维度）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>append-only：只做 INSERT + 查询，每次录入新增 {@code checkVersion = max + 1}；
 *       数据库触发器拒绝任何 UPDATE/DELETE/TRUNCATE（55000）。</li>
 *   <li>完成判定：最新版本同时有 timeOutAt 与 signOutAt。{@link #isThreeWayCheckCompleted}
 *       是 M3 锁单前置集成点，非法标识/无记录返回 false（fail-safe）。</li>
 *   <li>时间校验：Time-out/Sign-out 不得晚于当前时间 5 分钟以上（容忍客户端时钟偏差），
 *       Sign-out 不得早于 Time-out；超时/缺失返回 400。</li>
 *   <li>数据范围沿 M1 申请可见性：申请不存在/越界 404；审计 THREEWAY_RECORD 与业务同事务，
 *       detail 只含标识、版本、核对结果与问题项数量，不含问题项原文。</li>
 * </ul>
 */
@Service
public class ThreeWayCheckServiceImpl implements ThreeWayCheckService {

    private static final String RESOURCE_TYPE = "THREE_WAY_CHECK";
    private static final String ACTION_RECORD = "THREEWAY_RECORD";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_ISSUES = 20;
    private static final int MAX_ISSUE_LENGTH = 200;
    private static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);

    /** 允许录入核查的申请状态；取消/驳回为终态。 */
    private static final Set<String> ACTIVE_APPLICATION_STATUSES =
            Set.of("SUBMITTED", "ACCEPTED", "SCHEDULED");

    private final ThreeWayCheckMapper checkMapper;
    private final SurgeryApplicationService applicationService;
    private final AuditWriter auditWriter;
    private final ClinicalFailureAuditor failureAuditor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务/序列化器由容器管理，服务不修改其内部状态")
    public ThreeWayCheckServiceImpl(ThreeWayCheckMapper checkMapper,
            SurgeryApplicationService applicationService,
            AuditWriter auditWriter,
            ClinicalFailureAuditor failureAuditor,
            ObjectMapper objectMapper,
            Clock clock) {
        this.checkMapper = checkMapper;
        this.applicationService = applicationService;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public ApplicationThreeWayCheckList listByApplication(String applicationId, AuthPrincipal caller) {
        UUID id = requireApplicationId(applicationId);
        SurgeryApplication application = loadApplication(id, caller);
        List<ThreeWayCheckEntity> rows = checkMapper.selectList(
                new QueryWrapper<ThreeWayCheckEntity>()
                        .eq("application_id", id)
                        .orderByDesc("check_version"));
        ApplicationThreeWayCheckList result = new ApplicationThreeWayCheckList();
        result.setApplication(AnesthesiaApplicationSummaries.toSummary(application));
        List<ApplicationThreeWayCheck> items = new ArrayList<>();
        for (ThreeWayCheckEntity row : rows) {
            items.add(toDto(row));
        }
        result.setItems(items);
        result.setCompleted(isCompleted(rows.isEmpty() ? null : rows.get(0)));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ApplicationThreeWayCheck record(String applicationId,
            RecordApplicationThreeWayCheckRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        UUID id = requireApplicationId(applicationId);
        boolean inheritedTimeOut = request.getTimeOutAt() == null;
        try {
            SurgeryApplication application = loadApplication(id, caller);
            requireActiveApplication(application);

            Instant now = clock.instant();
            ThreeWayCheckEntity latest = checkMapper.selectLatest(id);
            Instant timeOutAt = resolveTimeOutAt(request, latest, now);
            Instant signOutAt = request.getSignOutAt() == null ? null
                    : requireInstant(request.getSignOutAt(), "signOutAt", now);
            if (signOutAt != null && signOutAt.isBefore(timeOutAt)) {
                throw new M2ValidationException("signOutAt", "Sign-out 不得早于 Time-out");
            }
            UUID callerId = actorId(caller);
            UUID timeOutBy = optionalActor(request.getTimeOutBy(), "timeOutBy", callerId);
            UUID signOutBy = null;
            if (signOutAt != null) {
                signOutBy = optionalActor(request.getSignOutBy(), "signOutBy", callerId);
            } else if (request.getSignOutBy() != null && !request.getSignOutBy().isBlank()) {
                throw new M2ValidationException("signOutBy",
                        "填写 Sign-out 执行人时必须同时填写 Sign-out 时间");
            }
            if (request.getSpecimenChecked() == null || request.getImplantChecked() == null) {
                throw new M2ValidationException("specimenChecked", "标本与植入物核对结果必须填写");
            }
            String issuesJson = writeIssues(request.getIssues());

            int nextVersion = checkMapper.maxCheckVersion(id) + 1;
            ThreeWayCheckEntity entity = new ThreeWayCheckEntity();
            entity.setId(UUID.randomUUID());
            entity.setApplicationId(id);
            entity.setCheckVersion(nextVersion);
            entity.setTimeOutAt(timeOutAt);
            entity.setTimeOutBy(timeOutBy);
            entity.setSignOutAt(signOutAt);
            entity.setSignOutBy(signOutBy);
            entity.setSpecimenChecked(request.getSpecimenChecked());
            entity.setImplantChecked(request.getImplantChecked());
            entity.setIssuesJson(issuesJson);
            entity.setCreatedAt(now);
            entity.setCreatedBy(callerId);
            entity.setUpdatedAt(now);
            entity.setUpdatedBy(callerId);
            try {
                checkMapper.insertCheck(entity);
            } catch (DuplicateKeyException concurrentRecord) {
                // 并发录入：uk_three_way_check_application_version 兜底，提示重试新增版本。
                throw new M2ConflictException("三方核查版本冲突，请刷新后重试");
            }
            audit(entity, caller, context, inheritedTimeOut);
            return toDto(entity);
        } catch (M2ConflictException | M2ValidationException failure) {
            auditFailure(id, failure, caller, context);
            throw failure;
        }
    }

    /**
     * 跨版本 Time-out 时序（S-1）：
     * 省略 timeOutAt 时继承上一版本；首版必须显式填写；显式值与上一版本不一致且未
     * confirmTimeOutChange=true 时拒绝（409），避免无痕改写已完成核查的 Time-out。
     */
    private Instant resolveTimeOutAt(RecordApplicationThreeWayCheckRequest request,
            ThreeWayCheckEntity latest, Instant now) {
        if (request.getTimeOutAt() == null) {
            if (latest == null || latest.getTimeOutAt() == null) {
                throw new M2ValidationException("timeOutAt", "首次核查必须填写 Time-out 时间");
            }
            return latest.getTimeOutAt();
        }
        Instant requested = requireInstant(request.getTimeOutAt(), "timeOutAt", now);
        if (latest != null && latest.getTimeOutAt() != null) {
            Instant previous = latest.getTimeOutAt().truncatedTo(ChronoUnit.MILLIS);
            boolean changed = !requested.truncatedTo(ChronoUnit.MILLIS).equals(previous);
            if (changed && !Boolean.TRUE.equals(request.getConfirmTimeOutChange())) {
                throw new M2ConflictException("Time-out 时间与上一版本不一致，请显式确认后提交");
            }
        }
        return requested;
    }

    @Override
    public boolean isThreeWayCheckCompleted(String applicationId) {
        UUID id = parseUuid(applicationId);
        if (id == null) {
            // fail-safe：标识非法视为未完成，宁可不放行锁单。
            return false;
        }
        return isCompleted(checkMapper.selectLatest(id));
    }

    // ------------------------------------------------------------- helpers --

    private static boolean isCompleted(ThreeWayCheckEntity latest) {
        return latest != null && latest.getTimeOutAt() != null && latest.getSignOutAt() != null;
    }

    private UUID requireApplicationId(String applicationId) {
        UUID id = parseUuid(applicationId);
        if (id == null) {
            // 路径参数非法：与“申请不存在/不可见”同语义，返回 404，不泄露格式细节。
            throw new NoSuchElementException("手术申请不存在或不可见");
        }
        return id;
    }

    private SurgeryApplication loadApplication(UUID applicationId, AuthPrincipal caller) {
        try {
            return applicationService.get(applicationId.toString(), caller);
        } catch (NoSuchElementException notVisible) {
            throw new NoSuchElementException("手术申请不存在或不可见");
        }
    }

    private static void requireActiveApplication(SurgeryApplication application) {
        String status = application.getStatus() == null ? null : application.getStatus().getValue();
        if (status == null || !ACTIVE_APPLICATION_STATUSES.contains(status)) {
            throw new M2ConflictException("手术申请状态不允许录入三方核查");
        }
    }

    private Instant requireInstant(OffsetDateTime value, String field, Instant now) {
        if (value == null) {
            throw new M2ValidationException(field, "时间不能为空");
        }
        Instant instant = value.toInstant();
        if (instant.isAfter(now.plus(CLOCK_TOLERANCE))) {
            throw new M2ValidationException(field, "时间不得晚于当前时间（允许 5 分钟时钟偏差）");
        }
        return instant;
    }

    private static UUID optionalActor(String value, String field, UUID fallback) {
        if (value == null || value.isBlank()) {
            if (fallback == null) {
                throw new M2ValidationException(field, "执行人不能为空");
            }
            return fallback;
        }
        UUID parsed = parseUuid(value);
        if (parsed == null) {
            throw new M2ValidationException(field, "执行人标识格式不正确");
        }
        return parsed;
    }

    private String writeIssues(List<String> issues) {
        List<String> normalized = new ArrayList<>();
        if (issues != null) {
            if (issues.size() > MAX_ISSUES) {
                throw new M2ValidationException("issues", "问题项最多 " + MAX_ISSUES + " 条");
            }
            for (String issue : issues) {
                if (issue == null || issue.isBlank()) {
                    continue;
                }
                String trimmed = issue.trim();
                if (trimmed.length() > MAX_ISSUE_LENGTH) {
                    throw new M2ValidationException("issues", "单条问题项长度超出限制");
                }
                normalized.add(trimmed);
            }
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("三方核查问题项序列化失败", serializationFailure);
        }
    }

    private List<String> readIssues(String json) {
        try {
            List<String> issues = objectMapper.readValue(json,
                    new TypeReference<List<String>>() { });
            return issues == null ? List.of() : issues;
        } catch (JsonProcessingException parseFailure) {
            throw new IllegalStateException("三方核查问题项数据损坏", parseFailure);
        }
    }

    private ApplicationThreeWayCheck toDto(ThreeWayCheckEntity entity) {
        ApplicationThreeWayCheck dto = new ApplicationThreeWayCheck();
        dto.setCheckId(entity.getId().toString());
        dto.setApplicationId(entity.getApplicationId().toString());
        dto.setTimeOutAt(offset(entity.getTimeOutAt()));
        dto.setTimeOutBy(string(entity.getTimeOutBy()));
        dto.setSignOutAt(offset(entity.getSignOutAt()));
        dto.setSignOutBy(string(entity.getSignOutBy()));
        dto.setSpecimenChecked(entity.getSpecimenChecked());
        dto.setImplantChecked(entity.getImplantChecked());
        dto.setIssues(readIssues(entity.getIssuesJson()));
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        dto.setUpdatedAt(offset(entity.getUpdatedAt()));
        dto.setUpdatedBy(string(entity.getUpdatedBy()));
        dto.setVersion(entity.getCheckVersion());
        return dto;
    }

    private void audit(ThreeWayCheckEntity entity, AuthPrincipal caller, AuthRequestContext context,
            boolean inheritedTimeOut) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_RECORD);
        detail.put("checkId", entity.getId().toString());
        detail.put("applicationId", entity.getApplicationId().toString());
        detail.put("checkVersion", entity.getCheckVersion());
        detail.put("specimenChecked", entity.getSpecimenChecked());
        detail.put("implantChecked", entity.getImplantChecked());
        detail.put("issuesCount", readIssues(entity.getIssuesJson()).size());
        detail.put("signOutRecorded", entity.getSignOutAt() != null);
        detail.put("timeOutInherited", inheritedTimeOut);
        auditWriter.record(ACTION_RECORD, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);
    }

    /**
     * 失败路径审计（S-4）：409/400 也留痕（result=0），独立事务写入；detail 只含动作、
     * 申请标识与错误类别，不含问题项原文、执行人姓名或患者标识。
     */
    private void auditFailure(UUID applicationId, RuntimeException failure, AuthPrincipal caller,
            AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_RECORD);
        detail.put("applicationId", applicationId.toString());
        detail.put("error", failure instanceof M2ConflictException
                ? "STATE_CONFLICT" : "PARAM_INVALID");
        failureAuditor.recordFailure(ACTION_RECORD, RESOURCE_TYPE, applicationId.toString(),
                detail, caller, context);
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
