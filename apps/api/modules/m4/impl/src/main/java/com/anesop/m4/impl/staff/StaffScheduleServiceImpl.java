package com.anesop.m4.impl.staff;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.staff.model.CancelStaffScheduleRequest;
import com.anesop.contracts.staff.model.CreateStaffScheduleRequest;
import com.anesop.contracts.staff.model.StaffSchedule;
import com.anesop.contracts.staff.model.StaffScheduleList;
import com.anesop.contracts.staff.model.UpdateStaffScheduleRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.staff.StaffScheduleService;
import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
import com.anesop.m4.impl.persistence.entity.StaffScheduleEntity;
import com.anesop.m4.impl.persistence.mapper.StaffProfileMapper;
import com.anesop.m4.impl.persistence.mapper.StaffScheduleMapper;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 排班服务实现（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>冲突检测：同一人员同一日期同一班次、同一术间同一日期同一班次 → 409，
 * {@code details[]} 给出 field（staffId/roomId）与冲突排班标识；已取消不占用名额。
 * 更新/取消为乐观锁；已取消不得复活（服务层 409 + 数据库守卫 55000）。审计
 * SCHEDULE_CREATE/SCHEDULE_UPDATE/SCHEDULE_CANCEL 只含标识、日期、班次、科室与状态，
 * 不含姓名与患者信息。
 */
@Service
public class StaffScheduleServiceImpl implements StaffScheduleService {

    private static final String RESOURCE_TYPE = "STAFF_SCHEDULE";
    private static final String STATUS_PLANNED = "PLANNED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final List<String> SHIFTS = List.of("DAY", "NIGHT", "ON_CALL");
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final Map<String, String> SHIFT_LABELS =
            Map.of("DAY", "白班", "NIGHT", "夜班", "ON_CALL", "备班");

