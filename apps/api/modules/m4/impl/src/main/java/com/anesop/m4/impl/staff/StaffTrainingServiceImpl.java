package com.anesop.m4.impl.staff;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.staff.model.CreateStaffTrainingRequest;
import com.anesop.contracts.staff.model.ExpiringTrainingList;
import com.anesop.contracts.staff.model.StaffTraining;
import com.anesop.contracts.staff.model.StaffTrainingList;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.staff.StaffTrainingService;
import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
import com.anesop.m4.impl.persistence.entity.StaffTrainingEntity;
import com.anesop.m4.impl.persistence.mapper.StaffProfileMapper;
import com.anesop.m4.impl.persistence.mapper.StaffTrainingMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.anesop.m4.impl.support.M4Values;
import com.anesop.m4.impl.support.StaffAccessGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 培训记录服务实现（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>到期提醒：{@code expiresOn <= 今天 + days}（含已过期，便于清零），默认 30 天（方案书
 * “证书到期前 30 天提醒”）。{@code status} 按 30 天固定窗口计算（VALID/EXPIRING/EXPIRED/
 * NO_EXPIRY），与查询参数 {@code days} 无关。审计 TRAINING_RECORD 只含标识、课程编码与
 * 日期，**不含证书号原文**。
 */
@Service
public class StaffTrainingServiceImpl implements StaffTrainingService {

    private static final String RESOURCE_TYPE = "STAFF_TRAINING";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final Pattern COURSE_CODE_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    /** 方案书口径：证书到期前 30 天提醒（查询参数可放大窗口，但状态按 30 天计算）。 */
    private static final int REMINDER_WINDOW_DAYS = 30;
    private static final int DEFAULT_EXPIRING_DAYS = 30;

    private final StaffTrainingMapper trainingMapper;
    private final StaffProfileMapper profileMapper;
    private final StaffAccessGuard accessGuard;
    private final AuditWriter auditWriter;
    private final M4FailureAuditor failureAuditor;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public StaffTrainingServiceImpl(StaffTrainingMapper trainingMapper,
            StaffProfileMapper profileMapper, StaffAccessGuard accessGuard, AuditWriter auditWriter,
            M4FailureAuditor failureAuditor, Clock clock) {
        this.trainingMapper = trainingMapper;
        this.profileMapper = profileMapper;
        this.accessGuard = accessGuard;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 查询 --

    @Override
    public StaffTrainingList list(String staffId, int limit, AuthPrincipal caller) {
        requireLimit(limit);
        String staffFilter = M4Values.optionalText(staffId, "staffId", 36);
        if (staffFilter != null) {
            requireReadableStaff(staffFilter, caller);
        }
        StaffAccessGuard.StaffVisibility visibility = accessGuard.visibility(caller);
        List<StaffTrainingEntity> rows = trainingMapper.selectVisible(staffFilter,
                visibility.allowedDeptIds(), visibility.selfUserId(), limit);
        StaffTrainingList result = new StaffTrainingList();
        List<StaffTraining> items = new ArrayList<>();
        LocalDate today = today();
        for (StaffTrainingEntity row : rows) {
            items.add(toDto(row, today));
        }
        result.setItems(items);
        return result;
    }

    @Override
    public ExpiringTrainingList expiring(Integer days, String departmentId, AuthPrincipal caller) {
        int window = days == null ? DEFAULT_EXPIRING_DAYS : days;
        if (window < 1 || window > 365) {
            throw new M4ValidationException("days", "提醒窗口天数必须在 1–365 之间");
        }
        String deptFilter = M4Values.optionalText(departmentId, "departmentId", 36);
        if (deptFilter != null) {
            deptFilter = accessGuard.requireDepartmentFilter(caller, deptFilter, "departmentId");
        }
        StaffAccessGuard.StaffVisibility visibility = accessGuard.visibility(caller);
        LocalDate today = today();
        List<StaffTrainingEntity> rows = trainingMapper.selectExpiring(today.plusDays(window),
                deptFilter, visibility.allowedDeptIds(), visibility.selfUserId());
        ExpiringTrainingList result = new ExpiringTrainingList();
        List<StaffTraining> items = new ArrayList<>();
        for (StaffTrainingEntity row : rows) {
            items.add(toDto(row, today));
        }
        result.setItems(items);
        result.setDays(window);
        result.setAsOf(today);
        return result;
    }

    // ------------------------------------------------------------------ 写入 --

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StaffTraining create(CreateStaffTrainingRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        try {
            return doCreate(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("TRAINING_RECORD", "NEW", failure, caller, context);
            throw failure;
        }
    }

    private StaffTraining doCreate(CreateStaffTrainingRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        accessGuard.requireWritable(caller);
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        StaffProfileEntity staff = requireWritableStaff(request.getStaffId(), caller);
        String courseCode = M4Values.requireText(request.getCourseCode(), "courseCode", 64);
        if (!COURSE_CODE_PATTERN.matcher(courseCode).matches()) {
            throw new M4ValidationException("courseCode",
                    "课程编码只能包含字母、数字、点、下划线与连字符（1–64）");
        }
        String courseName = M4Values.requireText(request.getCourseName(), "courseName", 200);
        LocalDate completedOn = request.getCompletedOn();
        if (completedOn == null) {
            throw new M4ValidationException("completedOn", "完成日期不能为空");
        }
        LocalDate today = today();
        if (completedOn.isAfter(today)) {
            throw new M4ValidationException("completedOn", "完成日期不能晚于今天");
        }
        LocalDate expiresOn = request.getExpiresOn();
        if (expiresOn != null && expiresOn.isBefore(completedOn)) {
            throw new M4ValidationException("expiresOn", "到期日期不得早于完成日期");
        }
        String certificateNo = M4Values.optionalText(request.getCertificateNo(), "certificateNo", 64);
        if (trainingMapper.countCourseConflict(staff.getId().toString(), courseCode) > 0) {
            throw new M4ConflictException("该人员已登记同一课程编码的培训记录");
        }
        Instant now = now();
        StaffTrainingEntity entity = new StaffTrainingEntity();
        entity.setId(UUID.randomUUID());
        entity.setStaffId(staff.getId());
        entity.setCourseCode(courseCode);
        entity.setCourseName(courseName);
        entity.setCompletedOn(completedOn);
        entity.setExpiresOn(expiresOn);
        entity.setCertificateNo(certificateNo);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        entity.setStaffNo(staff.getStaffNo());
        try {
            trainingMapper.insertTraining(entity);
        } catch (DuplicateKeyException duplicate) {
            throw new M4ConflictException("该人员已登记同一课程编码的培训记录");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "TRAINING_RECORD");
        detail.put("trainingId", entity.getId().toString());
        detail.put("staffId", staff.getId().toString());
        detail.put("courseCode", courseCode);
        detail.put("completedOn", completedOn.toString());
        detail.put("expiresOn", expiresOn == null ? null : expiresOn.toString());
        auditWriter.record("TRAINING_RECORD", RESOURCE_TYPE, entity.getId().toString(), true,
                detail, actorId(caller), username(caller), context);
        return toDto(entity, today);
    }

    // ------------------------------------------------------------------ 转换 --

    private StaffTraining toDto(StaffTrainingEntity entity, LocalDate today) {
        StaffTraining dto = new StaffTraining();
        dto.setTrainingId(entity.getId().toString());
        dto.setStaffId(entity.getStaffId().toString());
        dto.setStaffNo(entity.getStaffNo());
        dto.setCourseCode(entity.getCourseCode());
        dto.setCourseName(entity.getCourseName());
        dto.setCompletedOn(entity.getCompletedOn());
        dto.setExpiresOn(entity.getExpiresOn());
        dto.setCertificateNo(entity.getCertificateNo());
        if (entity.getExpiresOn() == null) {
            dto.setStatus(StaffTraining.StatusEnum.NO_EXPIRY);
        } else {
            long days = ChronoUnit.DAYS.between(today, entity.getExpiresOn());
            dto.setDaysUntilExpiry(Math.toIntExact(days));
            if (days < 0) {
                dto.setStatus(StaffTraining.StatusEnum.EXPIRED);
            } else if (days <= REMINDER_WINDOW_DAYS) {
                dto.setStatus(StaffTraining.StatusEnum.EXPIRING);
            } else {
                dto.setStatus(StaffTraining.StatusEnum.VALID);
            }
        }
        dto.setCreatedAt(entity.getCreatedAt().atOffset(DISPLAY_OFFSET));
        dto.setCreatedBy(entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString());
        return dto;
    }

    // ------------------------------------------------------------------ 辅助 --

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new M4ValidationException("limit", "返回条数必须在 1–200 之间");
        }
    }

