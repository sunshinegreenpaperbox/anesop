package com.anesop.m4.impl.material;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.material.model.LowStockThresholdRequest;
import com.anesop.contracts.material.model.Material;
import com.anesop.contracts.material.model.MaterialStockAlert;
import com.anesop.contracts.material.model.MaterialStockAlertList;
import com.anesop.contracts.material.model.StockAlertType;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m4.api.material.MaterialAlertService;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 耗材库存预警与阈值维护实现（契约 material.yaml 0.2.0，M8-M4-02）。
 *
 * <p>预警聚合在 SQL 中完成（{@code HAVING} 先过滤再 {@code LIMIT}，避免“先取全量再截断”
 * 造成漏报）；服务层只做边界与口径换算：
 * <ul>
 *   <li>可用量 = 该 材料 + 科室 的批次数量合计；可用量**严格低于**阈值才算低库存；</li>
 *   <li>零库存 = 合计为 0（仅对已发生过出入库、存在库存行的材料判定）；</li>
 *   <li>临期 = 存在数量 &gt; 0 且效期 ≤ 今天 + 90 天的批次（含已过期）；</li>
 *   <li>建议补货量 = max(0, 阈值 − 可用量)；阈值缺失为 null（不臆造补货量）。</li>
 * </ul>
 *
 * <p>阈值维护单独更新 {@code low_stock_threshold}（乐观锁 version），审计
 * {@code MATERIAL_THRESHOLD_UPDATE}（detail 只含 materialId/code/前后阈值/version）。
 * 数据范围 ALL/SITE/DEPT；SELF/ROOM 与科室越界 403（fail-closed）。
 */
@Service
public class MaterialAlertServiceImpl implements MaterialAlertService {

    /** 临期天数（契约 material.yaml 0.2.0 固定 90 天；是否按耗材分类配置【待确认】）。 */
    public static final int NEAR_EXPIRY_DAYS = 90;

    private static final String RESOURCE_TYPE = "MATERIAL";
    private static final String LOW_STOCK = "LOW_STOCK";
    private static final String ZERO_STOCK = "ZERO_STOCK";
    private static final String NEAR_EXPIRY = "NEAR_EXPIRY";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    /** numeric(12,2) 的整数位上限（10 位），越界 400 而不是让数据库报错。 */
    private static final int MAX_INTEGER_DIGITS = 10;

    private final MaterialMapper materialMapper;
    private final MaterialStockMapper stockMapper;
    private final MaterialCatalogService catalogService;
    private final MaterialAccessGuard accessGuard;
    private final OrganizationQueryService organizationQueryService;
    private final AuditWriter auditWriter;
    private final M4FailureAuditor failureAuditor;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务/审计由容器管理，本类不修改其内部状态")
    public MaterialAlertServiceImpl(MaterialMapper materialMapper, MaterialStockMapper stockMapper,
            MaterialCatalogService catalogService, MaterialAccessGuard accessGuard,
            OrganizationQueryService organizationQueryService, AuditWriter auditWriter,
            M4FailureAuditor failureAuditor, Clock clock) {
        this.materialMapper = materialMapper;
        this.stockMapper = stockMapper;
        this.catalogService = catalogService;
        this.accessGuard = accessGuard;
        this.organizationQueryService = organizationQueryService;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.clock = clock;
    }

