package com.anesop.m4.impl.narcotic;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.drug.model.CabinetHandover;
import com.anesop.contracts.drug.model.CabinetHandoverItem;
import com.anesop.contracts.drug.model.CabinetHandoverList;
import com.anesop.contracts.drug.model.CreateCabinetHandoverRequest;
import com.anesop.contracts.drug.model.CreateDailyCloseRequest;
import com.anesop.contracts.drug.model.CreateNarcoticRegisterRequest;
import com.anesop.contracts.drug.model.DailyClose;
import com.anesop.contracts.drug.model.DepartmentList;
import com.anesop.contracts.drug.model.DepartmentOption;
import com.anesop.contracts.drug.model.DrugBaseline;
import com.anesop.contracts.drug.model.DrugBaselineList;
import com.anesop.contracts.drug.model.NarcoticRegister;
import com.anesop.contracts.drug.model.NarcoticRegisterList;
import com.anesop.contracts.drug.model.UpdateDrugBaselinesRequest;
import com.anesop.contracts.drug.model.UpdateDrugBaselinesRequestItemsInner;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.narcotic.NarcoticService;
import com.anesop.m4.impl.persistence.entity.CabinetHandoverEntity;
import com.anesop.m4.impl.persistence.entity.DailyCloseEntity;
import com.anesop.m4.impl.persistence.entity.DrugBaselineEntity;
import com.anesop.m4.impl.persistence.entity.DrugEntity;
import com.anesop.m4.impl.persistence.entity.NarcoticRegisterEntity;
import com.anesop.m4.impl.persistence.mapper.CabinetHandoverMapper;
import com.anesop.m4.impl.persistence.mapper.DailyCloseMapper;
import com.anesop.m4.impl.persistence.mapper.DrugBaselineMapper;
import com.anesop.m4.impl.persistence.mapper.DrugMapper;
import com.anesop.m4.impl.persistence.mapper.NarcoticRegisterMapper;
import com.anesop.m4.impl.support.M4FailureAuditor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 麻精五专服务实现（契约 drug.yaml 0.1.0，M4-M15-01）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>数据范围按科室：ALL 全院；SITE 院区子树；DEPT 授权科室；SELF 按本人所属科室推导；
 *       ROOM/无范围 fail-closed 403；写入科室必须为启用 DEPT 组织（422）且在范围内（403）；</li>
 *   <li>专册/交接/日结 append-only（只 INSERT）；双人签字必须不同人（400），交接另要求
 *       交班人 ≠ 接班人；</li>
 *   <li>日结平账：期初 + 入库 − 使用 − 退回 = 期末；不平 409 并在 details 给出期望值与实盘值；
 *       同一柜 + 业务日 + 班次重复 409；</li>
 *   <li>基数按 品种 + 柜 唯一，更新必须回传 version（过期 409），数量 ≥ 0；科室随柜不可改
 *       （数据库守卫 55000），upsert 校验 existing.departmentId 一致且在可见范围（409/403）；</li>
 *   <li>审计 NARCOTIC_REGISTER/NARCOTIC_HANDOVER/NARCOTIC_BASELINE/NARCOTIC_DAILY_CLOSE：
 *       detail 只含标识、数量与科室，不含处方号原文、人员姓名与患者信息；失败路径
 *       （400/409/422）以独立事务写 result=0 审计。</li>
 * </ul>
 */
@Service
public class NarcoticServiceImpl implements NarcoticService {

