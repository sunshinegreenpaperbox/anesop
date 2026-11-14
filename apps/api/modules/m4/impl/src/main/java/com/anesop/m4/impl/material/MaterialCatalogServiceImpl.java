package com.anesop.m4.impl.material;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.material.model.CreateMaterialRequest;
import com.anesop.contracts.material.model.Material;
import com.anesop.contracts.material.model.MaterialList;
import com.anesop.contracts.material.model.MaterialStatusRequest;
import com.anesop.contracts.material.model.UpdateMaterialRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.material.MaterialCatalogService;
import com.anesop.m4.impl.persistence.entity.MaterialEntity;
import com.anesop.m4.impl.persistence.mapper.MaterialMapper;
import com.anesop.m4.impl.persistence.mapper.MaterialStockMapper;
import com.anesop.m4.impl.persistence.mapper.UdiInstanceMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.anesop.m4.impl.support.M4Values;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 耗材目录服务实现（契约 material.yaml 0.1.0，M4-M14-01）。
 *
 * <p>约束：编码/名称按 trim + 忽略大小写唯一；UDI-DI 非空时唯一；编码创建后不可改；
 * 更新为乐观锁（过期 409）。目录全院可读，维护需要 {@code m4:material:write}（Web 层校验）。
 * highValue 由 false 改 true 时若仍有数量 &gt; 0 的批次库存 → 409（不允许出现无法追溯的高值库存）。
 * 审计 MATERIAL_CREATE/MATERIAL_UPDATE/MATERIAL_STATUS 只含标识、编码、名称与标志摘要。
 * 失败审计带 materialId/code/udiDi 摘要，便于定位且不含患者信息。
 */
