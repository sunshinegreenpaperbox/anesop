package com.anesop.m4.impl.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.material.model.CreateMaterialRequest;
import com.anesop.contracts.material.model.Material;
import com.anesop.contracts.material.model.MaterialList;
import com.anesop.contracts.material.model.MaterialStatusRequest;
import com.anesop.contracts.material.model.UpdateMaterialRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.impl.persistence.entity.MaterialEntity;
import com.anesop.m4.impl.persistence.mapper.MaterialMapper;
import com.anesop.m4.impl.persistence.mapper.MaterialStockMapper;
import com.anesop.m4.impl.persistence.mapper.UdiInstanceMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
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
import org.springframework.dao.DuplicateKeyException;

/**
 * 耗材目录单测（M4-M14-01）：编码/名称/UDI-DI 唯一、乐观锁、高值切换库存守卫、审计。
 *
 * <p>Mock Mapper 与审计；数据库唯一索引与触发器兜底由 M4MaterialApiIntegrationTest 验证。
 */
@ExtendWith(MockitoExtension.class)
class MaterialCatalogServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID MATERIAL_ID =
            UUID.fromString("4a000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_manager", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-2001");

    @Mock
    private MaterialMapper materialMapper;
    @Mock
    private MaterialStockMapper stockMapper;
    @Mock
    private UdiInstanceMapper udiMapper;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private M4FailureAuditor failureAuditor;

    private MaterialCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MaterialCatalogServiceImpl(materialMapper, stockMapper, udiMapper,
                auditWriter, failureAuditor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createPersistsAndAuditsWithoutRemark() {
        when(materialMapper.countCodeConflict(eq("MAT-CVC-001"),
                eq("00000000-0000-0000-0000-000000000000"))).thenReturn(0L);
        when(materialMapper.countNameConflict(eq("中心静脉导管（测试）"),
                eq("00000000-0000-0000-0000-000000000000"))).thenReturn(0L);
        when(materialMapper.countUdiDiConflict(eq("06937000000001"),
                eq("00000000-0000-0000-0000-000000000000"))).thenReturn(0L);
        when(materialMapper.insertMaterial(any())).thenReturn(1);

        Material created = service.create(request(), CALLER, CONTEXT);

        assertThat(created.getMaterialId()).isNotBlank();
        assertThat(created.getCode()).isEqualTo("MAT-CVC-001");
        assertThat(created.getHighValue()).isTrue();
        assertThat(created.getExpiryManaged()).isTrue();
        assertThat(created.getStatus()).isEqualTo(Material.StatusEnum.ACTIVE);
        assertThat(created.getVersion()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("MATERIAL_CREATE"), eq("MATERIAL"), anyString(), eq(true),
                detail.capture(), eq(ACTOR), eq("synth_manager"), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("code", "MAT-CVC-001")
                .containsEntry("highValue", true);
        assertThat(String.valueOf(detail.getValue())).doesNotContain("演示备注");
    }

    @Test
    void createRejectsDuplicateCodeNameAndUdiDi() {
        when(materialMapper.countCodeConflict(anyString(), anyString())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(request(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("编码");

        when(materialMapper.countCodeConflict(anyString(), anyString())).thenReturn(0L);
        when(materialMapper.countNameConflict(anyString(), anyString())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(request(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("名称");

        when(materialMapper.countNameConflict(anyString(), anyString())).thenReturn(0L);
        when(materialMapper.countUdiDiConflict(anyString(), anyString())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(request(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("UDI-DI");

        verify(materialMapper, never()).insertMaterial(any());
        verify(failureAuditor, times(3)).recordFailure(eq("MATERIAL_CREATE"), eq("MATERIAL"),
                eq("NEW"), any(), eq(CALLER), eq(CONTEXT));
    }

    @Test
    void createValidatesFieldsAndFlags() {
        CreateMaterialRequest badCode = request();
        badCode.setCode("编码 非 法");
        assertThatThrownBy(() -> service.create(badCode, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("编码");

        CreateMaterialRequest blankName = request();
        blankName.setName(" ");
        assertThatThrownBy(() -> service.create(blankName, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);

        CreateMaterialRequest badUdiDi = request();
        badUdiDi.setUdiDi("DI 非 法");
        assertThatThrownBy(() -> service.create(badUdiDi, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("UDI-DI");

        CreateMaterialRequest nullFlag = request();
        nullFlag.setHighValue(null);
        assertThatThrownBy(() -> service.create(nullFlag, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);

        CreateMaterialRequest longUnit = request();
        longUnit.setUnit("单位单位单位单位单位单位单位单位单位单位");
        assertThatThrownBy(() -> service.create(longUnit, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("unit");
    }

    @Test
    void createMapsDatabaseDuplicateToConflict() {
        when(materialMapper.countCodeConflict(anyString(), anyString())).thenReturn(0L);
        when(materialMapper.countNameConflict(anyString(), anyString())).thenReturn(0L);
        when(materialMapper.countUdiDiConflict(anyString(), anyString())).thenReturn(0L);
        when(materialMapper.insertMaterial(any())).thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.create(request(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("已存在");
    }

    @Test
    void listValidatesPagingAndMapsRows() {
        when(materialMapper.selectPage(eq("导管"), eq("ACTIVE"), eq(true), eq(20), eq(0)))
                .thenReturn(List.of(entity()));
        when(materialMapper.countPage(eq("导管"), eq("ACTIVE"), eq(true))).thenReturn(1L);

        MaterialList list = service.list(" 导管 ", "ACTIVE", true, 1, 20);

        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getTotal()).isEqualTo(1);
        assertThat(list.getTotalPages()).isEqualTo(1);
        assertThat(list.getItems().get(0).getUdiDi()).isEqualTo("06937000000001");

        assertThatThrownBy(() -> service.list(null, null, null, 0, 20))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> service.list(null, null, null, 1, 0))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> service.list(null, "UNKNOWN", null, 1, 20))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("状态");
    }

    @Test
    void searchReturnsEmptyForBlankQueryAndMapsRows() {
        MaterialList empty = service.search("  ", 10);
        assertThat(empty.getItems()).isEmpty();
        assertThat(empty.getTotal()).isZero();

        when(materialMapper.searchActive(eq("CVC"), eq(10))).thenReturn(List.of(entity()));
        MaterialList result = service.search("CVC", 10);
        assertThat(result.getItems()).hasSize(1);

        assertThatThrownBy(() -> service.search("x", 0))
                .isInstanceOf(M4ValidationException.class);
    }

    @Test
    void getHandlesMissingAndInvalidIds() {
        assertThatThrownBy(() -> service.get("not-a-uuid"))
                .isInstanceOf(NoSuchElementException.class);
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.get(MATERIAL_ID.toString()))
                .isInstanceOf(NoSuchElementException.class);
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(entity());
        assertThat(service.get(MATERIAL_ID.toString()).getCode()).isEqualTo("MAT-CVC-001");
    }

    @Test
    void updateUsesOptimisticLockAndRejectsHighValueSwitchWithStock() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID))
                .thenReturn(entity(), entity(), entity(2));
        when(materialMapper.countNameConflict(anyString(), anyString())).thenReturn(0L);
        when(materialMapper.countUdiDiConflict(anyString(), anyString())).thenReturn(0L);
        when(stockMapper.countPositiveByMaterial(MATERIAL_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.update(MATERIAL_ID.toString(), update(1, true), CALLER,
                CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("高值");
        verify(materialMapper, never()).updateMaterial(any());

        when(stockMapper.countPositiveByMaterial(MATERIAL_ID)).thenReturn(0L);
        when(materialMapper.updateMaterial(any())).thenReturn(1);
        Material updated = service.update(MATERIAL_ID.toString(), update(1, true), CALLER, CONTEXT);
        assertThat(updated.getVersion()).isEqualTo(2);
        verify(auditWriter).record(eq("MATERIAL_UPDATE"), eq("MATERIAL"), eq(MATERIAL_ID.toString()),
                eq(true), any(), eq(ACTOR), eq("synth_manager"), eq(CONTEXT));
    }

    @Test
    void updateRejectsStaleVersionAndDatabaseConflict() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(entity());
        assertThatThrownBy(() -> service.update(MATERIAL_ID.toString(), update(99, false), CALLER,
                CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("刷新");

        UpdateMaterialRequest missingVersion = update(1, false);
        missingVersion.setVersion(null);
        assertThatThrownBy(() -> service.update(MATERIAL_ID.toString(), missingVersion, CALLER,
                CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("版本号");

        when(materialMapper.countNameConflict(anyString(), anyString())).thenReturn(0L);
        when(materialMapper.countUdiDiConflict(anyString(), anyString())).thenReturn(0L);
        when(materialMapper.updateMaterial(any())).thenReturn(0);
        assertThatThrownBy(() -> service.update(MATERIAL_ID.toString(), update(1, false), CALLER,
                CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("刷新");

        when(materialMapper.updateMaterial(any())).thenThrow(new DuplicateKeyException("dup"));
        assertThatThrownBy(() -> service.update(MATERIAL_ID.toString(), update(1, false), CALLER,
                CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("名称");
    }

    @Test
    void statusChangeIsIdempotentWhenTargetEqualsCurrent() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(entity());
        MaterialStatusRequest request = statusRequest(MaterialStatusRequest.StatusEnum.ACTIVE);

        Material unchanged = service.changeStatus(MATERIAL_ID.toString(), request, CALLER, CONTEXT);

        assertThat(unchanged.getStatus()).isEqualTo(Material.StatusEnum.ACTIVE);
        assertThat(unchanged.getVersion()).isEqualTo(1);
        verify(materialMapper, never()).updateStatus(any(), anyString(), anyInt(), any(), any());
        verify(auditWriter, never()).record(anyString(), anyString(), anyString(), eq(true), any(),
                any(), any(), any());
    }

    @Test
    void statusChangeUpdatesVersionAndAudits() {
        MaterialEntity inactive = entity(2);
        inactive.setStatus("INACTIVE");
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(entity(), inactive);
        when(materialMapper.updateStatus(eq(MATERIAL_ID), eq("INACTIVE"), eq(1), any(), eq(ACTOR)))
                .thenReturn(1);
        MaterialStatusRequest request = statusRequest(MaterialStatusRequest.StatusEnum.INACTIVE);

        Material changed = service.changeStatus(MATERIAL_ID.toString(), request, CALLER, CONTEXT);

        assertThat(changed.getStatus()).isEqualTo(Material.StatusEnum.INACTIVE);
        assertThat(changed.getVersion()).isEqualTo(2);
        verify(auditWriter).record(eq("MATERIAL_STATUS"), eq("MATERIAL"),
                eq(MATERIAL_ID.toString()), eq(true), any(), eq(ACTOR), eq("synth_manager"),
                eq(CONTEXT));
    }

    @Test
    void statusChangeRejectsMissingStatusAndConcurrentUpdate() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(entity(), entity());
        MaterialStatusRequest missing = new MaterialStatusRequest();
        assertThatThrownBy(() -> service.changeStatus(MATERIAL_ID.toString(), missing, CALLER,
                CONTEXT))
                .isInstanceOf(M4ValidationException.class).hasMessageContaining("状态");

        when(materialMapper.updateStatus(any(), anyString(), anyInt(), any(), any())).thenReturn(0);
        MaterialStatusRequest inactive = statusRequest(MaterialStatusRequest.StatusEnum.INACTIVE);
        assertThatThrownBy(() -> service.changeStatus(MATERIAL_ID.toString(), inactive, CALLER,
                CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("刷新");
    }

    @Test
    void updateRejectsUdiDiChangeWhenUdiInstancesOrPositiveStockExist() {
        when(materialMapper.selectByMaterialId(MATERIAL_ID)).thenReturn(entity(), entity());
        when(udiMapper.countByMaterialId(MATERIAL_ID)).thenReturn(1L);

        UpdateMaterialRequest cleared = update(1, false);
        cleared.setUdiDi(null);
        assertThatThrownBy(() -> service.update(MATERIAL_ID.toString(), cleared, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("UDI-DI");
        verify(materialMapper, never()).updateMaterial(any());

        when(udiMapper.countByMaterialId(MATERIAL_ID)).thenReturn(0L);
        when(stockMapper.countPositiveByMaterial(MATERIAL_ID)).thenReturn(1L);
        UpdateMaterialRequest replaced = update(1, false);
        replaced.setUdiDi("06937000000002");
        assertThatThrownBy(() -> service.update(MATERIAL_ID.toString(), replaced, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class).hasMessageContaining("UDI-DI");
        verify(materialMapper, never()).updateMaterial(any());
    }

    private static MaterialStatusRequest statusRequest(MaterialStatusRequest.StatusEnum status) {
        MaterialStatusRequest request = new MaterialStatusRequest();
        request.setStatus(status);
        return request;
    }

    private static CreateMaterialRequest request() {
        CreateMaterialRequest request = new CreateMaterialRequest();
        request.setCode("MAT-CVC-001");
        request.setName("中心静脉导管（测试）");
        request.setSpecification("7F 三腔");
        request.setUnit("套");
        request.setCategory("CATHETER");
        request.setUdiDi("06937000000001");
        request.setHighValue(true);
        request.setExpiryManaged(true);
        request.setRemark("演示备注（合成）");
        return request;
    }

    private static UpdateMaterialRequest update(int version, boolean highValue) {
        UpdateMaterialRequest request = new UpdateMaterialRequest();
        request.setName("中心静脉导管（测试）");
        request.setSpecification("7F 三腔");
        request.setUnit("套");
        request.setCategory("CATHETER");
        request.setUdiDi("06937000000001");
        request.setHighValue(highValue);
        request.setExpiryManaged(true);
        request.setVersion(version);
        return request;
    }

    private static MaterialEntity entity() {
        return entity(1);
    }

    private static MaterialEntity entity(int version) {
        MaterialEntity entity = new MaterialEntity();
        entity.setId(MATERIAL_ID);
        entity.setCode("MAT-CVC-001");
        entity.setName("中心静脉导管（测试）");
        entity.setSpecification("7F 三腔");
        entity.setUnit("套");
        entity.setCategory("CATHETER");
        entity.setUdiDi("06937000000001");
        entity.setHighValue(false);
        entity.setExpiryManaged(true);
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        entity.setUpdatedAt(NOW);
        entity.setUpdatedBy(ACTOR);
        entity.setVersion(version);
        return entity;
    }
}
