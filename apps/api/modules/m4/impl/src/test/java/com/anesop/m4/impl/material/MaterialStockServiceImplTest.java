package com.anesop.m4.impl.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.material.model.CreateStockMovementRequest;
import com.anesop.contracts.material.model.MaterialStockLevelList;
import com.anesop.contracts.material.model.StockMovement;
import com.anesop.contracts.material.model.StockMovementList;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.impl.persistence.entity.MaterialEntity;
import com.anesop.m4.impl.persistence.entity.MaterialStockEntity;
import com.anesop.m4.impl.persistence.entity.UdiInstanceEntity;
import com.anesop.m4.impl.persistence.mapper.MaterialMapper;
import com.anesop.m4.impl.persistence.mapper.MaterialStockMapper;
import com.anesop.m4.impl.persistence.mapper.StockMovementMapper;
import com.anesop.m4.impl.persistence.mapper.UdiInstanceMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.anesop.m4.impl.support.MaterialAccessGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 出入库单测（M4-M14-01）：数量不足 409、效期/批次校验、高值裸数量拒绝、UDI 退回状态、
 * 审计与数据范围入口。数据库 CHECK/触发器由集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class MaterialStockServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID MATERIAL_ID =
            UUID.fromString("4a000000-0000-4000-8000-000000000001");
    private static final UUID STOCK_ID = UUID.fromString("4b000000-0000-4000-8000-000000000001");
    private static final UUID UDI_ID = UUID.fromString("4d000000-0000-4000-8000-000000000001");
    private static final String DEPT = "f1000000-0000-4000-8000-000000000002";
    private static final String UDI = "06937000000001^SN-DEMO-001";
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    /**
     * 夹具“今日”必须与注入的固定 Clock 对齐：此前用真实 {@code LocalDate.now()} 构造
     * “昨日过期”夹具，跨自然日后相对固定 Clock 的今天可能仍在效期内，导致 422 断言偶发失败。
     */
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.ofHours(8));
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_nurse", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-2002");
    private static final DataScopeAssignments ALL =
            DataScopeAssignments.of(List.of(new DataScopeAssignment(DataScope.ALL, List.of())));

    @Mock
    private MaterialMapper materialMapper;
    @Mock
    private MaterialStockMapper stockMapper;
    @Mock
    private StockMovementMapper movementMapper;
    @Mock
    private UdiInstanceMapper udiMapper;
    @Mock
    private MaterialAccessGuard accessGuard;
    @Mock
    private OrganizationQueryService organizationQueryService;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private M4FailureAuditor failureAuditor;

    private MaterialStockServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MaterialStockServiceImpl(materialMapper, stockMapper, movementMapper, udiMapper,
                accessGuard, organizationQueryService, auditWriter, failureAuditor,
                Clock.fixed(NOW, ZoneOffset.ofHours(8)));
        lenient().when(accessGuard.scope(CALLER)).thenReturn(ALL);
        lenient().when(accessGuard.requireDepartment(eq(ALL), eq(CALLER), eq(DEPT),
                eq("departmentId"))).thenReturn(DEPT);
    }

    @Test
    void listDepartmentsReturnsVisibleDepartments() {
        when(organizationQueryService.visibleDepartments(ACTOR))
                .thenReturn(List.of(new OrgDepartment(DEPT, "DEPT-ANES", "麻醉科（合成）")));
        when(accessGuard.visibleDepartmentIds(eq(ALL), eq(CALLER))).thenReturn(null);

        var departments = service.listDepartments(CALLER);

        assertThat(departments.getItems()).hasSize(1);
        assertThat(departments.getItems().get(0).getDepartmentId()).isEqualTo(DEPT);
        assertThat(departments.getItems().get(0).getName()).isEqualTo("麻醉科（合成）");
    }

    @Test
    void inboundIncrementsBatchAndWritesAppendOnlyMovement() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(false, true));
        when(stockMapper.upsertIncrement(any())).thenReturn(1);
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any())).thenReturn(stock(20));
        when(movementMapper.insertMovement(any())).thenReturn(1);

        StockMovement movement = service.createMovement(
                request(CreateStockMovementRequest.MovementTypeEnum.IN, "20", "BATCH-1",
                        LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1)), CALLER, CONTEXT);

        assertThat(movement.getMovementType()).isEqualTo(StockMovement.MovementTypeEnum.IN);
        assertThat(movement.getBalanceAfter()).isEqualByComparingTo("20");
        assertThat(movement.getOperatorName()).isEqualTo("synth_nurse");
        verify(movementMapper).insertMovement(any());
        verify(auditWriter).record(eq("STOCK_MOVE"), eq("STOCK_MOVEMENT"), anyString(), eq(true),
                any(), eq(ACTOR), eq("synth_nurse"), eq(CONTEXT));
    }

    @Test
    void inboundRejectsExpiredOrMissingExpiry() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(false, true));

        CreateStockMovementRequest missingExpiry = request(
                CreateStockMovementRequest.MovementTypeEnum.IN, "1", "BATCH-1", null);
        assertThatThrownBy(() -> service.createMovement(missingExpiry, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("效期");

        CreateStockMovementRequest expired = request(
                CreateStockMovementRequest.MovementTypeEnum.IN, "1", "BATCH-1",
                TODAY.minusDays(1));
        assertThatThrownBy(() -> service.createMovement(expired, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("过效期");
    }

    @Test
    void inboundRejectsBatchExpiryMismatch() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(false, true));
        when(stockMapper.upsertIncrement(any())).thenReturn(0);

        CreateStockMovementRequest request = request(
                CreateStockMovementRequest.MovementTypeEnum.IN, "1", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        assertThatThrownBy(() -> service.createMovement(request, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("效期");
    }

    @Test
    void outboundDecrementsAndReportsInsufficientStock() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(false, true));
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any())).thenReturn(stock(5));
        when(stockMapper.decrement(eq(STOCK_ID), eq(new BigDecimal("2")), any())).thenReturn(1);
        when(stockMapper.selectQuantity(STOCK_ID)).thenReturn(new BigDecimal("3"));
        when(movementMapper.insertMovement(any())).thenReturn(1);

        StockMovement movement = service.createMovement(
                request(CreateStockMovementRequest.MovementTypeEnum.OUT, "2", "BATCH-1",
                        LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1)), CALLER, CONTEXT);
        assertThat(movement.getMovementType()).isEqualTo(StockMovement.MovementTypeEnum.OUT);
        assertThat(movement.getBalanceAfter()).isEqualByComparingTo("3");

        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-2"), any())).thenReturn(null);
        CreateStockMovementRequest missing = request(
                CreateStockMovementRequest.MovementTypeEnum.OUT, "2", "BATCH-2",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        assertThatThrownBy(() -> service.createMovement(missing, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("库存不足");
    }

    @Test
    void outboundRaceReportsAvailableQuantity() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(false, true));
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any())).thenReturn(stock(1));
        when(stockMapper.decrement(eq(STOCK_ID), eq(BigDecimal.ONE), any())).thenReturn(0);
        when(stockMapper.selectQuantity(STOCK_ID)).thenReturn(BigDecimal.ZERO);

        CreateStockMovementRequest request = request(
                CreateStockMovementRequest.MovementTypeEnum.OUT, "1", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        assertThatThrownBy(() -> service.createMovement(request, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .satisfies(failure -> assertThat(((M4ConflictException) failure).details())
                        .extracting(M4ConflictException.Detail::field).containsExactly("quantity"));
        verify(movementMapper, never()).insertMovement(any());
    }

    @Test
    void adjustRequiresReasonAndHandlesSignedDelta() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(false, false));

        CreateStockMovementRequest noReason = request(
                CreateStockMovementRequest.MovementTypeEnum.ADJUST, "-3", "BATCH-1", null);
        assertThatThrownBy(() -> service.createMovement(noReason, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("原因");

        CreateStockMovementRequest zero = request(
                CreateStockMovementRequest.MovementTypeEnum.ADJUST, "0", "BATCH-1", null);
        zero.setReason("盘点（合成）");
        assertThatThrownBy(() -> service.createMovement(zero, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("0");

        CreateStockMovementRequest negative = request(
                CreateStockMovementRequest.MovementTypeEnum.ADJUST, "-3", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        negative.setReason("盘亏（合成）");
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any())).thenReturn(stock(10));
        when(stockMapper.decrement(eq(STOCK_ID), eq(new BigDecimal("3")), any())).thenReturn(1);
        when(stockMapper.selectQuantity(STOCK_ID)).thenReturn(new BigDecimal("7"));
        when(movementMapper.insertMovement(any())).thenReturn(1);
        StockMovement movement = service.createMovement(negative, CALLER, CONTEXT);
        assertThat(movement.getQuantity()).isEqualByComparingTo("-3");
        assertThat(movement.getBalanceAfter()).isEqualByComparingTo("7");
    }

    @Test
    void highValueRejectsBareQuantityMovementsAndRequiresUdiForReturn() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(true, true));

        for (CreateStockMovementRequest.MovementTypeEnum type : List.of(
                CreateStockMovementRequest.MovementTypeEnum.IN,
                CreateStockMovementRequest.MovementTypeEnum.OUT,
                CreateStockMovementRequest.MovementTypeEnum.ADJUST)) {
            CreateStockMovementRequest request = request(type, "1", "BATCH-1",
                    LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
            request.setReason("调整（合成）");
            assertThatThrownBy(() -> service.createMovement(request, CALLER, CONTEXT))
                    .isInstanceOf(M4UnprocessableException.class)
                    .hasMessageContaining("高值");
        }
        CreateStockMovementRequest returnWithoutUdi = request(
                CreateStockMovementRequest.MovementTypeEnum.RETURN, "1", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        assertThatThrownBy(() -> service.createMovement(returnWithoutUdi, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class)
                .hasMessageContaining("UDI");
    }

    @Test
    void returnWithUdiMarksInstanceReturnedAndDecrementsStock() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(true, true));
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(instance("IN_STOCK"));
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any())).thenReturn(stock(2));
        when(stockMapper.decrement(eq(STOCK_ID), eq(BigDecimal.ONE), any())).thenReturn(1);
        when(stockMapper.selectQuantity(STOCK_ID)).thenReturn(BigDecimal.ONE);
        when(udiMapper.markReturned(eq(UDI_ID), eq(1), any(), eq(ACTOR))).thenReturn(1);
        when(movementMapper.insertMovement(any())).thenReturn(1);

        CreateStockMovementRequest request = request(
                CreateStockMovementRequest.MovementTypeEnum.RETURN, "1", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        request.setUdi(UDI);
        StockMovement movement = service.createMovement(request, CALLER, CONTEXT);

        assertThat(movement.getMovementType()).isEqualTo(StockMovement.MovementTypeEnum.RETURN);
        assertThat(movement.getUdi()).isEqualTo(UDI);
        verify(udiMapper).markReturned(eq(UDI_ID), eq(1), any(), eq(ACTOR));
    }

    @Test
    void returnWithUsedUdiIsStateConflict() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(true, true));
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(instance("USED"));

        CreateStockMovementRequest request = request(
                CreateStockMovementRequest.MovementTypeEnum.RETURN, "1", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        request.setUdi(UDI);
        assertThatThrownBy(() -> service.createMovement(request, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("已使用");
    }

    @Test
    void returnRejectsUdiFromAnotherDepartmentAndInvalidUdiUse() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(true, true));
        UdiInstanceEntity other = instance("IN_STOCK");
        other.setDepartmentId(UUID.fromString("f1000000-0000-4000-8000-000000000003"));
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(other);

        CreateStockMovementRequest request = request(
                CreateStockMovementRequest.MovementTypeEnum.RETURN, "1", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        request.setUdi(UDI);
        assertThatThrownBy(() -> service.createMovement(request, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("跨科室");

        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(false, true));
        CreateStockMovementRequest udiOnInbound = request(
                CreateStockMovementRequest.MovementTypeEnum.IN, "1", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        udiOnInbound.setUdi(UDI);
        assertThatThrownBy(() -> service.createMovement(udiOnInbound, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("RETURN");
    }

    @Test
    void stockAndMovementListsRespectScopeAndValidateFilters() {
        when(accessGuard.visibleDepartmentIds(eq(ALL), eq(CALLER))).thenReturn(null);
        when(accessGuard.optionalDepartmentFilter(eq(ALL), eq(CALLER), eq(null), eq("departmentId")))
                .thenReturn(null);
        when(stockMapper.selectStock(isNull(), isNull(), isNull(), eq(100)))
                .thenReturn(List.of(stock(3)));
        MaterialStockLevelList stockList = service.listStock(null, null, 100, CALLER);
        assertThat(stockList.getItems()).hasSize(1);
        assertThat(stockList.getItems().get(0).getQuantity()).isEqualByComparingTo("3");

        when(movementMapper.selectMovements(isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(100))).thenReturn(List.of(movement()));
        StockMovementList movements = service.listMovements(null, null, null, null, 100, CALLER);
        assertThat(movements.getItems()).hasSize(1);
        assertThat(movements.getItems().get(0).getMovementType())
                .isEqualTo(StockMovement.MovementTypeEnum.IN);

        assertThatThrownBy(() -> service.listStock("bad-uuid", null, 100, CALLER))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> service.listMovements(null, null,
                OffsetDateTime.now(ZoneOffset.ofHours(8)),
                OffsetDateTime.now(ZoneOffset.ofHours(8)).minusHours(1), 100, CALLER))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("时间");
        assertThatThrownBy(() -> service.listStock(null, null, 0, CALLER))
                .isInstanceOf(M4ValidationException.class);
    }

    @Test
    void outboundRejectsExpiredBatchExpiryMismatchAndExpiredOutbound() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(false, true));
        LocalDate today = LocalDate.now(ZoneOffset.ofHours(8));
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any()))
                .thenReturn(stock(5, today.minusDays(10)));

        CreateStockMovementRequest futureExpiry = request(
                CreateStockMovementRequest.MovementTypeEnum.OUT, "1", "BATCH-1", today.plusYears(1));
        assertThatThrownBy(() -> service.createMovement(futureExpiry, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .satisfies(failure -> assertThat(((M4ConflictException) failure).details())
                        .extracting(M4ConflictException.Detail::field).containsExactly("expiryDate"));
        verify(stockMapper, never()).decrement(any(), any(), any());

        CreateStockMovementRequest matchingExpiry = request(
                CreateStockMovementRequest.MovementTypeEnum.OUT, "1", "BATCH-1",
                today.minusDays(10));
        assertThatThrownBy(() -> service.createMovement(matchingExpiry, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("过效期");
        verify(stockMapper, never()).decrement(any(), any(), any());
    }

    @Test
    void returnUdiBatchOrExpiryMismatchIsRejectedBeforeDecrement() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(true, true));
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(instance("IN_STOCK"));

        CreateStockMovementRequest wrongBatch = request(
                CreateStockMovementRequest.MovementTypeEnum.RETURN, "1", "BATCH-2",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        wrongBatch.setUdi(UDI);
        assertThatThrownBy(() -> service.createMovement(wrongBatch, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("批次");
        verify(stockMapper, never()).selectByBatch(any(), anyString(), any());
        verify(stockMapper, never()).decrement(any(), any(), any());

        CreateStockMovementRequest wrongExpiry = request(
                CreateStockMovementRequest.MovementTypeEnum.RETURN, "1", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(2));
        wrongExpiry.setUdi(UDI);
        assertThatThrownBy(() -> service.createMovement(wrongExpiry, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("效期");
        verify(stockMapper, never()).decrement(any(), any(), any());
    }

    @Test
    void returnUdiUsesInstanceBatchAndExpiryForMovement() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(true, true));
        UdiInstanceEntity instance = instance("IN_STOCK");
        instance.setBatchNo("BATCH-UDI");
        LocalDate instanceExpiry = LocalDate.now(ZoneOffset.ofHours(8)).plusYears(3);
        instance.setExpiryDate(instanceExpiry);
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(instance);
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-UDI"), any()))
                .thenReturn(stock(2, instanceExpiry));
        when(stockMapper.decrement(eq(STOCK_ID), eq(BigDecimal.ONE), any())).thenReturn(1);
        when(stockMapper.selectQuantity(STOCK_ID)).thenReturn(BigDecimal.ONE);
        when(udiMapper.markReturned(eq(UDI_ID), eq(1), any(), eq(ACTOR))).thenReturn(1);
        when(movementMapper.insertMovement(any())).thenReturn(1);

        CreateStockMovementRequest request = request(
                CreateStockMovementRequest.MovementTypeEnum.RETURN, "1", "BATCH-UDI",
                instanceExpiry);
        request.setUdi(UDI);
        StockMovement movement = service.createMovement(request, CALLER, CONTEXT);

        assertThat(movement.getBatchNo()).isEqualTo("BATCH-UDI");
        assertThat(movement.getExpiryDate()).isEqualTo(instanceExpiry);
        verify(udiMapper).markReturned(eq(UDI_ID), eq(1), any(), eq(ACTOR));
    }

    @Test
    void quantityScaleOverTwoDecimalsIsRejected() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(false, true));
        CreateStockMovementRequest request = request(
                CreateStockMovementRequest.MovementTypeEnum.IN, "1.001", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        assertThatThrownBy(() -> service.createMovement(request, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("2 位小数");
        verify(movementMapper, never()).insertMovement(any());
    }

    @Test
    void failureAuditCarriesBatchAndUdiDiDigestWithoutFullUdi() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(true, true));
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(null);
        CreateStockMovementRequest request = request(
                CreateStockMovementRequest.MovementTypeEnum.RETURN, "1", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        request.setUdi(UDI);
        assertThatThrownBy(() -> service.createMovement(request, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("不存在");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(failureAuditor).recordFailure(eq("STOCK_MOVE"), eq("STOCK_MOVEMENT"),
                eq(MATERIAL_ID.toString()), detail.capture(), eq(CALLER), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("udiDi", "06937000000001")
                .containsEntry("batchNo", "BATCH-1")
                .containsEntry("movementType", "RETURN");
        assertThat(String.valueOf(detail.getValue())).doesNotContain("SN-DEMO-001");
    }

    @Test
    void rejectsInactiveMaterialAndInvalidQuantity() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(null);
        CreateStockMovementRequest request = request(
                CreateStockMovementRequest.MovementTypeEnum.IN, "1", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        assertThatThrownBy(() -> service.createMovement(request, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("不存在");

        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(false, true));
        CreateStockMovementRequest zero = request(
                CreateStockMovementRequest.MovementTypeEnum.IN, "0", "BATCH-1",
                LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        assertThatThrownBy(() -> service.createMovement(zero, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("大于 0");
    }

    private static CreateStockMovementRequest request(
            CreateStockMovementRequest.MovementTypeEnum type, String quantity, String batchNo,
            LocalDate expiry) {
        CreateStockMovementRequest request = new CreateStockMovementRequest();
        request.setMovementType(type);
        request.setMaterialId(MATERIAL_ID.toString());
        request.setBatchNo(batchNo);
        request.setExpiryDate(expiry);
        request.setQuantity(new BigDecimal(quantity));
        request.setDepartmentId(DEPT);
        request.setCounterparty("中心库房（合成）");
        return request;
    }

    private static MaterialEntity material(boolean highValue, boolean expiryManaged) {
        MaterialEntity entity = new MaterialEntity();
        entity.setId(MATERIAL_ID);
        entity.setCode("MAT-CVC-001");
        entity.setName("中心静脉导管（合成）");
        entity.setSpecification("7F 三腔");
        entity.setUnit("套");
        entity.setCategory("CATHETER");
        entity.setUdiDi("06937000000001");
        entity.setHighValue(highValue);
        entity.setExpiryManaged(expiryManaged);
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        entity.setVersion(1);
        return entity;
    }

    private static MaterialStockEntity stock(int quantity) {
        return stock(quantity, LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
    }

    private static MaterialStockEntity stock(int quantity, LocalDate expiryDate) {
        MaterialStockEntity stock = new MaterialStockEntity();
        stock.setId(STOCK_ID);
        stock.setMaterialId(MATERIAL_ID);
        stock.setMaterialCode("MAT-CVC-001");
        stock.setMaterialName("中心静脉导管（合成）");
        stock.setBatchNo("BATCH-1");
        stock.setDepartmentId(UUID.fromString(DEPT));
        stock.setQuantity(new BigDecimal(quantity));
        stock.setUnit("套");
        stock.setExpiryDate(expiryDate);
        stock.setCreatedAt(NOW);
        stock.setUpdatedAt(NOW);
        stock.setVersion(1);
        return stock;
    }

    private static UdiInstanceEntity instance(String status) {
        UdiInstanceEntity entity = new UdiInstanceEntity();
        entity.setId(UDI_ID);
        entity.setUdiCode(UDI);
        entity.setUdiDi("06937000000001");
        entity.setUdiPi("SN-DEMO-001");
        entity.setMaterialId(MATERIAL_ID);
        entity.setMaterialCode("MAT-CVC-001");
        entity.setMaterialName("中心静脉导管（合成）");
        entity.setBatchNo("BATCH-1");
        entity.setExpiryDate(LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        entity.setDepartmentId(UUID.fromString(DEPT));
        entity.setStatus(status);
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        entity.setVersion(1);
        return entity;
    }

    private static com.anesop.m4.impl.persistence.entity.StockMovementEntity movement() {
        com.anesop.m4.impl.persistence.entity.StockMovementEntity entity =
                new com.anesop.m4.impl.persistence.entity.StockMovementEntity();
        entity.setId(UUID.fromString("4c000000-0000-4000-8000-000000000001"));
        entity.setMovementType("IN");
        entity.setMaterialId(MATERIAL_ID);
        entity.setMaterialCode("MAT-CVC-001");
        entity.setMaterialName("中心静脉导管（合成）");
        entity.setBatchNo("BATCH-1");
        entity.setQuantity(BigDecimal.ONE);
        entity.setUnit("套");
        entity.setDepartmentId(UUID.fromString(DEPT));
        entity.setCounterparty("中心库房（合成）");
        entity.setBalanceAfter(BigDecimal.ONE);
        entity.setOperatorId(ACTOR);
        entity.setOperatorName("synth_nurse");
        entity.setOccurredAt(NOW);
        entity.setCreatedAt(NOW);
        return entity;
    }
}
