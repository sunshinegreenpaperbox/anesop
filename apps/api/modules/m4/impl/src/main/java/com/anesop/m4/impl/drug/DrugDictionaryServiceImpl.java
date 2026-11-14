package com.anesop.m4.impl.drug;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.m4.api.drug.DrugDictionaryAmbiguousException;
import com.anesop.m4.api.drug.DrugDictionaryEntry;
import com.anesop.m4.api.drug.DrugDictionaryService;
import com.anesop.m4.impl.persistence.entity.DrugEntity;
import com.anesop.m4.impl.persistence.mapper.DrugMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 药品字典判定服务实现（M4-M15-01，收口 M3 T-4）。
 *
 * <p>只读 {@code m4_drug.drug}（含别名/通用名解析）：麻醉记录用药的麻精标志以本服务返回值为准，
 * 客户端声明只做一致性校验。停用药品仍返回属性（{@code active=false}），由调用方决定是否拒绝
 * 新录入；解析不到返回 {@link Optional#empty()}（调用方映射 400“药品未收录于药品字典”）。
 *
 * <p>归一化（trim + 忽略大小写）后命中多个不同药品时抛
 * {@link DrugDictionaryAmbiguousException}（fail-closed），绝不按名称优先级静默取第一条。
 */
@Service
public class DrugDictionaryServiceImpl implements DrugDictionaryService {

    private final DrugMapper drugMapper;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper 由容器管理，服务不修改其内部状态")
    public DrugDictionaryServiceImpl(DrugMapper drugMapper) {
        this.drugMapper = drugMapper;
    }

    @Override
    public Optional<DrugDictionaryEntry> findById(String drugId) {
        UUID id = parseUuid(drugId);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(drugMapper.selectByDrugId(id)).map(DrugDictionaryServiceImpl::entry);
    }

    @Override
    public Optional<DrugDictionaryEntry> findByName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 200) {
            return Optional.empty();
        }
        List<DrugEntity> candidates = drugMapper.selectCandidatesForDictionaryByName(name.trim());
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() > 1) {
            throw new DrugDictionaryAmbiguousException(candidates.size());
        }
        return Optional.of(entry(candidates.get(0)));
    }

    private static DrugDictionaryEntry entry(DrugEntity entity) {
        return new DrugDictionaryEntry(entity.getId().toString(), entity.getCode(), entity.getName(),
                Boolean.TRUE.equals(entity.getNarcotic()), Boolean.TRUE.equals(entity.getHighRisk()),
                "ACTIVE".equals(entity.getStatus()));
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
