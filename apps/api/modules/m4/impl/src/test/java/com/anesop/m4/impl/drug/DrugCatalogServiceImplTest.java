package com.anesop.m4.impl.drug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.drug.model.CreateDrugRequest;
import com.anesop.contracts.drug.model.Drug;
import com.anesop.contracts.drug.model.DrugList;
import com.anesop.contracts.drug.model.UpdateDrugRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.impl.persistence.entity.DrugEntity;
import com.anesop.m4.impl.persistence.mapper.DrugAliasMapper;
import com.anesop.m4.impl.persistence.mapper.DrugMapper;
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

/**
 * 药品目录服务单测（M4-M15-01）：编码/名称唯一、别名冲突、乐观锁、别名替换与审计。
 *
 * <p>Mock Mapper 与审计；断言可观察行为（异常字段/返回 DTO/落库调用）。数据库唯一索引与
 * 触发器兜底由集成测试验证。
 */
@ExtendWith(MockitoExtension.class)
class DrugCatalogServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_pharmacist", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-1001");

    @Mock
    private DrugMapper drugMapper;
    @Mock
    private DrugAliasMapper aliasMapper;
    @Mock
    private AuditWriter auditWriter;
    @Mock
    private M4FailureAuditor failureAuditor;

    private DrugCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DrugCatalogServiceImpl(drugMapper, aliasMapper, auditWriter, failureAuditor,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createPersistsDrugAliasesAndAuditsWithoutRemark() {
        when(drugMapper.countCodeConflict(eq("DRUG-SUF-002"),
                eq("00000000-0000-0000-0000-000000000000"))).thenReturn(0L);
        when(drugMapper.countNameConflict(eq("舒芬太尼（测试）"),
                eq("00000000-0000-0000-0000-000000000000"))).thenReturn(0L);
        when(aliasMapper.countAliasConflict(eq("舒芬（测试）"),
                eq("00000000-0000-0000-0000-000000000000"))).thenReturn(0L);
        when(drugMapper.insertDrug(any())).thenReturn(1);
        when(aliasMapper.selectAliases(any())).thenReturn(List.of("舒芬（测试）"));

        Drug created = service.create(request(), CALLER, CONTEXT);

        assertThat(created.getDrugId()).isNotBlank();
        assertThat(created.getCode()).isEqualTo("DRUG-SUF-002");
        assertThat(created.getNarcotic()).isTrue();
        assertThat(created.getHighRisk()).isTrue();
        assertThat(created.getStatus()).isEqualTo(Drug.StatusEnum.ACTIVE);
        assertThat(created.getAliases()).containsExactly("舒芬（测试）");
        assertThat(created.getVersion()).isEqualTo(1);
        verify(aliasMapper).insertAlias(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("DRUG_CREATE"), eq("DRUG"), anyString(), eq(true),
                detail.capture(), eq(ACTOR), eq("synth_pharmacist"), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("narcotic", true)
                .containsEntry("code", "DRUG-SUF-002");
        assertThat(String.valueOf(detail.getValue())).doesNotContain("演示备注");
    }

    @Test
    void createRejectsDuplicateCodeAndNameAndAlias() {
        when(drugMapper.countCodeConflict(anyString(), anyString())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(request(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("编码");

        when(drugMapper.countCodeConflict(anyString(), anyString())).thenReturn(0L);
        when(drugMapper.countNameConflict(anyString(), anyString())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(request(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("名称");

        when(drugMapper.countNameConflict(anyString(), anyString())).thenReturn(0L);
        when(aliasMapper.countAliasConflict(anyString(), anyString())).thenReturn(1L);
        assertThatThrownBy(() -> service.create(request(), CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("别名");
        verify(drugMapper, never()).insertDrug(any());
        verify(failureAuditor, org.mockito.Mockito.times(3)).recordFailure(eq("DRUG_CREATE"),
                eq("DRUG"), eq("NEW"), any(), eq(CALLER), eq(CONTEXT));
    }

    @Test
    void createValidatesCodeQuantityAndAliases() {
        CreateDrugRequest badCode = request();
        badCode.setCode("编码 非 法");
        assertThatThrownBy(() -> service.create(badCode, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("编码");

        CreateDrugRequest blankName = request();
        blankName.setName(" ");
        assertThatThrownBy(() -> service.create(blankName, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);

        CreateDrugRequest nullFlag = request();
        nullFlag.setNarcotic(null);
        assertThatThrownBy(() -> service.create(nullFlag, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("narcotic");

        CreateDrugRequest duplicateAlias = request();
        duplicateAlias.setAliases(List.of("别名（测试）", "别名（测试）"));
        assertThatThrownBy(() -> service.create(duplicateAlias, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("重复");

        CreateDrugRequest tooManyAliases = request();
        tooManyAliases.setAliases(java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> "别名" + index).toList());
        assertThatThrownBy(() -> service.create(tooManyAliases, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("20");
        assertThatThrownBy(() -> service.create(null, CALLER, CONTEXT))
                .isInstanceOf(M4ValidationException.class);
    }

    @Test
    void updateRejectsStaleVersionAndReplacesAliases() {
        DrugEntity existing = entity(2);
        when(drugMapper.selectByDrugId(existing.getId())).thenReturn(existing);

        UpdateDrugRequest stale = updateRequest();
        stale.setVersion(1);
        assertThatThrownBy(() -> service.update(existing.getId().toString(), stale, CALLER, CONTEXT))
                .isInstanceOf(M4ConflictException.class)
                .hasMessageContaining("刷新");

        UpdateDrugRequest missingVersion = updateRequest();
        missingVersion.setVersion(null);
        assertThatThrownBy(() -> service.update(existing.getId().toString(), missingVersion, CALLER,
                CONTEXT))
                .isInstanceOf(M4ValidationException.class)
                .hasMessageContaining("版本");

        UpdateDrugRequest ok = updateRequest();
        ok.setVersion(2);
        when(drugMapper.countCodeConflict(anyString(), anyString())).thenReturn(0L);
        when(drugMapper.countNameConflict(anyString(), anyString())).thenReturn(0L);
        when(aliasMapper.countAliasConflict(anyString(), anyString())).thenReturn(0L);
        when(drugMapper.updateDrug(any())).thenReturn(1);
        when(aliasMapper.selectAliases(any())).thenReturn(List.of("新别名（测试）"));
        DrugEntity updated = entity(3);
        updated.setNarcotic(false);
        when(drugMapper.selectByDrugId(existing.getId())).thenReturn(existing, updated);

        Drug result = service.update(existing.getId().toString(), ok, CALLER, CONTEXT);

        assertThat(result.getVersion()).isEqualTo(3);
        assertThat(result.getNarcotic()).isFalse();
        verify(aliasMapper).deleteByDrug(existing.getId());
        verify(auditWriter).record(eq("DRUG_UPDATE"), eq("DRUG"), eq(existing.getId().toString()),
                eq(true), any(), eq(ACTOR), eq("synth_pharmacist"), eq(CONTEXT));
    }

    @Test
    void updateRejectsWhenOptimisticLockLosesRace() {
        DrugEntity existing = entity(1);
        when(drugMapper.selectByDrugId(existing.getId())).thenReturn(existing);
        when(drugMapper.countCodeConflict(anyString(), anyString())).thenReturn(0L);
        when(drugMapper.countNameConflict(anyString(), anyString())).thenReturn(0L);
        when(aliasMapper.countAliasConflict(anyString(), anyString())).thenReturn(0L);
        when(drugMapper.updateDrug(any())).thenReturn(0);

        UpdateDrugRequest request = updateRequest();
        request.setVersion(1);
        assertThatThrownBy(() -> service.update(existing.getId().toString(), request, CALLER,
                CONTEXT))
                .isInstanceOf(M4ConflictException.class);
        verify(auditWriter, never()).record(anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void getAndListMapContractFields() {
        DrugEntity row = entity(1);
        when(drugMapper.selectByDrugId(row.getId())).thenReturn(row);
        when(aliasMapper.selectAliases(row.getId())).thenReturn(List.of());

        Drug drug = service.get(row.getId().toString());
        assertThat(drug.getName()).isEqualTo("舒芬太尼（测试）");
        assertThat(drug.getCreatedAt()).isNotNull();

        assertThatThrownBy(() -> service.get("not-a-uuid"))
                .isInstanceOf(NoSuchElementException.class);
        when(drugMapper.selectByDrugId(row.getId())).thenReturn(null);
        assertThatThrownBy(() -> service.get(row.getId().toString()))
                .isInstanceOf(NoSuchElementException.class);

        when(drugMapper.selectPage(eq("舒芬"), eq("ACTIVE"), eq(20), eq(0)))
                .thenReturn(List.of(row));
        when(drugMapper.countPage("舒芬", "ACTIVE")).thenReturn(1L);
        DrugList list = service.list(" 舒芬 ", "ACTIVE", 1, 20);
        assertThat(list.getItems()).hasSize(1);
        assertThat(list.getTotal()).isEqualTo(1);
        assertThat(list.getTotalPages()).isEqualTo(1);

        when(drugMapper.searchActive("舒芬", 10)).thenReturn(List.of(row));
        DrugList search = service.search("舒芬", 10);
        assertThat(search.getItems()).hasSize(1);
        assertThat(service.search("   ", 10).getItems()).isEmpty();

        assertThatThrownBy(() -> service.list(null, null, 0, 20))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> service.list(null, null, 1, 201))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> service.list(null, "UNKNOWN", 1, 20))
                .isInstanceOf(M4ValidationException.class);
        assertThatThrownBy(() -> service.search("x", 51))
                .isInstanceOf(M4ValidationException.class);
    }

    private static CreateDrugRequest request() {
        CreateDrugRequest request = new CreateDrugRequest();
        request.setCode("DRUG-SUF-002");
        request.setName("舒芬太尼（测试）");
        request.setGenericName("枸橼酸舒芬太尼注射液（测试）");
        request.setSpecification("50ug/1ml");
        request.setUnit("支");
        request.setDosageForm("INJECTION");
        request.setCategory("OPIOID");
        request.setNarcotic(true);
        request.setHighRisk(true);
        request.setAliases(List.of("舒芬（测试）"));
        request.setRemark("演示备注");
        return request;
    }

    private static UpdateDrugRequest updateRequest() {
        UpdateDrugRequest request = new UpdateDrugRequest();
        request.setName("舒芬太尼（测试）");
        request.setGenericName("枸橼酸舒芬太尼注射液（测试）");
        request.setSpecification("50ug/1ml");
        request.setUnit("支");
        request.setDosageForm("INJECTION");
        request.setCategory("OPIOID");
        request.setNarcotic(false);
        request.setHighRisk(true);
        request.setAliases(List.of("新别名（测试）"));
        request.setVersion(2);
        return request;
    }

    private static DrugEntity entity(int version) {
        DrugEntity entity = new DrugEntity();
        entity.setId(UUID.fromString("2a000000-0000-4000-8000-000000000001"));
        entity.setCode("DRUG-SUF-001");
        entity.setName("舒芬太尼（测试）");
        entity.setGenericName("枸橼酸舒芬太尼注射液（测试）");
        entity.setSpecification("50ug/1ml");
        entity.setUnit("支");
        entity.setDosageForm("INJECTION");
        entity.setCategory("OPIOID");
        entity.setNarcotic(true);
        entity.setHighRisk(true);
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(NOW);
        entity.setCreatedBy(ACTOR);
        entity.setUpdatedAt(NOW);
        entity.setUpdatedBy(ACTOR);
        entity.setVersion(version);
        return entity;
    }
}