    @Override
    public MaterialStockAlertList stockAlerts(String departmentId, String alertType, int limit,
            AuthPrincipal caller) {
        requireLimit(limit);
        String normalizedType = normalizeAlertType(alertType);
        DataScopeAssignments scope = accessGuard.scope(caller);
        // 先按数据范围判定（SELF/ROOM/无科室维度 fail-closed 403），再套科室过滤。
        List<String> allowed = accessGuard.visibleDepartmentIds(scope, caller);
        String deptFilter = accessGuard.optionalDepartmentFilter(scope, caller, departmentId,
                "departmentId");
        LocalDate today = LocalDate.now(clock.withZone(DISPLAY_OFFSET));
        LocalDate cutoff = today.plusDays(NEAR_EXPIRY_DAYS);
        List<MaterialStockAlertRow> rows = stockMapper.selectStockAlerts(deptFilter, allowed,
                cutoff, normalizedType, limit);
        Map<String, String> departmentNames = departmentNames(caller);
        List<MaterialStockAlert> items = new ArrayList<>(rows.size());
        for (MaterialStockAlertRow row : rows) {
            items.add(toAlert(row, departmentNames, today));
        }
        MaterialStockAlertList result = new MaterialStockAlertList();
        result.setItems(items);
        result.setTotal(items.size());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Material updateThreshold(String materialId, LowStockThresholdRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        String resourceId = safeResourceId(materialId);
        try {
            return doUpdateThreshold(materialId, request, caller, context);
        } catch (M4ValidationException | M4ConflictException failure) {
            Map<String, Object> locator = new LinkedHashMap<>();
            if (parseUuid(materialId) != null) {
                locator.put("materialId", materialId.trim());
            }
            if (request != null && request.getThreshold() != null) {
                locator.put("threshold", request.getThreshold());
            }
            recordFailure("MATERIAL_THRESHOLD_UPDATE", resourceId, failure, locator, caller,
                    context);
            throw failure;
        }
    }

    private Material doUpdateThreshold(String materialId, LowStockThresholdRequest request,
            AuthPrincipal caller, AuthRequestContext context) {
        MaterialEntity existing = requireMaterial(materialId);
        if (request == null) {
            throw new M4ValidationException("body", "请求体不能为空");
        }
        Integer expectedVersion = request.getVersion();
        if (expectedVersion == null || expectedVersion < 1) {
            throw new M4ValidationException("version", "乐观锁版本号不能为空且必须大于等于 1");
        }
        if (!existing.getVersion().equals(expectedVersion)) {
            throw new M4ConflictException("耗材已被修改，请刷新后重试");
        }
        BigDecimal threshold = requireThreshold(request.getThreshold());
        // 阈值是全院主数据（粒度【待确认】）：维护权限必须有科室数据范围（ALL/SITE/DEPT），
        // SELF/ROOM 与无范围 fail-closed 403。
        DataScopeAssignments scope = accessGuard.scope(caller);
        accessGuard.visibleDepartmentIds(scope, caller);

        Instant now = now();
        int affected = materialMapper.updateLowStockThreshold(existing.getId(), threshold,
                expectedVersion, now, caller == null ? null : caller.userId());
        if (affected == 0) {
            throw new M4ConflictException("耗材已被修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "MATERIAL_THRESHOLD_UPDATE");
        detail.put("materialId", existing.getId().toString());
        detail.put("code", existing.getCode());
        detail.put("previousThreshold", existing.getLowStockThreshold());
        detail.put("threshold", threshold);
        detail.put("version", expectedVersion + 1);
        auditWriter.record("MATERIAL_THRESHOLD_UPDATE", RESOURCE_TYPE, existing.getId().toString(),
                true, detail, caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);
        return catalogService.get(existing.getId().toString());
    }

    private MaterialStockAlert toAlert(MaterialStockAlertRow row, Map<String, String> names,
            LocalDate today) {
        BigDecimal available = row.getAvailableQuantity() == null
                ? BigDecimal.ZERO : row.getAvailableQuantity();
        BigDecimal threshold = row.getLowStockThreshold();
        boolean zeroStock = available.signum() == 0;
        boolean lowStock = threshold != null && available.compareTo(threshold) < 0;
        long nearExpiryBatches = row.getNearExpiryBatchCount() == null
                ? 0L : row.getNearExpiryBatchCount();
        boolean nearExpiry = nearExpiryBatches > 0;

        List<StockAlertType> types = new ArrayList<>(3);
        if (lowStock) {
            types.add(StockAlertType.LOW_STOCK);
        }
        if (zeroStock) {
            types.add(StockAlertType.ZERO_STOCK);
        }
        if (nearExpiry) {
            types.add(StockAlertType.NEAR_EXPIRY);
        }

        MaterialStockAlert alert = new MaterialStockAlert();
        alert.setMaterialId(row.getMaterialId().toString());
        alert.setMaterialCode(row.getMaterialCode());
        alert.setMaterialName(row.getMaterialName());
        alert.setUnit(row.getUnit());
        String departmentId = row.getDepartmentId().toString();
        alert.setDepartmentId(departmentId);
        alert.setDepartmentName(names.getOrDefault(departmentId, departmentId));
        alert.setAvailableQuantity(available);
        alert.setLowStockThreshold(threshold);
        alert.setSuggestedQuantity(lowStock
                ? threshold.subtract(available).max(BigDecimal.ZERO) : null);
        alert.setAlertTypes(types);
        alert.setBatchCount(row.getBatchCount() == null ? 0 : Math.toIntExact(row.getBatchCount()));
        LocalDate nearest = row.getNearestExpiryDate();
        alert.setNearestExpiryDate(nearest);
        alert.setDaysToExpiry(nearest == null ? null
                : Math.toIntExact(ChronoUnit.DAYS.between(today, nearest)));
        alert.setNearExpiryBatchCount(Math.toIntExact(nearExpiryBatches));
        return alert;
    }

    /** 可见科室名称（ALL 返回全部启用科室；停用/不可见科室回退为科室标识，不泄露额外信息）。 */
    private Map<String, String> departmentNames(AuthPrincipal caller) {
        Map<String, String> names = new LinkedHashMap<>();
        List<OrgDepartment> departments =
                organizationQueryService.visibleDepartments(caller == null ? null : caller.userId());
        for (OrgDepartment department : departments) {
            names.put(department.orgId(), department.name());
        }
        return names;
    }

    private static String normalizeAlertType(String alertType) {
        if (alertType == null || alertType.isBlank()) {
            return null;
        }
        String normalized = alertType.trim().toUpperCase(Locale.ROOT);
        if (!LOW_STOCK.equals(normalized) && !ZERO_STOCK.equals(normalized)
                && !NEAR_EXPIRY.equals(normalized)) {
            throw new M4ValidationException("alertType",
                    "alertType 必须为 LOW_STOCK/ZERO_STOCK/NEAR_EXPIRY");
        }
        return normalized;
    }

    private static BigDecimal requireThreshold(BigDecimal threshold) {
        if (threshold == null) {
            return null;
        }
        if (threshold.signum() < 0) {
            throw new M4ValidationException("threshold", "阈值不能为负数");
        }
        if (threshold.stripTrailingZeros().scale() > 2) {
            throw new M4ValidationException("threshold", "阈值最多支持 2 位小数");
        }
        if (threshold.precision() - threshold.scale() > MAX_INTEGER_DIGITS) {
            throw new M4ValidationException("threshold", "阈值超出 numeric(12,2) 范围");
        }
        return threshold;
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new M4ValidationException("limit", "返回条数必须在 1–200 之间");
        }
    }

    /**
     * 取耗材主数据（不存在 404）。
     *
     * <p>停用耗材也允许维护阈值（历史库存仍可能在库，后续重新启用可直接生效），
     * 但停用耗材不参与预警查询（SQL 过滤 {@code status=ACTIVE}）。
     */
    private MaterialEntity requireMaterial(String materialId) {
        UUID id = parseUuid(materialId);
        MaterialEntity entity = id == null ? null : materialMapper.selectByMaterialId(id);
        if (entity == null) {
            throw new NoSuchElementException("耗材不存在或不可见");
        }
        return entity;
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
