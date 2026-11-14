package com.anesop.m4.impl.drug;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.drug.model.CreateDrugRequest;
import com.anesop.contracts.drug.model.Drug;
import com.anesop.contracts.drug.model.DrugList;
import com.anesop.contracts.drug.model.UpdateDrugRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.drug.DrugCatalogService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.impl.persistence.entity.DrugAliasEntity;
import com.anesop.m4.impl.persistence.entity.DrugEntity;
import com.anesop.m4.impl.persistence.mapper.DrugAliasMapper;
import com.anesop.m4.impl.persistence.mapper.DrugMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 药品目录服务实现（契约 drug.yaml 0.1.0，M4-M15-01）。
 *
 * <p>约束：编码/名称/通用名按 trim + 忽略大小写唯一；别名全局唯一，名称/通用名不得撞他人
 * 别名、别名不得撞他人名称/通用名（否则按名称解析麻精属性会歧义，冲突 409）；编码创建后
 * 不可改；更新为乐观锁（过期 409）。失败路径写 result=0 审计（detail 不含备注/患者信息）。
 * 目录全院可读，维护需要 {@code m4:drug:write}（由 Web 层校验）。
 * 审计 DRUG_CREATE/DRUG_UPDATE 只含标识、编码、名称与 narcotic/highRisk/status 摘要。
 */
