package com.anesop.m4.impl.material;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.material.model.CreateStockMovementRequest;
import com.anesop.contracts.material.model.DepartmentList;
import com.anesop.contracts.material.model.DepartmentOption;
import com.anesop.contracts.material.model.MaterialStockLevel;
import com.anesop.contracts.material.model.MaterialStockLevelList;
import com.anesop.contracts.material.model.StockMovement;
import com.anesop.contracts.material.model.StockMovementList;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.material.MaterialStockService;
import com.anesop.m4.impl.persistence.entity.MaterialEntity;
import com.anesop.m4.impl.persistence.entity.MaterialStockEntity;
import com.anesop.m4.impl.persistence.entity.StockMovementEntity;
import com.anesop.m4.impl.persistence.entity.UdiInstanceEntity;
import com.anesop.m4.impl.persistence.mapper.MaterialMapper;
import com.anesop.m4.impl.persistence.mapper.MaterialStockMapper;
import com.anesop.m4.impl.persistence.mapper.StockMovementMapper;
import com.anesop.m4.impl.persistence.mapper.UdiInstanceMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.anesop.m4.impl.support.M4Values;
import com.anesop.m4.impl.support.MaterialAccessGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 耗材出入库与批次库存实现（契约 material.yaml 0.1.0，M4-M14-01）。
 *
 * <p>库存变更全部走原子 SQL：入库 {@code ON CONFLICT DO UPDATE} 累加（效期不一致返回 0 → 409），
 * 出库/负向调整 {@code WHERE quantity >= ?} 扣减（返回 0 → 409），避免“先查后写”的并发超扣。
 * 扣减前按 材料 + 批次 + 科室 定位库存行：请求效期不一致 409、过期批次 OUT 422；RETURN 带
 * UDI 时以实例批次/效期为扣减依据（请求不一致 422）。流水 append-only，审计 STOCK_MOVE 只含
 * 标识、类型、数量、科室与是否关联 UDI；失败审计附 materialId/batchNo/udiDi 摘要。
 */
@Service
public class MaterialStockServiceImpl implements MaterialStockService {

