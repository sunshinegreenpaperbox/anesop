package com.anesop.m2.impl.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.anesthesia.model.CreatePostopFollowupRequest;
import com.anesop.contracts.anesthesia.model.FollowupComplication;
import com.anesop.contracts.anesthesia.model.FollowupType;
import com.anesop.contracts.anesthesia.model.PainAdjustment;
import com.anesop.contracts.anesthesia.model.PainManagement;
import com.anesop.contracts.anesthesia.model.PainManagementEffect;
import com.anesop.contracts.anesthesia.model.PainManagementList;
import com.anesop.contracts.anesthesia.model.PainManagementModality;
import com.anesop.contracts.anesthesia.model.PostopFollowup;
import com.anesop.contracts.anesthesia.model.PostopFollowupList;
import com.anesop.contracts.anesthesia.model.RecordPainManagementRequest;
import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.config.RecordProperties;
import com.anesop.m2.impl.persistence.entity.AnesthesiaRecordEntity;
import com.anesop.m2.impl.persistence.entity.FollowupRecordEntity;
import com.anesop.m2.impl.persistence.entity.PainManagementEntity;
import com.anesop.m2.impl.persistence.mapper.AnesthesiaRecordMapper;
import com.anesop.m2.impl.persistence.mapper.FollowupRecordMapper;
import com.anesop.m2.impl.persistence.mapper.PainManagementMapper;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 术后随访与镇痛服务单测（M3-M11-01）：记录单未结束 409、随访时间窗与同类型时间单调 400、
 * NRS/PONV 边界、并发症白名单与去重、自由文本脱敏（审计无原文）、镇痛参数物理校验、
 * append-only 语义（不调用更新）、数据范围 403/404/422 与审计。
 * Mock 掉 Mapper / m1-api / m0-api；数据库触发器（append-only、结束前置守卫）与权限由
 * 集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class FollowupServiceImplTest {

    private static final UUID FOLLOWUP_ID =
            UUID.fromString("1c000000-0000-4000-8000-000000000001");
    private static final UUID PAIN_ID = UUID.fromString("1d000000-0000-4000-8000-000000000001");
    private static final UUID RECORD_ID = UUID.fromString("12000000-0000-4000-8000-000000000001");
    private static final UUID SCHEDULE_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID PATIENT_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-12-02T03:00:00Z");
    private static final Instant ENDED_AT = NOW.minusSeconds(7200);
    /** 合成手机号（仅用于验证脱敏，非真实号码）。 */
    private static final String SYNTH_MOBILE = "13800138000";
    /** 合成 18 位身份证号形似串（仅用于验证脱敏）。 */
    private static final String SYNTH_ID_CARD = "110101199003074567";

    @Mock
    private FollowupRecordMapper followupMapper;
    @Mock
    private PainManagementMapper painManagementMapper;
    @Mock
    private AnesthesiaRecordMapper recordMapper;
    @Mock
    private SchedulingService schedulingService;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private ClinicalFailureAuditor failureAuditor;

    private FollowupServiceImpl service;
    private AuthPrincipal caller;
    private AuthRequestContext context;

    @BeforeEach
    void setUp() {
        service = new FollowupServiceImpl(followupMapper, painManagementMapper, recordMapper,
                schedulingService, auditWriter, failureAuditor,
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(NOW, ZoneOffset.UTC),
                new RecordProperties(600));
        caller = new AuthPrincipal(ACTOR_ID, "synth_followup", "jti", NOW.plusSeconds(900), "sid");
        context = new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE");
    }

    // --------------------------------------------------------------- 随访 --

    @Test
    void createPersistsMaskedFollowupAndAuditsWithoutFreeText() {
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        CreatePostopFollowupRequest request = followupRequest(FollowupType.H24,
                NOW.minusSeconds(1800));
        request.setNrsScore(3);
        request.setPonvScore(1);
        request.setComplications(List.of(FollowupComplication.PONV,
                FollowupComplication.PAIN_INADEQUATE));
        request.setComplicationNotes("演示：恶心，联系电话 " + SYNTH_MOBILE + "（合成）");
        request.setPatientFeedback("演示：身份证 " + SYNTH_ID_CARD + "，疼痛可耐受（合成）");
        request.setFollowedUpBy("  演示随访护士（合成）  ");

        PostopFollowup result = service.create(request, caller, context);

        ArgumentCaptor<FollowupRecordEntity> entityCaptor =
                ArgumentCaptor.forClass(FollowupRecordEntity.class);
        verify(followupMapper).insertFollowup(entityCaptor.capture());
        FollowupRecordEntity saved = entityCaptor.getValue();
        assertThat(saved.getPatientId()).isEqualTo(PATIENT_ID);
        assertThat(saved.getFollowupType()).isEqualTo("H24");
        assertThat(saved.getAssessedAt()).isEqualTo(NOW.minusSeconds(1800));
        assertThat(saved.getNrsScore()).isEqualTo(3);
        assertThat(saved.getPonvScore()).isEqualTo(1);
        assertThat(saved.getComplications()).isEqualTo("[\"PONV\",\"PAIN_INADEQUATE\"]");
        // 脱敏：手机号中段与身份证中段遮蔽；随访人 trim。
        assertThat(saved.getComplicationNotes()).isEqualTo(
                "演示：恶心，联系电话 138****8000（合成）");
        assertThat(saved.getPatientFeedback()).isEqualTo(
                "演示：身份证 110101********4567，疼痛可耐受（合成）");
        assertThat(saved.getFollowedUpBy()).isEqualTo("演示随访护士（合成）");
        assertThat(saved.getVersion()).isEqualTo(1L);

        assertThat(result.getFollowupId()).isEqualTo(saved.getId().toString());
        assertThat(result.getRecordId()).isEqualTo(RECORD_ID.toString());
        assertThat(result.getFollowupType()).isEqualTo(FollowupType.H24);
        assertThat(result.getComplications()).containsExactly(FollowupComplication.PONV,
                FollowupComplication.PAIN_INADEQUATE);
        assertThat(result.getPatientFeedback()).doesNotContain(SYNTH_ID_CARD);

        // 审计：成功 result=1，detail 只含结构化字段，不含手机号/身份证号/文本原文。
        ArgumentCaptor<Map<String, Object>> detailCaptor = auditDetailCaptor();
        verify(auditWriter).record(eq("FOLLOWUP_CREATE"), eq("FOLLOWUP_RECORD"),
                eq(saved.getId().toString()), eq(true), detailCaptor.capture(),
                eq(ACTOR_ID), eq("synth_followup"), eq(context));
        Map<String, Object> detail = detailCaptor.getValue();
        assertThat(detail).containsEntry("followupType", "H24")
                .containsEntry("nrsScore", 3)
                .containsEntry("ponvScore", 1)
                .containsEntry("hasComplicationNotes", true)
                .containsEntry("hasPatientFeedback", true);
        String detailText = detail.values().toString();
        assertThat(detailText).doesNotContain(SYNTH_MOBILE, SYNTH_ID_CARD, "疼痛可耐受",
                "演示随访护士");
    }

    @Test
    void createRejectsRecordNotEndedWithConflictAndFailureAudit() {
        AnesthesiaRecordEntity record = endedRecord();
        record.setEndedAt(null);
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(record);
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        assertThatThrownBy(() -> service.create(followupRequest(FollowupType.H24, NOW), caller,
                context))
                .isInstanceOf(M2ConflictException.class)
                .hasMessageContaining("尚未结束");

        verify(followupMapper, never()).insertFollowup(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailCaptor = ArgumentCaptor.forClass(Map.class);
        verify(failureAuditor).recordFailure(eq("FOLLOWUP_CREATE"), eq("FOLLOWUP_RECORD"),
                eq(RECORD_ID.toString()), detailCaptor.capture(), eq(caller), eq(context));
        assertThat(detailCaptor.getValue()).containsEntry("rejection", "RECORD_NOT_ENDED");
    }

    @Test
    void createRejectsInvisibleRecordAsUnprocessable() {
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller))
                .thenThrow(new NoSuchElementException("不可见"));

        assertThatThrownBy(() -> service.create(followupRequest(FollowupType.H24, NOW), caller,
                context))
                .isInstanceOf(M2UnprocessableException.class);
    }

    @Test
    void createRejectsAssessedAtBeforeRecordEndAndFutureTime() {
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        assertThatThrownBy(() -> service.create(
                followupRequest(FollowupType.H24, ENDED_AT.minusSeconds(1)), caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("不得早于麻醉结束时间");
        assertThatThrownBy(() -> service.create(
                followupRequest(FollowupType.H24, NOW.plusSeconds(601)), caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("不得晚于服务端当前时间");
    }

    @Test
    void createEnforcesMonotonicTimePerFollowupType() {
        FollowupRecordEntity previous = persistedFollowup(FollowupType.H48,
                NOW.minusSeconds(600));
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(followupMapper.selectLatestByRecordAndType(RECORD_ID, "H48")).thenReturn(previous);

        assertThatThrownBy(() -> service.create(
                followupRequest(FollowupType.H48, NOW.minusSeconds(601)), caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("时间序列只增");
        verify(followupMapper, never()).insertFollowup(any());
    }

    @Test
    void createAllowsRepeatedSameTypeAndEqualAssessedAt() {
        FollowupRecordEntity previous = persistedFollowup(FollowupType.H24,
                NOW.minusSeconds(600));
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(followupMapper.selectLatestByRecordAndType(RECORD_ID, "H24")).thenReturn(previous);

        // 同刻允许（时间单调不减），同类型第二次随访可追加。
        PostopFollowup result = service.create(followupRequest(FollowupType.H24,
                NOW.minusSeconds(600)), caller, context);

        verify(followupMapper).insertFollowup(any(FollowupRecordEntity.class));
        assertThat(result.getFollowupType()).isEqualTo(FollowupType.H24);
    }

    @Test
    void createAcceptsNrsAndPonvBoundaryValues() {
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        CreatePostopFollowupRequest low = followupRequest(FollowupType.H24, NOW.minusSeconds(60));
        low.setNrsScore(0);
        low.setPonvScore(0);
        PostopFollowup lowResult = service.create(low, caller, context);
        assertThat(lowResult.getNrsScore()).isZero();
        assertThat(lowResult.getPonvScore()).isZero();

        CreatePostopFollowupRequest high = followupRequest(FollowupType.H24,
                NOW.minusSeconds(30));
        high.setNrsScore(10);
        high.setPonvScore(3);
        PostopFollowup highResult = service.create(high, caller, context);
        assertThat(highResult.getNrsScore()).isEqualTo(10);
        assertThat(highResult.getPonvScore()).isEqualTo(3);
    }

    @Test
    void createRejectsScoresOutOfRange() {
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        CreatePostopFollowupRequest nrsTooHigh = followupRequest(FollowupType.H24,
                NOW.minusSeconds(60));
        nrsTooHigh.setNrsScore(11);
        assertThatThrownBy(() -> service.create(nrsTooHigh, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("0–10");

        CreatePostopFollowupRequest negative = followupRequest(FollowupType.H24,
                NOW.minusSeconds(60));
        negative.setNrsScore(-1);
        assertThatThrownBy(() -> service.create(negative, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("0–10");

        CreatePostopFollowupRequest ponvTooHigh = followupRequest(FollowupType.H24,
                NOW.minusSeconds(60));
        ponvTooHigh.setPonvScore(4);
        assertThatThrownBy(() -> service.create(ponvTooHigh, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("0–3");

        CreatePostopFollowupRequest missing = followupRequest(FollowupType.H24,
                NOW.minusSeconds(60));
        missing.setNrsScore(null);
        assertThatThrownBy(() -> service.create(missing, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void createDeduplicatesComplicationsAndRejectsTooMany() {
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        CreatePostopFollowupRequest duplicates = followupRequest(FollowupType.H48,
                NOW.minusSeconds(60));
        duplicates.setComplications(List.of(FollowupComplication.PONV,
                FollowupComplication.PONV, FollowupComplication.OTHER));
        PostopFollowup result = service.create(duplicates, caller, context);
        assertThat(result.getComplications()).containsExactly(FollowupComplication.PONV,
                FollowupComplication.OTHER);

        CreatePostopFollowupRequest tooMany = followupRequest(FollowupType.H48,
                NOW.minusSeconds(30));
        List<FollowupComplication> repeated = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            repeated.add(i % 2 == 0 ? FollowupComplication.PONV
                    : FollowupComplication.PAIN_INADEQUATE);
        }
        tooMany.setComplications(repeated);
        assertThatThrownBy(() -> service.create(tooMany, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("最多");
    }

    @Test
    void createRejectsBlankOrOverlongFollowedUpByAndText() {
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        CreatePostopFollowupRequest blank = followupRequest(FollowupType.H24,
                NOW.minusSeconds(60));
        blank.setFollowedUpBy("   ");
        assertThatThrownBy(() -> service.create(blank, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("随访人不能为空");

        CreatePostopFollowupRequest longName = followupRequest(FollowupType.H24,
                NOW.minusSeconds(60));
        longName.setFollowedUpBy("随".repeat(65));
        assertThatThrownBy(() -> service.create(longName, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("≤64");

        CreatePostopFollowupRequest longNotes = followupRequest(FollowupType.H24,
                NOW.minusSeconds(60));
        longNotes.setComplicationNotes("备".repeat(501));
        assertThatThrownBy(() -> service.create(longNotes, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("≤500");

        CreatePostopFollowupRequest longFeedback = followupRequest(FollowupType.H24,
                NOW.minusSeconds(60));
        longFeedback.setPatientFeedback("反".repeat(501));
        assertThatThrownBy(() -> service.create(longFeedback, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("≤500");
    }

    @Test
    void createRejectsMissingTypeAndInvalidRecordId() {
        when(recordMapper.selectByIdForUpdate(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        CreatePostopFollowupRequest noType = followupRequest(FollowupType.H24,
                NOW.minusSeconds(60));
        noType.setFollowupType(null);
        assertThatThrownBy(() -> service.create(noType, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("H24/H48");

        CreatePostopFollowupRequest badId = followupRequest(FollowupType.H24,
                NOW.minusSeconds(60));
        badId.setAnesthesiaRecordId("not-a-uuid");
        assertThatThrownBy(() -> service.create(badId, caller, context))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("格式不正确");

        assertThatThrownBy(() -> service.create(null, caller, context))
                .isInstanceOf(M2ValidationException.class);
    }

    @Test
    void listByRecordRejectsInvalidIdAndInvisibleRecord() {
        assertThatThrownBy(() -> service.listByRecord("not-a-uuid", caller))
                .isInstanceOf(M2ValidationException.class);

        when(recordMapper.selectById(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller))
                .thenThrow(new NoSuchElementException("不可见"));
        assertThatThrownBy(() -> service.listByRecord(RECORD_ID.toString(), caller))
                .isInstanceOf(AccessDeniedException.class);

        when(recordMapper.selectById(RECORD_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.listByRecord(RECORD_ID.toString(), caller))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listByRecordMapsRowsAndReturnsEmptyWhenNone() {
        when(recordMapper.selectById(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        when(followupMapper.selectByRecord(RECORD_ID)).thenReturn(List.of());

        PostopFollowupList empty = service.listByRecord(RECORD_ID.toString(), caller);
        assertThat(empty.getItems()).isEmpty();

        FollowupRecordEntity first = persistedFollowup(FollowupType.H48, NOW.minusSeconds(3600));
        FollowupRecordEntity second = persistedFollowup(FollowupType.H24, NOW.minusSeconds(60));
        when(followupMapper.selectByRecord(RECORD_ID)).thenReturn(List.of(second, first));
        PostopFollowupList list = service.listByRecord(RECORD_ID.toString(), caller);
        assertThat(list.getItems()).extracting(PostopFollowup::getFollowupType)
                .containsExactly(FollowupType.H24, FollowupType.H48);
    }

    @Test
    void getReturnsDetailAndHidesNotFound() {
        FollowupRecordEntity entity = persistedFollowup(FollowupType.H24, NOW.minusSeconds(60));
        when(followupMapper.selectById(FOLLOWUP_ID)).thenReturn(entity);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        PostopFollowup dto = service.get(FOLLOWUP_ID.toString(), caller);
        assertThat(dto.getFollowupId()).isEqualTo(FOLLOWUP_ID.toString());
        assertThat(dto.getPatientId()).isEqualTo(PATIENT_ID.toString());

        assertThatThrownBy(() -> service.get("not-a-uuid", caller))
                .isInstanceOf(NoSuchElementException.class);
        when(followupMapper.selectById(FOLLOWUP_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.get(FOLLOWUP_ID.toString(), caller))
                .isInstanceOf(NoSuchElementException.class);

        when(followupMapper.selectById(FOLLOWUP_ID)).thenReturn(entity);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller))
                .thenThrow(new NoSuchElementException("不可见"));
        assertThatThrownBy(() -> service.get(FOLLOWUP_ID.toString(), caller))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --------------------------------------------------------------- 镇痛 --

    @Test
    void recordPainManagementPersistsMaskedAdjustmentsAndAuditsWithoutText() {
        FollowupRecordEntity followup = persistedFollowup(FollowupType.H24, NOW.minusSeconds(600));
        when(followupMapper.selectById(FOLLOWUP_ID)).thenReturn(followup);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());

        RecordPainManagementRequest request = painRequest();
        request.setDrug("演示舒芬太尼方案 138 0013 8000（合成）");
        request.setAdjustments(List.of(adjustment(NOW.minusSeconds(60),
                "演示：追加 bolus 1 次，联系 " + SYNTH_MOBILE + "（合成）")));

        PainManagement result = service.recordPainManagement(FOLLOWUP_ID.toString(), request,
                caller, context);

        ArgumentCaptor<PainManagementEntity> entityCaptor =
                ArgumentCaptor.forClass(PainManagementEntity.class);
        verify(painManagementMapper).insertPainManagement(entityCaptor.capture());
        PainManagementEntity saved = entityCaptor.getValue();
        assertThat(saved.getFollowupId()).isEqualTo(FOLLOWUP_ID);
        assertThat(saved.getModality()).isEqualTo("PCIA");
        assertThat(saved.getDrug()).isEqualTo("演示舒芬太尼方案 138****8000（合成）");
        assertThat(saved.getBolusDose()).isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(saved.getBackgroundDose()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(saved.getLockoutMinutes()).isEqualTo(15);
        assertThat(saved.getEffect()).isEqualTo("PARTIAL");
        assertThat(saved.getAdjustments()).contains("138****8000").doesNotContain(SYNTH_MOBILE);

        assertThat(result.getPainManagementId()).isEqualTo(saved.getId().toString());
        assertThat(result.getRecordId()).isEqualTo(RECORD_ID.toString());
        assertThat(result.getDrug()).isEqualTo("演示舒芬太尼方案 138****8000（合成）");
        assertThat(result.getAdjustments()).hasSize(1);
        assertThat(result.getAdjustments().get(0).getDescription())
                .contains("138****8000").doesNotContain(SYNTH_MOBILE);

        ArgumentCaptor<Map<String, Object>> detailCaptor = auditDetailCaptor();
        verify(auditWriter).record(eq("PAIN_MANAGE"), eq("PAIN_MANAGEMENT"),
                eq(saved.getId().toString()), eq(true), detailCaptor.capture(),
                eq(ACTOR_ID), eq("synth_followup"), eq(context));
        Map<String, Object> detail = detailCaptor.getValue();
        assertThat(detail).containsEntry("modality", "PCIA")
                .containsEntry("lockoutMinutes", 15)
                .containsEntry("adjustmentCount", 1);
        assertThat(detail.values().toString()).doesNotContain(SYNTH_MOBILE, "追加 bolus",
                "138 0013 8000");
    }

    @Test
    void recordPainManagementRejectsInvalidDosesAndLockout() {
        stubVisibleFollowup();

        RecordPainManagementRequest zeroBolus = painRequest();
        zeroBolus.setBolusDose(0.0);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), zeroBolus,
                caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("bolus");

        RecordPainManagementRequest negativeBolus = painRequest();
        negativeBolus.setBolusDose(-1.0);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(),
                negativeBolus, caller, context)).isInstanceOf(M2ValidationException.class);

        RecordPainManagementRequest nullBolus = painRequest();
        nullBolus.setBolusDose(null);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), nullBolus,
                caller, context)).isInstanceOf(M2ValidationException.class);

        RecordPainManagementRequest negativeBackground = painRequest();
        negativeBackground.setBackgroundDose(-0.01);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(),
                negativeBackground, caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("background");

        RecordPainManagementRequest nullBackground = painRequest();
        nullBackground.setBackgroundDose(null);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(),
                nullBackground, caller, context)).isInstanceOf(M2ValidationException.class);

        RecordPainManagementRequest zeroLockout = painRequest();
        zeroLockout.setLockoutMinutes(0);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(),
                zeroLockout, caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("lockout");

        RecordPainManagementRequest nullLockout = painRequest();
        nullLockout.setLockoutMinutes(null);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(),
                nullLockout, caller, context)).isInstanceOf(M2ValidationException.class);

        verify(painManagementMapper, never()).insertPainManagement(any());
    }

    @Test
    void recordPainManagementRejectsMissingFieldsAndOverlongDrug() {
        stubVisibleFollowup();

        RecordPainManagementRequest noModality = painRequest();
        noModality.setModality(null);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), noModality,
                caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("镇痛方式");

        RecordPainManagementRequest noEffect = painRequest();
        noEffect.setEffect(null);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), noEffect,
                caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("镇痛效果");

        RecordPainManagementRequest blankDrug = painRequest();
        blankDrug.setDrug("  ");
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), blankDrug,
                caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("药物名称");

        RecordPainManagementRequest longDrug = painRequest();
        longDrug.setDrug("药".repeat(129));
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), longDrug,
                caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("≤128");

        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), null,
                caller, context)).isInstanceOf(M2ValidationException.class);
    }

    @Test
    void recordPainManagementValidatesAdjustments() {
        stubVisibleFollowup();

        RecordPainManagementRequest tooMany = painRequest();
        List<PainAdjustment> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add(adjustment(NOW.minusSeconds(i + 1L), "演示调整 " + i + "（合成）"));
        }
        tooMany.setAdjustments(many);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), tooMany,
                caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("最多");

        RecordPainManagementRequest noTime = painRequest();
        noTime.setAdjustments(List.of(adjustment(null, "演示调整（合成）")));
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), noTime,
                caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("调整时间");

        RecordPainManagementRequest future = painRequest();
        future.setAdjustments(List.of(adjustment(NOW.plusSeconds(601), "演示调整（合成）")));
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), future,
                caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("不得晚于服务端当前时间");

        RecordPainManagementRequest blank = painRequest();
        blank.setAdjustments(List.of(adjustment(NOW, "  ")));
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), blank,
                caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("调整说明");

        RecordPainManagementRequest longText = painRequest();
        longText.setAdjustments(List.of(adjustment(NOW, "调".repeat(201))));
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), longText,
                caller, context)).isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("≤200");
    }

    @Test
    void recordPainManagementHidesMissingOrInvisibleFollowup() {
        when(followupMapper.selectById(FOLLOWUP_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), painRequest(),
                caller, context)).isInstanceOf(NoSuchElementException.class);

        FollowupRecordEntity followup = persistedFollowup(FollowupType.H24, NOW.minusSeconds(600));
        when(followupMapper.selectById(FOLLOWUP_ID)).thenReturn(followup);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller))
                .thenThrow(new NoSuchElementException("不可见"));
        assertThatThrownBy(() -> service.recordPainManagement(FOLLOWUP_ID.toString(), painRequest(),
                caller, context)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.listPainManagement("not-a-uuid", caller))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void listPainManagementMapsRowsInOrder() {
        FollowupRecordEntity followup = persistedFollowup(FollowupType.H48, NOW.minusSeconds(600));
        when(followupMapper.selectById(FOLLOWUP_ID)).thenReturn(followup);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
        PainManagementEntity first = persistedPain(NOW.minusSeconds(120), "PCIA");
        PainManagementEntity second = persistedPain(NOW.minusSeconds(60), "ORAL");
        when(painManagementMapper.selectByFollowup(FOLLOWUP_ID)).thenReturn(List.of(first, second));

        PainManagementList list = service.listPainManagement(FOLLOWUP_ID.toString(), caller);

        assertThat(list.getItems()).extracting(PainManagement::getModality)
                .containsExactly(PainManagementModality.PCIA, PainManagementModality.ORAL);
        assertThat(list.getItems().get(0).getRecordId()).isEqualTo(RECORD_ID.toString());
        assertThat(list.getItems().get(0).getEffect()).isEqualTo(PainManagementEffect.PARTIAL);
    }

    // ------------------------------------------------------------- helpers --

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> auditDetailCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private void stubVisibleFollowup() {
        FollowupRecordEntity followup = persistedFollowup(FollowupType.H24, NOW.minusSeconds(600));
        when(followupMapper.selectById(FOLLOWUP_ID)).thenReturn(followup);
        when(recordMapper.selectById(RECORD_ID)).thenReturn(endedRecord());
        when(schedulingService.get(SCHEDULE_ID.toString(), caller)).thenReturn(new Schedule());
    }

    private static AnesthesiaRecordEntity endedRecord() {
        AnesthesiaRecordEntity record = new AnesthesiaRecordEntity();
        record.setId(RECORD_ID);
        record.setScheduleId(SCHEDULE_ID);
        record.setPatientId(PATIENT_ID);
        record.setStatus("REVISED");
        record.setStartedAt(ENDED_AT.minusSeconds(3600));
        record.setEndedAt(ENDED_AT);
        return record;
    }

    private static CreatePostopFollowupRequest followupRequest(FollowupType type,
            Instant assessedAt) {
        CreatePostopFollowupRequest request = new CreatePostopFollowupRequest();
        request.setAnesthesiaRecordId(RECORD_ID.toString());
        request.setFollowupType(type);
        request.setAssessedAt(assessedAt.atOffset(ZoneOffset.ofHours(8)));
        request.setNrsScore(2);
        request.setPonvScore(1);
        request.setComplications(new ArrayList<>(List.of(FollowupComplication.PONV)));
        request.setFollowedUpBy("演示随访护士（合成）");
        return request;
    }

    private static FollowupRecordEntity persistedFollowup(FollowupType type, Instant assessedAt) {
        FollowupRecordEntity entity = new FollowupRecordEntity();
        entity.setId(FOLLOWUP_ID);
        entity.setAnesthesiaRecordId(RECORD_ID);
        entity.setPatientId(PATIENT_ID);
        entity.setFollowupType(type.getValue());
        entity.setAssessedAt(assessedAt);
        entity.setNrsScore(2);
        entity.setPonvScore(1);
        entity.setComplications("[\"PONV\"]");
        entity.setFollowedUpBy("演示随访护士（合成）");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR_ID);
        entity.setVersion(1L);
        return entity;
    }

    private static RecordPainManagementRequest painRequest() {
        RecordPainManagementRequest request = new RecordPainManagementRequest();
        request.setModality(PainManagementModality.PCIA);
        request.setDrug("演示舒芬太尼方案（合成数据）");
        request.setBolusDose(2.0);
        request.setBackgroundDose(1.0);
        request.setLockoutMinutes(15);
        request.setEffect(PainManagementEffect.PARTIAL);
        request.setAdjustments(new ArrayList<>(List.of(adjustment(NOW.minusSeconds(60),
                "演示：追加 bolus 1 次（合成）"))));
        return request;
    }

    private static PainAdjustment adjustment(Instant adjustedAt, String description) {
        PainAdjustment adjustment = new PainAdjustment();
        adjustment.setAdjustedAt(adjustedAt == null ? null
                : OffsetDateTime.ofInstant(adjustedAt, ZoneOffset.ofHours(8)));
        adjustment.setDescription(description);
        return adjustment;
    }

    private static PainManagementEntity persistedPain(Instant createdAt, String modality) {
        PainManagementEntity entity = new PainManagementEntity();
        entity.setId(PAIN_ID);
        entity.setFollowupId(FOLLOWUP_ID);
        entity.setModality(modality);
        entity.setDrug("演示镇痛药（合成数据）");
        entity.setBolusDose(new BigDecimal("2.00"));
        entity.setBackgroundDose(new BigDecimal("1.00"));
        entity.setLockoutMinutes(15);
        entity.setEffect("PARTIAL");
        entity.setAdjustments("[]");
        entity.setCreatedAt(createdAt);
        entity.setCreatedBy(ACTOR_ID);
        entity.setVersion(1L);
        return entity;
    }
}
