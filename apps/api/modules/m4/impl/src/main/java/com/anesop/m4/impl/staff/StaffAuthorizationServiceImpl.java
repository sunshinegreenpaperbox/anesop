package com.anesop.m4.impl.staff;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.UserStatus;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.contracts.staff.model.AuthorizationCheckReason;
import com.anesop.contracts.staff.model.AuthorizationCheckRequest;
import com.anesop.contracts.staff.model.AuthorizationCheckResult;
import com.anesop.contracts.staff.model.StaffAuthorization;
import com.anesop.contracts.staff.model.StaffAuthorizationHistory;
import com.anesop.contracts.staff.model.UpdateStaffAuthorizationRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.AuthService;
import com.anesop.m0.api.security.UserAdminService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.security.M4Permissions;
import com.anesop.m4.api.staff.AuthorizationDecision;
import com.anesop.m4.api.staff.AuthorizationDimension;
import com.anesop.m4.api.staff.StaffAuthorizationService;
import com.anesop.m4.impl.config.StaffAuthorizationProperties;
import com.anesop.m4.impl.persistence.entity.StaffAuthorizationEntity;
import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
import com.anesop.m4.impl.persistence.mapper.StaffAuthorizationMapper;
import com.anesop.m4.impl.persistence.mapper.StaffProfileMapper;
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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分级授权服务实现（契约 staff.yaml 0.1.1，M4-M13-01 / 评审 B-1/B-3/B-4/S-2）。
 *
 * <p>append-only 版本链：每次 GRANT/REVOKE 写入新 version，旧版本保留；当前版本统一读
 * 视图 {@code staff_authorization_current}（撤销后当前版本 status=REVOKED）。
 *
 * <p>授权治理（B-1）：操作者不得自授；审批人必须存在、启用、具备
 * {@code m4:staff:authorize} 且不是操作者/被授权人；R1/R2、范围 {@code *} 或 ASA≥4 强制
 * 两名不同审批人并记录双方。撤销时记录 {@code revokedAt}（非追溯，Q-21）。
 * 校验返回**全部**未通过原因（多原因一次纠正）；术式/年龄/ASA 缺失且未声明不适用时
 * 返回 INPUT_INCOMPLETE（B-3，fail-closed）；回溯按当时生效版本判定（B-4）。
 * 审计 AUTH_GRANT/AUTH_REVOKE 只含标识、级别、版本、范围摘要与审批人数，不含姓名与证书号。
 */
@Service
public class StaffAuthorizationServiceImpl implements StaffAuthorizationService {

    private static final String RESOURCE_TYPE = "STAFF_AUTHORIZATION";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String STATUS_STAFF_ACTIVE = "ACTIVE";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final List<String> LEVELS = List.of("R1", "R2", "R3", "R4", "R5");
    private static final List<String> ASA_CLASSES =
            List.of("ASA1", "ASA2", "ASA3", "ASA4", "ASA5");
    private static final Pattern SCOPE_PATTERN =
            Pattern.compile("^(\\*|[A-Za-z0-9._-]{1,64})$");
    private static final int MAX_SCOPES = 100;

