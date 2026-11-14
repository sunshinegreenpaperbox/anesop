package com.anesop.m4.impl.support;

import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 耗材域数据范围守卫（M4-M14-01）。
 *
 * <p>耗材目录是全院主数据（不按科室过滤），**批次库存、出入库流水与 UDI 实例按科室隔离**：
 * <ul>
 *   <li>{@code ALL}：全院；</li>
 *   <li>{@code SITE}：授予院区 {@code sys_org.path} 子树内的启用科室；</li>
 *   <li>{@code DEPT}：授权科室；</li>
 *   <li>{@code SELF}/{@code ROOM}/无范围：fail-closed 403（仓库/追溯没有“本人/术间”语义，
 *       避免把术间可见性误当成库存可见性）。</li>
 * </ul>
 *
 * <p>写入科室必须是启用 {@code org_type=DEPT} 组织（缺失/停用/非科室 422），且在调用者
 * 数据范围内（403）；列表过滤同样先校验科室合法性再校验范围。本类只做访问控制，
 * 不返回患者信息或业务字段。
 */
@Component
public class MaterialAccessGuard {

    private final DataScopeResolver dataScopeResolver;
    private final OrganizationQueryService organizationQueryService;

    public MaterialAccessGuard(DataScopeResolver dataScopeResolver,
            OrganizationQueryService organizationQueryService) {
        this.dataScopeResolver = dataScopeResolver;
        this.organizationQueryService = organizationQueryService;
    }

    /** 解析调用者数据范围；被拒绝（无范围/未知）→ 403。 */
    public DataScopeAssignments scope(AuthPrincipal caller) {
        DataScopeAssignments scope =
                dataScopeResolver.resolve(caller == null ? null : caller.userId());
        if (scope == null || scope.isDenied()) {
            throw new AccessDeniedException("当前用户无数据范围");
        }
        return scope;
    }

    /**
     * 可见科室标识：ALL 返回 {@code null}（不过滤）；SITE/DEPT 返回可见科室；
     * SELF/ROOM/其他无科室维度 → 403。
     */
    public List<String> visibleDepartmentIds(DataScopeAssignments scope, AuthPrincipal caller) {
        if (scope.isUnrestricted()) {
            return null;
        }
        if (scope.isSelfOnly() || !hasObjectScopeTypes(scope, DataScope.SITE, DataScope.DEPT)) {
            throw new AccessDeniedException("耗材库存仅按科室数据范围开放");
        }
        UUID userId = caller == null ? null : caller.userId();
        List<OrgDepartment> departments = organizationQueryService.visibleDepartments(userId);
        if (departments.isEmpty()) {
            throw new AccessDeniedException("耗材库存仅按科室数据范围开放");
        }
        return departments.stream().map(OrgDepartment::orgId).toList();
    }

    /** 列表科室过滤（可空）：校验格式、启用 DEPT 与数据范围；空值返回 null。 */
    public String optionalDepartmentFilter(DataScopeAssignments scope, AuthPrincipal caller,
            String departmentId, String field) {
        if (departmentId == null || departmentId.isBlank()) {
            return null;
        }
        return requireDepartment(scope, caller, departmentId, field);
    }

    /** 写入/过滤科室：必须存在、启用且为 DEPT（422），并在数据范围内（403）。 */
    public String requireDepartment(DataScopeAssignments scope, AuthPrincipal caller,
            String departmentId, String field) {
        UUID dept = parseUuid(departmentId);
        if (dept == null) {
            throw new M4ValidationException(field, "科室标识格式非法");
        }
        OrgDepartment active = organizationQueryService.findActiveDepartment(dept)
                .orElseThrow(() -> new M4UnprocessableException(field,
                        "科室不存在、已停用或不是科室组织"));
        if (scope.isUnrestricted()) {
            return active.orgId();
        }
        List<String> allowed = visibleDepartmentIds(scope, caller);
        if (allowed == null || !allowed.contains(active.orgId())) {
            throw new AccessDeniedException("科室不在当前用户数据范围内");
        }
        return active.orgId();
    }

    /** 单对象可见性（UDI 实例/流水）：不可见返回 false，由调用方按 404 处理。 */
    public boolean isVisible(DataScopeAssignments scope, AuthPrincipal caller, UUID departmentId) {
        if (departmentId == null) {
            return false;
        }
        if (scope.isUnrestricted()) {
            return true;
        }
        List<String> allowed;
        try {
            allowed = visibleDepartmentIds(scope, caller);
        } catch (AccessDeniedException denied) {
            return false;
        }
        return allowed != null && allowed.contains(departmentId.toString());
    }

    private static boolean hasObjectScopeTypes(DataScopeAssignments scope, DataScope... types) {
        for (DataScopeAssignment assignment : scope.assignments()) {
            for (DataScope type : types) {
                if (assignment.type() == type) {
                    return true;
                }
            }
        }
        return false;
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