    private final StaffScheduleMapper scheduleMapper;
    private final StaffProfileMapper profileMapper;
    private final StaffAccessGuard accessGuard;
    private final AuditWriter auditWriter;
    private final M4FailureAuditor failureAuditor;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public StaffScheduleServiceImpl(StaffScheduleMapper scheduleMapper,
            StaffProfileMapper profileMapper, StaffAccessGuard accessGuard, AuditWriter auditWriter,
            M4FailureAuditor failureAuditor, Clock clock) {
        this.scheduleMapper = scheduleMapper;
        this.profileMapper = profileMapper;
        this.accessGuard = accessGuard;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 查询 --

    @Override
    public StaffScheduleList list(String staffId, String departmentId, LocalDate from,
            LocalDate to, String shift, String status, int limit, AuthPrincipal caller) {
        if (limit < 1 || limit > 200) {
            throw new M4ValidationException("limit", "返回条数必须在 1–200 之间");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new M4ValidationException("from", "开始日期不能晚于结束日期");
        }
        String shiftFilter = M4Values.optionalText(shift, "shift", 16);
        if (shiftFilter != null && !SHIFTS.contains(shiftFilter)) {
            throw new M4ValidationException("shift", "班次只能是 DAY/NIGHT/ON_CALL");
        }
        String statusFilter = M4Values.optionalText(status, "status", 16);
        if (statusFilter != null && !STATUS_PLANNED.equals(statusFilter)
                && !STATUS_CANCELLED.equals(statusFilter)) {
            throw new M4ValidationException("status", "状态只能是 PLANNED 或 CANCELLED");
        }
        String staffFilter = M4Values.optionalText(staffId, "staffId", 36);
        if (staffFilter != null) {
            requireReadableStaff(staffFilter, caller);
        }
        String deptFilter = M4Values.optionalText(departmentId, "departmentId", 36);
        if (deptFilter != null) {
            deptFilter = accessGuard.requireDepartmentFilter(caller, deptFilter, "departmentId");
        }
        StaffAccessGuard.StaffVisibility visibility = accessGuard.visibility(caller);
        List<StaffScheduleEntity> rows = scheduleMapper.selectList(staffFilter, deptFilter, from, to,
                shiftFilter, statusFilter, visibility.allowedDeptIds(), visibility.selfUserId(),
                limit);
        StaffScheduleList result = new StaffScheduleList();
        List<StaffSchedule> items = new ArrayList<>();
        for (StaffScheduleEntity row : rows) {
            items.add(toDto(row));
        }
        result.setItems(items);
        return result;
    }

    // ------------------------------------------------------------------ 写入 --

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StaffSchedule create(CreateStaffScheduleRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        try {
            return doCreate(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("SCHEDULE_CREATE", "NEW", failure, caller, context);
            throw failure;
        }
    }

    private StaffSchedule doCreate(CreateStaffScheduleRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        accessGuard.requireWritable(caller);
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        StaffProfileEntity staff = requireWritableStaff(request.getStaffId(), caller);
        LocalDate scheduleDate = requireDate(request.getScheduleDate(), "scheduleDate");
        String shift = requireShift(
                request.getShift() == null ? null : request.getShift().getValue());
        String departmentId =
                accessGuard.requireDepartment(caller, request.getDepartmentId(), "departmentId");
        UUID roomId = M4Values.optionalUuid(request.getRoomId(), "roomId");
        String note = M4Values.optionalText(request.getNote(), "note", 200);
        requireNoConflict(staff.getId(), scheduleDate, shift, roomId, null);

        Instant now = now();
        StaffScheduleEntity entity = new StaffScheduleEntity();
        entity.setId(UUID.randomUUID());
        entity.setStaffId(staff.getId());
        entity.setScheduleDate(scheduleDate);
        entity.setShift(shift);
        entity.setRoomId(roomId);
        entity.setDepartmentId(UUID.fromString(departmentId));
        entity.setStatus(STATUS_PLANNED);
        entity.setNote(note);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(1);
        try {
            scheduleMapper.insertSchedule(entity);
        } catch (DuplicateKeyException duplicate) {
            throw new M4ConflictException("排班冲突：该人员或术间在该班次已有排班");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "SCHEDULE_CREATE");
        detail.put("scheduleId", entity.getId().toString());
        detail.put("staffId", staff.getId().toString());
        detail.put("scheduleDate", scheduleDate.toString());
        detail.put("shift", shift);
        detail.put("departmentId", departmentId);
        detail.put("roomId", roomId == null ? null : roomId.toString());
        detail.put("status", STATUS_PLANNED);
        auditWriter.record("SCHEDULE_CREATE", RESOURCE_TYPE, entity.getId().toString(), true,
                detail, actorId(caller), username(caller), context);
        return toDto(scheduleMapper.selectById(entity.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StaffSchedule update(String scheduleId, UpdateStaffScheduleRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        String resourceId = safeResourceId(scheduleId);
        try {
            return doUpdate(scheduleId, request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("SCHEDULE_UPDATE", resourceId, failure, caller, context);
            throw failure;
        }
    }

    private StaffSchedule doUpdate(String scheduleId, UpdateStaffScheduleRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        StaffScheduleEntity existing = requireReadableSchedule(scheduleId, caller);
        accessGuard.requireWritable(caller);
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        if (!STATUS_PLANNED.equals(existing.getStatus())) {
            throw new M4ConflictException("排班已取消，不能修改；请新建排班");
        }
        if (request.getVersion() == null || !request.getVersion().equals(existing.getVersion())) {
            throw new M4ConflictException("排班已被他人修改，请刷新后重试");
        }
        StaffProfileEntity staff = request.getStaffId() == null
                ? requireReadableStaff(existing.getStaffId().toString(), caller)
                : requireWritableStaff(request.getStaffId(), caller);
        LocalDate scheduleDate = requireDate(request.getScheduleDate(), "scheduleDate");
        String shift = requireShift(
                request.getShift() == null ? null : request.getShift().getValue());
        String departmentId =
                accessGuard.requireDepartment(caller, request.getDepartmentId(), "departmentId");
        UUID roomId = M4Values.optionalUuid(request.getRoomId(), "roomId");
        String note = M4Values.optionalText(request.getNote(), "note", 200);
        requireNoConflict(staff.getId(), scheduleDate, shift, roomId, existing.getId().toString());

        StaffScheduleEntity updated = new StaffScheduleEntity();
        updated.setId(existing.getId());
        updated.setStaffId(staff.getId());
        updated.setScheduleDate(scheduleDate);
        updated.setShift(shift);
        updated.setRoomId(roomId);
        updated.setDepartmentId(UUID.fromString(departmentId));
        updated.setNote(note);
        updated.setUpdatedAt(now());
        updated.setUpdatedBy(actorId(caller));
        updated.setVersion(existing.getVersion());
        int affected = scheduleMapper.updateSchedule(updated);
        if (affected == 0) {
            throw new M4ConflictException("排班已被他人修改，请刷新后重试");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "SCHEDULE_UPDATE");
        detail.put("scheduleId", existing.getId().toString());
        detail.put("staffId", staff.getId().toString());
        detail.put("scheduleDate", scheduleDate.toString());
        detail.put("shift", shift);
        detail.put("departmentId", departmentId);
        detail.put("roomId", roomId == null ? null : roomId.toString());
        detail.put("version", existing.getVersion());
        auditWriter.record("SCHEDULE_UPDATE", RESOURCE_TYPE, existing.getId().toString(), true,
                detail, actorId(caller), username(caller), context);
        return toDto(scheduleMapper.selectById(existing.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StaffSchedule cancel(String scheduleId, CancelStaffScheduleRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        String resourceId = safeResourceId(scheduleId);
        try {
            return doCancel(scheduleId, request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("SCHEDULE_CANCEL", resourceId, failure, caller, context);
            throw failure;
        }
    }

    private StaffSchedule doCancel(String scheduleId, CancelStaffScheduleRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        StaffScheduleEntity existing = requireReadableSchedule(scheduleId, caller);
        accessGuard.requireWritable(caller);
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        if (!STATUS_PLANNED.equals(existing.getStatus())) {
            throw new M4ConflictException("排班已取消，请勿重复取消");
        }
        if (request.getVersion() == null || !request.getVersion().equals(existing.getVersion())) {
            throw new M4ConflictException("排班已被他人修改，请刷新后重试");
        }
        String reason = M4Values.optionalText(request.getReason(), "reason", 200);
        Instant now = now();
        StaffScheduleEntity cancelled = new StaffScheduleEntity();
        cancelled.setId(existing.getId());
        cancelled.setCancelledAt(now);
        cancelled.setCancelledBy(actorId(caller));
        cancelled.setCancelReason(reason);
        cancelled.setUpdatedAt(now);
        cancelled.setUpdatedBy(actorId(caller));
        cancelled.setVersion(existing.getVersion());
        int affected = scheduleMapper.cancelSchedule(cancelled);
        if (affected == 0) {
            throw new M4ConflictException("排班已被他人修改或已取消，请刷新后重试");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "SCHEDULE_CANCEL");
        detail.put("scheduleId", existing.getId().toString());
        detail.put("staffId", existing.getStaffId().toString());
        detail.put("scheduleDate", existing.getScheduleDate().toString());
        detail.put("shift", existing.getShift());
        detail.put("departmentId", existing.getDepartmentId().toString());
        detail.put("status", STATUS_CANCELLED);
        detail.put("version", existing.getVersion());
        auditWriter.record("SCHEDULE_CANCEL", RESOURCE_TYPE, existing.getId().toString(), true,
                detail, actorId(caller), username(caller), context);
        return toDto(scheduleMapper.selectById(existing.getId()));
    }

    // ------------------------------------------------------------------ 冲突 --

    /**
     * 冲突检测：同人员同日同班次、同术间同日同班次（PLANNED；排除自身）。
     * 命中任意一项即 409，并一次性返回全部冲突明细（多冲突一次纠正）。
     */
    private void requireNoConflict(UUID staffId, LocalDate scheduleDate, String shift, UUID roomId,
            String excludeId) {
        List<M4ConflictException.Detail> details = new ArrayList<>();
        StaffScheduleEntity staffConflict = scheduleMapper.selectStaffConflict(staffId.toString(),
                scheduleDate, shift, excludeId);
        if (staffConflict != null) {
            details.add(new M4ConflictException.Detail("staffId",
                    "该人员 " + scheduleDate + " " + label(shift) + "已有排班（排班标识 "
                            + staffConflict.getId() + "）"));
        }
        if (roomId != null) {
            StaffScheduleEntity roomConflict = scheduleMapper.selectRoomConflict(
                    roomId.toString(), scheduleDate, shift, excludeId);
            if (roomConflict != null) {
                details.add(new M4ConflictException.Detail("roomId",
                        "该术间 " + scheduleDate + " " + label(shift) + "已有排班（排班标识 "
                                + roomConflict.getId() + "）"));
            }
        }
        if (!details.isEmpty()) {
            throw new M4ConflictException("排班冲突，操作未生效", details);
        }
    }

    private static String label(String shift) {
        return SHIFT_LABELS.getOrDefault(shift, shift);
    }

    // ------------------------------------------------------------------ 辅助 --

    private StaffScheduleEntity requireSchedule(String scheduleId) {
        UUID id = M4Values.parseUuid(scheduleId);
        StaffScheduleEntity schedule = id == null ? null : scheduleMapper.selectById(id);
        if (schedule == null) {
            throw new NoSuchElementException("排班不存在或不可见");
        }
        return schedule;
    }

    private StaffScheduleEntity requireReadableSchedule(String scheduleId, AuthPrincipal caller) {
        StaffScheduleEntity schedule = requireSchedule(scheduleId);
        requireReadableStaff(schedule.getStaffId().toString(), caller);
        return schedule;
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
        if (!"ACTIVE".equals(staff.getEmploymentStatus())) {
            throw new M4UnprocessableException("staffId", "人员已离职/停用，不能排班");
        }
        return staff;
    }

    private static LocalDate requireDate(LocalDate value, String field) {
        if (value == null) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        return value;
    }

    private static String requireShift(String shift) {
        if (shift == null || !SHIFTS.contains(shift)) {
            throw new M4ValidationException("shift", "班次只能是 DAY/NIGHT/ON_CALL");
        }
        return shift;
    }

    private StaffSchedule toDto(StaffScheduleEntity entity) {
        StaffSchedule dto = new StaffSchedule();
        dto.setScheduleId(entity.getId().toString());
        dto.setStaffId(entity.getStaffId().toString());
        dto.setStaffNo(entity.getStaffNo());
        dto.setScheduleDate(entity.getScheduleDate());
        dto.setShift(StaffSchedule.ShiftEnum.fromValue(entity.getShift()));
        dto.setRoomId(entity.getRoomId() == null ? null : entity.getRoomId().toString());
        dto.setDepartmentId(entity.getDepartmentId().toString());
        dto.setStatus(StaffSchedule.StatusEnum.fromValue(entity.getStatus()));
        dto.setNote(entity.getNote());
        dto.setCancelledAt(entity.getCancelledAt() == null
                ? null : entity.getCancelledAt().atOffset(DISPLAY_OFFSET));
        dto.setCancelledBy(entity.getCancelledBy() == null
                ? null : entity.getCancelledBy().toString());
        dto.setCancelReason(entity.getCancelReason());
        dto.setCreatedAt(entity.getCreatedAt().atOffset(DISPLAY_OFFSET));
        dto.setCreatedBy(entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString());
        dto.setUpdatedAt(entity.getUpdatedAt().atOffset(DISPLAY_OFFSET));
        dto.setUpdatedBy(entity.getUpdatedBy() == null ? null : entity.getUpdatedBy().toString());
        dto.setVersion(entity.getVersion());
        return dto;
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

    private static String safeResourceId(String value) {
        return M4Values.parseUuid(value) == null ? "INVALID" : value.trim();
    }
}
