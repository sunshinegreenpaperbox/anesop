package com.anesop.m4.impl.material;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.material.model.MaterialTraceSummary;
import com.anesop.contracts.material.model.RecordLink;
import com.anesop.contracts.material.model.RegisterUdiInstanceRequest;
import com.anesop.contracts.material.model.StockMovement;
import com.anesop.contracts.material.model.UdiInstance;
import com.anesop.contracts.material.model.UdiTrace;
import com.anesop.contracts.material.model.UseUdiInstanceRequest;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.material.RecordVisibilityPort;
import com.anesop.m4.api.material.UdiTraceService;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UDI 一物一码追溯实现（契约 material.yaml 0.1.0，M4-M14-01）。
 *
 * <p>登记 = 该件入库（UDI 实例 + 批次库存 +1 + IN 流水同事务）；使用 = 状态迁移
 * IN_STOCK → USED + 批次库存 −1 + OUT 流水 + 绑定记录/台次，三者同事务。记录存在/可见性
 * 通过 {@link RecordVisibilityPort} 校验（m4-api 端口，由装配层用 m2-api 实现，避免模块循环）。
 * 审计 UDI_REGISTER/UDI_USE 与 STOCK_MOVE 只含标识与状态摘要，不含 UDI 原文全串与患者信息。
 */
@Service
public class UdiTraceServiceImpl implements UdiTraceService {

