package com.anesop.m4.impl.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.staff.model.AuthorizationCheckReason;
import com.anesop.contracts.staff.model.AuthorizationCheckRequest;
import com.anesop.contracts.staff.model.AuthorizationCheckResult;
import com.anesop.contracts.staff.model.StaffAuthorization;
import com.anesop.contracts.staff.model.StaffAuthorizationHistory;
import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.UserStatus;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.contracts.staff.model.UpdateStaffAuthorizationRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.AuthService;
import com.anesop.m0.api.security.UserAdminService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.staff.AuthorizationDecision;
import com.anesop.m4.api.staff.AuthorizationDimension;
import com.anesop.m4.impl.config.StaffAuthorizationProperties;
import com.anesop.m4.impl.persistence.entity.StaffAuthorizationEntity;
import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
import com.anesop.m4.impl.persistence.mapper.StaffAuthorizationMapper;
import com.anesop.m4.impl.persistence.mapper.StaffProfileMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.anesop.m4.impl.support.StaffAccessGuard;
import com.anesop.m4.impl.support.StaffJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * 分级授权服务单测（M4-M13-01）：append-only 版本链、撤销语义、级别策略与授权校验矩阵。
 *
 * <p>覆盖允许 / 无授权 / 已撤销 / 未生效 / 过期 / 术式超范围 / 年龄超范围 / ASA 超上限 /
 * 离职 / 级别策略超限；断言 details 与原因码而非实现细节。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StaffAuthorizationServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STAFF_ID = UUID.fromString("3a000000-0000-4000-8000-000000000001");
    private static final UUID APPROVER = UUID.fromString("10000000-0000-4000-8000-000000000009");
    private static final UUID SECOND_APPROVER =
            UUID.fromString("10000000-0000-4000-8000-000000000010");
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-14");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_anes", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-3001");

    @Mock
    private StaffProfileMapper profileMapper;
    @Mock
    private StaffAuthorizationMapper authorizationMapper;
    @Mock
    private StaffAccessGuard accessGuard;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private M4FailureAuditor failureAuditor;
    @Mock
    private UserAdminService userAdminService;
    @Mock
    private AuthService authService;

    private StaffAuthorizationProperties properties;
    private StaffAuthorizationServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new StaffAuthorizationProperties();
        service = new StaffAuthorizationServiceImpl(profileMapper, authorizationMapper, accessGuard,
                new StaffJson(new ObjectMapper()), properties, auditWriter, failureAuditor,
                userAdminService, authService, Clock.fixed(NOW, ZoneOffset.UTC));
        when(profileMapper.selectById(STAFF_ID)).thenReturn(staff("ACTIVE"));
        when(userAdminService.get(APPROVER)).thenReturn(activeUser(APPROVER));
        when(userAdminService.get(SECOND_APPROVER)).thenReturn(activeUser(SECOND_APPROVER));
        when(authService.currentUser(APPROVER)).thenReturn(authorizedUser(APPROVER));
        when(authService.currentUser(SECOND_APPROVER)).thenReturn(authorizedUser(SECOND_APPROVER));
    }

    @Test
    void grantCreatesFirstVersionAndAuditsWithoutPersonalData() {
        mockAuthorization(null);
        when(authorizationMapper.selectHistory(STAFF_ID)).thenReturn(List.of(entity(1, "ACTIVE")));

        StaffAuthorizationHistory history =
                service.update(STAFF_ID.toString(), grant(0, "ASA3"), CALLER, CONTEXT);

        assertThat(history.getCurrent()).isNotNull();
        assertThat(history.getCurrent().getLevel()).isEqualTo(StaffAuthorization.LevelEnum.R3);
        ArgumentCaptor<StaffAuthorizationEntity> inserted =
                ArgumentCaptor.forClass(StaffAuthorizationEntity.class);
        verify(authorizationMapper).insertAuthorization(inserted.capture());
        assertThat(inserted.getValue().getVersion()).isEqualTo(1);
        assertThat(inserted.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(inserted.getValue().getAgeMin()).isEqualTo(18);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("AUTH_GRANT"), anyString(), anyString(), eq(true),
                detail.capture(), eq(ACTOR), eq("synth_anes"), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("level", "R3");
        assertThat(detail.getValue()).doesNotContainKeys("name", "approvedBy", "certificateNo");
    }

    @Test
    void grantRejectsStaleVersion() {
        mockAuthorization(entity(2, "ACTIVE"));

        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), grant(1, "ASA3"), CALLER,
                CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("刷新");
        verify(authorizationMapper, never()).insertAuthorization(any());
    }

    @Test
    void grantRejectsScopeOutsideConfiguredLevelPolicy() {
        StaffAuthorizationProperties.LevelPolicy policy = new StaffAuthorizationProperties.LevelPolicy();
        policy.setAsaMax("ASA2");
        properties.getLevelPolicies().put("R3", policy);
        mockAuthorization(null);

        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), grant(0, "ASA3"), CALLER,
                CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("策略上限");
    }

    @Test
    void revokeWritesNewVersionInsteadOfUpdatingHistory() {
        StaffAuthorizationEntity current = entity(1, "ACTIVE");
        mockAuthorization(current);
        when(authorizationMapper.selectHistory(STAFF_ID)).thenReturn(List.of(entity(2, "REVOKED")));
        UpdateStaffAuthorizationRequest request = new UpdateStaffAuthorizationRequest();
        request.setAction(UpdateStaffAuthorizationRequest.ActionEnum.REVOKE);
        request.setVersion(1);
        request.setApprovedBy(APPROVER.toString());
        request.setSecondApproverId(SECOND_APPROVER.toString());
        request.setChangeReason("资质到期复核（SYNTHETIC）");

        service.update(STAFF_ID.toString(), request, CALLER, CONTEXT);

        ArgumentCaptor<StaffAuthorizationEntity> inserted =
                ArgumentCaptor.forClass(StaffAuthorizationEntity.class);
        verify(authorizationMapper).insertAuthorization(inserted.capture());
        assertThat(inserted.getValue().getVersion()).isEqualTo(2);
        assertThat(inserted.getValue().getStatus()).isEqualTo("REVOKED");
        assertThat(inserted.getValue().getLevel()).isEqualTo(current.getLevel());
        verify(auditWriter).record(eq("AUTH_REVOKE"), anyString(), anyString(), eq(true),
                any(), eq(ACTOR), eq("synth_anes"), eq(CONTEXT));
    }

    @Test
    void revokeRequiresReasonAndExistingAuthorization() {
        mockAuthorization(entity(1, "ACTIVE"));
        UpdateStaffAuthorizationRequest noReason = new UpdateStaffAuthorizationRequest();
        noReason.setAction(UpdateStaffAuthorizationRequest.ActionEnum.REVOKE);
        noReason.setVersion(1);
        noReason.setApprovedBy(APPROVER.toString());
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), noReason, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("原因");

        mockAuthorization(null);
        UpdateStaffAuthorizationRequest none = new UpdateStaffAuthorizationRequest();
        none.setAction(UpdateStaffAuthorizationRequest.ActionEnum.REVOKE);
        none.setVersion(0);
        none.setApprovedBy(APPROVER.toString());
        none.setChangeReason("无授权（SYNTHETIC）");
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), none, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("无法撤销");
    }

    @Test
    void revokeRejectsAlreadyRevokedAuthorization() {
        mockAuthorization(entity(2, "REVOKED"));
        UpdateStaffAuthorizationRequest request = new UpdateStaffAuthorizationRequest();
        request.setAction(UpdateStaffAuthorizationRequest.ActionEnum.REVOKE);
        request.setVersion(2);
        request.setApprovedBy(APPROVER.toString());
        request.setChangeReason("重复撤销（SYNTHETIC）");

        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), request, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class);
    }

    @Test
    void checkReportsAllFailureReasonsAtOnce() {
        StaffAuthorizationEntity current = authorization(1, "ACTIVE", "R2", "[\"GENERAL\"]",
                18, 60, "ASA2", LocalDate.parse("2024-01-01"), LocalDate.parse("2025-01-01"));
        mockAuthorization(current);
        AuthorizationCheckRequest request = new AuthorizationCheckRequest();
        request.setStaffId(STAFF_ID.toString());
        request.setSurgeryTypeId("CARDIAC");
        request.setAge(72);
        request.setAsa(AuthorizationCheckRequest.AsaEnum.ASA4);

        AuthorizationCheckResult result = service.check(request, CALLER);

        assertThat(result.getAllowed()).isFalse();
        assertThat(result.getReasons()).extracting(reason ->
                reason.getCode()).containsExactlyInAnyOrder(
                        "EXPIRED", "SURGERY_SCOPE_MISSING", "AGE_OUT_OF_RANGE", "ASA_EXCEEDS_LIMIT");
        assertThat(result.getLevel()).isEqualTo(AuthorizationCheckResult.LevelEnum.R2);
        assertThat(result.getValidUntil()).isEqualTo(LocalDate.parse("2025-01-01"));
        assertThat(result.getReasons()).allSatisfy(reason ->
                assertThat(reason.getMessage()).doesNotContain("身份证", "住院号"));
    }

    @Test
    void checkAllowsWithinScope() {
        mockAuthorization(
                authorization(1, "ACTIVE", "R3", "[\"*\"]", 18, 90, "ASA4",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31")));
        AuthorizationCheckRequest request = new AuthorizationCheckRequest();
        request.setStaffId(STAFF_ID.toString());
        request.setSurgeryTypeId("GENERAL");
        request.setAge(70);
        request.setAsa(AuthorizationCheckRequest.AsaEnum.ASA3);

        AuthorizationCheckResult result = service.check(request, CALLER);

        assertThat(result.getAllowed()).isTrue();
        assertThat(result.getReasons()).isEmpty();
        assertThat(result.getLevel()).isEqualTo(AuthorizationCheckResult.LevelEnum.R3);
    }

    @Test
    void checkDeniesWithoutAuthorizationAndForInactiveStaff() {
        mockAuthorization(null);
        AuthorizationCheckResult noAuth = service.check(minimalCheck(), CALLER);
        assertThat(noAuth.getAllowed()).isFalse();
        assertThat(noAuth.getReasons()).extracting(reason -> reason.getCode())
                .containsExactly("NO_AUTHORIZATION");

        when(profileMapper.selectById(STAFF_ID)).thenReturn(staff("INACTIVE"));
        mockAuthorization(
                authorization(1, "ACTIVE", "R3", "[\"*\"]", 18, 90, "ASA3",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31")));
        AuthorizationCheckResult inactive = service.check(minimalCheck(), CALLER);
        assertThat(inactive.getAllowed()).isFalse();
        assertThat(inactive.getReasons()).extracting(reason -> reason.getCode())
                .contains("STAFF_INACTIVE");
    }

    @Test
    void checkDeniesRevokedAndNotYetValidAuthorization() {
        mockAuthorization(
                authorization(2, "REVOKED", "R3", "[\"*\"]", 18, 90, "ASA3",
                        LocalDate.parse("2027-01-01"), LocalDate.parse("2028-01-01")));

        AuthorizationCheckResult result = service.check(minimalCheck(), CALLER);

        assertThat(result.getAllowed()).isFalse();
        assertThat(result.getReasons()).extracting(reason -> reason.getCode())
                .contains("AUTHORIZATION_REVOKED", "NOT_YET_VALID");
    }

    @Test
    void checkAddsLevelPolicyExceededWhenConfigured() {
        StaffAuthorizationProperties.LevelPolicy policy = new StaffAuthorizationProperties.LevelPolicy();
        policy.setAsaMax("ASA2");
        properties.getLevelPolicies().put("R3", policy);
        mockAuthorization(
                authorization(1, "ACTIVE", "R3", "[\"*\"]", 18, 90, "ASA3",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31")));
        AuthorizationCheckRequest request = minimalCheck();
        request.setAsa(AuthorizationCheckRequest.AsaEnum.ASA3);

        AuthorizationCheckResult result = service.check(request, CALLER);

        assertThat(result.getAllowed()).isFalse();
        assertThat(result.getReasons()).extracting(reason ->
                reason.getCode()).contains("LEVEL_POLICY_EXCEEDED");
    }

    @Test
    void checkRejectsUnknownStaffWith422() {
        when(profileMapper.selectById(STAFF_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.check(minimalCheck(), CALLER))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("人员不存在");
    }

    @Test
    void evaluateUnknownStaffThrowsNotFound() {
        when(profileMapper.selectById(STAFF_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.evaluate(STAFF_ID, null, null, null, NOW))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void evaluateIsUsableBySchedulingWithoutHttpContext() {
        mockAuthorization(
                authorization(1, "ACTIVE", "R3", "[\"GENERAL\"]", 18, 90, "ASA3",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31")));

        AuthorizationDecision decision = service.evaluate(STAFF_ID, "GENERAL", 66, "ASA2", NOW);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reasonCodes()).isEmpty();
        assertThat(decision.level()).isEqualTo("R3");
        assertThat(decision.validUntil()).isEqualTo(LocalDate.parse("2027-12-31"));
    }

    @Test
    void historyReturnsCurrentAndOrderedVersions() {
        when(authorizationMapper.selectHistory(STAFF_ID)).thenReturn(
                List.of(entity(2, "REVOKED"), entity(1, "ACTIVE")));

        StaffAuthorizationHistory history =
                service.getHistory(STAFF_ID.toString(), CALLER);

        assertThat(history.getHistory()).hasSize(2);
        assertThat(history.getCurrent().getVersion()).isEqualTo(2);
        assertThat(history.getCurrent().getStatus()).isEqualTo(StaffAuthorization.StatusEnum.REVOKED);
    }

    @Test
    void grantRejectsInvalidGrantFields() {
        mockAuthorization(null);

        UpdateStaffAuthorizationRequest noAction = grant(0, "ASA3");
        noAction.setAction(null);
        assertRejected(noAction, M4ValidationException.class, "动作");

        UpdateStaffAuthorizationRequest noApprover = grant(0, "ASA3");
        noApprover.setApprovedBy(null);
        assertRejected(noApprover, M4ValidationException.class, "审批人");

        UpdateStaffAuthorizationRequest noLevel = grant(0, "ASA3");
        noLevel.setLevel(null);
        assertRejected(noLevel, M4ValidationException.class, "R1");

        UpdateStaffAuthorizationRequest noScopes = grant(0, "ASA3");
        noScopes.setSurgeryScopes(List.of());
        assertRejected(noScopes, M4ValidationException.class, "术式范围");

        UpdateStaffAuthorizationRequest badScope = grant(0, "ASA3");
        badScope.setSurgeryScopes(List.of("bad scope!"));
        assertRejected(badScope, M4ValidationException.class, "术式范围");

        UpdateStaffAuthorizationRequest noAgeMin = grant(0, "ASA3");
        noAgeMin.setAgeMin(null);
        assertRejected(noAgeMin, M4ValidationException.class, "ageMin");

        UpdateStaffAuthorizationRequest badAge = grant(0, "ASA3");
        badAge.setAgeMin(90);
        badAge.setAgeMax(18);
        assertRejected(badAge, M4ValidationException.class, "年龄上限");

        UpdateStaffAuthorizationRequest noAsa = grant(0, "ASA3");
        noAsa.setAsaMax(null);
        assertRejected(noAsa, M4ValidationException.class, "ASA");

        UpdateStaffAuthorizationRequest noFrom = grant(0, "ASA3");
        noFrom.setValidFrom(null);
        assertRejected(noFrom, M4ValidationException.class, "validFrom");

        UpdateStaffAuthorizationRequest badRange = grant(0, "ASA3");
        badRange.setValidTo(LocalDate.parse("2020-01-01"));
        assertRejected(badRange, M4ValidationException.class, "失效日期");
    }

    @Test
    void grantRejectsInactiveStaffAndMapsDuplicateVersion() {
        when(profileMapper.selectById(STAFF_ID)).thenReturn(staff("INACTIVE"));
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), grant(0, "ASA3"), CALLER,
                CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("离职");

        when(profileMapper.selectById(STAFF_ID)).thenReturn(staff("ACTIVE"));
        mockAuthorization(null);
        when(authorizationMapper.insertAuthorization(any())).thenThrow(
                new org.springframework.dao.DuplicateKeyException("uk_staff_authorization_version"));
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), grant(0, "ASA3"), CALLER,
                CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void levelPolicyRejectsAgeAndScopeBeyondPolicy() {
        mockAuthorization(null);
        StaffAuthorizationProperties.LevelPolicy policy = new StaffAuthorizationProperties.LevelPolicy();
        policy.setAgeMin(18);
        policy.setAgeMax(60);
        policy.setSurgeryScopes(List.of("GENERAL"));
        properties.getLevelPolicies().put("R3", policy);

        UpdateStaffAuthorizationRequest younger = grant(0, "ASA3");
        younger.setAgeMin(10);
        younger.setAgeMax(90);
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), younger, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("年龄下限");

        UpdateStaffAuthorizationRequest older = grant(0, "ASA3");
        older.setAgeMax(70);
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), older, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("年龄上限");

        UpdateStaffAuthorizationRequest scoped = grant(0, "ASA3");
        scoped.setAgeMin(18);
        scoped.setAgeMax(60);
        scoped.setSurgeryScopes(List.of("CARDIAC"));
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), scoped, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("术式范围");
    }

    @Test
    void checkRejectsInvalidAgeAndMissingStaffId() {
        AuthorizationCheckRequest invalidAge = minimalCheck();
        invalidAge.setAge(200);
        assertThatThrownBy(() -> service.check(invalidAge, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("年龄");

        AuthorizationCheckRequest missingStaff = new AuthorizationCheckRequest();
        assertThatThrownBy(() -> service.check(missingStaff, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("人员标识");
    }

    @Test
    void checkAddsLevelPolicyExceededForAgeAndScopeBeyondPolicy() {
        StaffAuthorizationProperties.LevelPolicy policy = new StaffAuthorizationProperties.LevelPolicy();
        policy.setAgeMax(60);
        policy.setSurgeryScopes(List.of("GENERAL"));
        properties.getLevelPolicies().put("R3", policy);
        mockAuthorization(
                authorization(1, "ACTIVE", "R3", "[\"CARDIAC\"]", 18, 90, "ASA3",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31")));

        AuthorizationCheckRequest request = minimalCheck();
        request.setAge(80);
        request.setSurgeryTypeId("CARDIAC");
        AuthorizationCheckResult result = service.check(request, CALLER);

        assertThat(result.getAllowed()).isFalse();
        assertThat(result.getReasons()).extracting(reason ->
                reason.getCode()).contains("LEVEL_POLICY_EXCEEDED");
    }

    @Test
    void evaluateRejectsInvalidAsa() {
        mockAuthorization(
                authorization(1, "ACTIVE", "R3", "[\"*\"]", 18, 90, "ASA3",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31")));

        assertThatThrownBy(() -> service.evaluate(STAFF_ID, null, null, "ASA9", NOW))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("ASA");
    }

    @Test
    void historyRejectsUnknownStaff() {
        when(profileMapper.selectById(STAFF_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getHistory(STAFF_ID.toString(), CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void selfGrantAndSelfApprovalAreForbidden() {
        when(profileMapper.selectById(STAFF_ID)).thenReturn(staffForUser(ACTOR));
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), grant(0, "ASA3"), CALLER,
                CONTEXT))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("本人");

        when(profileMapper.selectById(STAFF_ID)).thenReturn(staff("ACTIVE"));
        UpdateStaffAuthorizationRequest selfApproved = grant(0, "ASA3");
        selfApproved.setApprovedBy(ACTOR.toString());
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), selfApproved, CALLER,
                CONTEXT))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("操作者");
    }

    @Test
    void fakeOrDisabledApproverIsRejectedWith400() {
        when(userAdminService.get(APPROVER))
                .thenThrow(new NoSuchElementException("missing"))
                .thenReturn(disabledUser(APPROVER));
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), grant(0, "ASA3"), CALLER,
                CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("审批人不存在");

        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), grant(0, "ASA3"), CALLER,
                CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("审批人不存在");
    }

    @Test
    void approverWithoutAuthorizePermissionIsRejectedWith403() {
        when(authService.currentUser(APPROVER)).thenReturn(currentUser(APPROVER,
                List.of("m4:staff:read")));
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), grant(0, "ASA3"), CALLER,
                CONTEXT))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("m4:staff:authorize");
    }

    @Test
    void dualApprovalIsRequiredAndBothApproversAreRecorded() {
        mockAuthorization(null);
        UpdateStaffAuthorizationRequest missingSecond = grant(0, "ASA3");
        missingSecond.setSecondApproverId(null);
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), missingSecond, CALLER,
                CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("第二审批人");

        UpdateStaffAuthorizationRequest sameSecond = grant(0, "ASA3");
        sameSecond.setSecondApproverId(APPROVER.toString());
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), sameSecond, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("不同");

        when(authorizationMapper.selectHistory(STAFF_ID)).thenReturn(List.of(entity(1, "ACTIVE")));
        service.update(STAFF_ID.toString(), grant(0, "ASA3"), CALLER, CONTEXT);
        ArgumentCaptor<StaffAuthorizationEntity> inserted =
                ArgumentCaptor.forClass(StaffAuthorizationEntity.class);
        verify(authorizationMapper).insertAuthorization(inserted.capture());
        assertThat(inserted.getValue().getSecondApproverId()).isEqualTo(SECOND_APPROVER);
        assertThat(inserted.getValue().getSecondApprovedAt()).isEqualTo(NOW);
    }

    @Test
    void singleApprovalSucceedsForR3WithoutWildcardOrHighAsa() {
        mockAuthorization(null);
        when(authorizationMapper.selectHistory(STAFF_ID)).thenReturn(List.of(entity(1, "ACTIVE")));
        UpdateStaffAuthorizationRequest request = grantSingle();

        service.update(STAFF_ID.toString(), request, CALLER, CONTEXT);

        ArgumentCaptor<StaffAuthorizationEntity> inserted =
                ArgumentCaptor.forClass(StaffAuthorizationEntity.class);
        verify(authorizationMapper).insertAuthorization(inserted.capture());
        assertThat(inserted.getValue().getSecondApproverId()).isNull();
    }

    @Test
    void checkRequiresExplicitNotApplicableForMissingDimensions() {
        mockAuthorization(
                authorization(1, "ACTIVE", "R3", "[\"GENERAL\"]", 18, 90, "ASA3",
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31")));

        AuthorizationCheckRequest missing = new AuthorizationCheckRequest();
        missing.setStaffId(STAFF_ID.toString());
        AuthorizationCheckResult denied = service.check(missing, CALLER);
        assertThat(denied.getAllowed()).isFalse();
        assertThat(denied.getReasons()).extracting(reason -> reason.getCode())
                .contains("INPUT_INCOMPLETE");

        AuthorizationCheckRequest declared = new AuthorizationCheckRequest();
        declared.setStaffId(STAFF_ID.toString());
        declared.setNotApplicable(Set.of(
                AuthorizationCheckRequest.NotApplicableEnum.SURGERY_TYPE,
                AuthorizationCheckRequest.NotApplicableEnum.AGE,
                AuthorizationCheckRequest.NotApplicableEnum.ASA));
        assertThat(service.check(declared, CALLER).getAllowed()).isTrue();

        AuthorizationCheckRequest partial = new AuthorizationCheckRequest();
        partial.setStaffId(STAFF_ID.toString());
        partial.setSurgeryTypeId("GENERAL");
        partial.setAsa(AuthorizationCheckRequest.AsaEnum.ASA2);
        partial.setNotApplicable(Set.of(AuthorizationCheckRequest.NotApplicableEnum.AGE));
        assertThat(service.check(partial, CALLER).getAllowed()).isTrue();

        AuthorizationDecision evaluated = service.evaluate(STAFF_ID, "GENERAL", null, "ASA2", NOW,
                Set.of(AuthorizationDimension.AGE));
        assertThat(evaluated.allowed()).isTrue();
        AuthorizationDecision missingEvaluated = service.evaluate(STAFF_ID, "GENERAL", null,
                "ASA2", NOW);
        assertThat(missingEvaluated.allowed()).isFalse();
        assertThat(missingEvaluated.reasonCodes()).contains("INPUT_INCOMPLETE");
    }

    @Test
    void retrospectiveCheckUsesVersionEffectiveAtThatTime() {
        StaffAuthorizationEntity first = authorization(1, "ACTIVE", "R3", "[\"GENERAL\"]",
                18, 90, "ASA3", LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31"));
        StaffAuthorizationEntity second = authorization(2, "ACTIVE", "R4", "[\"CARDIAC\"]",
                18, 90, "ASA4", LocalDate.parse("2026-06-01"), LocalDate.parse("2027-12-31"));
        Instant beforeExpansion = Instant.parse("2026-03-01T00:00:00Z");
        Instant afterExpansion = Instant.parse("2026-07-01T00:00:00Z");
        when(authorizationMapper.selectLatestApprovedAt(STAFF_ID, beforeExpansion))
                .thenReturn(first);
        when(authorizationMapper.selectEffectiveAt(STAFF_ID, beforeExpansion)).thenReturn(first);
        when(authorizationMapper.selectLatestApprovedAt(STAFF_ID, afterExpansion))
                .thenReturn(second);
        when(authorizationMapper.selectEffectiveAt(STAFF_ID, afterExpansion)).thenReturn(second);
        when(authorizationMapper.selectCurrent(STAFF_ID)).thenReturn(second);

        AuthorizationCheckRequest past = new AuthorizationCheckRequest();
        past.setStaffId(STAFF_ID.toString());
        past.setSurgeryTypeId("GENERAL");
        past.setAge(50);
        past.setAsa(AuthorizationCheckRequest.AsaEnum.ASA2);
        past.setAt(beforeExpansion.atOffset(ZoneOffset.UTC));
        AuthorizationCheckResult pastResult = service.check(past, CALLER);
        assertThat(pastResult.getAllowed()).isTrue();
        assertThat(pastResult.getLevel()).isEqualTo(AuthorizationCheckResult.LevelEnum.R3);

        AuthorizationCheckRequest current = new AuthorizationCheckRequest();
        current.setStaffId(STAFF_ID.toString());
        current.setSurgeryTypeId("GENERAL");
        current.setAge(50);
        current.setAsa(AuthorizationCheckRequest.AsaEnum.ASA2);
        current.setAt(afterExpansion.atOffset(ZoneOffset.UTC));
        AuthorizationCheckResult currentResult = service.check(current, CALLER);
        assertThat(currentResult.getAllowed()).isFalse();
        assertThat(currentResult.getReasons()).extracting(reason -> reason.getCode())
                .contains("SURGERY_SCOPE_MISSING");
    }

    @Test
    void inactiveStaffCanStillRevokeAndFailureAuditUsesActualAction() {
        StaffAuthorizationEntity current = entity(1, "ACTIVE");
        when(profileMapper.selectById(STAFF_ID)).thenReturn(staff("INACTIVE"));
        mockAuthorization(current);
        when(authorizationMapper.selectHistory(STAFF_ID)).thenReturn(List.of(entity(2, "REVOKED")));

        UpdateStaffAuthorizationRequest revoke = new UpdateStaffAuthorizationRequest();
        revoke.setAction(UpdateStaffAuthorizationRequest.ActionEnum.REVOKE);
        revoke.setVersion(1);
        revoke.setApprovedBy(APPROVER.toString());
        revoke.setSecondApproverId(SECOND_APPROVER.toString());
        revoke.setChangeReason("离职清理（SYNTHETIC）");
        service.update(STAFF_ID.toString(), revoke, CALLER, CONTEXT);

        ArgumentCaptor<StaffAuthorizationEntity> inserted =
                ArgumentCaptor.forClass(StaffAuthorizationEntity.class);
        verify(authorizationMapper).insertAuthorization(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo("REVOKED");
        assertThat(inserted.getValue().getRevokedAt()).isEqualTo(NOW);
        assertThat(inserted.getValue().getRevokedBy()).isEqualTo(ACTOR);

        UpdateStaffAuthorizationRequest invalidRevoke = new UpdateStaffAuthorizationRequest();
        invalidRevoke.setAction(UpdateStaffAuthorizationRequest.ActionEnum.REVOKE);
        invalidRevoke.setVersion(999);
        invalidRevoke.setApprovedBy(APPROVER.toString());
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), invalidRevoke, CALLER,
                CONTEXT))
                .isInstanceOf(M4ConflictException.class);
        verify(failureAuditor).recordFailure(eq("AUTH_REVOKE"), anyString(), anyString(),
                any(), eq(CALLER), eq(CONTEXT));
    }

    private void assertRejected(UpdateStaffAuthorizationRequest request,
            Class<? extends RuntimeException> expected, String messagePart) {
        assertThatThrownBy(() -> service.update(STAFF_ID.toString(), request, CALLER, CONTEXT))
                .isInstanceOf(expected)
                .hasMessageContaining(messagePart);
    }

    private void mockAuthorization(StaffAuthorizationEntity entity) {
        when(authorizationMapper.selectCurrent(STAFF_ID)).thenReturn(entity);
        when(authorizationMapper.selectLatestApprovedAt(eq(STAFF_ID), any(Instant.class)))
                .thenReturn(entity);
        when(authorizationMapper.selectEffectiveAt(eq(STAFF_ID), any(Instant.class)))
                .thenReturn(entity);
    }

    private static UserSummary activeUser(UUID userId) {
        return new UserSummary().userId(userId.toString()).username("synth_approver")
                .displayName("合成审批人").status(UserStatus.ACTIVE)
                .roles(List.of("SYS_AUTH_MANAGER")).mustChangePassword(false)
                .createdAt(NOW.atOffset(ZoneOffset.UTC));
    }

    private static UserSummary disabledUser(UUID userId) {
        return activeUser(userId).status(UserStatus.DISABLED);
    }

    private static CurrentUser authorizedUser(UUID userId) {
        return currentUser(userId, List.of("m4:staff:authorize"));
    }

    private static CurrentUser currentUser(UUID userId, List<String> permissions) {
        return new CurrentUser().userId(userId.toString()).username("synth_approver")
                .displayName("合成审批人").roles(List.of("SYS_AUTH_MANAGER"))
                .permissions(permissions).dataScopes(List.of()).siteIds(List.of())
                .mustChangePassword(false);
    }

    // ------------------------------------------------------------------ 夹具 --

    private static AuthorizationCheckRequest minimalCheck() {
        AuthorizationCheckRequest request = new AuthorizationCheckRequest();
        request.setStaffId(STAFF_ID.toString());
        request.setNotApplicable(Set.of(
                AuthorizationCheckRequest.NotApplicableEnum.SURGERY_TYPE,
                AuthorizationCheckRequest.NotApplicableEnum.AGE,
                AuthorizationCheckRequest.NotApplicableEnum.ASA));
        return request;
    }

    private static UpdateStaffAuthorizationRequest grant(int version, String asaMax) {
        UpdateStaffAuthorizationRequest request = new UpdateStaffAuthorizationRequest();
        request.setAction(UpdateStaffAuthorizationRequest.ActionEnum.GRANT);
        request.setVersion(version);
        request.setLevel(UpdateStaffAuthorizationRequest.LevelEnum.R3);
        request.setSurgeryScopes(List.of("*"));
        request.setAgeMin(18);
        request.setAgeMax(90);
        request.setAsaMax(UpdateStaffAuthorizationRequest.AsaMaxEnum.fromValue(asaMax));
        request.setValidFrom(LocalDate.parse("2026-11-01"));
        request.setValidTo(LocalDate.parse("2027-10-31"));
        request.setApprovedBy(APPROVER.toString());
        request.setSecondApproverId(SECOND_APPROVER.toString());
        request.setSecondApproverReason("第二审批人复核（SYNTHETIC）");
        request.setChangeReason("年度授权复评（SYNTHETIC）");
        return request;
    }

    private static UpdateStaffAuthorizationRequest grantSingle() {
        UpdateStaffAuthorizationRequest request = grant(0, "ASA3");
        request.setSurgeryScopes(List.of("GENERAL"));
        request.setSecondApproverId(null);
        request.setSecondApproverReason(null);
        return request;
    }

    private static StaffProfileEntity staff(String employmentStatus) {
        return staff(employmentStatus,
                UUID.fromString("10000000-0000-4000-8000-000000000012"));
    }

    private static StaffProfileEntity staffForUser(UUID userId) {
        return staff("ACTIVE", userId);
    }

    private static StaffProfileEntity staff(String employmentStatus, UUID userId) {
        StaffProfileEntity entity = new StaffProfileEntity();
        entity.setId(STAFF_ID);
        entity.setUserId(userId);
        entity.setStaffNo("STAFF-ANES-001");
        entity.setName("演示麻醉医师甲（合成）");
        entity.setDepartmentId(UUID.fromString("f1000000-0000-4000-8000-000000000002"));
        entity.setTitle("主任医师");
        entity.setEmploymentStatus(employmentStatus);
        entity.setSpecialties("[]");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        entity.setUpdatedAt(NOW);
        entity.setUpdatedBy(ACTOR);
        entity.setVersion(1);
        return entity;
    }

    private static StaffAuthorizationEntity entity(int version, String status) {
        return authorization(version, status, "R3", "[\"*\"]", 18, 90, "ASA3",
                LocalDate.parse("2026-11-01"), LocalDate.parse("2027-10-31"));
    }

    private static StaffAuthorizationEntity authorization(int version, String status, String level,
            String scopes, int ageMin, int ageMax, String asaMax, LocalDate validFrom,
            LocalDate validTo) {
        StaffAuthorizationEntity entity = new StaffAuthorizationEntity();
        entity.setId(UUID.fromString(String.format("5a000000-0000-4000-8000-%012d", version)));
        entity.setStaffId(STAFF_ID);
        entity.setVersion(version);
        entity.setLevel(level);
        entity.setSurgeryScopes(scopes);
        entity.setAgeMin(ageMin);
        entity.setAgeMax(ageMax);
        entity.setAsaMax(asaMax);
        entity.setValidFrom(validFrom);
        entity.setValidTo(validTo);
        entity.setStatus(status);
        entity.setApprovedBy(APPROVER);
        entity.setApprovedAt(NOW);
        entity.setChangeReason("SYNTHETIC");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        return entity;
    }
}
