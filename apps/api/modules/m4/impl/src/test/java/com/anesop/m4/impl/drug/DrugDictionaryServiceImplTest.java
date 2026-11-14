package com.anesop.m4.impl.drug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.m4.api.drug.DrugDictionaryAmbiguousException;
import com.anesop.m4.api.drug.DrugDictionaryEntry;
import com.anesop.m4.impl.persistence.entity.DrugEntity;
import com.anesop.m4.impl.persistence.mapper.DrugMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 药品字典判定单测（T-4 收口）：按标识/名称解析麻精与高危属性，未收录返回 empty。
 */
@ExtendWith(MockitoExtension.class)
class DrugDictionaryServiceImplTest {

    private static final UUID DRUG_ID = UUID.fromString("2a000000-0000-4000-8000-000000000001");

    @Mock
    private DrugMapper drugMapper;

    private DrugDictionaryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DrugDictionaryServiceImpl(drugMapper);
    }

    @Test
    void resolvesByIdAndNameWithTrim() {
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(entity());
        when(drugMapper.selectCandidatesForDictionaryByName("舒芬太尼（演示）"))
                .thenReturn(List.of(entity()));

        Optional<DrugDictionaryEntry> byId = service.findById(DRUG_ID.toString());
        assertThat(byId).isPresent();
        assertThat(byId.get().narcotic()).isTrue();
        assertThat(byId.get().highRisk()).isTrue();
        assertThat(byId.get().active()).isTrue();
        assertThat(byId.get().code()).isEqualTo("DRUG-SUF-001");

        Optional<DrugDictionaryEntry> byName = service.findByName("  舒芬太尼（演示） ");
        assertThat(byName).isPresent();
        assertThat(byName.get().name()).isEqualTo("舒芬太尼（演示）");
    }

    @Test
    void throwsAmbiguousWhenNormalizedNameMatchesDifferentDrugs() {
        when(drugMapper.selectCandidatesForDictionaryByName("歧义药（演示）"))
                .thenReturn(List.of(entity(), entity()));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.findByName("  歧义药（演示） "))
                .isInstanceOf(DrugDictionaryAmbiguousException.class)
                .satisfies(failure -> org.assertj.core.api.Assertions.assertThat(
                        ((DrugDictionaryAmbiguousException) failure).candidateCount()).isEqualTo(2));
    }

    @Test
    void returnsEmptyForUnknownOrInvalidInput() {
        assertThat(service.findById("not-a-uuid")).isEmpty();
        assertThat(service.findById(null)).isEmpty();
        assertThat(service.findByName(null)).isEmpty();
        assertThat(service.findByName("   ")).isEmpty();
        assertThat(service.findByName("x".repeat(201))).isEmpty();
        verify(drugMapper, never()).selectByDrugId(any());
    }

    @Test
    void marksInactiveDrugAsNotActive() {
        DrugEntity entity = entity();
        entity.setStatus("INACTIVE");
        when(drugMapper.selectByDrugId(DRUG_ID)).thenReturn(entity);
        assertThat(service.findById(DRUG_ID.toString()).orElseThrow().active()).isFalse();
    }

    private static DrugEntity entity() {
        DrugEntity drug = new DrugEntity();
        drug.setId(DRUG_ID);
        drug.setCode("DRUG-SUF-001");
        drug.setName("舒芬太尼（演示）");
        drug.setNarcotic(true);
        drug.setHighRisk(true);
        drug.setStatus("ACTIVE");
        drug.setVersion(1);
        return drug;
    }
}
