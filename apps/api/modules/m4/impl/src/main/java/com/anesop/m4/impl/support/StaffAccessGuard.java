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
import com.anesop.m4.impl.persistence.entity.StaffProfileEntity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 人员域数据范围守卫（M4-M13-01）。
 *
 * <p>四个服务（档案/授权/排班/培训）共用同一套数据范围语义，避免各写一遍导致越权缺口：
 * <ul>
 *   <li>{@code ALL}：全院；</li>
 *   <li>{@code SITE}：授予院区 {@code sys_org.path} 子树内的启用科室；</li>
 *   <li>{@code DEPT}：授权科室；</li>
 *   <li>{@code SELF}：仅本人档案/排班/培训（按 m0 {@code sys_user} userId 关联），
 *       **写操作一律 403**（人员主数据不允许本人自改科室/职称）；</li>
 *   <li>{@code ROOM}/无范围：fail-closed 403（本域没有术间维度的人员可见性语义）。</li>
 * </ul>
 *
 * <p>单对象读取越界与不存在统一抛 {@link NoSuchElementException}（Web 层 404），不泄露
 * 其他科室数据的存在性。本类只做访问控制，不返回姓名等业务字段。
 */
@Component
public class StaffAccessGuard {

    private final DataScopeResolver dataScopeResolver;
    private final OrganizationQueryService organizationQueryService;

    public StaffAccessGuard(DataScopeResolver dataScopeResolver,
            OrganizationQueryService organizationQueryService) {
        this.dataScopeResolver = dataScopeResolver;
        this.organizationQueryService = organizationQueryService;
    }

    /**
     * 数据范围过滤条件。
     *
     * @param allowedDeptIds 允许的科室（null 表示全院不过滤）
     * @param selfUserId     仅本人时的 m0 用户标识（null 表示不按本人过滤）
     */
    public record StaffVisibility(List<String> allowedDeptIds, String selfUserId) {

        /** 防御性复制：避免调用方修改可见科室集合造成越权。 */
        public StaffVisibility {
            allowedDeptIds = allowedDeptIds == null ? null : List.copyOf(allowedDeptIds);
        }

        /** 返回不可变副本（SpotBugs EI_EXPOSE_REP 防护）。 */
        @Override
        public List<String> allowedDeptIds() {
            return allowedDeptIds == null ? null : List.copyOf(allowedDeptIds);
        }

        /** 是否全院可见。 */
        public boolean unrestricted() {
            return allowedDeptIds == null && selfUserId == null;
        }
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
     * 计算列表过滤条件：ALL → 都不过滤；SELF → 按本人 userId；SITE/DEPT → 科室列表；
     * ROOM 或不可用范围 → 403。
     */
    public StaffVisibility visibility(AuthPrincipal caller) {
        DataScopeAssignments scope = scope(caller);
        if (scope.isUnrestricted()) {
            return new StaffVisibility(null, null);
        }
        if (scope.isSelfOnly()) {
            UUID userId = caller == null ? null : caller.userId();
            if (userId == null) {
                throw new AccessDeniedException("当前用户无数据范围");
            }
            return new StaffVisibility(null, userId.toString());
        }
        if (!hasObjectScopeTypes(scope, DataScope.SITE, DataScope.DEPT)) {
            throw new AccessDeniedException("人员管理仅按科室数据范围开放");
        }
        List<OrgDepartment> departments =
                organizationQueryService.visibleDepartments(caller == null ? null : caller.userId());
        if (departments.isEmpty()) {
            throw new AccessDeniedException("人员管理仅按科室数据范围开放");
        }
        return new StaffVisibility(departments.stream().map(OrgDepartment::orgId).toList(), null);
    }

    /** 写操作守卫：SELF 范围只读（403）。 */
    public void requireWritable(AuthPrincipal caller) {
        StaffVisibility visibility = visibility(caller);
        if (visibility.selfUserId() != null) {
            throw new AccessDeniedException("本人数据范围仅允许查看，不能维护人员/排班/培训");
        }
    }

    /**
     * 校验科室可用于过滤：存在、启用且 {@code org_type=DEPT}（422），并在数据范围内（403）。
     * SELF 范围允许按本人所属科室过滤。
     */
    public String requireDepartmentFilter(AuthPrincipal caller, String departmentId, String field) {
        if (departmentId == null || departmentId.isBlank()) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        UUID dept = parseUuid(departmentId);
        if (dept == null) {
            throw new M4ValidationException(field, "科室标识格式非法");
        }
        OrgDepartment active = organizationQueryService.findActiveDepartment(dept)
                .orElseThrow(() -> new M4UnprocessableException(field,
                        "科室不存在、已停用或不是科室组织"));
        if (scope(caller).isUnrestricted()) {
            return active.orgId();
        }
        List<OrgDepartment> departments =
                organizationQueryService.visibleDepartments(caller == null ? null : caller.userId());
        boolean visible = departments.stream()
                .anyMatch(department -> department.orgId().equals(active.orgId()));
        if (!visible) {
            throw new AccessDeniedException("科室不在当前用户数据范围内");
        }
        return active.orgId();
    }

    /** 校验并规整科室（写入）：先要求可写（SELF 403），再校验存在/启用/DEPT 与数据范围。 */
    public String requireDepartment(AuthPrincipal caller, String departmentId, String field) {
        requireWritable(caller);
        return requireDepartmentFilter(caller, departmentId, field);
    }

    /** 单对象可见性：不可见与不存在统一 404（不泄露存在性）。 */
    public void requireReadable(StaffProfileEntity staff, AuthPrincipal caller) {
        StaffVisibility visibility = visibility(caller);
        if (visibility.unrestricted()) {
            return;
        }
        if (visibility.selfUserId() != null) {
            if (staff.getUserId() == null
                    || !staff.getUserId().toString().equals(visibility.selfUserId())) {
                throw new NoSuchElementException("人员不存在或不可见");
            }
            return;
        }
        if (!visibility.allowedDeptIds().contains(staff.getDepartmentId().toString())) {
            throw new NoSuchElementException("人员不存在或不可见");
        }
    }

    /** 解析 UUID；格式非法返回 null（由调用方按字段校验统一 400/404）。 */
    public static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
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

    /** 可见科室标识集合（用于跨模块写入校验；空集合表示不可见任何科室）。 */
    public Set<String> visibleDepartmentIds(AuthPrincipal caller) {
        StaffVisibility visibility = visibility(caller);
        return visibility.allowedDeptIds() == null ? Set.of() : new LinkedHashSet<>(
                visibility.allowedDeptIds());
    }
}