    private static final String RESOURCE_TYPE = "NARCOTIC";
    private static final String SHIFT_DAY = "DAY";
    private static final String SHIFT_NIGHT = "NIGHT";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);
    private static final int MAX_LIMIT = 200;

    private final NarcoticRegisterMapper registerMapper;
    private final CabinetHandoverMapper handoverMapper;
    private final DrugBaselineMapper baselineMapper;
    private final DailyCloseMapper closeMapper;
    private final DrugMapper drugMapper;
    private final DataScopeResolver dataScopeResolver;
    private final OrganizationQueryService organizationQueryService;
    private final AuditWriter auditWriter;
    private final M4FailureAuditor failureAuditor;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public NarcoticServiceImpl(NarcoticRegisterMapper registerMapper,
            CabinetHandoverMapper handoverMapper, DrugBaselineMapper baselineMapper,
            DailyCloseMapper closeMapper, DrugMapper drugMapper,
            DataScopeResolver dataScopeResolver, OrganizationQueryService organizationQueryService,
            AuditWriter auditWriter, M4FailureAuditor failureAuditor, ObjectMapper objectMapper,
            Clock clock) {
        this.registerMapper = registerMapper;
        this.handoverMapper = handoverMapper;
        this.baselineMapper = baselineMapper;
        this.closeMapper = closeMapper;
        this.drugMapper = drugMapper;
        this.dataScopeResolver = dataScopeResolver;
        this.organizationQueryService = organizationQueryService;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 科室选项 --

    @Override
    public DepartmentList listDepartments(AuthPrincipal caller) {
        DataScopeAssignments scope = scope(caller);
        List<OrgDepartment> departments = organizationQueryService.visibleDepartments(
                caller == null ? null : caller.userId());
        if (!scope.isUnrestricted() && departments.isEmpty()) {
            throw new AccessDeniedException("麻精五专仅按科室数据范围开放");
        }
        DepartmentList result = new DepartmentList();
        List<DepartmentOption> items = new ArrayList<>();
        for (OrgDepartment department : departments) {
            DepartmentOption option = new DepartmentOption(department.orgId(), department.code(),
                    department.name());
            items.add(option);
        }
        result.setItems(items);
        return result;
    }

    // ------------------------------------------------------------------ 专册 --

    @Override
    public NarcoticRegisterList listRegisters(String departmentId, String drugId,
            OffsetDateTime from, OffsetDateTime to, int limit, AuthPrincipal caller) {
        requireLimit(limit);
        DataScopeAssignments scope = scope(caller);
        String deptFilter = optionalText(departmentId, "departmentId", 36);
        if (deptFilter == null && scope.isSelfOnly()) {
            deptFilter = requireSelfDepartment(caller);
        }
        if (deptFilter != null) {
            requireDepartmentAllowed(scope, caller, deptFilter);
        }
        List<String> allowedDeptIds = visibleDepartmentIds(scope, caller);
        if (from != null && to != null && from.isAfter(to)) {
            throw new M4ValidationException("from", "开始时间不能晚于结束时间");
        }
        List<NarcoticRegisterEntity> rows = registerMapper.selectRegisters(deptFilter,
                optionalText(drugId, "drugId", 36), from, to, allowedDeptIds, limit);
        NarcoticRegisterList result = new NarcoticRegisterList();
        List<NarcoticRegister> items = new ArrayList<>();
        for (NarcoticRegisterEntity row : rows) {
            items.add(toRegisterDto(row));
        }
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NarcoticRegister createRegister(CreateNarcoticRegisterRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        try {
            return doCreateRegister(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("NARCOTIC_REGISTER", "NEW", failure, caller, context);
            throw failure;
        }
    }

    private NarcoticRegister doCreateRegister(CreateNarcoticRegisterRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        DataScopeAssignments scope = scope(caller);
        String departmentId = requireDepartment(scope, caller, request.getDepartmentId(),
                "departmentId");
        DrugEntity drug = requireActiveDrug(request.getDrugId());
        String batchNo = requireText(request.getBatchNo(), "batchNo", 64);
        BigDecimal quantity = requireQuantity(request.getQuantity(), "quantity", false);
        String unit = requireText(request.getUnit(), "unit", 16);
        String prescriptionNo = requireText(request.getPrescriptionNo(), "prescriptionNo", 64);
        String usedBy = requireText(request.getUsedBy(), "usedBy", 64);
        String destination = requireText(request.getDestination(), "destination", 100);
        if (request.getUsageAt() == null) {
            throw new M4ValidationException("usageAt", "使用时间不能为空");
        }
        Instant usageAt = request.getUsageAt().toInstant().truncatedTo(ChronoUnit.MILLIS);
        if (usageAt.isAfter(now().plus(FUTURE_TOLERANCE))) {
            throw new M4ValidationException("usageAt", "使用时间不能晚于当前时间");
        }
        UUID firstSignerId = requireUuid(request.getFirstSignerId(), "firstSignerId");
        String firstSignerName = requireText(request.getFirstSignerName(), "firstSignerName", 64);
        UUID secondSignerId = requireUuid(request.getSecondSignerId(), "secondSignerId");
        String secondSignerName = requireText(request.getSecondSignerName(), "secondSignerName", 64);
        requireDistinctSigners(firstSignerId, secondSignerId);

        Instant now = now();
        NarcoticRegisterEntity entity = new NarcoticRegisterEntity();
        entity.setId(UUID.randomUUID());
        entity.setDrugId(drug.getId());
        entity.setDrugName(drug.getName());
        entity.setBatchNo(batchNo);
        entity.setQuantity(quantity);
        entity.setUnit(unit);
        entity.setPrescriptionNo(prescriptionNo);
        entity.setUsedBy(usedBy);
        entity.setDepartmentId(UUID.fromString(departmentId));
        entity.setUsageAt(usageAt);
        entity.setDestination(destination);
        entity.setFirstSignerId(firstSignerId);
        entity.setFirstSignerName(firstSignerName);
        entity.setSecondSignerId(secondSignerId);
        entity.setSecondSignerName(secondSignerName);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        registerMapper.insertRegister(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "NARCOTIC_REGISTER");
        detail.put("registerId", entity.getId().toString());
        detail.put("drugId", drug.getId().toString());
        detail.put("drugName", drug.getName());
        detail.put("batchNo", batchNo);
        detail.put("quantity", quantity.toPlainString());
        detail.put("unit", unit);
        detail.put("departmentId", departmentId);
        auditWriter.record("NARCOTIC_REGISTER", RESOURCE_TYPE, entity.getId().toString(), true,
                detail, actorId(caller), username(caller), context);
        return toRegisterDto(entity);
    }

    // ------------------------------------------------------------------ 交接 --

    @Override
    public CabinetHandoverList listHandovers(String cabinetCode, String departmentId, int limit,
            AuthPrincipal caller) {
        requireLimit(limit);
        DataScopeAssignments scope = scope(caller);
        String deptFilter = optionalText(departmentId, "departmentId", 36);
        if (deptFilter == null && scope.isSelfOnly()) {
            deptFilter = requireSelfDepartment(caller);
        }
        if (deptFilter != null) {
            requireDepartmentAllowed(scope, caller, deptFilter);
        }
        List<String> allowedDeptIds = visibleDepartmentIds(scope, caller);
        List<CabinetHandoverEntity> rows = handoverMapper.selectHandovers(
                optionalText(cabinetCode, "cabinetCode", 32), deptFilter, allowedDeptIds, limit);
        CabinetHandoverList result = new CabinetHandoverList();
        List<CabinetHandover> items = new ArrayList<>();
        for (CabinetHandoverEntity row : rows) {
            items.add(toHandoverDto(row));
        }
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CabinetHandover createHandover(CreateCabinetHandoverRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        try {
            return doCreateHandover(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("NARCOTIC_HANDOVER", "NEW", failure, caller, context);
            throw failure;
        }
    }

    private CabinetHandover doCreateHandover(CreateCabinetHandoverRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        DataScopeAssignments scope = scope(caller);
        String departmentId = requireDepartment(scope, caller, request.getDepartmentId(),
                "departmentId");
        String cabinetCode = requireText(request.getCabinetCode(), "cabinetCode", 32);
        String shift = requireShift(request.getShift());
        if (request.getHandedOverAt() == null) {
            throw new M4ValidationException("handedOverAt", "交接时间不能为空");
        }
        Instant handedOverAt = request.getHandedOverAt().toInstant().truncatedTo(ChronoUnit.MILLIS);
        if (handedOverAt.isAfter(now().plus(FUTURE_TOLERANCE))) {
            throw new M4ValidationException("handedOverAt", "交接时间不能晚于当前时间");
        }
        UUID fromId = requireUuid(request.getHandoverFromId(), "handoverFromId");
        String fromName = requireText(request.getHandoverFromName(), "handoverFromName", 64);
        UUID toId = requireUuid(request.getHandoverToId(), "handoverToId");
        String toName = requireText(request.getHandoverToName(), "handoverToName", 64);
        if (fromId.equals(toId)) {
            throw new M4ValidationException("handoverToId", "接班人不得与交班人为同一人");
        }
        UUID firstSignerId = requireUuid(request.getFirstSignerId(), "firstSignerId");
        String firstSignerName = requireText(request.getFirstSignerName(), "firstSignerName", 64);
        UUID secondSignerId = requireUuid(request.getSecondSignerId(), "secondSignerId");
        String secondSignerName = requireText(request.getSecondSignerName(), "secondSignerName", 64);
        requireDistinctSigners(firstSignerId, secondSignerId);

        List<Map<String, Object>> itemRows = new ArrayList<>();
        List<CabinetHandoverItem> items = new ArrayList<>();
        Set<String> seenDrugIds = new HashSet<>();
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new M4ValidationException("items", "品种数量明细至少 1 条");
        }
        if (request.getItems().size() > 200) {
            throw new M4ValidationException("items", "品种数量明细不能超过 200 条");
        }
        for (var item : request.getItems()) {
            if (item == null) {
                throw new M4ValidationException("items", "品种数量明细不能包含空项");
            }
            DrugEntity drug = requireActiveDrug(item.getDrugId());
            if (!seenDrugIds.add(drug.getId().toString())) {
                throw new M4ValidationException("items", "同一药品不能重复登记：" + drug.getName());
            }
            BigDecimal quantity = requireQuantity(item.getQuantity(), "items.quantity", true);
            String unit = optionalText(item.getUnit(), "items.unit", 16);
            String effectiveUnit = unit == null ? drug.getUnit() : unit;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("drugId", drug.getId().toString());
            row.put("drugName", drug.getName());
            row.put("quantity", quantity.toPlainString());
            row.put("unit", effectiveUnit);
            itemRows.add(row);
            CabinetHandoverItem dto = new CabinetHandoverItem();
            dto.setDrugId(drug.getId().toString());
            dto.setDrugName(drug.getName());
            dto.setQuantity(quantity);
            dto.setUnit(effectiveUnit);
            items.add(dto);
        }

        Instant now = now();
        CabinetHandoverEntity entity = new CabinetHandoverEntity();
        entity.setId(UUID.randomUUID());
        entity.setCabinetCode(cabinetCode);
        entity.setDepartmentId(UUID.fromString(departmentId));
        entity.setShift(shift);
        entity.setHandedOverAt(handedOverAt);
        entity.setHandoverFromId(fromId);
        entity.setHandoverFromName(fromName);
        entity.setHandoverToId(toId);
        entity.setHandoverToName(toName);
        entity.setItems(writeJson(itemRows));
        entity.setFirstSignerId(firstSignerId);
        entity.setFirstSignerName(firstSignerName);
        entity.setSecondSignerId(secondSignerId);
        entity.setSecondSignerName(secondSignerName);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        handoverMapper.insertHandover(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "NARCOTIC_HANDOVER");
        detail.put("handoverId", entity.getId().toString());
        detail.put("cabinetCode", cabinetCode);
        detail.put("shift", shift);
        detail.put("departmentId", departmentId);
        detail.put("itemCount", items.size());
        auditWriter.record("NARCOTIC_HANDOVER", RESOURCE_TYPE, entity.getId().toString(), true,
                detail, actorId(caller), username(caller), context);
        return toHandoverDto(entity);
    }

    // ------------------------------------------------------------------ 基数 --

    @Override
    public DrugBaselineList listBaselines(String cabinetCode, String departmentId,
            AuthPrincipal caller) {
        DataScopeAssignments scope = scope(caller);
        String deptFilter = optionalText(departmentId, "departmentId", 36);
        if (deptFilter == null && scope.isSelfOnly()) {
            deptFilter = requireSelfDepartment(caller);
        }
        if (deptFilter != null) {
            requireDepartmentAllowed(scope, caller, deptFilter);
        }
        List<String> allowedDeptIds = visibleDepartmentIds(scope, caller);
        List<DrugBaselineEntity> rows = baselineMapper.selectBaselines(
                optionalText(cabinetCode, "cabinetCode", 32), deptFilter, allowedDeptIds);
        return toBaselineList(rows);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DrugBaselineList updateBaselines(UpdateDrugBaselinesRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        try {
            return doUpdateBaselines(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("NARCOTIC_BASELINE", "BASELINE", failure, caller, context);
            throw failure;
        }
    }

    private DrugBaselineList doUpdateBaselines(UpdateDrugBaselinesRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new M4ValidationException("items", "基数条目至少 1 条");
        }
        if (request.getItems().size() > 200) {
            throw new M4ValidationException("items", "基数条目不能超过 200 条");
        }
        DataScopeAssignments scope = scope(caller);
        List<Map<String, Object>> changes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Instant now = now();
        for (UpdateDrugBaselinesRequestItemsInner item : request.getItems()) {
            if (item == null) {
                throw new M4ValidationException("items", "基数条目不能为空");
            }
            DrugEntity drug = requireActiveDrug(item.getDrugId());
            String cabinetCode = requireText(item.getCabinetCode(), "items.cabinetCode", 32);
            String departmentId = requireDepartment(scope, caller, item.getDepartmentId(),
                    "items.departmentId");
            BigDecimal quantity = requireQuantity(item.getQuantity(), "items.quantity", true);
            if (!seen.add(drug.getId() + "|" + cabinetCode)) {
                throw new M4ValidationException("items", "同一药品 + 柜不能重复提交");
            }
            DrugBaselineEntity existing = baselineMapper.selectByDrugAndCabinet(
                    drug.getId().toString(), cabinetCode);
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("drugId", drug.getId().toString());
            change.put("cabinetCode", cabinetCode);
            change.put("quantity", quantity.toPlainString());
            if (existing == null) {
                DrugBaselineEntity entity = new DrugBaselineEntity();
                entity.setId(UUID.randomUUID());
                entity.setDrugId(drug.getId());
                entity.setCabinetCode(cabinetCode);
                entity.setDepartmentId(UUID.fromString(departmentId));
                entity.setQuantity(quantity);
                entity.setCreatedAt(now);
                entity.setCreatedBy(actorId(caller));
                entity.setUpdatedAt(now);
                entity.setUpdatedBy(actorId(caller));
                entity.setVersion(1);
                baselineMapper.insertBaseline(entity);
                change.put("operation", "CREATE");
            } else {
                // B-2：基数随柜，科室不可跨科改写；existing.departmentId 必须在调用者可见范围。
                String existingDepartmentId = existing.getDepartmentId().toString();
                if (!scope.isUnrestricted()
                        && !visibleDepartmentIds(scope, caller).contains(existingDepartmentId)) {
                    throw new AccessDeniedException("基数所属科室不在当前用户数据范围内");
                }
                if (!existingDepartmentId.equals(departmentId)) {
                    throw new M4ConflictException("基数所属科室不可变更；请按新科室新增记录");
                }
                Integer expectedVersion = item.getVersion();
                if (expectedVersion == null) {
                    throw new M4ValidationException("items.version", "更新基数必须回传版本号");
                }
                if (!existing.getVersion().equals(expectedVersion)) {
                    throw new M4ConflictException("基数已被修改，请刷新后重试");
                }
                DrugBaselineEntity entity = new DrugBaselineEntity();
                entity.setId(existing.getId());
                entity.setQuantity(quantity);
                entity.setUpdatedAt(now);
                entity.setUpdatedBy(actorId(caller));
                entity.setVersion(expectedVersion);
                if (baselineMapper.updateBaseline(entity) == 0) {
                    throw new M4ConflictException("基数已被修改，请刷新后重试");
                }
                change.put("operation", "UPDATE");
            }
            changes.add(change);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "NARCOTIC_BASELINE");
        detail.put("itemCount", changes.size());
        detail.put("changes", changes);
        auditWriter.record("NARCOTIC_BASELINE", RESOURCE_TYPE, "BASELINE", true, detail,
                actorId(caller), username(caller), context);
        return listBaselines(null, null, caller);
    }

    // ------------------------------------------------------------------ 日结 --

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DailyClose createDailyClose(CreateDailyCloseRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        try {
            return doCreateDailyClose(request, caller, context);
        } catch (M4ValidationException | M4ConflictException | M4UnprocessableException failure) {
            recordFailure("NARCOTIC_DAILY_CLOSE", "NEW", failure, caller, context);
            throw failure;
        }
    }

    private DailyClose doCreateDailyClose(CreateDailyCloseRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        DataScopeAssignments scope = scope(caller);
        String departmentId = requireDepartment(scope, caller, request.getDepartmentId(),
                "departmentId");
        if (request.getCloseDate() == null) {
            throw new M4ValidationException("closeDate", "日结日期不能为空");
        }
        LocalDate closeDate = request.getCloseDate();
        if (closeDate.isAfter(LocalDate.now(clock.withZone(DISPLAY_OFFSET)))) {
            throw new M4ValidationException("closeDate", "日结日期不能晚于今天");
        }
        String shift = requireShift(request.getShift());
        String cabinetCode = requireText(request.getCabinetCode(), "cabinetCode", 32);
        BigDecimal opening = requireQuantity(request.getOpeningQuantity(), "openingQuantity", true);
        BigDecimal inbound = requireQuantity(request.getInboundQuantity(), "inboundQuantity", true);
        BigDecimal used = requireQuantity(request.getUsedQuantity(), "usedQuantity", true);
        BigDecimal returned = requireQuantity(request.getReturnedQuantity(), "returnedQuantity",
                true);
        BigDecimal closing = requireQuantity(request.getClosingQuantity(), "closingQuantity", true);
        BigDecimal expected = opening.add(inbound).subtract(used).subtract(returned);
        if (expected.compareTo(closing) != 0) {
            throw new M4ConflictException("平账校验未通过，操作未生效",
                    List.of(new M4ConflictException.Detail("closingQuantity",
                            "平账校验未通过：期望 " + plain(expected) + "，实盘 " + plain(closing)
                                    + "（期初 " + plain(opening) + " + 入库 " + plain(inbound)
                                    + " − 使用 " + plain(used) + " − 退回 " + plain(returned) + "）")));
        }
        if (closeMapper.countDuplicate(cabinetCode, closeDate, shift) > 0) {
            throw new M4ConflictException("该柜号 + 日期 + 班次已日结，操作未生效");
        }
        Instant now = now();
        DailyCloseEntity entity = new DailyCloseEntity();
        entity.setId(UUID.randomUUID());
        entity.setCloseDate(closeDate);
        entity.setShift(shift);
        entity.setCabinetCode(cabinetCode);
        entity.setDepartmentId(UUID.fromString(departmentId));
        entity.setOpeningQuantity(opening);
        entity.setInboundQuantity(inbound);
        entity.setUsedQuantity(used);
        entity.setReturnedQuantity(returned);
        entity.setClosingQuantity(closing);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        closeMapper.insertClose(entity);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "NARCOTIC_DAILY_CLOSE");
        detail.put("closeId", entity.getId().toString());
        detail.put("closeDate", closeDate.toString());
        detail.put("shift", shift);
        detail.put("cabinetCode", cabinetCode);
        detail.put("departmentId", departmentId);
        detail.put("openingQuantity", plain(opening));
        detail.put("inboundQuantity", plain(inbound));
        detail.put("usedQuantity", plain(used));
        detail.put("returnedQuantity", plain(returned));
        detail.put("closingQuantity", plain(closing));
        auditWriter.record("NARCOTIC_DAILY_CLOSE", RESOURCE_TYPE, entity.getId().toString(), true,
                detail, actorId(caller), username(caller), context);
        return toCloseDto(entity);
    }

    @Override
    public DailyClose getDailyClose(String closeId, AuthPrincipal caller) {
        UUID id = parseUuid(closeId);
        DailyCloseEntity entity = id == null ? null : closeMapper.selectByCloseId(id);
        if (entity == null) {
            throw new NoSuchElementException("日结记录不存在或不可见");
        }
        DataScopeAssignments scope = scope(caller);
        if (!scope.isUnrestricted() && !visibleDepartmentIds(scope, caller)
                .contains(entity.getDepartmentId().toString())) {
            throw new NoSuchElementException("日结记录不存在或不可见");
        }
        return toCloseDto(entity);
    }

    // ---------------------------------------------------------------- 数据范围 --

    private DataScopeAssignments scope(AuthPrincipal caller) {
        DataScopeAssignments scope = dataScopeResolver.resolve(caller == null ? null : caller.userId());
        if (scope == null || scope.isDenied()) {
            throw new AccessDeniedException("当前用户无数据范围");
        }
        return scope;
    }

    /**
     * 可见科室标识：ALL 返回 null（不限制）；SITE 展开院区子树、DEPT 取授权科室、
     * SELF 取本人绑定科室（S-5）；ROOM/无组织绑定 → 403（五专无“术间/本人患者”语义）。
     */
    private List<String> visibleDepartmentIds(DataScopeAssignments scope, AuthPrincipal caller) {
        if (scope.isUnrestricted()) {
            return null;
        }
        UUID userId = caller == null ? null : caller.userId();
        List<OrgDepartment> departments = organizationQueryService.visibleDepartments(userId);
        if (departments.isEmpty()) {
            throw new AccessDeniedException("麻精五专仅按科室数据范围开放");
        }
        return departments.stream().map(OrgDepartment::orgId).toList();
    }

    /**
     * SELF 范围推导“本人所属科室”（S-5）：取主科室（主科室优先，其次首个绑定科室）。
     * 无绑定科室 → 403；五专记录一律落该科室，跨科室 403。
     */
    private String requireSelfDepartment(AuthPrincipal caller) {
        UUID userId = caller == null ? null : caller.userId();
        List<OrgDepartment> departments = organizationQueryService.departmentsOfUser(userId);
        if (departments.isEmpty()) {
            throw new AccessDeniedException("当前用户未绑定科室，无法使用麻精五专");
        }
        return departments.get(0).orgId();
    }

    /** 列表过滤科室：必须是启用 DEPT 且在调用者可见范围内（B-3 服务端校验）。 */
    private void requireDepartmentAllowed(DataScopeAssignments scope, AuthPrincipal caller,
            String departmentId) {
        UUID dept = parseUuid(departmentId);
        if (dept == null) {
            throw new M4ValidationException("departmentId", "科室标识格式非法");
        }
        OrgDepartment active = organizationQueryService.findActiveDepartment(dept)
                .orElseThrow(() -> new M4UnprocessableException("departmentId",
                        "科室不存在、已停用或不是科室组织"));
        if (scope.isUnrestricted()) {
            return;
        }
        if (!visibleDepartmentIds(scope, caller).contains(active.orgId())) {
            throw new AccessDeniedException("科室不在当前用户数据范围内");
        }
    }

    /**
     * 写入科室：SELF 未显式传值时按本人所属科室推导；其余场景必填。
     * 校验组织存在/启用/org_type=DEPT（422），再校验数据范围（403）。
     */
    private String requireDepartment(DataScopeAssignments scope, AuthPrincipal caller,
            String departmentId, String field) {
        String value = departmentId == null ? null : departmentId.trim();
        if (value == null || value.isEmpty()) {
            if (scope.isSelfOnly()) {
                value = requireSelfDepartment(caller);
            } else {
                throw new M4ValidationException(field, field + " 不能为空");
            }
        }
        UUID dept = parseUuid(value);
        if (dept == null) {
            throw new M4ValidationException(field, "科室标识格式非法");
        }
        OrgDepartment active = organizationQueryService.findActiveDepartment(dept)
                .orElseThrow(() -> new M4UnprocessableException(field,
                        "科室不存在、已停用或不是科室组织"));
        if (scope.isUnrestricted()) {
            return active.orgId();
        }
        if (scope.isSelfOnly()) {
            if (!active.orgId().equals(requireSelfDepartment(caller))) {
                throw new AccessDeniedException("科室不在当前用户数据范围内");
            }
            return active.orgId();
        }
        if (!visibleDepartmentIds(scope, caller).contains(active.orgId())) {
            throw new AccessDeniedException("科室不在当前用户数据范围内");
        }
        return active.orgId();
    }

    // ------------------------------------------------------------------ 辅助 --

    /**
     * 失败路径审计（S-2）：detail 只含 operation/field/rejection 与固定标识，
     * 不含处方号原文、人员姓名、备注原文与患者信息。
     */
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

    private DrugEntity requireActiveDrug(String drugId) {
        UUID id = parseUuid(drugId);
        DrugEntity drug = id == null ? null : drugMapper.selectByDrugId(id);
        if (drug == null) {
            throw new M4UnprocessableException("drugId", "药品未收录于药品字典");
        }
        if (!STATUS_ACTIVE.equals(drug.getStatus())) {
            throw new M4UnprocessableException("drugId", "药品已停用，不能用于五专登记");
        }
        return drug;
    }

    private static void requireDistinctSigners(UUID firstSignerId, UUID secondSignerId) {
        if (firstSignerId.equals(secondSignerId)) {
            throw new M4ValidationException("secondSignerId", "第二签字人不得与第一签字人为同一人");
        }
    }

    private static String requireShift(Object shift) {
        String value = shift == null ? null : shift.toString().trim().toUpperCase(Locale.ROOT);
        if (value == null || value.isEmpty()) {
            throw new M4ValidationException("shift", "班次不能为空");
        }
        if (!SHIFT_DAY.equals(value) && !SHIFT_NIGHT.equals(value)) {
            throw new M4ValidationException("shift", "班次只能是 DAY 或 NIGHT");
        }
        return value;
    }

    private static BigDecimal requireQuantity(BigDecimal value, String field, boolean allowZero) {
        if (value == null) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        if (value.scale() > 2) {
            throw new M4ValidationException(field, field + " 最多两位小数");
        }
        BigDecimal normalized = value.setScale(2, RoundingMode.UNNECESSARY);
        if (allowZero ? normalized.signum() < 0 : normalized.signum() <= 0) {
            throw new M4ValidationException(field,
                    field + (allowZero ? " 不能小于 0" : " 必须大于 0"));
        }
        return normalized;
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new M4ValidationException("limit", "返回条数必须在 1–200 之间");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("序列化五专明细失败", failure);
        }
    }

    private List<CabinetHandoverItem> readItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() { });
            List<CabinetHandoverItem> items = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                CabinetHandoverItem item = new CabinetHandoverItem();
                item.setDrugId(String.valueOf(row.get("drugId")));
                item.setDrugName(String.valueOf(row.get("drugName")));
                Object quantity = row.get("quantity");
                item.setQuantity(quantity == null ? null : new BigDecimal(quantity.toString()));
                item.setUnit(String.valueOf(row.get("unit")));
                items.add(item);
            }
            return items;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("解析五专明细失败", failure);
        }
    }

    private NarcoticRegister toRegisterDto(NarcoticRegisterEntity entity) {
        NarcoticRegister dto = new NarcoticRegister();
        dto.setRegisterId(entity.getId().toString());
        dto.setDrugId(entity.getDrugId().toString());
        dto.setDrugName(entity.getDrugName());
        dto.setBatchNo(entity.getBatchNo());
        dto.setQuantity(entity.getQuantity());
        dto.setUnit(entity.getUnit());
        dto.setPrescriptionNo(entity.getPrescriptionNo());
        dto.setUsedBy(entity.getUsedBy());
        dto.setDepartmentId(entity.getDepartmentId().toString());
        dto.setUsageAt(entity.getUsageAt().atOffset(DISPLAY_OFFSET));
        dto.setDestination(entity.getDestination());
        dto.setFirstSignerId(entity.getFirstSignerId().toString());
        dto.setFirstSignerName(entity.getFirstSignerName());
        dto.setSecondSignerId(entity.getSecondSignerId().toString());
        dto.setSecondSignerName(entity.getSecondSignerName());
        dto.setCreatedAt(entity.getCreatedAt().atOffset(DISPLAY_OFFSET));
        dto.setCreatedBy(entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString());
        return dto;
    }

    private CabinetHandover toHandoverDto(CabinetHandoverEntity entity) {
        CabinetHandover dto = new CabinetHandover();
        dto.setHandoverId(entity.getId().toString());
        dto.setCabinetCode(entity.getCabinetCode());
        dto.setDepartmentId(entity.getDepartmentId().toString());
        dto.setShift(CabinetHandover.ShiftEnum.fromValue(entity.getShift()));
        dto.setHandedOverAt(entity.getHandedOverAt().atOffset(DISPLAY_OFFSET));
        dto.setHandoverFromId(entity.getHandoverFromId().toString());
        dto.setHandoverFromName(entity.getHandoverFromName());
        dto.setHandoverToId(entity.getHandoverToId().toString());
        dto.setHandoverToName(entity.getHandoverToName());
        dto.setItems(readItems(entity.getItems()));
        dto.setFirstSignerId(entity.getFirstSignerId().toString());
        dto.setFirstSignerName(entity.getFirstSignerName());
        dto.setSecondSignerId(entity.getSecondSignerId().toString());
        dto.setSecondSignerName(entity.getSecondSignerName());
        dto.setCreatedAt(entity.getCreatedAt().atOffset(DISPLAY_OFFSET));
        dto.setCreatedBy(entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString());
        return dto;
    }

    private DrugBaselineList toBaselineList(List<DrugBaselineEntity> rows) {
        Map<String, DrugEntity> drugs = new LinkedHashMap<>();
        DrugBaselineList result = new DrugBaselineList();
        List<DrugBaseline> items = new ArrayList<>();
        for (DrugBaselineEntity row : rows) {
            DrugEntity drug = drugs.computeIfAbsent(row.getDrugId().toString(),
                    key -> drugMapper.selectByDrugId(row.getDrugId()));
            DrugBaseline dto = new DrugBaseline();
            dto.setBaselineId(row.getId().toString());
            dto.setDrugId(row.getDrugId().toString());
            dto.setDrugName(drug == null ? "" : drug.getName());
            dto.setCabinetCode(row.getCabinetCode());
            dto.setDepartmentId(row.getDepartmentId().toString());
            dto.setQuantity(row.getQuantity());
            dto.setUnit(drug == null ? "" : drug.getUnit());
            dto.setUpdatedAt(row.getUpdatedAt().atOffset(DISPLAY_OFFSET));
            dto.setUpdatedBy(row.getUpdatedBy() == null ? null : row.getUpdatedBy().toString());
            dto.setVersion(row.getVersion());
            items.add(dto);
        }
        result.setItems(items);
        return result;
    }

    private DailyClose toCloseDto(DailyCloseEntity entity) {
        DailyClose dto = new DailyClose();
        dto.setCloseId(entity.getId().toString());
        dto.setCloseDate(entity.getCloseDate());
        dto.setShift(DailyClose.ShiftEnum.fromValue(entity.getShift()));
        dto.setCabinetCode(entity.getCabinetCode());
        dto.setDepartmentId(entity.getDepartmentId().toString());
        dto.setOpeningQuantity(entity.getOpeningQuantity());
        dto.setInboundQuantity(entity.getInboundQuantity());
        dto.setUsedQuantity(entity.getUsedQuantity());
        dto.setReturnedQuantity(entity.getReturnedQuantity());
        dto.setClosingQuantity(entity.getClosingQuantity());
        dto.setBalanced(true);
        dto.setCreatedAt(entity.getCreatedAt().atOffset(DISPLAY_OFFSET));
        dto.setCreatedBy(entity.getCreatedBy() == null ? null : entity.getCreatedBy().toString());
        return dto;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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

    private static UUID requireUuid(String value, String field) {
        UUID parsed = parseUuid(value);
        if (parsed == null) {
            throw new M4ValidationException(field, field + " 不能为空且必须是 UUID");
        }
        return parsed;
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
