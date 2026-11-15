package com.anesop.m4.impl.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.material.model.LowStockThresholdRequest;
import com.anesop.contracts.material.model.Material;
import com.anesop.contracts.material.model.MaterialStockAlert;
import com.anesop.contracts.material.model.MaterialStockAlertList;
import com.anesop.contracts.material.model.StockAlertType;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.material.MaterialCatalogService;
import com.anesop.m4.impl.persistence.entity.MaterialEntity;
import com.anesop.m4.impl.persistence.entity.MaterialStockAlertRow;
import com.anesop.m4.impl.persistence.mapper.MaterialMapper;
import com.anesop.m4.impl.persistence.mapper.MaterialStockMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.anesop.m4.impl.support.MaterialAccessGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
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
 * 耗材库存预警与阈值维护单测（M8-M4-02，material.yaml 0.2.0）。
 *
 * <p>固定“今天”= 2026-11-15（Asia/Shanghai）与 SYNTHETIC 聚合行，覆盖：
 * 阈值严格边界（可用量 = 阈值不预警；低于阈值预警）、零库存、临期（含已过期为负天数）、
 * 建议补货量 = max(0, 阈值 − 可用量) 与阈值缺失为 null、类型过滤与 limit/alertType 400、
 * DataScope fail-closed（SELF/ROOM 403）、科室名回退、阈值维护乐观锁/校验/审计/404/403。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialAlertServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID MATERIAL_ID = UUID.fromString("4a000000-0000-4000-8000-000000000001");
    private static final UUID DEPT = UUID.fromString("f1000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-11-15");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_material", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-6201");

    @Mock
    private MaterialMapper materialMapper;
    @Mock
    private MaterialStockMapper stockMapper;
    @Mock
    private MaterialCatalogService catalogService;
    @Mock
    private MaterialAccessGuard accessGuard;
    @Mock
    private OrganizationQueryService organizationQueryService;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private M4FailureAuditor failureAuditor;

    private MaterialAlertServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.ofHours(8)).toInstant(),
                ZoneOffset.ofHours(8));
        service = new MaterialAlertServiceImpl(materialMapper, stockMapper, catalogService,
                accessGuard, organizationQueryService, auditWriter, failureAuditor, clock);
        when(accessGuard.scope(any())).thenReturn(DataScopeAssignments.of(
                List.of(new DataScopeAssignment(DataScope.ALL, List.of()))));
        when(accessGuard.visibleDepartmentIds(any(), any())).thenReturn(null);
        when(accessGuard.optionalDepartmentFilter(any(), any(), any(), any())).thenReturn(null);
        when(organizationQueryService.visibleDepartments(any()))
                .thenReturn(List.of(new OrgDepartment(DEPT.toString(), "DEPT-ANES", "麻醉科（合成）")));
    }

    // ------------------------------------------------------------------
    // 预警类型与建议补货量
    // ------------------------------------------------------------------

    @Test
    void stockAlertsMapsTypesSuggestedQuantityAndDepartmentName() {
        when(stockMapper.selectStockAlerts(isNull(), isNull(), eq(TODAY.plusDays(90)), isNull(),
                eq(100))).thenReturn(List.of(
                        row(2, "10", 1, LocalDate.parse("2027-01-31"), 0),
                        row(0, null, 0, null, 0),
                        row(5, null, 2, TODAY.plusDays(10), 1)));

        MaterialStockAlertList result = service.stockAlerts(null, null, 100, CALLER);

        assertThat(result.getTotal()).isEqualTo(3);
        MaterialStockAlert low = result.getItems().get(0);
        assertThat(low.getAlertTypes()).containsExactly(StockAlertType.LOW_STOCK);
        assertThat(low.getSuggestedQuantity()).isEqualByComparingTo("8");
        assertThat(low.getAvailableQuantity()).isEqualByComparingTo("2");
        assertThat(low.getDepartmentName()).isEqualTo("麻醉科（合成）");
        assertThat(low.getDaysToExpiry()).isEqualTo(77);

        MaterialStockAlert zero = result.getItems().get(1);
        assertThat(zero.getAlertTypes()).containsExactly(StockAlertType.ZERO_STOCK);
        assertThat(zero.getSuggestedQuantity()).isNull();
        assertThat(zero.getDaysToExpiry()).isNull();

        MaterialStockAlert near = result.getItems().get(2);
        assertThat(near.getAlertTypes()).containsExactly(StockAlertType.NEAR_EXPIRY);
        assertThat(near.getDaysToExpiry()).isEqualTo(10);
        assertThat(near.getNearExpiryBatchCount()).isEqualTo(1);
        assertThat(near.getSuggestedQuantity()).isNull();
    }

    @Test
    void thresholdBoundaryIsStrictlyBelowAndSuggestionIsCappedAtZero() {
        when(stockMapper.selectStockAlerts(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row(10, "10", 1, null, 0), row(9.99, "10", 1, null, 0)));

        MaterialStockAlertList result = service.stockAlerts(null, null, 100, CALLER);

        // 可用量 = 阈值：不预警（严格低于）；小于阈值 0.01：预警且补货量 0.01。
        assertThat(result.getItems().get(0).getAlertTypes()).isEmpty();
        assertThat(result.getItems().get(0).getSuggestedQuantity()).isNull();
        assertThat(result.getItems().get(1).getAlertTypes())
                .containsExactly(StockAlertType.LOW_STOCK);
        assertThat(result.getItems().get(1).getSuggestedQuantity())
                .isEqualByComparingTo("0.01");
    }

    @Test
    void zeroStockWithThresholdSuggestsThresholdAndCombinesTypes() {
        when(stockMapper.selectStockAlerts(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row(0, "10", 1, TODAY.plusDays(3), 1)));

        MaterialStockAlert alert = service.stockAlerts(null, null, 100, CALLER).getItems().get(0);

        assertThat(alert.getAlertTypes()).containsExactly(StockAlertType.LOW_STOCK,
                StockAlertType.ZERO_STOCK, StockAlertType.NEAR_EXPIRY);
        assertThat(alert.getSuggestedQuantity()).isEqualByComparingTo("10");
        assertThat(alert.getDaysToExpiry()).isEqualTo(3);
    }

    @Test
    void expiredBatchReportsNegativeDaysToExpiry() {
        when(stockMapper.selectStockAlerts(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row(3, null, 1, TODAY.minusDays(4), 1)));

        MaterialStockAlert alert = service.stockAlerts(null, null, 100, CALLER).getItems().get(0);

        assertThat(alert.getDaysToExpiry()).isEqualTo(-4);
        assertThat(alert.getAlertTypes()).containsExactly(StockAlertType.NEAR_EXPIRY);
    }

    // ------------------------------------------------------------------
    // 参数与数据范围
    // ------------------------------------------------------------------

    @Test
    void alertTypeIsNormalizedAndPassedToQuery() {
        service.stockAlerts(null, " near_expiry ", 50, CALLER);

        verify(stockMapper).selectStockAlerts(isNull(), isNull(), eq(TODAY.plusDays(90)),
                eq("NEAR_EXPIRY"), eq(50));
        assertThatThrownBy(() -> service.stockAlerts(null, "EXPIRED", 50, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("alertType");
    }

    @Test
    void limitIsValidated() {
        assertThatThrownBy(() -> service.stockAlerts(null, null, 0, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("1–200");
        assertThatThrownBy(() -> service.stockAlerts(null, null, 201, CALLER))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("1–200");
    }

    @Test
    void departmentFilterIsValidatedThroughGuard() {
        when(accessGuard.optionalDepartmentFilter(any(), any(), eq(DEPT.toString()), eq("departmentId")))
                .thenReturn(DEPT.toString());

        service.stockAlerts(DEPT.toString(), null, 100, CALLER);

        verify(stockMapper).selectStockAlerts(eq(DEPT.toString()), isNull(), any(), isNull(),
                eq(100));
        verify(stockMapper, never()).selectStockAlerts(isNull(), isNull(), any(), isNull(),
                anyInt());
    }

    @Test
    void selfOrRoomScopeFailsClosed() {
        when(accessGuard.visibleDepartmentIds(any(), any()))
                .thenThrow(new AccessDeniedException("耗材库存仅按科室数据范围开放"));

        assertThatThrownBy(() -> service.stockAlerts(null, null, 100, CALLER))
                .isInstanceOf(AccessDeniedException.class);
        verify(stockMapper, never()).selectStockAlerts(any(), any(), any(), any(), anyInt());
    }

    @Test
    void unknownDepartmentNameFallsBackToIdentifier() {
        when(organizationQueryService.visibleDepartments(any())).thenReturn(List.of());
        when(stockMapper.selectStockAlerts(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row(2, "10", 1, null, 0)));

        MaterialStockAlert alert = service.stockAlerts(null, null, 100, CALLER).getItems().get(0);

        assertThat(alert.getDepartmentName()).isEqualTo(DEPT.toString());
    }

    // ------------------------------------------------------------------
    // 阈值维护
    // ------------------------------------------------------------------

    @Test
    void updateThresholdAuditsAndReturnsUpdatedMaterial() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(1, null));
        when(materialMapper.updateLowStockThreshold(eq(MATERIAL_ID), eq(new BigDecimal("10")),
                eq(1), any(), eq(ACTOR))).thenReturn(1);
        when(catalogService.get(MATERIAL_ID.toString())).thenReturn(materialDto("10", 2));

        Material updated = service.updateThreshold(MATERIAL_ID.toString(),
                thresholdRequest(new BigDecimal("10"), 1), CALLER, CONTEXT);

        assertThat(updated.getLowStockThreshold()).isEqualByComparingTo("10");
        assertThat(updated.getVersion()).isEqualTo(2);
        ArgumentCaptor<java.util.Map<String, Object>> captor =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditWriter).record(eq("MATERIAL_THRESHOLD_UPDATE"), eq("MATERIAL"),
                eq(MATERIAL_ID.toString()), eq(true), captor.capture(), eq(ACTOR),
                eq("synth_material"), eq(CONTEXT));
        assertThat(captor.getValue())
                .containsEntry("code", "MAT-CVC-001")
                .containsEntry("threshold", new BigDecimal("10"))
                .containsEntry("version", 2);
        assertThat(captor.getValue().get("previousThreshold")).isNull();
    }

    @Test
    void updateThresholdWithNullClearsThreshold() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(2,
                new BigDecimal("10")));
        when(materialMapper.updateLowStockThreshold(eq(MATERIAL_ID), isNull(), eq(2), any(),
                eq(ACTOR))).thenReturn(1);
        when(catalogService.get(MATERIAL_ID.toString())).thenReturn(materialDto(null, 3));

        Material updated = service.updateThreshold(MATERIAL_ID.toString(),
                thresholdRequest(null, 2), CALLER, CONTEXT);

        assertThat(updated.getLowStockThreshold()).isNull();
        verify(materialMapper).updateLowStockThreshold(eq(MATERIAL_ID), isNull(), eq(2), any(),
                eq(ACTOR));
    }

    @Test
    void updateThresholdRejectsInvalidThresholdAndVersion() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(1, null));

        assertThatThrownBy(() -> service.updateThreshold(MATERIAL_ID.toString(),
                thresholdRequest(new BigDecimal("-1"), 1), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("负数");
        assertThatThrownBy(() -> service.updateThreshold(MATERIAL_ID.toString(),
                thresholdRequest(new BigDecimal("1.234"), 1), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("2 位小数");
        assertThatThrownBy(() -> service.updateThreshold(MATERIAL_ID.toString(),
                thresholdRequest(new BigDecimal("12345678901.00"), 1), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("numeric");
        assertThatThrownBy(() -> service.updateThreshold(MATERIAL_ID.toString(),
                thresholdRequest(BigDecimal.ONE, null), CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("版本号");
        assertThatThrownBy(() -> service.updateThreshold(MATERIAL_ID.toString(), null, CALLER,
                CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("请求体");
        verify(materialMapper, never()).updateLowStockThreshold(any(), any(), anyInt(), any(),
                any());
    }

    @Test
    void updateThresholdVersionMismatchConflictsAndAuditsFailure() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(3, null));

        assertThatThrownBy(() -> service.updateThreshold(MATERIAL_ID.toString(),
                thresholdRequest(new BigDecimal("10"), 2), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("刷新");
        verify(failureAuditor).recordFailure(eq("MATERIAL_THRESHOLD_UPDATE"), eq("MATERIAL"),
                eq(MATERIAL_ID.toString()), anyMap(), eq(CALLER), eq(CONTEXT));
    }

    @Test
    void updateThresholdConcurrentChangeConflicts() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(1, null));
        when(materialMapper.updateLowStockThreshold(any(), any(), anyInt(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.updateThreshold(MATERIAL_ID.toString(),
                thresholdRequest(new BigDecimal("10"), 1), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class);
    }

    @Test
    void updateThresholdMissingMaterialIsNotFound() {
        when(materialMapper.selectByMaterialId(any())).thenReturn(null);

        assertThatThrownBy(() -> service.updateThreshold(MATERIAL_ID.toString(),
                thresholdRequest(BigDecimal.ONE, 1), CALLER, CONTEXT))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.updateThreshold("not-a-uuid",
                thresholdRequest(BigDecimal.ONE, 1), CALLER, CONTEXT))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void updateThresholdRequiresDepartmentDataScope() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(material(1, null));
        when(accessGuard.visibleDepartmentIds(any(), any()))
                .thenThrow(new AccessDeniedException("耗材库存仅按科室数据范围开放"));

        assertThatThrownBy(() -> service.updateThreshold(MATERIAL_ID.toString(),
                thresholdRequest(new BigDecimal("10"), 1), CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
        verify(materialMapper, never()).updateLowStockThreshold(any(), any(), anyInt(), any(),
                any());
    }

    // ------------------------------------------------------------------
    // 固定数据
    // ------------------------------------------------------------------

    private static MaterialStockAlertRow row(double available, String threshold, long batchCount,
            LocalDate nearestExpiry, long nearExpiryBatches) {
        MaterialStockAlertRow row = new MaterialStockAlertRow();
        row.setMaterialId(MATERIAL_ID);
        row.setMaterialCode("MAT-CVC-001");
        row.setMaterialName("中心静脉导管（演示）");
        row.setUnit("套");
        row.setDepartmentId(DEPT);
        row.setLowStockThreshold(threshold == null ? null : new BigDecimal(threshold));
        row.setAvailableQuantity(BigDecimal.valueOf(available));
        row.setBatchCount(batchCount);
        row.setNearestExpiryDate(nearestExpiry);
        row.setNearExpiryBatchCount(nearExpiryBatches);
        return row;
    }

    private static MaterialEntity material(int version, BigDecimal threshold) {
        MaterialEntity entity = new MaterialEntity();
        entity.setId(MATERIAL_ID);
        entity.setCode("MAT-CVC-001");
        entity.setName("中心静脉导管（演示）");
        entity.setStatus("ACTIVE");
        entity.setLowStockThreshold(threshold);
        entity.setVersion(version);
        return entity;
    }

    private static Material materialDto(String threshold, int version) {
        Material material = new Material();
        material.setMaterialId(MATERIAL_ID.toString());
        material.setCode("MAT-CVC-001");
        material.setName("中心静脉导管（演示）");
        material.setSpecification("7F 三腔");
        material.setUnit("套");
        material.setCategory("CATHETER");
        material.setHighValue(false);
        material.setExpiryManaged(true);
        material.setStatus(Material.StatusEnum.ACTIVE);
        material.setLowStockThreshold(threshold == null ? null : new BigDecimal(threshold));
        material.setVersion(version);
        return material;
    }

    private static LowStockThresholdRequest thresholdRequest(BigDecimal threshold, Integer version) {
        LowStockThresholdRequest request = new LowStockThresholdRequest();
        request.setThreshold(threshold);
        request.setVersion(version);
        return request;
    }
}