    private StaffProfileEntity requireStaff(String staffId) {
        UUID id = M4Values.parseUuid(staffId);
        StaffProfileEntity staff = id == null ? null : profileMapper.selectById(id);
        if (staff == null) {
            throw new NoSuchElementException("人员不存在或不可见");
        }
        return staff;
    }

    private StaffProfileEntity requireReadableStaff(String staffId, AuthPrincipal caller) {
        StaffProfileEntity staff = requireStaff(staffId);
        accessGuard.requireReadable(staff, caller);
        return staff;
    }

    private StaffProfileEntity requireWritableStaff(String staffId, AuthPrincipal caller) {
        if (staffId == null || staffId.isBlank()) {
            throw new M4ValidationException("staffId", "人员标识不能为空");
        }
        StaffProfileEntity staff = requireReadableStaff(staffId, caller);
        if (!STATUS_ACTIVE.equals(staff.getEmploymentStatus())) {
            throw new M4UnprocessableException("staffId", "人员已离职/停用，不能登记培训");
        }
        return staff;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(DISPLAY_OFFSET));
    }

    // ------------------------------------------------------------------ 审计 --

    private void recordFailure(String action, String resourceId, RuntimeException failure,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", action);
        if (failure instanceof M4ValidationException validation) {
            detail.put("rejection", "PARAM_INVALID");
            detail.put("field", validation.field());
        } else if (failure instanceof M4UnprocessableException unprocessable) {
            detail.put("rejection", "BUSINESS_RULE_VIOLATION");
            detail.put("field", unprocessable.field());
        } else {
            detail.put("rejection", "STATE_CONFLICT");
        }
        failureAuditor.recordFailure(action, RESOURCE_TYPE, resourceId, detail, caller, context);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private static UUID actorId(AuthPrincipal caller) {
        return caller == null ? null : caller.userId();
    }

    private static String username(AuthPrincipal caller) {
        return caller == null ? null : caller.username();
    }
}
