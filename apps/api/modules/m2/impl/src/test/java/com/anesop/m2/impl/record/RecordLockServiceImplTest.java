package com.anesop.m2.impl.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.ConsentRecord;
import com.anesop.contracts.anesthesia.model.ConsentRecordList;
import com.anesop.contracts.anesthesia.model.CreateRevisionRequest;
import com.anesop.contracts.anesthesia.model.LockPrecondition;
import com.anesop.contracts.anesthesia.model.LockRecordRequest;
import com.anesop.contracts.anesthesia.model.LockRecordResult;
import com.anesop.contracts.anesthesia.model.RecordLockStatus;
import com.anesop.contracts.anesthesia.model.RecordRevision;
import com.anesop.contracts.anesthesia.model.RecordRevisionSummary;
import com.anesop.contracts.anesthesia.model.RevisionCorrections;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.consent.ConsentService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2LockPreconditionException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.threeway.ThreeWayCheckService;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.RecordRevisionEntity;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.DrugAdministrationMapper;
import com.anesop.m2.impl.persistence.mapper.FluidRecordMapper;
import com.anesop.m2.impl.persistence.mapper.RecordEventMapper;
import com.anesop.m2.impl.persistence.mapper.RecordRevisionMapper;
import com.anesop.m2.impl.persistence.mapper.ResuscitationRecordMapper;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * 锁单/修订服务单测（M3-M2-03）：前置清单、确认清单、重复/过期锁单、签名占位、修订原因码、
 * 快照与审计。Mock 掉 Mapper / m1-api / m0-api，数据库触发器与并发行锁由集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class RecordLockServiceImplTest {

    private static final UUID RECORD_ID = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID SCHEDULE_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID APPLICATION_ID =
            UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-12-01T03:00:00Z");

    @Mock
    private AnesthesiaRecordMapper recordMapper;
    @Mock
    private RecordEventMapper eventMapper;
    @Mock
    private DrugAdministrationMapper drugMapper;
    @Mock
    private FluidRecordMapper fluidMapper;
    @Mock
    private RecordRevisionMapper revisionMapper;
    @Mock
    private ResuscitationRecordMapper rescueMapper;
    @Mock
    private SchedulingService schedulingService;
    @Mock
    private ThreeWayCheckService threeWayCheckService;
    @Mock
    private ConsentService consentService;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private ClinicalFailureAuditor failureAuditor;

    private RecordLockServiceImpl service;
    private AuthPrincipal caller;
    private AuthRequestContext context;

    @BeforeEach
    void setUp() {
        service = new RecordLockServiceImpl(recordMapper, eventMapper, drugMapper, fluidMapper,
                rescueMapper, revisionMapper, schedulingService, threeWayCheckService, consentService,
                new RecordRevisionSnapshotBuilder(new ObjectMapper()),
                auditWriter, failureAuditor, Clock.fixed(NOW, ZoneOffset.UTC));
        caller = new AuthPrincipal(ACTOR_ID, "synth_doc", "jti", NOW.plusSeconds(900), "sid");
        context = new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE");
    }

    @Test
    void lockStatusListsAllPreconditionsAndBlocksWhenClinicalFactsMissing() {
        AnesthesiaRecordEntity record = ongoingRecord();
        record.setEndedAt(null);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(eventMapper.countByRecord(RECORD_ID)).thenReturn(0);
        when(threeWayCheckService.isThreeWayCheckCompleted(APPLICATION_ID.toString()))
                .thenReturn(false);
        when(consentService.listByApplication(APPLICATION_ID.toString(), caller))
                .thenReturn(consentList(ConsentRecord.StatusEnum.SIGNED));

        RecordLockStatus status = service.lockStatus(RECORD_ID.toString(), caller);

        assertThat(status.getLocked()).isFalse();
        assertThat(status.getCanLock()).isFalse();
        assertThat(status.getCurrentRevision()).isZero();
        assertThat(status.getPreconditions()).extracting(LockPrecondition::getCode)
                .extracting(LockPrecondition.CodeEnum::getValue)
                .containsExactly("THREE_WAY_CHECK_COMPLETED", "CONSENT_SIGNED", "RECORD_STARTED",
                        "RECORD_ENDED", "AT_LEAST_ONE_EVENT", "NOT_LOCKED");
        assertThat(status.getPreconditions()).filteredOn(item -> !item.getSatisfied())
                .extracting(LockPrecondition::getCode)
                .extracting(LockPrecondition.CodeEnum::getValue)
                .containsExactly("THREE_WAY_CHECK_COMPLETED", "RECORD_ENDED", "AT_LEAST_ONE_EVENT");
    }

    @Test
    void lockStatusTreatsUnreadableConsentAsUnsatisfiedFailSafe() {
        AnesthesiaRecordEntity record = ongoingRecord();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(eventMapper.countByRecord(RECORD_ID)).thenReturn(1);
        when(threeWayCheckService.isThreeWayCheckCompleted(APPLICATION_ID.toString()))
                .thenReturn(true);
        when(consentService.listByApplication(APPLICATION_ID.toString(), caller))
                .thenThrow(new AccessDeniedException("不可见"));

        RecordLockStatus status = service.lockStatus(RECORD_ID.toString(), caller);

        assertThat(status.getCanLock()).isFalse();
        assertThat(status.getPreconditions()).filteredOn(item -> !item.getSatisfied())
                .extracting(LockPrecondition::getCode)
                .containsExactly(LockPrecondition.CodeEnum.CONSENT_SIGNED);
    }

    @Test
    void lockRejectsUnmetPreconditionsWithConditionCodesAndFailureAudit() {
        AnesthesiaRecordEntity record = ongoingRecord();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(record);
        when(eventMapper.countByRecord(RECORD_ID)).thenReturn(1);
        when(threeWayCheckService.isThreeWayCheckCompleted(APPLICATION_ID.toString()))
                .thenReturn(false);
        when(consentService.listByApplication(APPLICATION_ID.toString(), caller))
                .thenReturn(consentList(ConsentRecord.StatusEnum.SIGNED));

        assertThatThrownBy(() -> service.lock(RECORD_ID.toString(), lockRequest(4), caller, context))
                .isInstanceOf(M2LockPreconditionException.class)
                .satisfies(failure -> assertThat(
                        ((M2LockPreconditionException) failure).unmetConditions())
                        .extracting(M2LockPreconditionException.UnmetCondition::code)
                        .containsExactly("THREE_WAY_CHECK_COMPLETED"));
        verify(recordMapper, never()).lockRecord(any(), any(), any(), any(), anyString(),
                anyString(), anyString(), any(), any());
        verify(failureAuditor).recordFailure(eq("RECORD_LOCK"), eq("ANESTHESIA_RECORD"),
                eq(RECORD_ID.toString()), any(), eq(caller), eq(context));
    }

    @Test
    void lockRequiresExplicitChecklistConfirmation() {
        LockRecordRequest request = lockRequest(4);
        request.setConfirmChecklist(false);

        assertThatThrownBy(() -> service.lock(RECORD_ID.toString(), request, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .satisfies(failure -> assertThat(((M2ValidationException) failure).field())
                        .isEqualTo("confirmChecklist"));
    }

    @Test
    void lockRejectsRepeatAndStaleVersion() {
        AnesthesiaRecordEntity locked = ongoingRecord();
        locked.setLockedAt(NOW.minusSeconds(60));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(locked);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(locked);

        assertThatThrownBy(() -> service.lock(RECORD_ID.toString(), lockRequest(4), caller, context))
                .isInstanceOf(M2ConflictException.class);

        AnesthesiaRecordEntity stale = ongoingRecord();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(stale);
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(stale);
        assertThatThrownBy(() -> service.lock(RECORD_ID.toString(), lockRequest(2), caller, context))
                .isInstanceOf(M2ConflictException.class);
        // S-6：重复锁单/版本过期失败写 result=0 审计（RECORD_LOCK，仅拒绝码）。
        verify(failureAuditor, times(2)).recordFailure(eq("RECORD_LOCK"), eq("ANESTHESIA_RECORD"),
                eq(RECORD_ID.toString()), any(), eq(caller), eq(context));
        verify(failureAuditor).recordFailure(eq("RECORD_LOCK"), eq("ANESTHESIA_RECORD"),
                eq(RECORD_ID.toString()),
                argThat(detail -> "ALREADY_LOCKED".equals(detail.get("rejection"))), eq(caller),
                eq(context));
        verify(failureAuditor).recordFailure(eq("RECORD_LOCK"), eq("ANESTHESIA_RECORD"),
                eq(RECORD_ID.toString()),
                argThat(detail -> "VERSION_CONFLICT".equals(detail.get("rejection"))), eq(caller),
                eq(context));
    }

    @Test
    void lockWritesLockColumnsSimulatedSignatureAndAudit() {
        stubLockableRecord();
        when(recordMapper.lockRecord(eq(RECORD_ID), eq(4L), eq(NOW), eq(ACTOR_ID), anyString(),
                anyString(), eq("SIMULATED"), eq(NOW), eq(ACTOR_ID))).thenReturn(1);

        LockRecordResult result = service.lock(RECORD_ID.toString(), lockRequest(4), caller,
                context);

        assertThat(result.getStatus()).isEqualTo(LockRecordResult.StatusEnum.LOCKED);
        assertThat(result.getSignatureStatus()).isEqualTo(
                LockRecordResult.SignatureStatusEnum.SIGNED);
        assertThat(result.getCaStatus()).isEqualTo(LockRecordResult.CaStatusEnum.SIMULATED);
        assertThat(result.getSignatureRef()).isEqualTo("SIM-CA-" + RECORD_ID);
        assertThat(result.getSignatureHash()).matches("^[0-9a-f]{64}$");
        assertThat(result.getCurrentRevision()).isZero();
        verify(auditWriter).record(eq("RECORD_LOCK"), eq("ANESTHESIA_RECORD"),
                eq(RECORD_ID.toString()), eq(true), any(), eq(ACTOR_ID), eq("synth_doc"),
                eq(context));
        verify(auditWriter).record(eq("RECORD_SIGN"), eq("ANESTHESIA_RECORD"),
                eq(RECORD_ID.toString()), eq(true), any(), eq(ACTOR_ID), eq("synth_doc"),
                eq(context));
        verify(recordMapper, never()).markRevision(any(), any(), any(), any(), any(), any());
    }

    @Test
    void lockFailsWhenConditionalUpdateLosesRace() {
        stubLockableRecord();
        when(recordMapper.lockRecord(eq(RECORD_ID), eq(4L), eq(NOW), eq(ACTOR_ID), anyString(),
                anyString(), eq("SIMULATED"), eq(NOW), eq(ACTOR_ID))).thenReturn(0);

        assertThatThrownBy(() -> service.lock(RECORD_ID.toString(), lockRequest(4), caller, context))
                .isInstanceOf(M2ConflictException.class);
        verify(auditWriter, never()).record(eq("RECORD_LOCK"), any(), any(), eq(true), any(),
                any(), any(), any());
    }

    @Test
    void createRevisionRequiresLockedRecord() {
        AnesthesiaRecordEntity record = ongoingRecord();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(record);

        assertThatThrownBy(() -> service.createRevision(RECORD_ID.toString(),
                revisionRequest(null), caller, context))
                .isInstanceOf(M2ConflictException.class);
        verify(revisionMapper, never()).insertRevision(any());
        // S-6：未锁单修订失败写 result=0 审计（RECORD_REVISE/RECORD_NOT_LOCKED）。
        verify(failureAuditor).recordFailure(eq("RECORD_REVISE"), eq("ANESTHESIA_RECORD"),
                eq(RECORD_ID.toString()),
                argThat(detail -> "RECORD_NOT_LOCKED".equals(detail.get("rejection"))), eq(caller),
                eq(context));
    }

    @Test
    void createRevisionWritesImmutableSnapshotWithSimulatedSignatureAndDiff() {
        AnesthesiaRecordEntity record = ongoingRecord();
        record.setLockedAt(NOW.minusSeconds(300));
        record.setLockedBy(ACTOR_ID);
        record.setLockSignatureHash("a".repeat(64));
        record.setLockCaStatus("SIMULATED");
        record.setStatus("LOCKED");
        record.setCurrentRevision(0);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(record);
        when(eventMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(drugMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(fluidMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(fluidMapper.selectTotals(RECORD_ID)).thenReturn(Map.of("intake", 500, "output", 100));
        when(revisionMapper.insertRevision(any())).thenAnswer(invocation -> {
            RecordRevisionEntity revision = invocation.getArgument(0);
            assertThat(revision.getRevisionNo()).isEqualTo(1);
            assertThat(revision.getPreviousRevisionNo()).isNull();
            assertThat(revision.getPreviousSignatureHash()).isNull();
            assertThat(revision.getReasonCode()).isEqualTo("ANESTHESIA_TYPE_CHANGE");
            assertThat(revision.getSnapshot()).contains("\"anesthesiaType\":\"COMBINED\"");
            return 1;
        });
        when(recordMapper.markRevision(RECORD_ID, 4L, 0, 1, NOW, ACTOR_ID)).thenReturn(1);

        RecordRevision revision = service.createRevision(RECORD_ID.toString(),
                revisionRequest("COMBINED"), caller, context);

        assertThat(revision.getRevisionNo()).isEqualTo(1);
        assertThat(revision.getCaStatus()).isEqualTo(RecordRevision.CaStatusEnum.SIMULATED);
        assertThat(revision.getSignatureRef()).startsWith("SIM-CA-");
        assertThat(revision.getSignatureHash()).matches("^[0-9a-f]{64}$");
        assertThat(revision.getSnapshot()).containsEntry("drugs", List.of());
        assertThat(revision.getDiff().getChangedFields()).hasSize(1);
        assertThat(revision.getDiff().getChangedFields().get(0).getField())
                .isEqualTo("record.anesthesiaType");
        assertThat(revision.getDiff().getChangedFields().get(0).getNewValue())
                .isEqualTo("COMBINED");
        verify(auditWriter).record(eq("RECORD_REVISE"), eq("ANESTHESIA_RECORD"),
                eq(RECORD_ID.toString()), eq(true), any(), eq(ACTOR_ID), eq("synth_doc"),
                eq(context));
        verify(auditWriter).record(eq("RECORD_SIGN"), eq("ANESTHESIA_RECORD"),
                eq(RECORD_ID.toString()), eq(true), any(), eq(ACTOR_ID), eq("synth_doc"),
                eq(context));
        ArgumentCaptor<RecordRevisionEntity> captor =
                ArgumentCaptor.forClass(RecordRevisionEntity.class);
        verify(revisionMapper).insertRevision(captor.capture());
        assertThat(captor.getValue().getSignatureHash()).isEqualTo(revision.getSignatureHash());
        assertThat(captor.getValue().getCaStatus()).isEqualTo("SIMULATED");
    }

    @Test
    void getRevisionReturnsNotFoundForUnknownVersion() {
        AnesthesiaRecordEntity record = ongoingRecord();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(revisionMapper.selectByRecordAndNo(RECORD_ID, 7)).thenReturn(null);

        assertThatThrownBy(() -> service.getRevision(RECORD_ID.toString(), "7", caller))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.getRevision(RECORD_ID.toString(), "abc", caller))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void lockStatusWhenLockedReportsSignatureAndCurrentRevision() {
        AnesthesiaRecordEntity locked = ongoingRecord();
        locked.setLockedAt(NOW.minusSeconds(600));
        locked.setLockedBy(ACTOR_ID);
        locked.setLockSignatureRef("SIM-CA-" + RECORD_ID);
        locked.setLockSignatureHash("a".repeat(64));
        locked.setLockCaStatus("SIMULATED");
        locked.setStatus("REVISED");
        locked.setCurrentRevision(2);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(locked);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(eventMapper.countByRecord(RECORD_ID)).thenReturn(3);
        when(threeWayCheckService.isThreeWayCheckCompleted(APPLICATION_ID.toString()))
                .thenReturn(true);
        when(consentService.listByApplication(APPLICATION_ID.toString(), caller))
                .thenReturn(consentList(ConsentRecord.StatusEnum.SIGNED));

        RecordLockStatus status = service.lockStatus(RECORD_ID.toString(), caller);

        assertThat(status.getLocked()).isTrue();
        assertThat(status.getCanLock()).isFalse();
        assertThat(status.getStatus()).isEqualTo(RecordLockStatus.StatusEnum.REVISED);
        assertThat(status.getCurrentRevision()).isEqualTo(2);
        assertThat(status.getLockedBy()).isEqualTo(ACTOR_ID.toString());
        assertThat(status.getCaStatus()).isEqualTo(RecordLockStatus.CaStatusEnum.SIMULATED);
        assertThat(status.getSignatureHash()).isEqualTo("a".repeat(64));
        assertThat(status.getPreconditions()).filteredOn(item -> !item.getSatisfied())
                .extracting(LockPrecondition::getCode)
                .containsExactly(LockPrecondition.CodeEnum.NOT_LOCKED);
    }

    @Test
    void revisionListAndDetailExposeSnapshotsAndVersionChainDiff() {
        AnesthesiaRecordEntity record = ongoingRecord();
        record.setStatus("REVISED");
        record.setCurrentRevision(2);
        record.setLockedAt(NOW.minusSeconds(600));
        record.setLockSignatureHash("a".repeat(64));
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        RecordRevisionEntity first = storedRevision(1, "COMBINED", null, "b".repeat(64));
        RecordRevisionEntity second = storedRevision(2, "COMBINED", "b".repeat(64), "c".repeat(64));
        when(revisionMapper.selectByRecord(RECORD_ID)).thenReturn(List.of(first, second));
        when(revisionMapper.selectByRecordAndNo(RECORD_ID, 1)).thenReturn(first);
        when(revisionMapper.selectByRecordAndNo(RECORD_ID, 2)).thenReturn(second);
        when(eventMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(drugMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(fluidMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(fluidMapper.selectTotals(RECORD_ID)).thenReturn(Map.of());

        assertThat(service.listRevisions(RECORD_ID.toString(), caller).getItems())
                .extracting(RecordRevisionSummary::getRevisionNo).containsExactly(1, 2);

        RecordRevision detail = service.getRevision(RECORD_ID.toString(), "2", caller);
        assertThat(detail.getSignatureHash()).isEqualTo("c".repeat(64));
        assertThat(detail.getDiff().getPreviousRevisionNo()).isEqualTo(1);
        assertThat(detail.getSnapshot()).containsEntry("record", Map.of(
                "anesthesiaType", "COMBINED",
                "startedAt", NOW.minusSeconds(3600)
                        .atOffset(ZoneOffset.ofHours(8)).toString(),
                "endedAt", NOW.minusSeconds(300).atOffset(ZoneOffset.ofHours(8)).toString()));

        RecordRevision firstDetail = service.getRevision(RECORD_ID.toString(), "1", caller);
        assertThat(firstDetail.getDiff().getPreviousRevisionNo()).isZero();
        assertThat(firstDetail.getDiff().getChangedFields()).hasSize(1);
    }

    @Test
    void createRevisionRejectsNullBodyMissingReasonCodeAndStaleVersion() {
        assertThatThrownBy(() -> service.createRevision(RECORD_ID.toString(), null, caller, context))
                .isInstanceOf(M2ValidationException.class);
        CreateRevisionRequest noCode = revisionRequest(null);
        noCode.setReasonCode(null);
        assertThatThrownBy(() -> service.createRevision(RECORD_ID.toString(), noCode, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .satisfies(failure -> assertThat(((M2ValidationException) failure).field())
                        .isEqualTo("reasonCode"));

        AnesthesiaRecordEntity record = ongoingRecord();
        record.setLockedAt(NOW.minusSeconds(300));
        record.setLockSignatureHash("a".repeat(64));
        record.setStatus("LOCKED");
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(record);
        CreateRevisionRequest stale = revisionRequest(null);
        stale.setVersion(2);
        assertThatThrownBy(() -> service.createRevision(RECORD_ID.toString(), stale, caller, context))
                .isInstanceOf(M2ConflictException.class);
        verify(revisionMapper, never()).insertRevision(any());
    }

    @Test
    void createRevisionCarriesForwardPreviousCorrectionWithoutRevertingBase() {
        AnesthesiaRecordEntity record = ongoingRecord();
        record.setLockedAt(NOW.minusSeconds(300));
        record.setLockSignatureHash("a".repeat(64));
        record.setStatus("REVISED");
        record.setCurrentRevision(1);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(record);
        RecordRevisionEntity previous = storedRevision(1, "COMBINED", null, "b".repeat(64));
        when(revisionMapper.selectByRecordAndNo(RECORD_ID, 1)).thenReturn(previous);
        when(eventMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(drugMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(fluidMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(fluidMapper.selectTotals(RECORD_ID)).thenReturn(Map.of());
        when(revisionMapper.insertRevision(any())).thenAnswer(invocation -> {
            RecordRevisionEntity revision = invocation.getArgument(0);
            assertThat(revision.getRevisionNo()).isEqualTo(2);
            assertThat(revision.getPreviousRevisionNo()).isEqualTo(1);
            assertThat(revision.getPreviousSignatureHash()).isEqualTo("b".repeat(64));
            assertThat(revision.getSnapshot()).contains("\"anesthesiaType\":\"COMBINED\"");
            return 1;
        });
        when(recordMapper.markRevision(RECORD_ID, 4L, 1, 2, NOW, ACTOR_ID)).thenReturn(1);

        CreateRevisionRequest request = revisionRequest(null);
        request.setReason("补充说明：用药已核对（演示）");
        request.setChanges("仅补充修订说明（演示）");
        request.setReasonCode(CreateRevisionRequest.ReasonCodeEnum.OTHER);
        RecordRevision revision = service.createRevision(RECORD_ID.toString(), request, caller,
                context);

        assertThat(revision.getRevisionNo()).isEqualTo(2);
        assertThat(revision.getDiff().getPreviousRevisionNo()).isEqualTo(1);
        assertThat(revision.getDiff().getChangedFields()).isEmpty();
        assertThat(revision.getDiff().getSummary()).isEqualTo("无内容差异（仅修订说明）");
    }

    @Test
    void lockRejectsMissingRequestBodyAndVersion() {
        assertThatThrownBy(() -> service.lock(RECORD_ID.toString(), null, caller, context))
                .isInstanceOf(M2ValidationException.class);
        LockRecordRequest noVersion = lockRequest(4);
        noVersion.setVersion(null);
        assertThatThrownBy(() -> service.lock(RECORD_ID.toString(), noVersion, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .satisfies(failure -> assertThat(((M2ValidationException) failure).field())
                        .isEqualTo("version"));
    }

    private RecordRevisionEntity storedRevision(int revisionNo, String anesthesiaType,
            String previousHash, String signatureHash) {
        RecordRevisionEntity revision = new RecordRevisionEntity();
        revision.setId(UUID.randomUUID());
        revision.setRecordId(RECORD_ID);
        revision.setRevisionNo(revisionNo);
        revision.setReasonCode("ANESTHESIA_TYPE_CHANGE");
        revision.setReason("锁单后发现麻醉方式记录笔误（演示）");
        revision.setChanges("将麻醉方式由 GENERAL 更正为 COMBINED（演示）");
        revision.setSnapshot("{\"record\":{\"anesthesiaType\":\"" + anesthesiaType
                + "\",\"startedAt\":\"" + NOW.minusSeconds(3600)
                        .atOffset(ZoneOffset.ofHours(8)) + "\",\"endedAt\":\""
                + NOW.minusSeconds(300).atOffset(ZoneOffset.ofHours(8))
                + "\"},\"events\":[],\"drugs\":[],\"fluids\":[],"
                + "\"ioSummary\":{\"intakeMl\":0,\"outputMl\":0,\"balanceMl\":0,"
                + "\"fluidCount\":0},"
                + "\"signatures\":{\"lockedSignatureHash\":\"" + "a".repeat(64)
                + "\",\"previousRevisionSignatureHash\":"
                + (previousHash == null ? "null" : "\"" + previousHash + "\"") + "}}");
        revision.setPreviousRevisionNo(revisionNo == 1 ? null : revisionNo - 1);
        revision.setPreviousSignatureHash(previousHash);
        revision.setSignatureRef("SIM-CA-" + revision.getId());
        revision.setSignatureHash(signatureHash);
        revision.setCaStatus("SIMULATED");
        revision.setCreatedAt(NOW);
        revision.setCreatedBy(ACTOR_ID);
        return revision;
    }

    private void stubLockableRecord() {
        AnesthesiaRecordEntity record = ongoingRecord();
        when(recordMapper.selectById(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(record);
        when(eventMapper.countByRecord(RECORD_ID)).thenReturn(2);
        when(eventMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(drugMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(fluidMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());
        when(fluidMapper.selectTotals(RECORD_ID)).thenReturn(Map.of());
        when(threeWayCheckService.isThreeWayCheckCompleted(APPLICATION_ID.toString()))
                .thenReturn(true);
        when(consentService.listByApplication(APPLICATION_ID.toString(), caller))
                .thenReturn(consentList(ConsentRecord.StatusEnum.SIGNED));
    }

    private static AnesthesiaRecordEntity ongoingRecord() {
        AnesthesiaRecordEntity record = new AnesthesiaRecordEntity();
        record.setId(RECORD_ID);
        record.setScheduleId(SCHEDULE_ID);
        record.setApplicationId(APPLICATION_ID);
        record.setPatientId(UUID.fromString("50000000-0000-4000-8000-000000000001"));
        record.setStatus("ONGOING");
        record.setAnesthesiaType("GENERAL");
        record.setStartedAt(NOW.minusSeconds(3600));
        record.setEndedAt(NOW.minusSeconds(300));
        record.setCreatedAt(NOW.minusSeconds(4000));
        record.setUpdatedAt(NOW.minusSeconds(300));
        record.setVersion(4L);
        record.setCurrentRevision(0);
        record.setDeleted(false);
        return record;
    }

    private static ConsentRecordList consentList(ConsentRecord.StatusEnum status) {
        ConsentRecord consent = new ConsentRecord();
        consent.setConsentId(UUID.randomUUID().toString());
        consent.setStatus(status);
        ConsentRecordList list = new ConsentRecordList();
        list.setItems(List.of(consent));
        return list;
    }

    private static LockRecordRequest lockRequest(int version) {
        LockRecordRequest request = new LockRecordRequest();
        request.setVersion(version);
        request.setSignatureRequested(true);
        request.setConfirmChecklist(true);
        return request;
    }

    private static CreateRevisionRequest revisionRequest(String anesthesiaType) {
        CreateRevisionRequest request = new CreateRevisionRequest();
        request.setReasonCode(CreateRevisionRequest.ReasonCodeEnum.ANESTHESIA_TYPE_CHANGE);
        request.setReason("锁单后发现麻醉方式记录笔误（演示）");
        request.setChanges("将麻醉方式由 GENERAL 更正为 COMBINED（演示）");
        if (anesthesiaType != null) {
            RevisionCorrections corrections = new RevisionCorrections();
            corrections.setAnesthesiaType(
                    RevisionCorrections.AnesthesiaTypeEnum.fromValue(anesthesiaType));
            request.setCorrections(corrections);
        }
        return request;
    }
}
