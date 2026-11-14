package com.anesop.m4.impl.staff;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.staff.model.CreateStaffRequest;
import com.anesop.contracts.staff.model.DepartmentList;
import com.anesop.contracts.staff.model.DepartmentOption;
import com.anesop.contracts.staff.model.Staff;
import com.anesop.contracts.staff.model.StaffList;
import com.anesop.contracts.staff.model.StaffQualification;
import com.anesop.contracts.staff.model.StaffQualificationInput;
import com.anesop.contracts.auth.model.UserStatus;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.contracts.staff.model.UpdateStaffRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m0.api.security.UserAdminService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.staff.StaffProfileService;
import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
import com.anesop.m4.impl.persistence.entity.StaffQualificationEntity;
import com.anesop.m4.impl.persistence.mapper.StaffProfileMapper;
import com.anesop.m4.impl.persistence.mapper.StaffQualificationMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.anesop.m4.impl.support.M4Values;
import com.anesop.m4.impl.support.StaffAccessGuard;
import com.anesop.m4.impl.support.StaffJson;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人员档案服务实现（契约 staff.yaml 0.1.1，M4-M13-01）。
 *
 * <p>工号/ userId 归一化唯一（409），科室校验 422/403，数据范围 SELF 只读（403），
 * 更新为乐观锁（409）；审计 STAFF_CREATE/STAFF_UPDATE 只含标识、工号、科室、状态与资质
 * 数量，**不含姓名与证书号原文**；失败路径以独立事务写 result=0 审计。
 */
@Service
public class StaffProfileServiceImpl implements StaffProfileService {

    private static final String RESOURCE_TYPE = "STAFF";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final Pattern STAFF_NO_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");
    private static final int MAX_SPECIALTIES = 20;
    private static final int MAX_QUALIFICATIONS = 20;
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final List<String> QUALIFICATION_TYPES =
            List.of("PRACTICE_LICENSE", "SPECIALTY_CERT", "NURSE_LICENSE", "OTHER");
    private static final List<String> QUALIFICATION_STATUSES = List.of("ACTIVE", "REVOKED");