    private static final String RESOURCE_TYPE = "UDI";
    private static final String IN = "IN";
    private static final String OUT = "OUT";
    private static final String STATUS_IN_STOCK = "IN_STOCK";
    private static final String STATUS_USED = "USED";
    private static final String ACTIVE = "ACTIVE";
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern PI_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,96}$");
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final String COUNTERPARTY_INBOUND = "UDI 逐件入库";
    private static final String COUNTERPARTY_USE = "术中使用（UDI 扫录）";

    private final MaterialMapper materialMapper;
    private final MaterialStockMapper stockMapper;
    private final StockMovementMapper movementMapper;
    private final UdiInstanceMapper udiMapper;
    private final RecordVisibilityPort recordVisibilityPort;
    private final MaterialAccessGuard accessGuard;
    private final AuditWriter auditWriter;
    private final M4FailureAuditor failureAuditor;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务/审计由容器管理，服务不修改其内部状态")
    public UdiTraceServiceImpl(MaterialMapper materialMapper, MaterialStockMapper stockMapper,
            StockMovementMapper movementMapper, UdiInstanceMapper udiMapper,
            RecordVisibilityPort recordVisibilityPort, MaterialAccessGuard accessGuard,
            AuditWriter auditWriter, M4FailureAuditor failureAuditor, Clock clock) {
        this.materialMapper = materialMapper;
        this.stockMapper = stockMapper;
        this.movementMapper = movementMapper;
        this.udiMapper = udiMapper;
        this.recordVisibilityPort = recordVisibilityPort;
        this.accessGuard = accessGuard;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UdiInstance register(RegisterUdiInstanceRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        String resourceId = M4Values.udiDiSummary(request == null ? null : request.getUdiDi());
        try {
            return doRegister(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("UDI_REGISTER", resourceId == null ? "NEW" : resourceId, failure,
                    locator(request == null ? null : request.getUdiDi()), caller, context);
            throw failure;
        }
    }

    private UdiInstance doRegister(RegisterUdiInstanceRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        DataScopeAssignments scope = accessGuard.scope(caller);
        String departmentId = accessGuard.requireDepartment(scope, caller,
                request.getDepartmentId(), "departmentId");
        MaterialEntity material = requireActiveMaterial(request.getMaterialId());
        String udiDi = requirePattern(request.getUdiDi(), "udiDi", 64, CODE_PATTERN,
                "UDI-DI 只能包含字母、数字、点、下划线与连字符（1–64）");
        String udiPi = requirePattern(request.getUdiPi(), "udiPi", 96, PI_PATTERN,
                "UDI-PI 只能包含字母、数字、点、下划线与连字符（1–96）");
        if (udiDi.length() + 1 + udiPi.length() > 160) {
            throw new M4ValidationException("udiPi",
                    "UDI-DI + 分隔符 + UDI-PI 总长不能超过 160");
        }
        String serialNumber = optionalText(request.getSerialNumber(), "serialNumber", 96);
        String batchNo = requireText(request.getBatchNo(), "batchNo", 64);
        String counterparty = requireText(request.getCounterparty(), "counterparty", 100);
        LocalDate expiryDate = request.getExpiryDate();
        requireExpiryForRegistration(material, expiryDate);

        String registeredDi = material.getUdiDi();
        if (registeredDi == null || !registeredDi.equalsIgnoreCase(udiDi)) {
            throw new M4UnprocessableException("udiDi",
                    "UDI-DI 与耗材主数据不一致；请先维护耗材 UDI-DI 或核对扫码内容");
        }
        if (udiMapper.countByUdiDiPi(udiDi, udiPi) > 0) {
            throw new M4ConflictException("UDI-DI + UDI-PI 已登记（一物一码不可重复）");
        }

        Instant now = now();
        UUID operatorId = caller == null ? null : caller.userId();
        String operatorName = caller == null ? null : caller.username();
        if (operatorId == null || operatorName == null || operatorName.isBlank()) {
            throw new M4ValidationException("operator", "无法识别当前操作人");
        }
        String udiCode = udiDi + "^" + udiPi;

        UdiInstanceEntity instance = new UdiInstanceEntity();
        instance.setId(UUID.randomUUID());
        instance.setUdiCode(udiCode);
        instance.setUdiDi(udiDi);
        instance.setUdiPi(udiPi);
        instance.setSerialNumber(serialNumber);
        instance.setMaterialId(material.getId());
        instance.setMaterialCode(material.getCode());
        instance.setMaterialName(material.getName());
        instance.setBatchNo(batchNo);
        instance.setExpiryDate(expiryDate);
        instance.setDepartmentId(UUID.fromString(departmentId));
        instance.setStatus(STATUS_IN_STOCK);
        instance.setCreatedAt(now);
        instance.setCreatedBy(operatorId);
        instance.setUpdatedAt(now);
        instance.setUpdatedBy(operatorId);
        instance.setVersion(1);
        try {
            udiMapper.insertUdi(instance);
        } catch (DuplicateKeyException duplicate) {
            throw new M4ConflictException("UDI-DI + UDI-PI 已登记（一物一码不可重复）");
        }

        BigDecimal balanceAfter = incrementStock(material, batchNo, departmentId, expiryDate, now);

        StockMovementEntity movement = buildMovement(IN, material, batchNo, expiryDate, udiCode,
                BigDecimal.ONE, departmentId, counterparty, "UDI 逐件入库", null, null, operatorId,
                operatorName, balanceAfter, now);
        movementMapper.insertMovement(movement);

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("operation", "UDI_REGISTER");
        auditDetail.put("udiDi", udiDi);
        auditDetail.put("materialId", material.getId().toString());
        auditDetail.put("batchNo", batchNo);
        auditDetail.put("departmentId", departmentId);
        auditDetail.put("status", STATUS_IN_STOCK);
        auditWriter.record("UDI_REGISTER", RESOURCE_TYPE, instance.getId().toString(), true,
                auditDetail, operatorId, operatorName, context);
        recordMovementAudit(movement, true, caller, context);
        return toDto(instance);
    }

    @Override
    public UdiTrace trace(String udi, AuthPrincipal caller) {
        String udiCode = requireText(udi, "udi", 160);
        DataScopeAssignments scope = accessGuard.scope(caller);
        List<String> allowed = accessGuard.visibleDepartmentIds(scope, caller);
        UdiInstanceEntity instance = udiMapper.selectScoped(udiCode, allowed);
        if (instance == null) {
            throw new NoSuchElementException("UDI 实例不存在或不可见");
        }
        MaterialEntity material = materialMapper.selectByMaterialId(instance.getMaterialId());
        if (material == null) {
            throw new NoSuchElementException("UDI 实例关联的耗材不存在");
        }
        UdiTrace trace = new UdiTrace();
        trace.setInstance(toDto(instance));
        trace.setMaterial(toMaterialSummary(material));
        List<StockMovement> movements = new ArrayList<>();
        for (StockMovementEntity row : movementMapper.selectByUdi(udiCode,
                instance.getDepartmentId())) {
            movements.add(MaterialStockServiceImpl.toMovementDto(row));
        }
        trace.setMovements(movements);
        if (instance.getRecordId() != null) {
            trace.setRecord(new RecordLink(instance.getRecordId().toString(),
                    instance.getScheduleId() == null ? null : instance.getScheduleId().toString()));
        }
        return trace;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UdiInstance use(String udi, UseUdiInstanceRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        String udiCode = requireText(udi, "udi", 160);
        String udiDi = M4Values.udiDiSummary(udiCode);
        try {
            return doUse(udiCode, request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("UDI_USE", udiDi == null ? "UDI" : udiDi, failure, locator(udiDi),
                    caller, context);
            throw failure;
        }
    }

    private UdiInstance doUse(String udiCode, UseUdiInstanceRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        DataScopeAssignments scope = accessGuard.scope(caller);
        UdiInstanceEntity instance = udiMapper.selectByUdiCodeForUpdate(udiCode);
        if (instance == null
                || !accessGuard.isVisible(scope, caller, instance.getDepartmentId())) {
            throw new NoSuchElementException("UDI 实例不存在或不可见");
        }
        if (!STATUS_IN_STOCK.equals(instance.getStatus())) {
            throw new M4ConflictException(STATUS_USED.equals(instance.getStatus())
                    ? "UDI 已使用，不能重复使用（一物一码）"
                    : "UDI 已退回，不能再次使用");
        }
        LocalDate today = LocalDate.now(clock.withZone(DISPLAY_OFFSET));
        if (instance.getExpiryDate() != null && instance.getExpiryDate().isBefore(today)) {
            throw new M4UnprocessableException("udi", "UDI 已过效期，不得使用");
        }
        UUID recordId = parseUuid(request.getRecordId());
        if (recordId == null) {
            throw new M4ValidationException("recordId", "记录标识格式非法");
        }
        Optional<RecordVisibilityPort.RecordReference> visible =
                recordVisibilityPort.findVisibleRecord(recordId.toString(), caller);
        RecordVisibilityPort.RecordReference record = visible.orElseThrow(
                () -> new M4UnprocessableException("recordId", "记录不存在或不可见"));
        if (record.scheduleId() == null) {
            throw new M4UnprocessableException("recordId", "记录不存在或未关联台次");
        }
        UUID scheduleId = parseUuid(record.scheduleId());
        if (scheduleId == null) {
            throw new M4UnprocessableException("recordId", "记录所属台次非法");
        }
        String requestSchedule = request.getScheduleId();
        if (requestSchedule != null && !requestSchedule.isBlank()
                && !requestSchedule.trim().equalsIgnoreCase(scheduleId.toString())) {
            throw new M4UnprocessableException("scheduleId", "台次与记录所属台次不一致");
        }
        Instant now = now();
        UUID operatorId = caller == null ? null : caller.userId();
        String operatorName = caller == null ? null : caller.username();
        if (operatorId == null || operatorName == null || operatorName.isBlank()) {
            throw new M4ValidationException("operator", "无法识别当前操作人");
        }
        BigDecimal balanceAfter = decrementStock(instance, now);
        int updated = udiMapper.markUsed(instance.getId(), instance.getVersion(), recordId,
                scheduleId, now, operatorId, operatorName, now, operatorId);
        if (updated == 0) {
            throw new M4ConflictException("UDI 状态已变化，请刷新后重试");
        }

        MaterialEntity material = materialMapper.selectByMaterialId(instance.getMaterialId());
        if (material == null) {
            throw new NoSuchElementException("UDI 实例关联的耗材不存在");
        }
        StockMovementEntity movement = buildMovement(OUT, material, instance.getBatchNo(),
                instance.getExpiryDate(), udiCode, BigDecimal.ONE,
                instance.getDepartmentId().toString(), COUNTERPARTY_USE, "UDI 使用绑定记录",
                recordId, scheduleId, operatorId, operatorName, balanceAfter, now);
        movementMapper.insertMovement(movement);

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("operation", "UDI_USE");
        auditDetail.put("udiDi", instance.getUdiDi());
        auditDetail.put("materialId", instance.getMaterialId().toString());
        auditDetail.put("recordId", recordId.toString());
        auditDetail.put("scheduleId", scheduleId.toString());
        auditDetail.put("status", STATUS_USED);
        auditWriter.record("UDI_USE", RESOURCE_TYPE, instance.getId().toString(), true,
                auditDetail, operatorId, operatorName, context);
        recordMovementAudit(movement, true, caller, context);

        UdiInstanceEntity persisted = udiMapper.selectByUdiCode(udiCode);
        return toDto(persisted == null ? instance : persisted);
    }

    private BigDecimal incrementStock(MaterialEntity material, String batchNo, String departmentId,
            LocalDate expiryDate, Instant now) {
        MaterialStockEntity stock = new MaterialStockEntity();
        stock.setId(UUID.randomUUID());
        stock.setMaterialId(material.getId());
        stock.setMaterialCode(material.getCode());
        stock.setMaterialName(material.getName());
        stock.setBatchNo(batchNo);
        stock.setDepartmentId(UUID.fromString(departmentId));
        stock.setQuantity(BigDecimal.ONE);
        stock.setUnit(material.getUnit());
        stock.setExpiryDate(expiryDate);
        stock.setCreatedAt(now);
        stock.setUpdatedAt(now);
        stock.setVersion(1);
        int affected;
        try {
            affected = stockMapper.upsertIncrement(stock);
        } catch (DuplicateKeyException race) {
            affected = stockMapper.upsertIncrement(stock);
        }
        if (affected == 0) {
            throw new M4ConflictException("同一批次已存在但效期不一致，请核对批次效期",
                    List.of(new M4ConflictException.Detail("expiryDate",
                            "批次效期与既有库存不一致")));
        }
        MaterialStockEntity persisted = stockMapper.selectByBatch(material.getId(), batchNo,
                UUID.fromString(departmentId));
        return persisted == null ? BigDecimal.ONE : persisted.getQuantity();
    }

    private BigDecimal decrementStock(UdiInstanceEntity instance, Instant now) {
        MaterialStockEntity stock = stockMapper.selectByBatch(instance.getMaterialId(),
                instance.getBatchNo(), instance.getDepartmentId());
        if (stock == null) {
            throw new M4ConflictException("批次库存不足，操作未生效",
                    List.of(new M4ConflictException.Detail("quantity",
                            "批次库存不足：可用 0，请求 1")));
        }
        if (!Objects.equals(stock.getExpiryDate(), instance.getExpiryDate())) {
            throw new M4ConflictException("UDI 实例效期与批次库存不一致，不能扣减",
                    List.of(new M4ConflictException.Detail("expiryDate",
                            "UDI 实例效期与批次库存效期不一致")));
        }
        int affected = stockMapper.decrement(stock.getId(), BigDecimal.ONE, now);
        if (affected == 0) {
            BigDecimal available = stockMapper.selectQuantity(stock.getId());
            throw new M4ConflictException("批次库存不足，操作未生效",
                    List.of(new M4ConflictException.Detail("quantity",
                            "批次库存不足：可用 " + plain(available) + "，请求 1")));
        }
        BigDecimal remaining = stockMapper.selectQuantity(stock.getId());
        return remaining == null ? BigDecimal.ZERO : remaining;
    }

    private StockMovementEntity buildMovement(String movementType, MaterialEntity material,
            String batchNo, LocalDate expiryDate, String udiCode, BigDecimal quantity,
            String departmentId, String counterparty, String reason, UUID recordId, UUID scheduleId,
            UUID operatorId, String operatorName, BigDecimal balanceAfter, Instant now) {
        StockMovementEntity movement = new StockMovementEntity();
        movement.setId(UUID.randomUUID());
        movement.setMovementType(movementType);
        movement.setMaterialId(material.getId());
        movement.setMaterialCode(material.getCode());
        movement.setMaterialName(material.getName());
        movement.setBatchNo(batchNo);
        movement.setExpiryDate(expiryDate);
        movement.setUdiCode(udiCode);
        movement.setQuantity(quantity);
        movement.setUnit(material.getUnit());
        movement.setDepartmentId(UUID.fromString(departmentId));
        movement.setCounterparty(counterparty);
        movement.setReason(reason);
        movement.setBalanceAfter(balanceAfter);
        movement.setRecordId(recordId);
        movement.setScheduleId(scheduleId);
        movement.setOperatorId(operatorId);
        movement.setOperatorName(operatorName);
        movement.setOccurredAt(now);
        movement.setCreatedAt(now);
        movement.setCreatedBy(operatorId);
        return movement;
    }

    private void recordMovementAudit(StockMovementEntity movement, boolean success,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "STOCK_MOVE");
        detail.put("movementId", movement.getId().toString());
        detail.put("movementType", movement.getMovementType());
        detail.put("materialId", movement.getMaterialId().toString());
        detail.put("batchNo", movement.getBatchNo());
        detail.put("quantity", movement.getQuantity());
        detail.put("departmentId", movement.getDepartmentId().toString());
        detail.put("udiLinked", movement.getUdiCode() != null);
        auditWriter.record("STOCK_MOVE", "STOCK_MOVEMENT", movement.getId().toString(), success,
                detail, caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
    }

    private void requireExpiryForRegistration(MaterialEntity material, LocalDate expiryDate) {
        if (!Boolean.TRUE.equals(material.getExpiryManaged())) {
            return;
        }
        if (expiryDate == null) {
            throw new M4UnprocessableException("expiryDate", "效期管理耗材必须填写效期");
        }
        LocalDate today = LocalDate.now(clock.withZone(DISPLAY_OFFSET));
        if (expiryDate.isBefore(today)) {
            throw new M4UnprocessableException("expiryDate", "耗材已过效期，不得入库");
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

    private UdiInstance toDto(UdiInstanceEntity entity) {
        UdiInstance dto = new UdiInstance();
        dto.setUdi(entity.getUdiCode());
        dto.setUdiDi(entity.getUdiDi());
        dto.setUdiPi(entity.getUdiPi());
        dto.setSerialNumber(entity.getSerialNumber());
        dto.setMaterialId(entity.getMaterialId().toString());
        dto.setMaterialCode(entity.getMaterialCode());
        dto.setMaterialName(entity.getMaterialName());
        dto.setBatchNo(entity.getBatchNo());
        dto.setExpiryDate(entity.getExpiryDate());
        dto.setDepartmentId(entity.getDepartmentId().toString());
        dto.setStatus(UdiInstance.StatusEnum.fromValue(entity.getStatus()));
        dto.setRecordId(entity.getRecordId() == null ? null : entity.getRecordId().toString());
        dto.setScheduleId(entity.getScheduleId() == null ? null : entity.getScheduleId().toString());
        dto.setUsedAt(entity.getUsedAt() == null ? null : entity.getUsedAt().atOffset(DISPLAY_OFFSET));
        dto.setUsedBy(entity.getUsedBy() == null ? null : entity.getUsedBy().toString());
        dto.setCreatedAt(entity.getCreatedAt().atOffset(DISPLAY_OFFSET));
        dto.setCreatedBy(entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString());
        dto.setVersion(entity.getVersion());
        return dto;
    }

    private static MaterialTraceSummary toMaterialSummary(MaterialEntity entity) {
        MaterialTraceSummary summary = new MaterialTraceSummary();
        summary.setMaterialId(entity.getId().toString());
        summary.setCode(entity.getCode());
        summary.setName(entity.getName());
        summary.setSpecification(entity.getSpecification());
        summary.setUnit(entity.getUnit());
        summary.setHighValue(Boolean.TRUE.equals(entity.getHighValue()));
        summary.setExpiryManaged(Boolean.TRUE.equals(entity.getExpiryManaged()));
        summary.setStatus(MaterialTraceSummary.StatusEnum.fromValue(entity.getStatus()));
        return summary;
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

    private static String requirePattern(String value, String field, int maxLength,
            Pattern pattern, String message) {
        String trimmed = requireText(value, field, maxLength);
        if (!pattern.matcher(trimmed).matches()) {
            throw new M4ValidationException(field, message);
        }
        return trimmed;
    }

    /** 失败审计定位字段：只记 UDI-DI 摘要，不记 UDI 全串与患者信息。 */
    private static Map<String, Object> locator(String udiDi) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (udiDi != null && !udiDi.isBlank()) {
            detail.put("udiDi", M4Values.udiDiSummary(udiDi));
        }
        return detail;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
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