@Service
public class MaterialCatalogServiceImpl implements MaterialCatalogService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    /** 新增时用于排除自身的哨兵 UUID（全 0，不会与真实主键冲突）。 */
    private static final String SENTINEL_ID = "00000000-0000-0000-0000-000000000000";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String RESOURCE_TYPE = "MATERIAL";

    private final MaterialMapper materialMapper;
    private final MaterialStockMapper stockMapper;
    private final UdiInstanceMapper udiMapper;
    private final AuditWriter auditWriter;
    private final M4FailureAuditor failureAuditor;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/审计由容器管理，服务不修改其内部状态")
    public MaterialCatalogServiceImpl(MaterialMapper materialMapper,
            MaterialStockMapper stockMapper, UdiInstanceMapper udiMapper, AuditWriter auditWriter,
            M4FailureAuditor failureAuditor, Clock clock) {
        this.materialMapper = materialMapper;
        this.stockMapper = stockMapper;
        this.udiMapper = udiMapper;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.clock = clock;
    }

    @Override
    public MaterialList list(String keyword, String status, Boolean highValue, int page, int size) {
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
        List<MaterialEntity> rows = materialMapper.selectPage(normalizedKeyword, normalizedStatus,
                highValue, size, offset);
        long total = materialMapper.countPage(normalizedKeyword, normalizedStatus, highValue);
        return toList(rows, page, size, total);
    }

    @Override
    public MaterialList search(String q, int limit) {
        if (limit < 1 || limit > 50) {
            throw new M4ValidationException("limit", "返回条数必须在 1–50 之间");
        }
        String keyword = optionalText(q, "q", 64);
        if (keyword == null) {
            return toList(List.of(), 1, limit, 0);
        }
        List<MaterialEntity> rows = materialMapper.searchActive(keyword, limit);
        return toList(rows, 1, limit, rows.size());
    }

    @Override
    public Material get(String materialId) {
        return toDto(requireMaterial(materialId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Material create(CreateMaterialRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        try {
            return doCreate(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("MATERIAL_CREATE", "NEW", failure,
                    locator(request == null ? null : request.getCode(),
                            request == null ? null : request.getUdiDi()),
                    caller, context);
            throw failure;
        }
    }

    private Material doCreate(CreateMaterialRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        String code = requireText(request.getCode(), "code", 64);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new M4ValidationException("code",
                    "耗材编码只能包含字母、数字、点、下划线与连字符（1–64）");
        }
        String name = requireText(request.getName(), "name", 200);
        String specification = requireText(request.getSpecification(), "specification", 100);
        String unit = requireText(request.getUnit(), "unit", 16);
        String category = requireText(request.getCategory(), "category", 32);
        String udiDi = optionalUdiDi(request.getUdiDi(), "udiDi");
        boolean highValue = requireFlag(request.getHighValue(), "highValue");
        boolean expiryManaged = requireFlag(request.getExpiryManaged(), "expiryManaged");
        String remark = optionalText(request.getRemark(), "remark", 500);

        requireNoConflict(code, name, udiDi, SENTINEL_ID);

        Instant now = now();
        MaterialEntity entity = new MaterialEntity();
        entity.setId(UUID.randomUUID());
        entity.setCode(code);
        entity.setName(name);
        entity.setSpecification(specification);
        entity.setUnit(unit);
        entity.setCategory(category);
        entity.setUdiDi(udiDi);
        entity.setHighValue(highValue);
        entity.setExpiryManaged(expiryManaged);
        entity.setStatus(STATUS_ACTIVE);
        entity.setRemark(remark);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(1);
        try {
            materialMapper.insertMaterial(entity);
        } catch (DuplicateKeyException duplicate) {
            throw new M4ConflictException("耗材编码/名称/UDI-DI 已存在");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "MATERIAL_CREATE");
        detail.put("materialId", entity.getId().toString());
        detail.put("code", code);
        detail.put("highValue", highValue);
        detail.put("expiryManaged", expiryManaged);
        auditWriter.record("MATERIAL_CREATE", RESOURCE_TYPE, entity.getId().toString(), true,
                detail, actorId(caller), username(caller), context);
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Material update(String materialId, UpdateMaterialRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        String resourceId = safeResourceId(materialId);
        try {
            return doUpdate(materialId, request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("MATERIAL_UPDATE", resourceId, failure,
                    locator(null, request == null ? null : request.getUdiDi()), caller, context);
            throw failure;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Material changeStatus(String materialId, MaterialStatusRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        String resourceId = safeResourceId(materialId);
        try {
            return doChangeStatus(materialId, request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            Map<String, Object> locator = new LinkedHashMap<>();
            if (request != null && request.getStatus() != null) {
                locator.put("targetStatus", request.getStatus().getValue());
            }
            recordFailure("MATERIAL_STATUS", resourceId, failure, locator, caller, context);
            throw failure;
        }
    }

    private Material doChangeStatus(String materialId, MaterialStatusRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        MaterialEntity existing = requireMaterial(materialId);
        if (request == null || request.getStatus() == null) {
            throw new M4ValidationException("status", "状态不能为空");
        }
        String target = request.getStatus().getValue();
        if (!STATUS_ACTIVE.equals(target) && !STATUS_INACTIVE.equals(target)) {
            throw new M4ValidationException("status", "状态只能是 ACTIVE 或 INACTIVE");
        }
        if (target.equals(existing.getStatus())) {
            // 幂等：状态未变化时不递增 version，避免前端重复点击造成无意义版本漂移。
            return toDto(existing);
        }
        Instant now = now();
        int affected = materialMapper.updateStatus(existing.getId(), target, existing.getVersion(),
                now, actorId(caller));
        if (affected == 0) {
            throw new M4ConflictException("耗材已被修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "MATERIAL_STATUS");
        detail.put("materialId", existing.getId().toString());
        detail.put("code", existing.getCode());
        detail.put("fromStatus", existing.getStatus());
        detail.put("status", target);
        detail.put("version", existing.getVersion() + 1);
        auditWriter.record("MATERIAL_STATUS", RESOURCE_TYPE, existing.getId().toString(), true,
                detail, actorId(caller), username(caller), context);
        return get(existing.getId().toString());
    }

    private Material doUpdate(String materialId, UpdateMaterialRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        MaterialEntity existing = requireMaterial(materialId);
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        Integer expectedVersion = request.getVersion();
        if (expectedVersion == null || expectedVersion < 1) {
            throw new M4ValidationException("version", "乐观锁版本号不能为空且必须大于等于 1");
        }
        String name = requireText(request.getName(), "name", 200);
        String specification = requireText(request.getSpecification(), "specification", 100);
        String unit = requireText(request.getUnit(), "unit", 16);
        String category = requireText(request.getCategory(), "category", 32);
        String udiDi = optionalUdiDi(request.getUdiDi(), "udiDi");
        boolean highValue = requireFlag(request.getHighValue(), "highValue");
        boolean expiryManaged = requireFlag(request.getExpiryManaged(), "expiryManaged");
        String remark = optionalText(request.getRemark(), "remark", 500);

        if (!existing.getVersion().equals(expectedVersion)) {
            throw new M4ConflictException("耗材已被修改，请刷新后重试");
        }
        String existingUdiDi = existing.getUdiDi();
        boolean udiDiChanged = !Objects.equals(
                existingUdiDi == null ? null : existingUdiDi.trim().toLowerCase(),
                udiDi == null ? null : udiDi.trim().toLowerCase());
        if (udiDiChanged && (udiMapper.countByMaterialId(existing.getId()) > 0
                || stockMapper.countPositiveByMaterial(existing.getId()) > 0)) {
            throw new M4ConflictException(
                    "该耗材存在 UDI 实例或数量大于 0 的批次库存，不能修改或清空 UDI-DI");
        }
        requireNoConflict(existing.getCode(), name, udiDi, existing.getId().toString());
        if (highValue && !Boolean.TRUE.equals(existing.getHighValue())
                && stockMapper.countPositiveByMaterial(existing.getId()) > 0) {
            throw new M4ConflictException("该耗材仍有数量大于 0 的批次库存，不能直接切换为高值耗材；"
                    + "请先清零或改为逐件 UDI 登记");
        }

        Instant now = now();
        MaterialEntity updated = new MaterialEntity();
        updated.setId(existing.getId());
        updated.setName(name);
        updated.setSpecification(specification);
        updated.setUnit(unit);
        updated.setCategory(category);
        updated.setUdiDi(udiDi);
        updated.setHighValue(highValue);
        updated.setExpiryManaged(expiryManaged);
        updated.setStatus(existing.getStatus());
        updated.setRemark(remark);
        updated.setUpdatedAt(now);
        updated.setUpdatedBy(actorId(caller));
        updated.setVersion(expectedVersion);
        int affected;
        try {
            affected = materialMapper.updateMaterial(updated);
        } catch (DuplicateKeyException duplicate) {
            throw new M4ConflictException("耗材名称或 UDI-DI 已存在");
        }
        if (affected == 0) {
            throw new M4ConflictException("耗材已被修改，请刷新后重试");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "MATERIAL_UPDATE");
        detail.put("materialId", existing.getId().toString());
        detail.put("code", existing.getCode());
        detail.put("highValue", highValue);
        detail.put("highValueChanged",
                !Boolean.valueOf(highValue).equals(existing.getHighValue()));
        detail.put("expiryManaged", expiryManaged);
        detail.put("version", expectedVersion);
        auditWriter.record("MATERIAL_UPDATE", RESOURCE_TYPE, existing.getId().toString(), true,
                detail, actorId(caller), username(caller), context);
        return get(existing.getId().toString());
    }

    private void requireNoConflict(String code, String name, String udiDi, String excludeId) {
        if (materialMapper.countCodeConflict(code, excludeId) > 0) {
            throw new M4ConflictException("耗材编码已存在");
        }
        if (materialMapper.countNameConflict(name, excludeId) > 0) {
            throw new M4ConflictException("耗材名称已存在");
        }
        if (udiDi != null && materialMapper.countUdiDiConflict(udiDi, excludeId) > 0) {
            throw new M4ConflictException("UDI-DI 已绑定其他耗材");
        }
    }

    private MaterialEntity requireMaterial(String materialId) {
        UUID id = parseUuid(materialId);
        MaterialEntity entity = id == null ? null : materialMapper.selectByMaterialId(id);
        if (entity == null) {
            throw new NoSuchElementException("耗材不存在或不可见");
        }
        return entity;
    }

    private MaterialList toList(List<MaterialEntity> rows, int page, int size, long total) {
        MaterialList result = new MaterialList();
        List<Material> items = new ArrayList<>();
        for (MaterialEntity row : rows) {
            items.add(toDto(row));
        }
        result.setItems(items);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(Math.toIntExact(total));
        result.setTotalPages(total == 0 ? 0 : (int) ((total + size - 1) / size));
        return result;
    }

    private Material toDto(MaterialEntity entity) {
        Material material = new Material();
        material.setMaterialId(entity.getId().toString());
        material.setCode(entity.getCode());
        material.setName(entity.getName());
        material.setSpecification(entity.getSpecification());
        material.setUnit(entity.getUnit());
        material.setCategory(entity.getCategory());
        material.setUdiDi(entity.getUdiDi());
        material.setHighValue(Boolean.TRUE.equals(entity.getHighValue()));
        material.setExpiryManaged(Boolean.TRUE.equals(entity.getExpiryManaged()));
        material.setStatus(Material.StatusEnum.fromValue(entity.getStatus()));
        material.setRemark(entity.getRemark());
        material.setCreatedAt(entity.getCreatedAt().atOffset(ZoneOffset.ofHours(8)));
        material.setCreatedBy(entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString());
        material.setUpdatedAt(entity.getUpdatedAt().atOffset(ZoneOffset.ofHours(8)));
        material.setUpdatedBy(entity.getUpdatedBy() == null ? null : entity.getUpdatedBy().toString());
        material.setVersion(entity.getVersion());
        return material;
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

    private String optionalUdiDi(String value, String field) {
        String trimmed = optionalText(value, field, 64);
        if (trimmed != null && !CODE_PATTERN.matcher(trimmed).matches()) {
            throw new M4ValidationException(field,
                    "UDI-DI 只能包含字母、数字、点、下划线与连字符（1–64）");
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
            Map<String, Object> locator, AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (locator != null) {
            detail.putAll(locator);
        }
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

    private static Map<String, Object> locator(String code, String udiDi) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (code != null && !code.isBlank()) {
            detail.put("code", code.trim());
        }
        String udiDiSummary = M4Values.udiDiSummary(udiDi);
        if (udiDiSummary != null) {
            detail.put("udiDi", udiDiSummary);
        }
        return detail;
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