    private final StaffProfileMapper profileMapper;
    private final StaffAuthorizationMapper authorizationMapper;
    private final StaffAccessGuard accessGuard;
    private final StaffJson staffJson;
    private final StaffAuthorizationProperties properties;
    private final AuditWriter auditWriter;
    private final M4FailureAuditor failureAuditor;
    private final UserAdminService userAdminService;
    private final AuthService authService;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public StaffAuthorizationServiceImpl(StaffProfileMapper profileMapper,
            StaffAuthorizationMapper authorizationMapper, StaffAccessGuard accessGuard,
            StaffJson staffJson, StaffAuthorizationProperties properties, AuditWriter auditWriter,
            M4FailureAuditor failureAuditor, UserAdminService userAdminService,
            AuthService authService, Clock clock) {
        this.profileMapper = profileMapper;
        this.authorizationMapper = authorizationMapper;
        this.accessGuard = accessGuard;
        this.staffJson = staffJson;
        this.properties = properties;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.userAdminService = userAdminService;
        this.authService = authService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 查询 --

    @Override
    public StaffAuthorizationHistory getHistory(String staffId, AuthPrincipal caller) {
        StaffProfileEntity staff = requireReadableStaff(staffId, caller);
        return buildHistory(staff);
    }

    private StaffAuthorizationHistory buildHistory(StaffProfileEntity staff) {
        List<StaffAuthorizationEntity> rows = authorizationMapper.selectHistory(staff.getId());
        StaffAuthorizationHistory result = new StaffAuthorizationHistory();
        result.setStaffId(staff.getId().toString());
        List<StaffAuthorization> history = new ArrayList<>();
        for (StaffAuthorizationEntity row : rows) {
            history.add(toDto(row));
        }
        result.setHistory(history);
        result.setCurrent(history.isEmpty() ? null : history.get(0));
        return result;
    }

    // ------------------------------------------------------------------ 写入 --

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StaffAuthorizationHistory update(String staffId, UpdateStaffAuthorizationRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        String resourceId = M4Values.parseUuid(staffId) == null ? "INVALID" : staffId.trim();
        String auditAction = request != null && request.getAction() != null
                && "REVOKE".equals(request.getAction().getValue()) ? "AUTH_REVOKE" : "AUTH_GRANT";
        try {
            return doUpdate(staffId, request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure(auditAction, resourceId, failure, caller, context);
            throw failure;
        }
    }

    private StaffAuthorizationHistory doUpdate(String staffId,
            UpdateStaffAuthorizationRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        StaffProfileEntity staff = requireReadableStaff(staffId, caller);
        accessGuard.requireWritable(caller);
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        // B-1：操作者不得给自己授予/撤销授权（staff.userId 关联到本人）。
        if (caller == null || caller.userId() == null) {
            throw new AccessDeniedException("无法确认操作者身份，拒绝授权操作");
        }
        if (staff.getUserId() != null && caller.userId().equals(staff.getUserId())) {
            throw new AccessDeniedException("不得给本人授予或撤销分级授权");
        }
        StaffAuthorizationEntity current = authorizationMapper.selectCurrent(staff.getId());
        int currentVersion = current == null ? 0 : current.getVersion();
        if (request.getVersion() == null || request.getVersion() != currentVersion) {
            throw new M4ConflictException("分级授权已被他人变更，请刷新后重试");
        }
        if (request.getAction() == null) {
            throw new M4ValidationException("action", "授权动作不能为空（GRANT/REVOKE）");
        }
        UUID approvedBy = M4Values.optionalUuid(request.getApprovedBy(), "approvedBy");
        if (approvedBy == null) {
            throw new M4ValidationException("approvedBy", "审批人不能为空");
        }
        requireEligibleApprover(approvedBy, staff, caller, "approvedBy");
        String changeReason = M4Values.optionalText(request.getChangeReason(), "changeReason", 200);
        String action = request.getAction().getValue();
        boolean revoke = "REVOKE".equals(action);
        if (!STATUS_STAFF_ACTIVE.equals(staff.getEmploymentStatus()) && !revoke) {
            throw new M4UnprocessableException("staffId", "人员已离职/停用，不能授予授权");
        }

        String level;
        List<String> scopes;
        int ageMin;
        int ageMax;
        String asaMax;
        LocalDate validFrom;
        LocalDate validTo;
        if (revoke) {
            if (current == null) {
                throw new M4UnprocessableException("action", "该人员当前无授权，无法撤销");
            }
            if (!STATUS_ACTIVE.equals(current.getStatus())) {
                throw new M4ConflictException("该授权已撤销，请刷新后重试");
            }
            if (changeReason == null) {
                throw new M4ValidationException("changeReason", "撤销授权必须填写原因");
            }
            level = current.getLevel();
            scopes = staffJson.read(current.getSurgeryScopes());
            ageMin = current.getAgeMin();
            ageMax = current.getAgeMax();
            asaMax = current.getAsaMax();
            validFrom = current.getValidFrom();
            validTo = current.getValidTo();
        } else {
            level = request.getLevel() == null ? null : request.getLevel().getValue();
            if (level == null || !LEVELS.contains(level)) {
                throw new M4ValidationException("level", "授权级别只能是 R1–R5");
            }
            scopes = normalizeScopes(request.getSurgeryScopes());
            ageMin = requireAge(request.getAgeMin(), "ageMin");
            ageMax = requireAge(request.getAgeMax(), "ageMax");
            if (ageMax < ageMin) {
                throw new M4ValidationException("ageMax", "年龄上限不得小于年龄下限");
            }
            asaMax = request.getAsaMax() == null ? null : request.getAsaMax().getValue();
            if (asaMax == null || !ASA_CLASSES.contains(asaMax)) {
                throw new M4ValidationException("asaMax", "ASA 上限只能是 ASA1–ASA5");
            }
            validFrom = requireDate(request.getValidFrom(), "validFrom");
            validTo = requireDate(request.getValidTo(), "validTo");
            if (validTo.isBefore(validFrom)) {
                throw new M4ValidationException("validTo", "授权失效日期不得早于生效日期");
            }
            requireWithinLevelPolicy(level, scopes, ageMin, ageMax, asaMax);
        }

        // B-1：R1/R2、范围 * 或 ASA≥4 必须双人审批（两名不同、合规的审批人）。
        UUID secondApproverId = null;
        String secondApproverReason = null;
        if (requiresDualApproval(level, scopes, asaMax)) {
            secondApproverId = M4Values.optionalUuid(request.getSecondApproverId(),
                    "secondApproverId");
            if (secondApproverId == null) {
                throw new M4ValidationException("secondApproverId",
                        "该授权级别/范围需双人审批，必须提供第二审批人");
            }
            if (secondApproverId.equals(approvedBy)) {
                throw new M4ValidationException("secondApproverId",
                        "第二审批人必须与第一审批人不同");
            }
            requireEligibleApprover(secondApproverId, staff, caller, "secondApproverId");
            secondApproverReason = M4Values.optionalText(request.getSecondApproverReason(),
                    "secondApproverReason", 200);
        }

        Instant now = now();
        StaffAuthorizationEntity entity = new StaffAuthorizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setStaffId(staff.getId());
        entity.setVersion(currentVersion + 1);
        entity.setLevel(level);
        entity.setSurgeryScopes(staffJson.write(scopes));
        entity.setAgeMin(ageMin);
        entity.setAgeMax(ageMax);
        entity.setAsaMax(asaMax);
        entity.setValidFrom(validFrom);
        entity.setValidTo(validTo);
        entity.setStatus(revoke ? STATUS_REVOKED : STATUS_ACTIVE);
        entity.setApprovedBy(approvedBy);
        entity.setApprovedAt(now);
        entity.setSecondApproverId(secondApproverId);
        entity.setSecondApprovedAt(secondApproverId == null ? null : now);
        entity.setSecondApproverReason(secondApproverReason);
        entity.setRevokedAt(revoke ? now : null);
        entity.setRevokedBy(revoke ? caller.userId() : null);
        entity.setChangeReason(changeReason);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        try {
            authorizationMapper.insertAuthorization(entity);
        } catch (DuplicateKeyException duplicate) {
            throw new M4ConflictException("分级授权已被他人变更，请刷新后重试");
        }

        String auditAction = revoke ? "AUTH_REVOKE" : "AUTH_GRANT";
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", auditAction);
        detail.put("staffId", staff.getId().toString());
        detail.put("authorizationId", entity.getId().toString());
        detail.put("level", level);
        detail.put("version", entity.getVersion());
        detail.put("surgeryScopeCount", scopes.size());
        detail.put("ageMin", ageMin);
        detail.put("ageMax", ageMax);
        detail.put("asaMax", asaMax);
        detail.put("validFrom", validFrom.toString());
        detail.put("validTo", validTo.toString());
        detail.put("status", entity.getStatus());
        detail.put("approvalCount", secondApproverId == null ? 1 : 2);
        if (secondApproverId != null) {
            detail.put("secondApproverId", secondApproverId.toString());
        }
        if (revoke) {
            detail.put("revokedAt", now.toString());
        }
        auditWriter.record(auditAction, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), username(caller), context);
        return buildHistory(staff);
    }

    // ------------------------------------------------------------------ 校验 --

    @Override
    public AuthorizationCheckResult check(AuthorizationCheckRequest request,
            AuthPrincipal caller) {
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        String staffId = M4Values.optionalText(request.getStaffId(), "staffId", 36);
        if (staffId == null) {
            throw new M4ValidationException("staffId", "人员标识不能为空");
        }
        UUID id = M4Values.parseUuid(staffId);
        StaffProfileEntity staff = id == null ? null : profileMapper.selectById(id);
        if (staff == null) {
            throw new M4UnprocessableException("staffId", "人员不存在或不可见");
        }
        try {
            accessGuard.requireReadable(staff, caller);
        } catch (NoSuchElementException invisible) {
            throw new M4UnprocessableException("staffId", "人员不存在或不可见");
        }
        Integer age = request.getAge();
        if (age != null && (age < 0 || age > 120)) {
            throw new M4ValidationException("age", "年龄必须在 0–120 之间");
        }
        String asa = request.getAsa() == null ? null : request.getAsa().getValue();
        String surgeryTypeId = M4Values.optionalText(request.getSurgeryTypeId(),
                "surgeryTypeId", 64);
        Instant at = request.getAt() == null ? now() : request.getAt().toInstant();
        Set<AuthorizationDimension> notApplicable = toDimensions(request.getNotApplicable());
        AuthorizationDecision decision = decide(staff, surgeryTypeId, age, asa, at, notApplicable);

        AuthorizationCheckResult result = new AuthorizationCheckResult();
        result.setAllowed(decision.allowed());
        List<AuthorizationCheckReason> reasons = new ArrayList<>();
        for (String code : decision.reasonCodes()) {
            AuthorizationCheckReason reason = new AuthorizationCheckReason();
            reason.setCode(code);
            reason.setMessage(reasonMessage(code, request, decision));
            reasons.add(reason);
        }
        result.setReasons(reasons);
        if (decision.level() != null) {
            result.setLevel(AuthorizationCheckResult.LevelEnum.fromValue(decision.level()));
        }
        result.setAuthorizationId(decision.authorizationId());
        result.setValidUntil(decision.validUntil());
        result.setCheckedAt(now().atOffset(DISPLAY_OFFSET));
        return result;
    }

    @Override
    public AuthorizationDecision evaluate(UUID staffId, String surgeryTypeId, Integer age,
            String asa, Instant at) {
        return evaluate(staffId, surgeryTypeId, age, asa, at, Set.of());
    }

    @Override
    public AuthorizationDecision evaluate(UUID staffId, String surgeryTypeId, Integer age,
            String asa, Instant at, Set<AuthorizationDimension> notApplicable) {
        StaffProfileEntity staff = staffId == null ? null : profileMapper.selectById(staffId);
        if (staff == null) {
            throw new NoSuchElementException("人员不存在");
        }
        return decide(staff, surgeryTypeId, age, asa, at == null ? now() : at,
                notApplicable == null ? Set.of() : notApplicable);
    }

    private AuthorizationDecision decide(StaffProfileEntity staff, String surgeryTypeId,
            Integer age, String asa, Instant at, Set<AuthorizationDimension> notApplicable) {
        LocalDate today = at.atZone(DISPLAY_OFFSET).toLocalDate();
        List<String> reasons = new ArrayList<>();
        boolean surgeryMissing = isBlank(surgeryTypeId)
                && !notApplicable.contains(AuthorizationDimension.SURGERY_TYPE);
        boolean ageMissing = age == null && !notApplicable.contains(AuthorizationDimension.AGE);
        boolean asaMissing = asa == null && !notApplicable.contains(AuthorizationDimension.ASA);
        if (surgeryMissing || ageMissing || asaMissing) {
            // B-3：缺失维度不得静默跳过（fail-closed）。
            reasons.add("INPUT_INCOMPLETE");
        }
        if (!STATUS_STAFF_ACTIVE.equals(staff.getEmploymentStatus())) {
            reasons.add("STAFF_INACTIVE");
        }
        StaffAuthorizationEntity latest =
                authorizationMapper.selectLatestApprovedAt(staff.getId(), at);
        if (latest == null) {
            reasons.add("NO_AUTHORIZATION");
            return new AuthorizationDecision(false, reasons, null, null, null);
        }
        String level = latest.getLevel();
        LocalDate validUntil = latest.getValidTo();
        StaffAuthorizationEntity effective;
        boolean revoked = STATUS_REVOKED.equals(latest.getStatus())
                || (latest.getRevokedAt() != null && !latest.getRevokedAt().isAfter(at));
        if (revoked) {
            reasons.add("AUTHORIZATION_REVOKED");
            effective = latest;
        } else {
            effective = authorizationMapper.selectEffectiveAt(staff.getId(), at);
            if (effective == null) {
                if (today.isBefore(latest.getValidFrom())) {
                    reasons.add("NOT_YET_VALID");
                } else {
                    reasons.add("NO_AUTHORIZATION");
                }
                effective = latest;
            }
        }
        level = effective.getLevel();
        validUntil = effective.getValidTo();
        if (today.isBefore(effective.getValidFrom()) && !reasons.contains("NOT_YET_VALID")) {
            reasons.add("NOT_YET_VALID");
        }
        if (today.isAfter(effective.getValidTo())) {
            reasons.add("EXPIRED");
        }
        List<String> scopes = staffJson.read(effective.getSurgeryScopes());
        if (!surgeryMissing && !isBlank(surgeryTypeId) && !scopeAllows(scopes, surgeryTypeId)) {
            reasons.add("SURGERY_SCOPE_MISSING");
        }
        if (age != null && !ageMissing
                && (age < effective.getAgeMin() || age > effective.getAgeMax())) {
            reasons.add("AGE_OUT_OF_RANGE");
        }
        if (asa != null && !asaMissing && asaRank(asa) > asaRank(effective.getAsaMax())) {
            reasons.add("ASA_EXCEEDS_LIMIT");
        }
        StaffAuthorizationProperties.LevelPolicy policy = policyFor(level);
        if (policy != null && policyViolated(policy, scopes, effective.getAgeMin(),
                effective.getAgeMax(), effective.getAsaMax(), surgeryTypeId, age, asa)) {
            reasons.add("LEVEL_POLICY_EXCEEDED");
        }
        return new AuthorizationDecision(reasons.isEmpty(), reasons, level, validUntil,
                effective.getId().toString());
    }

    private String reasonMessage(String code, AuthorizationCheckRequest request,
            AuthorizationDecision decision) {
        return switch (code) {
            case "STAFF_INACTIVE" -> "人员已离职/停用，不得执业";
            case "NO_AUTHORIZATION" -> "人员无分级授权";
            case "AUTHORIZATION_REVOKED" -> "分级授权已撤销";
            case "NOT_YET_VALID" -> "分级授权尚未生效";
            case "EXPIRED" -> "分级授权已过期"
                    + (decision.validUntil() == null ? "" : "（有效期至 " + decision.validUntil() + "）");
            case "SURGERY_SCOPE_MISSING" -> "术式 " + request.getSurgeryTypeId() + " 不在授权范围内";
            case "AGE_OUT_OF_RANGE" -> "患者年龄 " + request.getAge() + " 超出授权年龄段";
            case "ASA_EXCEEDS_LIMIT" -> "ASA 分级超出授权上限";
            case "INPUT_INCOMPLETE" -> "授权校验缺少术式/年龄/ASA 维度且未声明不适用";
            case "LEVEL_POLICY_EXCEEDED" -> "超出授权级别的院方策略上限";
            default -> "授权校验未通过";
        };
    }

    // ------------------------------------------------------------------ 审批人 --

    /**
     * 校验审批人资格（B-1）：存在、启用、具备 m4:staff:authorize，且不是操作者或被授权人。
     *
     * <p>不存在/停用返回 400（参数不合法），无审批权限/自审返回 403（越权）。
     */
    private void requireEligibleApprover(UUID approverId, StaffProfileEntity staff,
            AuthPrincipal caller, String field) {
        if (approverId.equals(caller.userId())) {
            throw new AccessDeniedException("审批人不能是操作者本人");
        }
        if (staff.getUserId() != null && approverId.equals(staff.getUserId())) {
            throw new AccessDeniedException("审批人不能是被授权人员本人");
        }
        UserSummary summary;
        try {
            summary = userAdminService.get(approverId);
        } catch (NoSuchElementException notFound) {
            throw new M4ValidationException(field, "审批人不存在或已停用");
        }
        if (summary == null || summary.getStatus() != UserStatus.ACTIVE) {
            throw new M4ValidationException(field, "审批人不存在或已停用");
        }
        CurrentUser approver;
        try {
            approver = authService.currentUser(approverId);
        } catch (RuntimeException unavailable) {
            throw new M4ValidationException(field, "审批人不存在或已停用");
        }
        if (approver == null || approver.getPermissions() == null
                || !approver.getPermissions().contains(M4Permissions.STAFF_AUTHORIZE)) {
            throw new AccessDeniedException("审批人不具备 " + M4Permissions.STAFF_AUTHORIZE
                    + " 权限");
        }
    }

    private static boolean requiresDualApproval(String level, List<String> scopes, String asaMax) {
        if ("R1".equals(level) || "R2".equals(level)) {
            return true;
        }
        if (scopes != null && scopes.stream().anyMatch(scope -> "*".equals(scope))) {
            return true;
        }
        return asaRank(asaMax) >= asaRank("ASA4");
    }

    private static Set<AuthorizationDimension> toDimensions(
            Set<AuthorizationCheckRequest.NotApplicableEnum> values) {
        Set<AuthorizationDimension> dimensions = EnumSet.noneOf(AuthorizationDimension.class);
        if (values == null) {
            return dimensions;
        }
        for (AuthorizationCheckRequest.NotApplicableEnum value : values) {
            if (value == null) {
                continue;
            }
            switch (value) {
                case SURGERY_TYPE -> dimensions.add(AuthorizationDimension.SURGERY_TYPE);
                case AGE -> dimensions.add(AuthorizationDimension.AGE);
                case ASA -> dimensions.add(AuthorizationDimension.ASA);
                default -> { }
            }
        }
        return dimensions;
    }

    // ------------------------------------------------------------------ 辅助 --

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

    private static List<String> normalizeScopes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new M4ValidationException("surgeryScopes", "术式范围至少需要一项");
        }
        if (raw.size() > MAX_SCOPES) {
            throw new M4ValidationException("surgeryScopes", "术式范围不能超过 100 项");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String item : raw) {
            String scope = M4Values.requireText(item, "surgeryScopes", 64);
            if (!SCOPE_PATTERN.matcher(scope).matches()) {
                throw new M4ValidationException("surgeryScopes",
                        "术式范围只能是 * 或字母/数字/点/下划线/连字符（1–64）");
            }
            unique.add(scope);
        }
        return List.copyOf(unique);
    }

    private static boolean scopeAllows(List<String> scopes, String surgeryTypeId) {
        String target = surgeryTypeId.trim().toUpperCase(Locale.ROOT);
        for (String scope : scopes) {
            String candidate = scope.trim().toUpperCase(Locale.ROOT);
            if ("*".equals(candidate) || candidate.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static int asaRank(String asa) {
        int index = ASA_CLASSES.indexOf(asa);
        if (index < 0) {
            throw new M4ValidationException("asa", "ASA 分级只能是 ASA1–ASA5");
        }
        return index;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int requireAge(Integer value, String field) {
        if (value == null) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        if (value < 0 || value > 120) {
            throw new M4ValidationException(field, field + " 必须在 0–120 之间");
        }
        return value;
    }

    private static LocalDate requireDate(LocalDate value, String field) {
        if (value == null) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        return value;
    }

    private StaffAuthorizationProperties.LevelPolicy policyFor(String level) {
        return properties.getLevelPolicies() == null ? null
                : properties.getLevelPolicies().get(level);
    }

    /** 授予/复评：范围超出级别策略上限 → 422（不静默截断）。 */
    private void requireWithinLevelPolicy(String level, List<String> scopes, int ageMin, int ageMax,
            String asaMax) {
        StaffAuthorizationProperties.LevelPolicy policy = policyFor(level);
        if (policy == null) {
            return;
        }
        if (policy.getAsaMax() != null
                && asaRank(asaMax) > asaRank(policy.getAsaMax())) {
            throw new M4UnprocessableException("asaMax",
                    "ASA 上限超出级别 " + level + " 的院方策略上限");
        }
        if (policy.getAgeMin() != null && ageMin < policy.getAgeMin()) {
            throw new M4UnprocessableException("ageMin",
                    "年龄下限低于级别 " + level + " 的院方策略下限");
        }
        if (policy.getAgeMax() != null && ageMax > policy.getAgeMax()) {
            throw new M4UnprocessableException("ageMax",
                    "年龄上限超出级别 " + level + " 的院方策略上限");
        }
        List<String> policyScopes = policy.getSurgeryScopes();
        if (policyScopes != null && !policyScopes.isEmpty() && !policyScopes.contains("*")) {
            for (String scope : scopes) {
                boolean covered = policyScopes.stream().anyMatch(
                        allowed -> allowed.equalsIgnoreCase(scope));
                if (!covered && !scope.equals("*")) {
                    throw new M4UnprocessableException("surgeryScopes",
                            "术式范围超出级别 " + level + " 的院方策略范围");
                }
            }
        }
    }

    private boolean policyViolated(StaffAuthorizationProperties.LevelPolicy policy,
            List<String> scopes, int ageMin, int ageMax, String asaMax, String surgeryTypeId,
            Integer age, String asa) {
        if (policy.getAsaMax() != null && asaRank(asaMax) > asaRank(policy.getAsaMax())) {
            return true;
        }
        if (policy.getAgeMin() != null && ageMin < policy.getAgeMin()) {
            return true;
        }
        if (policy.getAgeMax() != null && ageMax > policy.getAgeMax()) {
            return true;
        }
        if (age != null) {
            if (policy.getAgeMin() != null && age < policy.getAgeMin()) {
                return true;
            }
            if (policy.getAgeMax() != null && age > policy.getAgeMax()) {
                return true;
            }
        }
        if (asa != null && policy.getAsaMax() != null
                && asaRank(asa) > asaRank(policy.getAsaMax())) {
            return true;
        }
        List<String> policyScopes = policy.getSurgeryScopes();
        if (surgeryTypeId != null && !surgeryTypeId.isBlank() && policyScopes != null
                && !policyScopes.isEmpty() && !policyScopes.contains("*")) {
            String target = surgeryTypeId.trim().toUpperCase(Locale.ROOT);
            for (String scope : scopes) {
                if (scopeAllows(List.of(scope), surgeryTypeId)
                        && policyScopes.stream()
                                .noneMatch(allowed -> allowed.equalsIgnoreCase(target))) {
                    return true;
                }
            }
        }
        return false;
    }

    private StaffAuthorization toDto(StaffAuthorizationEntity entity) {
        StaffAuthorization dto = new StaffAuthorization();
        dto.setAuthorizationId(entity.getId().toString());
        dto.setStaffId(entity.getStaffId().toString());
        dto.setVersion(entity.getVersion());
        dto.setLevel(StaffAuthorization.LevelEnum.fromValue(entity.getLevel()));
        dto.setSurgeryScopes(staffJson.read(entity.getSurgeryScopes()));
        dto.setAgeMin(entity.getAgeMin());
        dto.setAgeMax(entity.getAgeMax());
        dto.setAsaMax(StaffAuthorization.AsaMaxEnum.fromValue(entity.getAsaMax()));
        dto.setValidFrom(entity.getValidFrom());
        dto.setValidTo(entity.getValidTo());
        dto.setStatus(StaffAuthorization.StatusEnum.fromValue(entity.getStatus()));
        dto.setApprovedBy(entity.getApprovedBy().toString());
        dto.setApprovedAt(entity.getApprovedAt().atOffset(DISPLAY_OFFSET));
        dto.setSecondApproverId(entity.getSecondApproverId() == null ? null
                : entity.getSecondApproverId().toString());
        dto.setSecondApprovedAt(entity.getSecondApprovedAt() == null ? null
                : entity.getSecondApprovedAt().atOffset(DISPLAY_OFFSET));
        dto.setSecondApproverReason(entity.getSecondApproverReason());
        dto.setRevokedAt(entity.getRevokedAt() == null ? null
                : entity.getRevokedAt().atOffset(DISPLAY_OFFSET));
        dto.setRevokedBy(entity.getRevokedBy() == null ? null
                : entity.getRevokedBy().toString());
        dto.setChangeReason(entity.getChangeReason());
        dto.setCreatedAt(entity.getCreatedAt().atOffset(DISPLAY_OFFSET));
        dto.setCreatedBy(entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString());
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
}
