package com.anesop.m4.impl.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.material.model.RegisterUdiInstanceRequest;
import com.anesop.contracts.material.model.UdiInstance;
import com.anesop.contracts.material.model.UdiTrace;
import com.anesop.contracts.material.model.UseUdiInstanceRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m4.api.material.RecordVisibilityPort;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.impl.persistence.entity.MaterialEntity;
import com.anesop.m4.impl.persistence.entity.MaterialStockEntity;
import com.anesop.m4.impl.persistence.entity.StockMovementEntity;
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

/**
 * UDI 一物一码单测（M4-M14-01）：重复登记/重复使用 409、UDI-DI 一致性、绑定记录校验、
 * 效期、库存扣减与追溯链。数据库唯一索引/状态机触发器由集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class UdiTraceServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final UUID MATERIAL_ID =
            UUID.fromString("4a000000-0000-4000-8000-000000000001");
    private static final UUID STOCK_ID = UUID.fromString("4b000000-0000-4000-8000-000000000001");
    private static final UUID UDI_ID = UUID.fromString("4d000000-0000-4000-8000-000000000001");
    private static final UUID RECORD_ID =
            UUID.fromString("7a000000-0000-4000-8000-000000000001");
    private static final UUID SCHEDULE_ID =
            UUID.fromString("6a000000-0000-4000-8000-000000000001");
    private static final String DEPT = "f1000000-0000-4000-8000-000000000002";
    private static final String UDI_DI = "06937000000001";
    private static final String UDI_PI = "SN-DEMO-001";
    private static final String UDI = UDI_DI + "^" + UDI_PI;
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_nurse", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-2003");
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
    private RecordVisibilityPort recordVisibilityPort;
    @Mock
    private MaterialAccessGuard accessGuard;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private M4FailureAuditor failureAuditor;

    private UdiTraceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UdiTraceServiceImpl(materialMapper, stockMapper, movementMapper, udiMapper,
                recordVisibilityPort, accessGuard, auditWriter, failureAuditor,
                Clock.fixed(NOW, ZoneOffset.ofHours(8)));
        lenient().when(accessGuard.scope(CALLER)).thenReturn(ALL);
        lenient().when(accessGuard.requireDepartment(eq(ALL), eq(CALLER), eq(DEPT),
                eq("departmentId"))).thenReturn(DEPT);
        lenient().when(accessGuard.isVisible(eq(ALL), eq(CALLER), any())).thenReturn(true);
    }

    @Test
    void registerCreatesInstanceStockAndInboundMovement() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material());
        when(udiMapper.countByUdiDiPi(eq(UDI_DI), eq(UDI_PI))).thenReturn(0L);
        when(udiMapper.insertUdi(any())).thenReturn(1);
        when(stockMapper.upsertIncrement(any())).thenReturn(1);
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any())).thenReturn(stock(1));
        when(movementMapper.insertMovement(any())).thenReturn(1);

        UdiInstance instance = service.register(registerRequest(), CALLER, CONTEXT);

        assertThat(instance.getUdi()).isEqualTo(UDI);
        assertThat(instance.getStatus()).isEqualTo(UdiInstance.StatusEnum.IN_STOCK);
        assertThat(instance.getVersion()).isEqualTo(1);
        verify(udiMapper).insertUdi(any());
        verify(movementMapper).insertMovement(any());
        verify(auditWriter).record(eq("UDI_REGISTER"), eq("UDI"), anyString(), eq(true), any(),
                eq(ACTOR), eq("synth_nurse"), eq(CONTEXT));
        verify(auditWriter).record(eq("STOCK_MOVE"), eq("STOCK_MOVEMENT"), anyString(), eq(true),
                any(), eq(ACTOR), eq("synth_nurse"), eq(CONTEXT));
    }

    @Test
    void registerRejectsDuplicateUdiAndUdiDiMismatch() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material());
        when(udiMapper.countByUdiDiPi(eq(UDI_DI), eq(UDI_PI))).thenReturn(1L);
        assertThatThrownBy(() -> service.register(registerRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("已登记");

        RegisterUdiInstanceRequest mismatch = registerRequest();
        mismatch.setUdiDi("06937000000009");
        assertThatThrownBy(() -> service.register(mismatch, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("不一致");
        verify(udiMapper, never()).insertUdi(any());
    }

    @Test
    void registerRejectsExpiredOrInactiveMaterial() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.register(registerRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("不存在");

        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material());
        RegisterUdiInstanceRequest expired = registerRequest();
        expired.setExpiryDate(LocalDate.now(ZoneOffset.ofHours(8)).minusDays(1));
        assertThatThrownBy(() -> service.register(expired, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("过效期");

        RegisterUdiInstanceRequest missing = registerRequest();
        missing.setExpiryDate(null);
        assertThatThrownBy(() -> service.register(missing, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("效期");
    }

    @Test
    void registerRejectsBatchExpiryMismatchAndDatabaseDuplicate() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material());
        when(udiMapper.countByUdiDiPi(eq(UDI_DI), eq(UDI_PI))).thenReturn(0L);
        when(udiMapper.insertUdi(any())).thenReturn(1).thenThrow(
                new org.springframework.dao.DuplicateKeyException("dup"));
        when(stockMapper.upsertIncrement(any())).thenReturn(0);
        assertThatThrownBy(() -> service.register(registerRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("效期");

        assertThatThrownBy(() -> service.register(registerRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("已登记");
    }

    @Test
    void registerRejectsUdiCombinedLengthOver160() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material());
        RegisterUdiInstanceRequest request = registerRequest();
        request.setUdiDi("D".repeat(64));
        request.setUdiPi("P".repeat(96));

        assertThatThrownBy(() -> service.register(request, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("160");
        verify(udiMapper, never()).insertUdi(any());
        verify(stockMapper, never()).upsertIncrement(any());
    }

    @Test
    void registerFailureAuditUsesUdiDiDigestWithoutFullUdi() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material());
        when(udiMapper.countByUdiDiPi(eq(UDI_DI), eq(UDI_PI))).thenReturn(1L);

        assertThatThrownBy(() -> service.register(registerRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("已登记");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(failureAuditor).recordFailure(eq("UDI_REGISTER"), eq("UDI"), eq(UDI_DI),
                detail.capture(), eq(CALLER), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("udiDi", UDI_DI);
        assertThat(String.valueOf(detail.getValue())).doesNotContain(UDI_PI);
    }

    @Test
    void useBindsRecordAndWritesOutboundMovement() {
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(instance("IN_STOCK"));
        when(recordVisibilityPort.findVisibleRecord(eq(RECORD_ID.toString()), eq(CALLER))).thenReturn(record());
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any())).thenReturn(stock(2));
        when(stockMapper.decrement(eq(STOCK_ID), eq(BigDecimal.ONE), any())).thenReturn(1);
        when(stockMapper.selectQuantity(STOCK_ID)).thenReturn(BigDecimal.ONE);
        when(udiMapper.markUsed(eq(UDI_ID), eq(1), eq(RECORD_ID), eq(SCHEDULE_ID), any(), eq(ACTOR),
                eq("synth_nurse"), any(), eq(ACTOR))).thenReturn(1);
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material());
        when(movementMapper.insertMovement(any())).thenReturn(1);
        UdiInstanceEntity used = instance("USED");
        used.setRecordId(RECORD_ID);
        used.setScheduleId(SCHEDULE_ID);
        when(udiMapper.selectByUdiCode(UDI)).thenReturn(used);

        UdiInstance result = service.use(UDI, useRequest(), CALLER, CONTEXT);

        assertThat(result.getStatus()).isEqualTo(UdiInstance.StatusEnum.USED);
        assertThat(result.getRecordId()).isEqualTo(RECORD_ID.toString());
        verify(auditWriter).record(eq("UDI_USE"), eq("UDI"), anyString(), eq(true), any(),
                eq(ACTOR), eq("synth_nurse"), eq(CONTEXT));
        verify(auditWriter).record(eq("STOCK_MOVE"), eq("STOCK_MOVEMENT"), anyString(), eq(true),
                any(), eq(ACTOR), eq("synth_nurse"), eq(CONTEXT));
    }

    @Test
    void useRejectsRepeatedUseAndUnknownUdi() {
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(instance("USED"));
        assertThatThrownBy(() -> service.use(UDI, useRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("已使用");

        when(udiMapper.selectByUdiCodeForUpdate("0000^X")).thenReturn(null);
        assertThatThrownBy(() -> service.use("0000^X", useRequest(), CALLER, CONTEXT))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void useRejectsInvisibleRecordAndScheduleMismatch() {
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(instance("IN_STOCK"));
        when(recordVisibilityPort.findVisibleRecord(eq(RECORD_ID.toString()), eq(CALLER)))
                .thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.use(UDI, useRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("记录");

        when(recordVisibilityPort.findVisibleRecord(eq(RECORD_ID.toString()), eq(CALLER))).thenReturn(record());
        UseUdiInstanceRequest mismatch = useRequest();
        mismatch.setScheduleId("6a000000-0000-4000-8000-000000000099");
        assertThatThrownBy(() -> service.use(UDI, mismatch, CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("台次");
        verify(udiMapper, never()).markUsed(any(), anyInt(), any(), any(), any(), any(), any(),
                any(), any());
    }

    @Test
    void useRejectsExpiredInstanceAndStockShortage() {
        UdiInstanceEntity expired = instance("IN_STOCK");
        expired.setExpiryDate(LocalDate.now(ZoneOffset.ofHours(8)).minusDays(1));
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(expired);
        assertThatThrownBy(() -> service.use(UDI, useRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4UnprocessableException.class).hasMessageContaining("过效期");

        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(instance("IN_STOCK"));
        when(recordVisibilityPort.findVisibleRecord(eq(RECORD_ID.toString()), eq(CALLER))).thenReturn(record());
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any())).thenReturn(null);
        assertThatThrownBy(() -> service.use(UDI, useRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("库存不足");

        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any())).thenReturn(stock(1));
        when(stockMapper.decrement(eq(STOCK_ID), eq(BigDecimal.ONE), any())).thenReturn(0);
        when(stockMapper.selectQuantity(STOCK_ID)).thenReturn(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.use(UDI, useRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("库存不足");
    }

    @Test
    void useRejectsConcurrentStatusChange() {
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(instance("IN_STOCK"));
        when(recordVisibilityPort.findVisibleRecord(eq(RECORD_ID.toString()), eq(CALLER))).thenReturn(record());
        when(stockMapper.selectByBatch(eq(MATERIAL_ID), eq("BATCH-1"), any())).thenReturn(stock(1));
        when(stockMapper.decrement(eq(STOCK_ID), eq(BigDecimal.ONE), any())).thenReturn(1);
        when(stockMapper.selectQuantity(STOCK_ID)).thenReturn(BigDecimal.ZERO);
        when(udiMapper.markUsed(any(), anyInt(), any(), any(), any(), any(), any(), any(),
                any())).thenReturn(0);

        assertThatThrownBy(() -> service.use(UDI, useRequest(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("刷新");
        verify(movementMapper, never()).insertMovement(any());
    }

    @Test
    void traceReturnsMaterialUdiAndRecordLink() {
        when(udiMapper.selectScoped(eq(UDI), any())).thenReturn(instance("USED"));
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material());
        when(movementMapper.selectByUdi(eq(UDI), any())).thenReturn(List.of(movement()));

        UdiTrace trace = service.trace(UDI, CALLER);

        assertThat(trace.getInstance().getStatus()).isEqualTo(UdiInstance.StatusEnum.USED);
        assertThat(trace.getMaterial().getCode()).isEqualTo("MAT-CVC-001");
        assertThat(trace.getMovements()).hasSize(1);
        assertThat(trace.getRecord().getRecordId()).isEqualTo(RECORD_ID.toString());
        assertThat(trace.getRecord().getScheduleId()).isEqualTo(SCHEDULE_ID.toString());
    }

    @Test
    void traceHidesUnknownOrInvisibleUdi() {
        when(udiMapper.selectScoped(eq(UDI), any())).thenReturn(null);
        assertThatThrownBy(() -> service.trace(UDI, CALLER))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.trace("  ", CALLER))
                .isInstanceOf(M4ValidationException.class);
    }

    @Test
    void useValidatesRecordIdFormat() {
        when(udiMapper.selectByUdiCodeForUpdate(UDI)).thenReturn(instance("IN_STOCK"));
        UseUdiInstanceRequest bad = useRequest();
        bad.setRecordId("not-a-uuid");
        assertThatThrownBy(() -> service.use(UDI, bad, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("记录");
    }

    private static RegisterUdiInstanceRequest registerRequest() {
        RegisterUdiInstanceRequest request = new RegisterUdiInstanceRequest();
        request.setMaterialId(MATERIAL_ID.toString());
        request.setUdiDi(UDI_DI);
        request.setUdiPi(UDI_PI);
        request.setSerialNumber(UDI_PI);
        request.setBatchNo("BATCH-1");
        request.setExpiryDate(LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        request.setDepartmentId(DEPT);
        request.setCounterparty("中心库房（合成）");
        return request;
    }

    private static UseUdiInstanceRequest useRequest() {
        UseUdiInstanceRequest request = new UseUdiInstanceRequest();
        request.setRecordId(RECORD_ID.toString());
        request.setScheduleId(SCHEDULE_ID.toString());
        return request;
    }

    private static MaterialEntity material() {
        MaterialEntity entity = new MaterialEntity();
        entity.setId(MATERIAL_ID);
        entity.setCode("MAT-CVC-001");
        entity.setName("中心静脉导管（合成）");
        entity.setSpecification("7F 三腔");
        entity.setUnit("套");
        entity.setCategory("CATHETER");
        entity.setUdiDi(UDI_DI);
        entity.setHighValue(true);
        entity.setExpiryManaged(true);
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        entity.setVersion(1);
        return entity;
    }

    private static MaterialStockEntity stock(int quantity) {
        MaterialStockEntity stock = new MaterialStockEntity();
        stock.setId(STOCK_ID);
        stock.setMaterialId(MATERIAL_ID);
        stock.setMaterialCode("MAT-CVC-001");
        stock.setMaterialName("中心静脉导管（合成）");
        stock.setBatchNo("BATCH-1");
        stock.setDepartmentId(UUID.fromString(DEPT));
        stock.setQuantity(new BigDecimal(quantity));
        stock.setUnit("套");
        stock.setExpiryDate(LocalDate.now(ZoneOffset.ofHours(8)).plusYears(1));
        stock.setCreatedAt(NOW);
        stock.setUpdatedAt(NOW);
        stock.setVersion(1);
        return stock;
    }

    private static UdiInstanceEntity instance(String status) {
        UdiInstanceEntity entity = new UdiInstanceEntity();
        entity.setId(UDI_ID);
        entity.setUdiCode(UDI);
        entity.setUdiDi(UDI_DI);
        entity.setUdiPi(UDI_PI);
        entity.setSerialNumber(UDI_PI);
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
        if ("USED".equals(status)) {
            entity.setRecordId(RECORD_ID);
            entity.setScheduleId(SCHEDULE_ID);
            entity.setUsedAt(NOW);
            entity.setUsedBy(ACTOR);
        }
        return entity;
    }

    private static java.util.Optional<RecordVisibilityPort.RecordReference> record() {
        return java.util.Optional.of(new RecordVisibilityPort.RecordReference(
                RECORD_ID.toString(), SCHEDULE_ID.toString()));
    }

    private static StockMovementEntity movement() {
        StockMovementEntity entity = new StockMovementEntity();
        entity.setId(UUID.fromString("4c000000-0000-4000-8000-000000000001"));
        entity.setMovementType("OUT");
        entity.setMaterialId(MATERIAL_ID);
        entity.setMaterialCode("MAT-CVC-001");
        entity.setMaterialName("中心静脉导管（合成）");
        entity.setBatchNo("BATCH-1");
        entity.setUdiCode(UDI);
        entity.setQuantity(BigDecimal.ONE);
        entity.setUnit("套");
        entity.setDepartmentId(UUID.fromString(DEPT));
        entity.setCounterparty("术中使用");
        entity.setBalanceAfter(BigDecimal.ZERO);
        entity.setRecordId(RECORD_ID);
        entity.setScheduleId(SCHEDULE_ID);
        entity.setOperatorId(ACTOR);
        entity.setOperatorName("synth_nurse");
        entity.setOccurredAt(NOW);
        entity.setCreatedAt(NOW);
        return entity;
    }
}