    private static final String RESOURCE_TYPE = "STOCK_MOVEMENT";
    private static final String IN = "IN";
    private static final String OUT = "OUT";
    private static final String RETURN = "RETURN";
    private static final String ADJUST = "ADJUST";
    private static final String ACTIVE = "ACTIVE";
    private static final String STATUS_IN_STOCK = "IN_STOCK";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);

    private final MaterialMapper materialMapper;
    private final MaterialStockMapper stockMapper;
    private final StockMovementMapper movementMapper;
    private final UdiInstanceMapper udiMapper;
    private final MaterialAccessGuard accessGuard;
    private final OrganizationQueryService organizationQueryService;
    private final AuditWriter auditWriter;
    private final M4FailureAuditor failureAuditor;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/审计由容器管理，服务不修改其内部状态")
    public MaterialStockServiceImpl(MaterialMapper materialMapper, MaterialStockMapper stockMapper,
            StockMovementMapper movementMapper, UdiInstanceMapper udiMapper,
            MaterialAccessGuard accessGuard, OrganizationQueryService organizationQueryService,
            AuditWriter auditWriter, M4FailureAuditor failureAuditor, Clock clock) {
        this.materialMapper = materialMapper;
        this.stockMapper = stockMapper;
        this.movementMapper = movementMapper;
        this.udiMapper = udiMapper;
        this.accessGuard = accessGuard;
        this.organizationQueryService = organizationQueryService;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.clock = clock;
    }

    @Override
    public DepartmentList listDepartments(AuthPrincipal caller) {
        DataScopeAssignments scope = accessGuard.scope(caller);
        // 先按数据范围判定（SELF/ROOM/无科室维度 fail-closed 403），再返回可见科室。
        accessGuard.visibleDepartmentIds(scope, caller);
        List<OrgDepartment> departments = organizationQueryService.visibleDepartments(
                caller == null ? null : caller.userId());
        DepartmentList result = new DepartmentList();
        List<DepartmentOption> items = new ArrayList<>();
        for (OrgDepartment department : departments) {
            items.add(new DepartmentOption(department.orgId(), department.code(),
                    department.name()));
        }
        result.setItems(items);
        return result;
    }

    @Override
    public MaterialStockLevelList listStock(String materialId, String departmentId, int limit,
            AuthPrincipal caller) {
        requireLimit(limit);
        if (materialId != null && !materialId.isBlank() && parseUuid(materialId) == null) {
            throw new M4ValidationException("materialId", "耗材标识格式非法");
        }
        DataScopeAssignments scope = accessGuard.scope(caller);
        String deptFilter = accessGuard.optionalDepartmentFilter(scope, caller, departmentId,
                "departmentId");
        List<String> allowed = accessGuard.visibleDepartmentIds(scope, caller);
        List<MaterialStockEntity> rows = stockMapper.selectStock(trimToNull(materialId), deptFilter,
                allowed, limit);
        MaterialStockLevelList result = new MaterialStockLevelList();
        List<MaterialStockLevel> items = new ArrayList<>();
        for (MaterialStockEntity row : rows) {
            items.add(toStockDto(row));
        }
        result.setItems(items);
        return result;
    }

    @Override
    public StockMovementList listMovements(String materialId, String departmentId,
            OffsetDateTime from, OffsetDateTime to, int limit, AuthPrincipal caller) {
        requireLimit(limit);
        if (materialId != null && !materialId.isBlank() && parseUuid(materialId) == null) {
            throw new M4ValidationException("materialId", "耗材标识格式非法");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new M4ValidationException("from", "开始时间不能晚于结束时间");
        }
        DataScopeAssignments scope = accessGuard.scope(caller);
        String deptFilter = accessGuard.optionalDepartmentFilter(scope, caller, departmentId,
                "departmentId");
        List<String> allowed = accessGuard.visibleDepartmentIds(scope, caller);
        List<StockMovementEntity> rows = movementMapper.selectMovements(trimToNull(materialId),
                deptFilter, from, to, allowed, limit);
        StockMovementList result = new StockMovementList();
        List<StockMovement> items = new ArrayList<>();
        for (StockMovementEntity row : rows) {
            items.add(toMovementDto(row));
        }
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StockMovement createMovement(CreateStockMovementRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        String resourceId = request == null ? "NEW" : safeResourceId(request.getMaterialId());
        try {
            return doCreateMovement(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("STOCK_MOVE", resourceId, failure, locator(request), caller, context);
            throw failure;
        }
    }

    private StockMovement doCreateMovement(CreateStockMovementRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        DataScopeAssignments scope = accessGuard.scope(caller);
        String departmentId = accessGuard.requireDepartment(scope, caller,
                request.getDepartmentId(), "departmentId");
        MaterialEntity material = requireActiveMaterial(request.getMaterialId());
        String movementType = requireMovementType(request.getMovementType());
        String requestBatchNo = requireText(request.getBatchNo(), "batchNo", 64);
        String counterparty = requireText(request.getCounterparty(), "counterparty", 100);
        String reason = optionalText(request.getReason(), "reason", 200);
        String udi = optionalText(request.getUdi(), "udi", 160);
        BigDecimal quantity = request.getQuantity();
        requireQuantity(movementType, quantity);
        if (ADJUST.equals(movementType) && reason == null) {
            throw new M4ValidationException("reason", "盘点调整必须填写原因");
        }

        boolean highValue = Boolean.TRUE.equals(material.getHighValue());
        if (highValue && !RETURN.equals(movementType)) {
            throw new M4UnprocessableException("movementType",
                    "高值耗材必须逐件登记 UDI 入库并通过 UDI 使用出库，禁止裸数量出入库");
        }
        if (highValue && RETURN.equals(movementType) && udi == null) {
            throw new M4UnprocessableException("udi", "高值耗材退回必须扫描 UDI，逐件退回");
        }
        if (udi != null && !RETURN.equals(movementType)) {
            throw new M4ValidationException("udi", "udi 仅用于 RETURN（UDI 使用请调用 UDI 使用接口）");
        }

        // RETURN 携带 UDI 时以 UDI 实例的批次/效期为唯一扣减依据：请求与实例不一致 422，
        // 避免用请求批次扣错库存行（B-1）。
        String batchNo = requestBatchNo;
        LocalDate expiryDate = request.getExpiryDate();
        UdiInstanceEntity udiInstance = null;
        if (udi != null) {
            udiInstance = udiMapper.selectByUdiCodeForUpdate(udi);
            if (udiInstance == null) {
                throw new M4UnprocessableException("udi", "UDI 实例不存在，无法退回");
            }
            if (!udiInstance.getMaterialId().equals(material.getId())) {
                throw new M4UnprocessableException("udi", "UDI 实例与耗材不一致");
            }
            if (!udiInstance.getDepartmentId().toString().equals(departmentId)) {
                throw new M4UnprocessableException("udi", "UDI 实例不在该科室，不能跨科室退回");
            }
            if (!STATUS_IN_STOCK.equals(udiInstance.getStatus())) {
                throw new M4ConflictException("UDI 实例已使用或已退回，不能重复退回");
            }
            if (quantity.compareTo(BigDecimal.ONE) != 0) {
                throw new M4ValidationException("quantity", "高值耗材逐件退回，数量必须为 1");
            }
            if (!requestBatchNo.equalsIgnoreCase(udiInstance.getBatchNo())) {
                throw new M4UnprocessableException("batchNo",
                        "退回批次与 UDI 实例登记批次不一致，不能扣减其他批次库存");
            }
            if (!Objects.equals(expiryDate, udiInstance.getExpiryDate())) {
                throw new M4UnprocessableException("expiryDate",
                        "退回效期与 UDI 实例登记效期不一致");
            }
            batchNo = udiInstance.getBatchNo();
            expiryDate = udiInstance.getExpiryDate();
        }
        requireExpiryForInbound(material, expiryDate);

        Instant now = now();
        UUID operatorId = caller == null ? null : caller.userId();
        String operatorName = caller == null ? null : caller.username();
        if (operatorId == null || operatorName == null || operatorName.isBlank()) {
            throw new M4ValidationException("operator", "无法识别当前操作人");
        }

        BigDecimal balanceAfter;
        if (IN.equals(movementType) || (ADJUST.equals(movementType)
                && quantity.signum() > 0)) {
            balanceAfter = increment(material, batchNo, departmentId, quantity, expiryDate, now);
        } else {
            BigDecimal decrementBy = quantity.abs();
            balanceAfter = decrement(material, batchNo, departmentId, decrementBy, expiryDate,
                    movementType, now);
        }

        if (udiInstance != null) {
            int updated = udiMapper.markReturned(udiInstance.getId(), udiInstance.getVersion(), now,
                    operatorId);
            if (updated == 0) {
                throw new M4ConflictException("UDI 实例状态已变化，请刷新后重试");
            }
        }

        StockMovementEntity entity = new StockMovementEntity();
        entity.setId(UUID.randomUUID());
        entity.setMovementType(movementType);
        entity.setMaterialId(material.getId());
        entity.setMaterialCode(material.getCode());
        entity.setMaterialName(material.getName());
        entity.setBatchNo(batchNo);
        entity.setExpiryDate(expiryDate);
        entity.setUdiCode(udi);
        entity.setQuantity(quantity);
        entity.setUnit(material.getUnit());
        entity.setDepartmentId(UUID.fromString(departmentId));
        entity.setCounterparty(counterparty);
        entity.setReason(reason);
        entity.setBalanceAfter(balanceAfter);
        entity.setOperatorId(operatorId);
        entity.setOperatorName(operatorName);
        entity.setOccurredAt(now);
        entity.setCreatedAt(now);
        entity.setCreatedBy(operatorId);
        movementMapper.insertMovement(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "STOCK_MOVE");
        detail.put("movementId", entity.getId().toString());
        detail.put("movementType", movementType);
        detail.put("materialId", material.getId().toString());
        detail.put("batchNo", batchNo);
        detail.put("quantity", quantity);
        detail.put("departmentId", departmentId);
        detail.put("highValue", highValue);
        detail.put("udiLinked", udi != null);
        auditWriter.record("STOCK_MOVE", RESOURCE_TYPE, entity.getId().toString(), true, detail,
                operatorId, operatorName, context);
        return toMovementDto(entity);
    }

    private BigDecimal increment(MaterialEntity material, String batchNo, String departmentId,
            BigDecimal quantity, LocalDate expiryDate, Instant now) {
        MaterialStockEntity stock = new MaterialStockEntity();
        stock.setId(UUID.randomUUID());
        stock.setMaterialId(material.getId());
        stock.setMaterialCode(material.getCode());
        stock.setMaterialName(material.getName());
        stock.setBatchNo(batchNo);
        stock.setDepartmentId(UUID.fromString(departmentId));
        stock.setQuantity(quantity);
        stock.setUnit(material.getUnit());
        stock.setExpiryDate(expiryDate);
        stock.setCreatedAt(now);
        stock.setUpdatedAt(now);
        stock.setVersion(1);
        int affected;
        try {
            affected = stockMapper.upsertIncrement(stock);
        } catch (DuplicateKeyException race) {
            // 并发首次入库：唯一键竞争后重试一次（第二次走 ON CONFLICT 累加）。
            affected = stockMapper.upsertIncrement(stock);
        }
        if (affected == 0) {
            throw new M4ConflictException("同一批次已存在但效期不一致，请核对批次效期",
                    List.of(new M4ConflictException.Detail("expiryDate",
                            "批次效期与既有库存不一致")));
        }
        MaterialStockEntity persisted = stockMapper.selectByBatch(material.getId(), batchNo,
                UUID.fromString(departmentId));
        if (persisted == null) {
            throw new M4ConflictException("批次库存写入失败，请重试");
        }
        return persisted.getQuantity();
    }

    private BigDecimal decrement(MaterialEntity material, String batchNo, String departmentId,
            BigDecimal quantity, LocalDate expiryDate, String movementType, Instant now) {
        MaterialStockEntity stock = stockMapper.selectByBatch(material.getId(), batchNo,
                UUID.fromString(departmentId));
        if (stock == null) {
            throw insufficient(quantity, BigDecimal.ZERO);
        }
        // 同一 材料 + 批次 + 科室 只允许一个效期：请求与库存行不一致 409（先于库存不足/过期判断，
        // 否则会以“未来效期”扣减过期批次，或反向把效期改错）。
        if (!Objects.equals(stock.getExpiryDate(), expiryDate)) {
            throw new M4ConflictException("同一批次效期与既有库存不一致，请核对批次效期",
                    List.of(new M4ConflictException.Detail("expiryDate",
                            "批次效期与既有库存不一致")));
        }
        LocalDate today = LocalDate.now(clock.withZone(DISPLAY_OFFSET));
        if (OUT.equals(movementType) && stock.getExpiryDate() != null
                && stock.getExpiryDate().isBefore(today)) {
            throw new M4UnprocessableException("expiryDate", "批次已过效期，不得出库使用");
        }
        int affected = stockMapper.decrement(stock.getId(), quantity, now);
        if (affected == 0) {
            BigDecimal available = stockMapper.selectQuantity(stock.getId());
            throw insufficient(quantity, available == null ? BigDecimal.ZERO : available);
        }
        BigDecimal remaining = stockMapper.selectQuantity(stock.getId());
        return remaining == null ? BigDecimal.ZERO : remaining;
    }

    private M4ConflictException insufficient(BigDecimal requested, BigDecimal available) {
        return new M4ConflictException("批次库存不足，操作未生效",
                List.of(new M4ConflictException.Detail("quantity",
                        "批次库存不足：可用 " + plain(available) + "，请求 " + plain(requested))));
    }

    private void requireExpiryForInbound(MaterialEntity material, LocalDate expiryDate) {
        if (!Boolean.TRUE.equals(material.getExpiryManaged())) {
            return;
        }
        if (expiryDate == null) {
            throw new M4UnprocessableException("expiryDate", "效期管理耗材必须填写批次效期");
        }
        LocalDate today = LocalDate.now(clock.withZone(DISPLAY_OFFSET));
        if (expiryDate.isBefore(today)) {
            throw new M4UnprocessableException("expiryDate", "耗材已过效期，不得入库或使用");
        }
    }

    private MaterialEntity requireActiveMaterial(String materialId) {
        UUID id = parseUuid(materialId);
        MaterialEntity material = id == null ? null : materialMapper.selectByMaterialId(id);
        if (material == null || !ACTIVE.equals(material.getStatus())) {
            throw new M4UnprocessableException("materialId", "耗材不存在或已停用");
        }
        return material;
    }

    private static String requireMovementType(
            CreateStockMovementRequest.MovementTypeEnum movementType) {
        if (movementType == null) {
            throw new M4ValidationException("movementType", "出入库类型不能为空");
        }
        return movementType.getValue();
    }

    private static void requireQuantity(String movementType, BigDecimal quantity) {
        if (quantity == null) {
            throw new M4ValidationException("quantity", "数量不能为空");
        }
        if (quantity.stripTrailingZeros().scale() > 2) {
            throw new M4ValidationException("quantity", "数量最多支持 2 位小数");
        }
        if (ADJUST.equals(movementType)) {
            if (quantity.signum() == 0) {
                throw new M4ValidationException("quantity", "盘点调整量不能为 0");
            }
            return;
        }
        if (quantity.signum() <= 0) {
            throw new M4ValidationException("quantity", "入库/出库/退回数量必须大于 0");
        }
    }

    private void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new M4ValidationException("limit", "返回条数必须在 1–200 之间");
        }
    }

    private MaterialStockLevel toStockDto(MaterialStockEntity entity) {
        MaterialStockLevel level = new MaterialStockLevel();
        level.setStockId(entity.getId().toString());
        level.setMaterialId(entity.getMaterialId().toString());
        level.setMaterialCode(entity.getMaterialCode());
        level.setMaterialName(entity.getMaterialName());
        level.setBatchNo(entity.getBatchNo());
        level.setDepartmentId(entity.getDepartmentId().toString());
        level.setQuantity(entity.getQuantity());
        level.setUnit(entity.getUnit());
        level.setExpiryDate(entity.getExpiryDate());
        level.setUpdatedAt(entity.getUpdatedAt().atOffset(DISPLAY_OFFSET));
        return level;
    }

    static StockMovement toMovementDto(StockMovementEntity entity) {
        StockMovement movement = new StockMovement();
        movement.setMovementId(entity.getId().toString());
        movement.setMovementType(StockMovement.MovementTypeEnum.fromValue(entity.getMovementType()));
        movement.setMaterialId(entity.getMaterialId().toString());
        movement.setMaterialCode(entity.getMaterialCode());
        movement.setMaterialName(entity.getMaterialName());
        movement.setBatchNo(entity.getBatchNo());
        movement.setExpiryDate(entity.getExpiryDate());
        movement.setUdi(entity.getUdiCode());
        movement.setQuantity(entity.getQuantity());
        movement.setUnit(entity.getUnit());
        movement.setDepartmentId(entity.getDepartmentId().toString());
        movement.setCounterparty(entity.getCounterparty());
        movement.setReason(entity.getReason());
        movement.setBalanceAfter(entity.getBalanceAfter());
        movement.setRecordId(entity.getRecordId() == null ? null : entity.getRecordId().toString());
        movement.setScheduleId(
                entity.getScheduleId() == null ? null : entity.getScheduleId().toString());
        movement.setOperatorId(entity.getOperatorId().toString());
        movement.setOperatorName(entity.getOperatorName());
        movement.setOccurredAt(entity.getOccurredAt().atOffset(DISPLAY_OFFSET));
        movement.setCreatedAt(entity.getCreatedAt().atOffset(DISPLAY_OFFSET));
        return movement;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
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

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /** 失败审计定位字段：耗材/批次/类型 + UDI-DI 摘要（不含 UDI 全串与患者信息）。 */
    private static Map<String, Object> locator(CreateStockMovementRequest request) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (request == null) {
            return detail;
        }
        if (parseUuid(request.getMaterialId()) != null) {
            detail.put("materialId", request.getMaterialId().trim());
        }
        if (request.getMovementType() != null) {
            detail.put("movementType", request.getMovementType().getValue());
        }
        String batchNo = trimToNull(request.getBatchNo());
        if (batchNo != null) {
            detail.put("batchNo", batchNo.length() > 64 ? batchNo.substring(0, 64) : batchNo);
        }
        String udiDi = M4Values.udiDiSummary(request.getUdi());
        if (udiDi != null) {
            detail.put("udiDi", udiDi);
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