@Service
public class DrugCatalogServiceImpl implements DrugCatalogService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    /** 新增时用于排除自身的哨兵 UUID（全 0，不会与真实主键冲突）。 */
    private static final String SENTINEL_ID = "00000000-0000-0000-0000-000000000000";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String RESOURCE_TYPE = "DRUG";
    private static final int MAX_ALIASES = 20;

    private final DrugMapper drugMapper;
    private final DrugAliasMapper aliasMapper;
    private final AuditWriter auditWriter;
    private final M4FailureAuditor failureAuditor;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/审计由容器管理，服务不修改其内部状态")
    public DrugCatalogServiceImpl(DrugMapper drugMapper, DrugAliasMapper aliasMapper,
            AuditWriter auditWriter, M4FailureAuditor failureAuditor, Clock clock) {
        this.drugMapper = drugMapper;
        this.aliasMapper = aliasMapper;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.clock = clock;
    }

    @Override
    public DrugList list(String keyword, String status, int page, int size) {
        if (page < 1) {
            throw new M4ValidationException("page", "页码必须大于等于 1");
        }
        if (size < 1 || size > 200) {
            throw new M4ValidationException("size", "每页条数必须在 1–200 之间");
        }
        String normalizedStatus = optionalText(status, "status", 16);
        if (normalizedStatus != null && !STATUS_ACTIVE.equals(normalizedStatus)
                && !STATUS_INACTIVE.equals(normalizedStatus)) {
            throw new M4ValidationException("status", "状态只能是 ACTIVE 或 INACTIVE");
        }
        String normalizedKeyword = optionalText(keyword, "keyword", 64);
        int offset = (page - 1) * size;
        List<DrugEntity> rows = drugMapper.selectPage(normalizedKeyword, normalizedStatus, size, offset);
        long total = drugMapper.countPage(normalizedKeyword, normalizedStatus);
        return toList(rows, page, size, total);
    }

    @Override
    public DrugList search(String q, int limit) {
        if (limit < 1 || limit > 50) {
            throw new M4ValidationException("limit", "返回条数必须在 1–50 之间");
        }
        String keyword = optionalText(q, "q", 64);
        if (keyword == null) {
            return toList(List.of(), 1, limit, 0);
        }
        List<DrugEntity> rows = drugMapper.searchActive(keyword, limit);
        return toList(rows, 1, limit, rows.size());
    }

    @Override
    public Drug get(String drugId) {
        return toDto(requireDrug(drugId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Drug create(CreateDrugRequest request, AuthPrincipal caller, AuthRequestContext context) {
        try {
            return doCreate(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("DRUG_CREATE", "NEW", failure, caller, context);
            throw failure;
        }
    }

    private Drug doCreate(CreateDrugRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        String code = requireText(request.getCode(), "code", 64);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new M4ValidationException("code", "药品编码只能包含字母、数字、点、下划线与连字符（1–64）");
        }
        String name = requireText(request.getName(), "name", 200);
        String genericName = optionalText(request.getGenericName(), "genericName", 200);
        String specification = requireText(request.getSpecification(), "specification", 100);
        String unit = requireText(request.getUnit(), "unit", 16);
        String dosageForm = requireText(request.getDosageForm(), "dosageForm", 32);
        String category = requireText(request.getCategory(), "category", 32);
        boolean narcotic = requireFlag(request.getNarcotic(), "narcotic");
        boolean highRisk = requireFlag(request.getHighRisk(), "highRisk");
        String remark = optionalText(request.getRemark(), "remark", 500);
        List<String> aliases = normalizeAliases(request.getAliases());

        requireNoConflict(code, name, genericName, null);
        requireAliasesFree(aliases, SENTINEL_ID);

        Instant now = now();
        DrugEntity entity = new DrugEntity();
        entity.setId(UUID.randomUUID());
        entity.setCode(code);
        entity.setName(name);
        entity.setGenericName(genericName);
        entity.setSpecification(specification);
        entity.setUnit(unit);
        entity.setDosageForm(dosageForm);
        entity.setCategory(category);
        entity.setNarcotic(narcotic);
        entity.setHighRisk(highRisk);
        entity.setStatus(STATUS_ACTIVE);
        entity.setRemark(remark);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(1);
        try {
            drugMapper.insertDrug(entity);
            insertAliases(entity.getId(), aliases, now, actorId(caller));
        } catch (DuplicateKeyException duplicate) {
            throw new M4ConflictException("药品编码/名称/通用名/别名已存在");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "DRUG_CREATE");
        detail.put("drugId", entity.getId().toString());
        detail.put("code", code);
        detail.put("name", name);
        detail.put("narcotic", narcotic);
        detail.put("highRisk", highRisk);
        detail.put("aliasCount", aliases.size());
        auditWriter.record("DRUG_CREATE", RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), username(caller), context);
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Drug update(String drugId, UpdateDrugRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        String resourceId = safeResourceId(drugId);
        try {
            return doUpdate(drugId, request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("DRUG_UPDATE", resourceId, failure, caller, context);
            throw failure;
        }
    }

    private Drug doUpdate(String drugId, UpdateDrugRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        DrugEntity existing = requireDrug(drugId);
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        Integer expectedVersion = request.getVersion();
        if (expectedVersion == null || expectedVersion < 1) {
            throw new M4ValidationException("version", "乐观锁版本号不能为空且必须大于等于 1");
        }
        String name = requireText(request.getName(), "name", 200);
        String genericName = optionalText(request.getGenericName(), "genericName", 200);
        String specification = requireText(request.getSpecification(), "specification", 100);
        String unit = requireText(request.getUnit(), "unit", 16);
        String dosageForm = requireText(request.getDosageForm(), "dosageForm", 32);
        String category = requireText(request.getCategory(), "category", 32);
        boolean narcotic = requireFlag(request.getNarcotic(), "narcotic");
        boolean highRisk = requireFlag(request.getHighRisk(), "highRisk");
        String remark = optionalText(request.getRemark(), "remark", 500);
        List<String> aliases = normalizeAliases(request.getAliases());

        if (!existing.getVersion().equals(expectedVersion)) {
            throw new M4ConflictException("药品已被修改，请刷新后重试");
        }
        requireNoConflict(existing.getCode(), name, genericName, existing.getId());
        requireAliasesFree(aliases, existing.getId().toString());

        boolean narcoticChanged = !Boolean.valueOf(narcotic).equals(existing.getNarcotic());
        Instant now = now();
        DrugEntity updated = new DrugEntity();
        updated.setId(existing.getId());
        updated.setName(name);
        updated.setGenericName(genericName);
        updated.setSpecification(specification);
        updated.setUnit(unit);
        updated.setDosageForm(dosageForm);
        updated.setCategory(category);
        updated.setNarcotic(narcotic);
        updated.setHighRisk(highRisk);
        updated.setStatus(existing.getStatus());
        updated.setRemark(remark);
        updated.setUpdatedAt(now);
        updated.setUpdatedBy(actorId(caller));
        updated.setVersion(expectedVersion);
        int affected;
        try {
            affected = drugMapper.updateDrug(updated);
        } catch (DuplicateKeyException duplicate) {
            throw new M4ConflictException("药品名称已存在");
        }
        if (affected == 0) {
            throw new M4ConflictException("药品已被修改，请刷新后重试");
        }
        try {
            aliasMapper.deleteByDrug(existing.getId());
            insertAliases(existing.getId(), aliases, now, actorId(caller));
        } catch (DuplicateKeyException duplicate) {
            throw new M4ConflictException("别名与其他药品的名称/通用名/别名冲突");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "DRUG_UPDATE");
        detail.put("drugId", existing.getId().toString());
        detail.put("code", existing.getCode());
        detail.put("narcotic", narcotic);
        detail.put("narcoticChanged", narcoticChanged);
        detail.put("highRisk", highRisk);
        detail.put("highRiskChanged", !Boolean.valueOf(highRisk).equals(existing.getHighRisk()));
        detail.put("version", expectedVersion);
        auditWriter.record("DRUG_UPDATE", RESOURCE_TYPE, existing.getId().toString(), true, detail,
                actorId(caller), username(caller), context);
        return get(existing.getId().toString());
    }

    private void requireNoConflict(String code, String name, String genericName, UUID excludeId) {
        String exclude = excludeId == null ? SENTINEL_ID : excludeId.toString();
        if (drugMapper.countCodeConflict(code, exclude) > 0) {
            throw new M4ConflictException("药品编码已存在");
        }
        if (drugMapper.countNameConflict(name, exclude) > 0) {
            throw new M4ConflictException("药品名称与其他药品的名称/通用名/别名冲突");
        }
        if (genericName != null && drugMapper.countNameConflict(genericName, exclude) > 0) {
            throw new M4ConflictException("药品通用名与其他药品的名称/通用名/别名冲突");
        }
    }

    private void requireAliasesFree(List<String> aliases, String excludeDrugId) {
        for (String alias : aliases) {
            if (aliasMapper.countAliasConflict(alias, excludeDrugId) > 0) {
                // 别名撞他人名称/通用名会让名称解析歧义；与唯一性冲突同按 409 处理。
                throw new M4ConflictException("别名与其他药品的名称/通用名/别名冲突");
            }
        }
    }

    private List<String> normalizeAliases(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_ALIASES) {
            throw new M4ValidationException("aliases", "别名数量不能超过 20 个");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        HashSet<String> normalized = new HashSet<>();
        for (String item : raw) {
            String alias = requireText(item, "aliases", 64);
            String key = alias.toLowerCase(Locale.ROOT);
            if (!normalized.add(key)) {
                throw new M4ValidationException("aliases", "别名不能重复（忽略大小写与首尾空白）");
            }
            unique.add(alias);
            // 保留展示用大小写；trim + 大小写不敏感的全局唯一由 SQL lower(btrim(alias)) 兜底。
        }
        return List.copyOf(unique);
    }

    private void insertAliases(UUID drugId, List<String> aliases, Instant now, UUID actor) {
        for (String alias : aliases) {
            DrugAliasEntity entity = new DrugAliasEntity();
            entity.setId(UUID.randomUUID());
            entity.setDrugId(drugId);
            entity.setAlias(alias);
            entity.setCreatedAt(now);
            entity.setCreatedBy(actor);
            aliasMapper.insertAlias(entity);
        }
    }

    private DrugEntity requireDrug(String drugId) {
        UUID id = parseUuid(drugId);
        DrugEntity entity = id == null ? null : drugMapper.selectByDrugId(id);
        if (entity == null) {
            throw new NoSuchElementException("药品不存在或不可见");
        }
        return entity;
    }

    private DrugList toList(List<DrugEntity> rows, int page, int size, long total) {
        DrugList result = new DrugList();
        List<Drug> items = new ArrayList<>();
        for (DrugEntity row : rows) {
            items.add(toDto(row));
        }
        result.setItems(items);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(Math.toIntExact(total));
        result.setTotalPages(total == 0 ? 0 : (int) ((total + size - 1) / size));
        return result;
    }

    private Drug toDto(DrugEntity entity) {
        Drug drug = new Drug();
        drug.setDrugId(entity.getId().toString());
        drug.setCode(entity.getCode());
        drug.setName(entity.getName());
        drug.setGenericName(entity.getGenericName());
        drug.setSpecification(entity.getSpecification());
        drug.setUnit(entity.getUnit());
        drug.setDosageForm(entity.getDosageForm());
        drug.setCategory(entity.getCategory());
        drug.setNarcotic(Boolean.TRUE.equals(entity.getNarcotic()));
        drug.setHighRisk(Boolean.TRUE.equals(entity.getHighRisk()));
        drug.setAliases(aliasMapper.selectAliases(entity.getId()));
        drug.setStatus(Drug.StatusEnum.fromValue(entity.getStatus()));
        drug.setRemark(entity.getRemark());
        drug.setCreatedAt(entity.getCreatedAt().atOffset(ZoneOffset.ofHours(8)));
        drug.setCreatedBy(entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString());
        drug.setUpdatedAt(entity.getUpdatedAt().atOffset(ZoneOffset.ofHours(8)));
        drug.setUpdatedBy(entity.getUpdatedBy() == null ? null : entity.getUpdatedBy().toString());
        drug.setVersion(entity.getVersion());
        return drug;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private static UUID actorId(AuthPrincipal caller) {
        return caller == null ? null : caller.userId();
    }

    private static String username(AuthPrincipal caller) {
        return caller == null ? null : caller.username();
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new M4ValidationException(field, field + " 长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new M4ValidationException(field, field + " 长度不能超过 " + maxLength);
        }
        return trimmed;
    }

    private static boolean requireFlag(Boolean value, String field) {
        if (value == null) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        return value;
    }

    private void recordFailure(String action, String resourceId, RuntimeException failure,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", action);
        if (failure instanceof M4ValidationException validation) {
            detail.put("rejection", "PARAM_INVALID");
            detail.put("field", validation.field());
        } else if (failure instanceof M4UnprocessableException unprocessable) {
            detail.put("rejection", "BUSINESS_RULE_VIOLATION");
            detail.put("field", unprocessable.field());
        } else {
            detail.put("rejection", "STATE_CONFLICT");
        }
        failureAuditor.recordFailure(action, RESOURCE_TYPE, resourceId, detail, caller, context);
    }

    private static String safeResourceId(String value) {
        return parseUuid(value) == null ? "INVALID" : value.trim();
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