    private final StaffProfileMapper profileMapper;
    private final StaffQualificationMapper qualificationMapper;
    private final StaffAccessGuard accessGuard;
    private final OrganizationQueryService organizationQueryService;
    private final UserAdminService userAdminService;
    private final AuditWriter auditWriter;
    private final M4FailureAuditor failureAuditor;
    private final StaffJson staffJson;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public StaffProfileServiceImpl(StaffProfileMapper profileMapper,
            StaffQualificationMapper qualificationMapper, StaffAccessGuard accessGuard,
            OrganizationQueryService organizationQueryService, UserAdminService userAdminService,
            AuditWriter auditWriter, M4FailureAuditor failureAuditor, StaffJson staffJson,
            Clock clock) {
        this.profileMapper = profileMapper;
        this.qualificationMapper = qualificationMapper;
        this.accessGuard = accessGuard;
        this.organizationQueryService = organizationQueryService;
        this.userAdminService = userAdminService;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.staffJson = staffJson;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 查询 --

    @Override
    public StaffList list(String keyword, String departmentId, String employmentStatus, int page,
            int size, AuthPrincipal caller) {
        if (page < 1) {
            throw new M4ValidationException("page", "页码必须大于等于 1");
        }
        if (size < 1 || size > 200) {
            throw new M4ValidationException("size", "每页条数必须在 1–200 之间");
        }
        String status = M4Values.optionalText(employmentStatus, "employmentStatus", 16);
        if (status != null && !STATUS_ACTIVE.equals(status) && !STATUS_INACTIVE.equals(status)) {
            throw new M4ValidationException("employmentStatus",
                    "在职状态只能是 ACTIVE 或 INACTIVE");
        }
        String normalizedKeyword = M4Values.optionalText(keyword, "keyword", 64);
        String deptFilter = M4Values.optionalText(departmentId, "departmentId", 36);
        if (deptFilter != null) {
            deptFilter = accessGuard.requireDepartmentFilter(caller, deptFilter, "departmentId");
        }
        StaffAccessGuard.StaffVisibility visibility = accessGuard.visibility(caller);
        int offset = (page - 1) * size;
        List<StaffProfileEntity> rows = profileMapper.selectPage(normalizedKeyword, status,
                deptFilter, visibility.allowedDeptIds(), visibility.selfUserId(), size, offset);
        long total = profileMapper.countPage(normalizedKeyword, status, deptFilter,
                visibility.allowedDeptIds(), visibility.selfUserId());
        StaffList result = new StaffList();
        List<Staff> items = new ArrayList<>();
        Map<UUID, String> departmentNames = new LinkedHashMap<>();
        for (StaffProfileEntity row : rows) {
            items.add(toDto(row, departmentNames));
        }
        result.setItems(items);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(Math.toIntExact(total));
        result.setTotalPages(total == 0 ? 0 : (int) ((total + size - 1) / size));
        return result;
    }

    @Override
    public Staff get(String staffId, AuthPrincipal caller) {
        return toDto(requireReadableStaff(staffId, caller), new LinkedHashMap<>());
    }

    @Override
    public DepartmentList listDepartments(AuthPrincipal caller) {
        // S-4：ROOM/无范围必须 fail-closed 403；不能只看“有数据范围”就返回空列表。
        accessGuard.visibility(caller);
        List<OrgDepartment> departments =
                organizationQueryService.visibleDepartments(caller == null ? null : caller.userId());
        DepartmentList result = new DepartmentList();
        List<DepartmentOption> items = new ArrayList<>();
        for (OrgDepartment department : departments) {
            items.add(new DepartmentOption(department.orgId(), department.code(),
                    department.name()));
        }
        result.setItems(items);
        return result;
    }

    // ------------------------------------------------------------------ 写入 --

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Staff create(CreateStaffRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        try {
            return doCreate(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("STAFF_CREATE", "NEW", failure, caller, context);
            throw failure;
        }
    }

    private Staff doCreate(CreateStaffRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        accessGuard.requireWritable(caller);
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        String staffNo = M4Values.requireText(request.getStaffNo(), "staffNo", 32);
        if (!STAFF_NO_PATTERN.matcher(staffNo).matches()) {
            throw new M4ValidationException("staffNo",
                    "工号只能包含字母、数字、点、下划线与连字符（1–32）");
        }
        String name = M4Values.requireText(request.getName(), "name", 64);
        String departmentId =
                accessGuard.requireDepartment(caller, request.getDepartmentId(), "departmentId");
        String title = M4Values.requireText(request.getTitle(), "title", 32);
        String jobGrade = M4Values.optionalText(request.getJobGrade(), "jobGrade", 32);
        UUID siteId = requireSite(caller, request.getSiteId());
        String employmentStatus = request.getEmploymentStatus() == null ? STATUS_ACTIVE
                : request.getEmploymentStatus().getValue();
        requireEmploymentStatus(employmentStatus);
        List<String> specialties = normalizeSpecialties(request.getSpecialties());
        List<StaffQualificationInput> qualifications =
                normalizeQualifications(request.getQualifications());

        UUID userId = M4Values.optionalUuid(request.getUserId(), "userId");
        requireActiveUser(userId, departmentId);
        if (profileMapper.selectByStaffNo(staffNo) != null) {
            throw new M4ConflictException("工号已存在");
        }
        if (userId != null && profileMapper.selectByUserId(userId) != null) {
            throw new M4ConflictException("该用户已关联人员档案");
        }

        Instant now = now();
        StaffProfileEntity entity = new StaffProfileEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setStaffNo(staffNo);
        entity.setName(name);
        entity.setDepartmentId(UUID.fromString(departmentId));
        entity.setSiteId(siteId);
        entity.setTitle(title);
        entity.setJobGrade(jobGrade);
        entity.setEmploymentStatus(employmentStatus);
        entity.setSpecialties(staffJson.write(specialties));
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(1);
        try {
            profileMapper.insertProfile(entity);
            insertQualifications(entity.getId(), qualifications, now, actorId(caller));
        } catch (DuplicateKeyException duplicate) {
            throw new M4ConflictException("工号或关联用户已存在");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "STAFF_CREATE");
        detail.put("staffId", entity.getId().toString());
        detail.put("staffNo", staffNo);
        detail.put("departmentId", departmentId);
        detail.put("employmentStatus", employmentStatus);
        detail.put("qualificationCount", qualifications.size());
        auditWriter.record("STAFF_CREATE", RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), username(caller), context);
        return toDto(entity, new LinkedHashMap<>());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Staff update(String staffId, UpdateStaffRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        String resourceId = safeResourceId(staffId);
        try {
            return doUpdate(staffId, request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("STAFF_UPDATE", resourceId, failure, caller, context);
            throw failure;
        }
    }

    private Staff doUpdate(String staffId, UpdateStaffRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        StaffProfileEntity existing = requireReadableStaff(staffId, caller);
        accessGuard.requireWritable(caller);
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        Integer expectedVersion = request.getVersion();
        if (expectedVersion == null || expectedVersion < 1) {
            throw new M4ValidationException("version", "乐观锁版本号不能为空且必须大于等于 1");
        }
        if (!existing.getVersion().equals(expectedVersion)) {
            throw new M4ConflictException("人员档案已被修改，请刷新后重试");
        }
        String name = M4Values.requireText(request.getName(), "name", 64);
        String departmentId =
                accessGuard.requireDepartment(caller, request.getDepartmentId(), "departmentId");
        String title = M4Values.requireText(request.getTitle(), "title", 32);
        String jobGrade = M4Values.optionalText(request.getJobGrade(), "jobGrade", 32);
        UUID siteId = requireSite(caller, request.getSiteId());
        String employmentStatus = request.getEmploymentStatus() == null
                ? existing.getEmploymentStatus() : request.getEmploymentStatus().getValue();
        requireEmploymentStatus(employmentStatus);
        List<String> specialties = normalizeSpecialties(request.getSpecialties());
        List<StaffQualificationInput> qualifications =
                normalizeQualifications(request.getQualifications());

        boolean departmentChanged =
                !existing.getDepartmentId().toString().equals(departmentId);
        boolean statusChanged = !existing.getEmploymentStatus().equals(employmentStatus);
        Instant now = now();
        StaffProfileEntity updated = new StaffProfileEntity();
        updated.setId(existing.getId());
        updated.setName(name);
        updated.setDepartmentId(UUID.fromString(departmentId));
        updated.setSiteId(siteId);
        updated.setTitle(title);
        updated.setJobGrade(jobGrade);
        updated.setEmploymentStatus(employmentStatus);
        updated.setSpecialties(staffJson.write(specialties));
        updated.setUpdatedAt(now);
        updated.setUpdatedBy(actorId(caller));
        updated.setVersion(expectedVersion);
        int affected = profileMapper.updateProfile(updated);
        if (affected == 0) {
            throw new M4ConflictException("人员档案已被修改，请刷新后重试");
        }
        qualificationMapper.deleteByStaff(existing.getId());
        insertQualifications(existing.getId(), qualifications, now, actorId(caller));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "STAFF_UPDATE");
        detail.put("staffId", existing.getId().toString());
        detail.put("staffNo", existing.getStaffNo());
        detail.put("departmentId", departmentId);
        detail.put("departmentChanged", departmentChanged);
        detail.put("employmentStatus", employmentStatus);
        detail.put("employmentStatusChanged", statusChanged);
        detail.put("qualificationCount", qualifications.size());
        detail.put("version", expectedVersion);
        auditWriter.record("STAFF_UPDATE", RESOURCE_TYPE, existing.getId().toString(), true, detail,
                actorId(caller), username(caller), context);
        return toDto(requireStaff(existing.getId().toString()), new LinkedHashMap<>());
    }

    // ------------------------------------------------------------------ 转换 --

    private Staff toDto(StaffProfileEntity entity, Map<UUID, String> departmentNameCache) {
        Staff dto = new Staff();
        dto.setStaffId(entity.getId().toString());
        dto.setUserId(entity.getUserId() == null ? null : entity.getUserId().toString());
        dto.setStaffNo(entity.getStaffNo());
        dto.setName(entity.getName());
        dto.setDepartmentId(entity.getDepartmentId().toString());
        dto.setDepartmentName(departmentNameCache.computeIfAbsent(entity.getDepartmentId(),
                deptId -> organizationQueryService.findActiveDepartment(deptId)
                        .map(OrgDepartment::name).orElse(null)));
        dto.setSiteId(entity.getSiteId() == null ? null : entity.getSiteId().toString());
        dto.setTitle(entity.getTitle());
        dto.setJobGrade(entity.getJobGrade());
        dto.setEmploymentStatus(
                Staff.EmploymentStatusEnum.fromValue(entity.getEmploymentStatus()));
        dto.setSpecialties(staffJson.read(entity.getSpecialties()));
        dto.setQualifications(toQualificationDtos(entity.getId()));
        dto.setCreatedAt(entity.getCreatedAt().atOffset(DISPLAY_OFFSET));
        dto.setCreatedBy(entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString());
        dto.setUpdatedAt(entity.getUpdatedAt().atOffset(DISPLAY_OFFSET));
        dto.setUpdatedBy(entity.getUpdatedBy() == null ? null : entity.getUpdatedBy().toString());
        dto.setVersion(entity.getVersion());
        return dto;
    }

    private List<StaffQualification> toQualificationDtos(UUID staffId) {
        LocalDate today = LocalDate.now(clock.withZone(DISPLAY_OFFSET));
        List<StaffQualification> items = new ArrayList<>();
        for (StaffQualificationEntity row : qualificationMapper.selectByStaff(staffId)) {
            StaffQualification dto = new StaffQualification();
            dto.setQualificationId(row.getId().toString());
            dto.setQualificationType(
                    StaffQualification.QualificationTypeEnum.fromValue(row.getQualificationType()));
            dto.setCertificateNo(row.getCertificateNo());
            dto.setIssuingAuthority(row.getIssuingAuthority());
            dto.setValidFrom(row.getValidFrom());
            dto.setValidTo(row.getValidTo());
            dto.setStatus(StaffQualification.StatusEnum.fromValue(row.getStatus()));
            dto.setExpired(row.getValidTo() != null && row.getValidTo().isBefore(today));
            items.add(dto);
        }
        return items;
    }

    // ------------------------------------------------------------------ 校验 --

    private StaffProfileEntity requireStaff(String staffId) {
        UUID id = M4Values.parseUuid(staffId);
        StaffProfileEntity entity = id == null ? null : profileMapper.selectById(id);
        if (entity == null) {
            throw new NoSuchElementException("人员不存在或不可见");
        }
        return entity;
    }

    private StaffProfileEntity requireReadableStaff(String staffId, AuthPrincipal caller) {
        StaffProfileEntity entity = requireStaff(staffId);
        accessGuard.requireReadable(entity, caller);
        return entity;
    }

    /**
     * S-1：关联用户必须存在、启用；用户已有组织绑定时，档案科室必须在其组织归属内。
     * 未绑定组织的账号（如尚未接 AD/人事）允许建档，避免因主数据缺失阻断业务。
     */
    private void requireActiveUser(UUID userId, String departmentId) {
        if (userId == null) {
            return;
        }
        UserSummary summary;
        try {
            summary = userAdminService.get(userId);
        } catch (NoSuchElementException notFound) {
            throw new M4ValidationException("userId", "关联用户不存在或已停用");
        }
        if (summary == null || summary.getStatus() != UserStatus.ACTIVE) {
            throw new M4ValidationException("userId", "关联用户不存在或已停用");
        }
        List<OrgDepartment> departments = organizationQueryService.departmentsOfUser(userId);
        if (!departments.isEmpty() && departments.stream()
                .noneMatch(department -> department.orgId().equals(departmentId))) {
            throw new M4UnprocessableException("userId",
                    "关联用户的组织归属与档案科室不一致");
        }
    }

    /**
     * S-1：siteId 必须是合法 UUID；SITE 范围用户只能使用授予院区，DEPT/SELF 范围不允许
     * 越权指定院区（403）。院区存在性/启用状态与“科室同院区”校验需 m0 组织查询扩展，
     * 当前由 m0 组织主数据与 AD/人事同步保证（Q-7）。
     */
    private UUID requireSite(AuthPrincipal caller, String siteId) {
        UUID site = M4Values.optionalUuid(siteId, "siteId");
        if (site == null) {
            return null;
        }
        DataScopeAssignments scope = accessGuard.scope(caller);
        if (scope.isUnrestricted()) {
            return site;
        }
        boolean siteScoped = false;
        for (DataScopeAssignment assignment : scope.assignments()) {
            if (assignment.type() == DataScope.SITE
                    && assignment.scopeIds().contains(site.toString())) {
                siteScoped = true;
            }
        }
        if (!siteScoped) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "院区不在当前用户数据范围内");
        }
        return site;
    }

