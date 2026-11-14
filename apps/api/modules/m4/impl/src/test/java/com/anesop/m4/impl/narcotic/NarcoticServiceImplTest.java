package com.anesop.m4.impl.narcotic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.drug.model.CabinetHandover;
import com.anesop.contracts.drug.model.CabinetHandoverItem;
import com.anesop.contracts.drug.model.CreateCabinetHandoverRequest;
import com.anesop.contracts.drug.model.CreateCabinetHandoverRequestItemsInner;
import com.anesop.contracts.drug.model.CreateDailyCloseRequest;
import com.anesop.contracts.drug.model.CreateNarcoticRegisterRequest;
import com.anesop.contracts.drug.model.DailyClose;
import com.anesop.contracts.drug.model.DrugBaselineList;
import com.anesop.contracts.drug.model.NarcoticRegister;
import com.anesop.contracts.drug.model.UpdateDrugBaselinesRequest;
import com.anesop.contracts.drug.model.UpdateDrugBaselinesRequestItemsInner;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.impl.persistence.entity.CabinetHandoverEntity;
import com.anesop.m4.impl.persistence.entity.DailyCloseEntity;
import com.anesop.m4.impl.persistence.entity.DrugBaselineEntity;
import com.anesop.m4.impl.persistence.entity.DrugEntity;
import com.anesop.m4.impl.persistence.entity.NarcoticRegisterEntity;
import com.anesop.m4.impl.persistence.mapper.CabinetHandoverMapper;
import com.anesop.m4.impl.persistence.mapper.DailyCloseMapper;
import com.anesop.m4.impl.persistence.mapper.DrugBaselineMapper;
import com.anesop.m4.impl.persistence.mapper.DrugMapper;
import com.anesop.m4.impl.persistence.mapper.NarcoticRegisterMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * 麻精五专服务单测（M4-M15-01）：双人签字不同人、科室数据范围、基数乐观锁、日结平账、
 * 药品未收录与 append-only 语义（服务只调用 INSERT，不调用任何 UPDATE/DELETE）。
 */