    private static void requireEmploymentStatus(String status) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_INACTIVE.equals(status)) {
            throw new M4ValidationException("employmentStatus", "在职状态只能是 ACTIVE 或 INACTIVE");
        }
    }

    private List<String> normalizeSpecialties(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_SPECIALTIES) {
            throw new M4ValidationException("specialties",
                    "专长数量不能超过 " + MAX_SPECIALTIES + " 个");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String item : raw) {
            String value = M4Values.requireText(item, "specialties", 32);
            unique.add(value);
        }
        return List.copyOf(unique);
    }

    private List<StaffQualificationInput> normalizeQualifications(
            List<StaffQualificationInput> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_QUALIFICATIONS) {
            throw new M4ValidationException("qualifications",
                    "资质数量不能超过 " + MAX_QUALIFICATIONS + " 个");
        }
        LinkedHashSet<String> certificateNos = new LinkedHashSet<>();
        for (StaffQualificationInput item : raw) {
            if (item == null) {
                throw new M4ValidationException("qualifications", "资质项不能为空");
            }
            String type = item.getQualificationType() == null ? null
                    : item.getQualificationType().getValue();
            if (type == null || !QUALIFICATION_TYPES.contains(type)) {
                throw new M4ValidationException("qualifications", "资质类型非法");
            }
            String certificateNo = M4Values.requireText(item.getCertificateNo(), "certificateNo", 64);
            String authority =
                    M4Values.requireText(item.getIssuingAuthority(), "issuingAuthority", 200);
            if (item.getValidFrom() == null) {
                throw new M4ValidationException("validFrom", "资质有效期起不能为空");
            }
            if (item.getValidTo() != null && item.getValidTo().isBefore(item.getValidFrom())) {
                throw new M4ValidationException("validTo", "资质有效期止不得早于有效期起");
            }
            String status = item.getStatus() == null ? STATUS_ACTIVE : item.getStatus().getValue();
            if (!QUALIFICATION_STATUSES.contains(status)) {
                throw new M4ValidationException("qualifications", "资质状态非法");
            }
            String key = certificateNo.toLowerCase(Locale.ROOT);
            if (!certificateNos.add(key)) {
                throw new M4ValidationException("certificateNo",
                        "同一人员的证书编号不能重复（忽略大小写与首尾空白）");
            }
        }
        return List.copyOf(raw);
    }

    private void insertQualifications(UUID staffId, List<StaffQualificationInput> qualifications,
            Instant now, UUID actor) {
        for (StaffQualificationInput item : qualifications) {
            StaffQualificationEntity entity = new StaffQualificationEntity();
            entity.setId(UUID.randomUUID());
            entity.setStaffId(staffId);
            entity.setQualificationType(item.getQualificationType().getValue());
            entity.setCertificateNo(item.getCertificateNo().trim());
            entity.setIssuingAuthority(item.getIssuingAuthority().trim());
            entity.setValidFrom(item.getValidFrom());
            entity.setValidTo(item.getValidTo());
            entity.setStatus(item.getStatus() == null ? STATUS_ACTIVE : item.getStatus().getValue());
            entity.setCreatedAt(now);
            entity.setCreatedBy(actor);
            qualificationMapper.insertQualification(entity);
        }
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