@ExtendWith(MockitoExtension.class)
class NarcoticServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final String DEPT = "f1000000-0000-4000-8000-000000000002";
    private static final String OTHER_DEPT = "f1000000-0000-4000-8000-000000000003";
    private static final UUID DRUG_ID = UUID.fromString("2a000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_anesthesiologist", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-2001");

    @Mock
    private NarcoticRegisterMapper registerMapper;
    @Mock
    private CabinetHandoverMapper handoverMapper;
    @Mock
    private DrugBaselineMapper baselineMapper;
    @Mock
    private DailyCloseMapper closeMapper;
    @Mock
    private DrugMapper drugMapper;
    @Mock
    private DataScopeResolver dataScopeResolver;
    @Mock
    private OrganizationQueryService organizationQueryService;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private M4FailureAuditor failureAuditor;

    private NarcoticServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NarcoticServiceImpl(registerMapper, handoverMapper, baselineMapper, closeMapper,
                drugMapper, dataScopeResolver, organizationQueryService, auditWriter, failureAuditor,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        // 默认：任意合法 UUID 都是启用科室；具体范围由各用例覆盖可见科室。
        org.mockito.Mockito.lenient().when(organizationQueryService.findActiveDepartment(any()))
                .thenAnswer(invocation -> Optional.of(
                        department(invocation.getArgument(0).toString())));
    }

    // ------------------------------------------------------------------ 专册 --

    @Test
    void createRegisterPersistsDrugSnapshotAndAudits() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(drug());
        when(registerMapper.insertRegister(any())).thenReturn(1);

        NarcoticRegister created = service.createRegister(registerRequest(), CALLER, CONTEXT);

        assertThat(created.getRegisterId()).isNotBlank();
        assertThat(created.getDrugName()).isEqualTo("舒芬太尼（演示）");
        assertThat(created.getQuantity()).isEqualByComparingTo("1.00");
        assertThat(created.getDepartmentId()).isEqualTo(DEPT);
        assertThat(created.getUsageAt().getOffset()).isEqualTo(ZoneOffset.ofHours(8));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("NARCOTIC_REGISTER"), eq("NARCOTIC"), anyString(), eq(true),
                detail.capture(), eq(ACTOR), any(), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("departmentId", DEPT)
                .containsEntry("drugName", "舒芬太尼（演示）");
        // 审计不含处方号原文与使用人姓名。
        assertThat(String.valueOf(detail.getValue()))
                .doesNotContain("RX-SYNTH-0001", "演示使用人（合成）");
    }

    @Test
    void createRegisterRejectsSameSignerAndUnknownDrug() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(drug());
        CreateNarcoticRegisterRequest sameSigner = registerRequest();
        sameSigner.setSecondSignerId(sameSigner.getFirstSignerId());
        assertThatThrownBy(() -> service.createRegister(sameSigner, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .satisfies(failure -> assertThat(((M4ValidationException) failure).field())
                        .isEqualTo("secondSignerId"))
                .hasMessageContaining("同一人");

        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.createRegister(registerRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("未收录");
        verify(registerMapper, never()).insertRegister(any());

        DrugEntity inactive = drug();
        inactive.setStatus("INACTIVE");
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(inactive);
        assertThatThrownBy(() -> service.createRegister(registerRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void createRegisterValidatesFieldsAndTime() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(drug());

        CreateNarcoticRegisterRequest noBatch = registerRequest();
        noBatch.setBatchNo(" ");
        assertThatThrownBy(() -> service.createRegister(noBatch, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);

        CreateNarcoticRegisterRequest zero = registerRequest();
        zero.setQuantity(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.createRegister(zero, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("大于 0");

        CreateNarcoticRegisterRequest tooPrecise = registerRequest();
        tooPrecise.setQuantity(new BigDecimal("1.234"));
        assertThatThrownBy(() -> service.createRegister(tooPrecise, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("小数");

        CreateNarcoticRegisterRequest future = registerRequest();
        future.setUsageAt(OffsetDateTime.now(ZoneOffset.ofHours(8)).plusHours(2));
        assertThatThrownBy(() -> service.createRegister(future, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("晚于当前时间");
        assertThatThrownBy(() -> service.createRegister(null, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);
    }

    @Test
    void registerReadAndWriteAreRestrictedToDepartmentScope() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(deptScope(DEPT));
        when(organizationQueryService.visibleDepartments(ACTOR))
                .thenReturn(List.of(department(DEPT)));

        CreateNarcoticRegisterRequest otherDept = registerRequest();
        otherDept.setDepartmentId(OTHER_DEPT);
        assertThatThrownBy(() -> service.createRegister(otherDept, CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> service.listRegisters(OTHER_DEPT, null, null, null, 100, CALLER))
                .isInstanceOf(AccessDeniedException.class);

        when(registerMapper.selectRegisters(eq(DEPT), eq(null), eq(null), eq(null),
                eq(List.of(DEPT)), eq(100))).thenReturn(List.of(registerEntity()));
        assertThat(service.listRegisters(DEPT, null, null, null, 100, CALLER).getItems())
                .hasSize(1);

        // S-5：SELF 不再一律 403，而是按本人所属科室推导过滤条件。
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(selfScope());
        when(organizationQueryService.departmentsOfUser(ACTOR)).thenReturn(List.of(department(DEPT)));
        when(organizationQueryService.visibleDepartments(ACTOR)).thenReturn(List.of(department(DEPT)));
        when(registerMapper.selectRegisters(eq(DEPT), eq(null), eq(null), eq(null),
                eq(List.of(DEPT)), eq(100))).thenReturn(List.of(registerEntity()));
        assertThat(service.listRegisters(null, null, null, null, 100, CALLER).getItems())
                .hasSize(1);
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(DataScopeAssignments.denied());
        assertThatThrownBy(() -> service.listRegisters(null, null, null, null, 100, CALLER))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listRegistersValidatesWindowAndLimit() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        OffsetDateTime from = OffsetDateTime.parse("2026-11-14T10:00:00+08:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-14T09:00:00+08:00");
        assertThatThrownBy(() -> service.listRegisters(null, null, from, to, 100, CALLER))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> service.listRegisters(null, null, null, null, 0, CALLER))
                .isInstanceOf(M4ValidationException.class);
    }

    // ------------------------------------------------------------------ 交接 --

    @Test
    void createHandoverPersistsItemsJsonAndAudits() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(drug());
        when(handoverMapper.insertHandover(any())).thenReturn(1);

        CabinetHandover created = service.createHandover(handoverRequest(), CALLER, CONTEXT);

        assertThat(created.getHandoverId()).isNotBlank();
        assertThat(created.getShift()).isEqualTo(CabinetHandover.ShiftEnum.DAY);
        assertThat(created.getItems()).hasSize(1);
        CabinetHandoverItem item = created.getItems().get(0);
        assertThat(item.getDrugName()).isEqualTo("舒芬太尼（演示）");
        assertThat(item.getQuantity()).isEqualByComparingTo("4");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("NARCOTIC_HANDOVER"), eq("NARCOTIC"), anyString(), eq(true),
                detail.capture(), eq(ACTOR), any(), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("itemCount", 1);
        assertThat(String.valueOf(detail.getValue()))
                .doesNotContain("演示交班人（合成）", "演示接班人（合成）");
    }

    @Test
    void createHandoverRejectsSameParticipantsAndSigners() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(drug());

        CreateCabinetHandoverRequest sameParticipant = handoverRequest();
        sameParticipant.setHandoverToId(sameParticipant.getHandoverFromId());
        assertThatThrownBy(() -> service.createHandover(sameParticipant, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .satisfies(failure -> assertThat(((M4ValidationException) failure).field())
                        .isEqualTo("handoverToId"));

        CreateCabinetHandoverRequest sameSigner = handoverRequest();
        sameSigner.setSecondSignerId(sameSigner.getFirstSignerId());
        assertThatThrownBy(() -> service.createHandover(sameSigner, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .satisfies(failure -> assertThat(((M4ValidationException) failure).field())
                        .isEqualTo("secondSignerId"));

        CreateCabinetHandoverRequest emptyItems = handoverRequest();
        emptyItems.setItems(List.of());
        assertThatThrownBy(() -> service.createHandover(emptyItems, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("至少 1 条");

        CreateCabinetHandoverRequest duplicate = handoverRequest();
        duplicate.setItems(List.of(item(), item()));
        assertThatThrownBy(() -> service.createHandover(duplicate, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("重复");

        CreateCabinetHandoverRequest future = handoverRequest();
        future.setHandedOverAt(OffsetDateTime.now(ZoneOffset.ofHours(8)).plusHours(3));
        assertThatThrownBy(() -> service.createHandover(future, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("晚于当前时间");
        verify(handoverMapper, never()).insertHandover(any());
    }

    @Test
    void listHandoversAppliesScope() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(deptScope(DEPT));
        when(organizationQueryService.visibleDepartments(ACTOR))
                .thenReturn(List.of(department(DEPT)));
        when(handoverMapper.selectHandovers(eq("CAB-ANES-01"), eq(null), eq(List.of(DEPT)),
                eq(100))).thenReturn(List.of(handoverEntity()));
        assertThat(service.listHandovers("CAB-ANES-01", null, 100, CALLER).getItems())
                .hasSize(1);
        assertThatThrownBy(() -> service.listHandovers(null, OTHER_DEPT, 100, CALLER))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ 基数 --

    @Test
    void updateBaselinesCreatesThenUpdatesWithOptimisticLock() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(drug());
        when(baselineMapper.selectByDrugAndCabinet(DRUG_ID.toString(), "CAB-ANES-01"))
                .thenReturn(null);
        when(baselineMapper.insertBaseline(any())).thenReturn(1);
        when(baselineMapper.selectBaselines(eq(null), eq(null), eq(null)))
                .thenReturn(List.of(baselineEntity(1)));

        UpdateDrugBaselinesRequest create = new UpdateDrugBaselinesRequest();
        create.setItems(List.of(baselineItem(null, "12")));
        DrugBaselineList created = service.updateBaselines(create, CALLER, CONTEXT);
        assertThat(created.getItems()).hasSize(1);
        verify(baselineMapper).insertBaseline(any());

        when(baselineMapper.selectByDrugAndCabinet(DRUG_ID.toString(), "CAB-ANES-01"))
                .thenReturn(baselineEntity(1));
        when(baselineMapper.updateBaseline(any())).thenReturn(1);
        UpdateDrugBaselinesRequest update = new UpdateDrugBaselinesRequest();
        update.setItems(List.of(baselineItem(1, "15")));
        service.updateBaselines(update, CALLER, CONTEXT);
        verify(baselineMapper).updateBaseline(any());

        UpdateDrugBaselinesRequest stale = new UpdateDrugBaselinesRequest();
        stale.setItems(List.of(baselineItem(2, "20")));
        assertThatThrownBy(() -> service.updateBaselines(stale, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("刷新");

        UpdateDrugBaselinesRequest missingVersion = new UpdateDrugBaselinesRequest();
        missingVersion.setItems(List.of(baselineItem(null, "20")));
        assertThatThrownBy(() -> service.updateBaselines(missingVersion, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("版本号");

        UpdateDrugBaselinesRequest negative = new UpdateDrugBaselinesRequest();
        negative.setItems(List.of(baselineItem(null, "-1")));
        assertThatThrownBy(() -> service.updateBaselines(negative, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> service.updateBaselines(null, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);
    }

    @Test
    void listBaselinesRestrictedToScope() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(deptScope(DEPT));
        when(organizationQueryService.visibleDepartments(ACTOR))
                .thenReturn(List.of(department(DEPT)));
        when(baselineMapper.selectBaselines(eq("CAB-ANES-01"), eq(null), eq(List.of(DEPT))))
                .thenReturn(List.of(baselineEntity(1)));
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(drug());

        DrugBaselineList list = service.listBaselines("CAB-ANES-01", null, CALLER);
        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getItems().get(0).getDrugName()).isEqualTo("舒芬太尼（演示）");
        assertThatThrownBy(() -> service.listBaselines(null, OTHER_DEPT, CALLER))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------ 日结 --

    @Test
    void createDailyCloseAcceptsBalancedEquationAndAudits() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        when(closeMapper.countDuplicate("CAB-ANES-01", LocalDate.parse("2026-11-13"), "DAY"))
                .thenReturn(0L);
        when(closeMapper.insertClose(any())).thenReturn(1);

        DailyClose close = service.createDailyClose(closeRequest(), CALLER, CONTEXT);

        assertThat(close.getBalanced()).isTrue();
        assertThat(close.getClosingQuantity()).isEqualByComparingTo("10");
        assertThat(close.getShift()).isEqualTo(DailyClose.ShiftEnum.DAY);
        verify(auditWriter).record(eq("NARCOTIC_DAILY_CLOSE"), eq("NARCOTIC"),
                anyString(), eq(true), any(), eq(ACTOR), any(), eq(CONTEXT));
    }

    @Test
    void createDailyCloseRejectsUnbalancedWithDetails() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        CreateDailyCloseRequest request = closeRequest();
        request.setClosingQuantity(new BigDecimal("9"));

        assertThatThrownBy(() -> service.createDailyClose(request, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .satisfies(failure -> {
                    M4ConflictException conflict = (M4ConflictException) failure;
                    assertThat(conflict.details()).hasSize(1);
                    assertThat(conflict.details().get(0).field()).isEqualTo("closingQuantity");
                    assertThat(conflict.details().get(0).reason()).contains("期望 10", "实盘 9");
                });
        verify(closeMapper, never()).insertClose(any());
    }

    @Test
    void createDailyCloseRejectsDuplicateFutureAndBadShift() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        when(closeMapper.countDuplicate(anyString(), any(), anyString())).thenReturn(1L);
        assertThatThrownBy(() -> service.createDailyClose(closeRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("已日结");

        CreateDailyCloseRequest future = closeRequest();
        future.setCloseDate(LocalDate.parse("2027-01-01"));
        assertThatThrownBy(() -> service.createDailyClose(future, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("晚于今天");

        CreateDailyCloseRequest badShift = closeRequest();
        badShift.setShift(null);
        assertThatThrownBy(() -> service.createDailyClose(badShift, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("班次");
        assertThatThrownBy(() -> service.createDailyClose(null, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);
    }

    @Test
    void getDailyCloseHidesOutOfScopeRecords() {
        DailyCloseEntity entity = closeEntity();
        when(closeMapper.selectByCloseId(entity.getId())).thenReturn(entity);
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        assertThat(service.getDailyClose(entity.getId().toString(), CALLER).getBalanced()).isTrue();

        when(dataScopeResolver.resolve(ACTOR)).thenReturn(deptScope(OTHER_DEPT));
        when(organizationQueryService.visibleDepartments(ACTOR))
                .thenReturn(List.of(department(OTHER_DEPT)));
        assertThatThrownBy(() -> service.getDailyClose(entity.getId().toString(), CALLER))
                .isInstanceOf(NoSuchElementException.class);

        when(closeMapper.selectByCloseId(entity.getId())).thenReturn(null);
        assertThatThrownBy(() -> service.getDailyClose(entity.getId().toString(), CALLER))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.getDailyClose("bad-id", CALLER))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void selfScopeDerivesDepartmentFromUserOrganization() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(selfScope());
        when(organizationQueryService.departmentsOfUser(ACTOR)).thenReturn(List.of(department(DEPT)));
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(drug());
        when(registerMapper.insertRegister(any())).thenReturn(1);
        CreateNarcoticRegisterRequest request = registerRequest();
        request.setDepartmentId(null);

        NarcoticRegister created = service.createRegister(request, CALLER, CONTEXT);

        assertThat(created.getDepartmentId()).isEqualTo(DEPT);
        CreateNarcoticRegisterRequest otherDept = registerRequest();
        otherDept.setDepartmentId(OTHER_DEPT);
        assertThatThrownBy(() -> service.createRegister(otherDept, CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void departmentMustBeActiveDepartmentOrg() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        when(organizationQueryService.findActiveDepartment(UUID.fromString(OTHER_DEPT)))
                .thenReturn(Optional.empty());

        CreateNarcoticRegisterRequest request = registerRequest();
        request.setDepartmentId(OTHER_DEPT);

        assertThatThrownBy(() -> service.createRegister(request, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .satisfies(failure -> assertThat(((M4UnprocessableException) failure).field())
                        .isEqualTo("departmentId"));
        verify(registerMapper, never()).insertRegister(any());
        verify(failureAuditor).recordFailure(eq("NARCOTIC_REGISTER"), eq("NARCOTIC"),
                eq("NEW"), any(), eq(CALLER), eq(CONTEXT));
    }

    @Test
    void baselineDepartmentIsImmutableAndMustBeVisible() {
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(allScope());
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(drug());
        DrugBaselineEntity existing = baselineEntity(1);
        existing.setDepartmentId(UUID.fromString(OTHER_DEPT));
        when(baselineMapper.selectByDrugAndCabinet(DRUG_ID.toString(), "CAB-ANES-01"))
                .thenReturn(existing);

        UpdateDrugBaselinesRequest crossDept = new UpdateDrugBaselinesRequest();
        crossDept.setItems(List.of(baselineItem(1, "15")));
        assertThatThrownBy(() -> service.updateBaselines(crossDept, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("科室");

        when(dataScopeResolver.resolve(ACTOR)).thenReturn(deptScope(DEPT));
        when(organizationQueryService.visibleDepartments(ACTOR))
                .thenReturn(List.of(department(DEPT)));
        assertThatThrownBy(() -> service.updateBaselines(crossDept, CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
        verify(baselineMapper, never()).updateBaseline(any());
    }

    // ------------------------------------------------------------------ 夹具 --

    private static OrgDepartment department(String deptId) {
        return new OrgDepartment(deptId, "DEPT-TEST", "测试科室（合成）");
    }

    private static DataScopeAssignments allScope() {
        return DataScopeAssignments.of(List.of(new DataScopeAssignment(DataScope.ALL, List.of())));
    }

    private static DataScopeAssignments deptScope(String deptId) {
        return DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.DEPT, List.of(deptId))));
    }

    private static DataScopeAssignments selfScope() {
        return DataScopeAssignments.of(List.of(new DataScopeAssignment(DataScope.SELF, List.of())));
    }

    private static DrugEntity drug() {
        DrugEntity drug = new DrugEntity();
        drug.setId(DRUG_ID);
        drug.setCode("DRUG-SUF-001");
        drug.setName("舒芬太尼（演示）");
        drug.setUnit("支");
        drug.setStatus("ACTIVE");
        drug.setNarcotic(true);
        drug.setHighRisk(true);
        drug.setVersion(1);
        return drug;
    }

    private static CreateNarcoticRegisterRequest registerRequest() {
        CreateNarcoticRegisterRequest request = new CreateNarcoticRegisterRequest();
        request.setDrugId(DRUG_ID.toString());
        request.setBatchNo("BATCH-SYNTH-0001");
        request.setQuantity(new BigDecimal("1"));
        request.setUnit("支");
        request.setPrescriptionNo("RX-SYNTH-0001");
        request.setUsedBy("演示使用人（合成）");
        request.setDepartmentId(DEPT);
        request.setUsageAt(OffsetDateTime.parse("2026-11-14T08:50:00+08:00"));
        request.setDestination("术中镇痛（演示）");
        request.setFirstSignerId("10000000-0000-4000-8000-000000000003");
        request.setFirstSignerName("演示给药人（合成）");
        request.setSecondSignerId("10000000-0000-4000-8000-000000000004");
        request.setSecondSignerName("演示核对人（合成）");
        return request;
    }

    private static NarcoticRegisterEntity registerEntity() {
        NarcoticRegisterEntity entity = new NarcoticRegisterEntity();
        entity.setId(UUID.randomUUID());
        entity.setDrugId(DRUG_ID);
        entity.setDrugName("舒芬太尼（演示）");
        entity.setBatchNo("BATCH-SYNTH-0001");
        entity.setQuantity(new BigDecimal("1.00"));
        entity.setUnit("支");
        entity.setPrescriptionNo("RX-SYNTH-0001");
        entity.setUsedBy("演示使用人（合成）");
        entity.setDepartmentId(UUID.fromString(DEPT));
        entity.setUsageAt(NOW);
        entity.setDestination("术中镇痛（演示）");
        entity.setFirstSignerId(ACTOR);
        entity.setFirstSignerName("演示给药人（合成）");
        entity.setSecondSignerId(UUID.fromString("10000000-0000-4000-8000-000000000004"));
        entity.setSecondSignerName("演示核对人（合成）");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        return entity;
    }

    private static CreateCabinetHandoverRequest handoverRequest() {
        CreateCabinetHandoverRequest request = new CreateCabinetHandoverRequest();
        request.setCabinetCode("CAB-ANES-01");
        request.setDepartmentId(DEPT);
        request.setShift(CreateCabinetHandoverRequest.ShiftEnum.DAY);
        request.setHandedOverAt(OffsetDateTime.parse("2026-11-14T08:30:00+08:00"));
        request.setHandoverFromId("10000000-0000-4000-8000-000000000003");
        request.setHandoverFromName("演示交班人（合成）");
        request.setHandoverToId("10000000-0000-4000-8000-000000000004");
        request.setHandoverToName("演示接班人（合成）");
        request.setItems(List.of(item()));
        request.setFirstSignerId("10000000-0000-4000-8000-000000000003");
        request.setFirstSignerName("演示交班人（合成）");
        request.setSecondSignerId("10000000-0000-4000-8000-000000000004");
        request.setSecondSignerName("演示接班人（合成）");
        return request;
    }

    private static CreateCabinetHandoverRequestItemsInner item() {
        CreateCabinetHandoverRequestItemsInner item = new CreateCabinetHandoverRequestItemsInner();
        item.setDrugId(DRUG_ID.toString());
        item.setQuantity(new BigDecimal("4"));
        item.setUnit("支");
        return item;
    }

    private static CabinetHandoverEntity handoverEntity() {
        CabinetHandoverEntity entity = new CabinetHandoverEntity();
        entity.setId(UUID.randomUUID());
        entity.setCabinetCode("CAB-ANES-01");
        entity.setDepartmentId(UUID.fromString(DEPT));
        entity.setShift("DAY");
        entity.setHandedOverAt(NOW);
        entity.setHandoverFromId(ACTOR);
        entity.setHandoverFromName("演示交班人（合成）");
        entity.setHandoverToId(UUID.fromString("10000000-0000-4000-8000-000000000004"));
        entity.setHandoverToName("演示接班人（合成）");
        entity.setItems("[{\"drugId\":\"" + DRUG_ID + "\",\"drugName\":\"舒芬太尼（演示）\","
                + "\"quantity\":\"4\",\"unit\":\"支\"}]");
        entity.setFirstSignerId(ACTOR);
        entity.setFirstSignerName("演示交班人（合成）");
        entity.setSecondSignerId(UUID.fromString("10000000-0000-4000-8000-000000000004"));
        entity.setSecondSignerName("演示接班人（合成）");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        return entity;
    }

    private static UpdateDrugBaselinesRequestItemsInner baselineItem(Integer version, String qty) {
        UpdateDrugBaselinesRequestItemsInner item = new UpdateDrugBaselinesRequestItemsInner();
        item.setDrugId(DRUG_ID.toString());
        item.setCabinetCode("CAB-ANES-01");
        item.setDepartmentId(DEPT);
        item.setQuantity(new BigDecimal(qty));
        item.setVersion(version);
        return item;
    }

    private static DrugBaselineEntity baselineEntity(int version) {
        DrugBaselineEntity entity = new DrugBaselineEntity();
        entity.setId(UUID.fromString("2d000000-0000-4000-8000-000000000001"));
        entity.setDrugId(DRUG_ID);
        entity.setCabinetCode("CAB-ANES-01");
        entity.setDepartmentId(UUID.fromString(DEPT));
        entity.setQuantity(new BigDecimal("12.00"));
        entity.setUpdatedAt(NOW);
        entity.setUpdatedBy(ACTOR);
        entity.setVersion(version);
        return entity;
    }

    private static CreateDailyCloseRequest closeRequest() {
        CreateDailyCloseRequest request = new CreateDailyCloseRequest();
        request.setCloseDate(LocalDate.parse("2026-11-13"));
        request.setShift(CreateDailyCloseRequest.ShiftEnum.DAY);
        request.setCabinetCode("CAB-ANES-01");
        request.setDepartmentId(DEPT);
        request.setOpeningQuantity(new BigDecimal("10"));
        request.setInboundQuantity(new BigDecimal("4"));
        request.setUsedQuantity(new BigDecimal("3"));
        request.setReturnedQuantity(new BigDecimal("1"));
        request.setClosingQuantity(new BigDecimal("10"));
        return request;
    }

    private static DailyCloseEntity closeEntity() {
        DailyCloseEntity entity = new DailyCloseEntity();
        entity.setId(UUID.fromString("2e000000-0000-4000-8000-000000000001"));
        entity.setCloseDate(LocalDate.parse("2026-11-13"));
        entity.setShift("DAY");
        entity.setCabinetCode("CAB-ANES-01");
        entity.setDepartmentId(UUID.fromString(DEPT));
        entity.setOpeningQuantity(new BigDecimal("10.00"));
        entity.setInboundQuantity(new BigDecimal("4.00"));
        entity.setUsedQuantity(new BigDecimal("3.00"));
        entity.setReturnedQuantity(new BigDecimal("1.00"));
        entity.setClosingQuantity(new BigDecimal("10.00"));
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        return entity;
    }
}
