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
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 质控域数据范围守卫（M4-M17-01）。
 *
 * <p>质控是**科室/全院聚合口径**，不提供个人视图：
 * <ul>
 *   <li>{@code ALL}：全院；</li>
 *   <li>{@code SITE}/{@code DEPT}：按可见科室（{@link OrganizationQueryService} 收敛）；</li>
 *   <li>{@code SELF}/{@code ROOM}/无范围：fail-closed 403（个人质控视图待院方确认）。</li>
 * </ul>
 *
 * <p>单对象越界与不存在统一抛 {@link NoSuchElementException}（Web 层 404），不泄露其他科室
 * 数据的存在性。本类只做访问控制，不返回患者信息。
 */
@Component
public class QcAccessGuard {

    private final DataScopeResolver dataScopeResolver;
    private final OrganizationQueryService organizationQueryService;

    public QcAccessGuard(DataScopeResolver dataScopeResolver,
            OrganizationQueryService organizationQueryService) {
        this.dataScopeResolver = dataScopeResolver;
        this.organizationQueryService = organizationQueryService;
    }

    /**
     * 可见范围。
     *
     * @param unrestricted   是否全院（ALL）
     * @param allowedDeptIds 可见科室标识（unrestricted 时为空列表；否则非空）
     */
    public record QcVisibility(boolean unrestricted, List<String> allowedDeptIds) {

        /** 防御性复制。 */
        public QcVisibility {
            allowedDeptIds = allowedDeptIds == null ? List.of() : List.copyOf(allowedDeptIds);
        }

        /** 不可变副本（SpotBugs EI_EXPOSE_REP 防护）。 */
        @Override
        public List<String> allowedDeptIds() {
            return List.copyOf(allowedDeptIds);
        }

        /** Mapper 过滤参数：全院返回 null（不过滤），否则返回可见科室列表。 */
        public List<String> mapperFilter() {
            return unrestricted ? null : List.copyOf(allowedDeptIds);
        }
    }

    /** 解析调用者数据范围并换算为质控可见范围；不可用范围一律 403。 */
    public QcVisibility visibility(AuthPrincipal caller) {
        DataScopeAssignments scope =
                dataScopeResolver.resolve(caller == null ? null : caller.userId());
        if (scope == null || scope.isDenied()) {
            throw new AccessDeniedException("当前用户无数据范围");
        }
        if (scope.isUnrestricted()) {
            return new QcVisibility(true, List.of());
        }
        if (scope.isSelfOnly() || !hasObjectScopeTypes(scope, DataScope.SITE, DataScope.DEPT)) {
            throw new AccessDeniedException("质控仅按科室或全院数据范围开放");
        }
        List<OrgDepartment> departments =
                organizationQueryService.visibleDepartments(caller == null ? null : caller.userId());
        List<String> deptIds = departments.stream().map(OrgDepartment::orgId).toList();
        if (deptIds.isEmpty()) {
            throw new AccessDeniedException("当前用户无可用质控数据范围");
        }
        return new QcVisibility(false, deptIds);
    }

    /**
     * 校验写入/查询使用的科室：格式非法 400、不存在/停用/非 DEPT 422、超出范围 403。
     *
     * @return 启用科室视图（含名称，用于快照展示）
     */
    public OrgDepartment requireDepartment(AuthPrincipal caller, String departmentId,
            String field) {
        UUID dept = M4Values.optionalUuid(departmentId, field);
        if (dept == null) {
            throw new M4ValidationException(field, field + " 不能为空");
        }
        OrgDepartment active = organizationQueryService.findActiveDepartment(dept)
                .orElseThrow(() -> new M4UnprocessableException(field,
                        "科室不存在、已停用或不是科室组织"));
        QcVisibility visibility = visibility(caller);
        if (!visibility.unrestricted()
                && !visibility.allowedDeptIds().contains(active.orgId())) {
            throw new AccessDeniedException("科室不在当前用户数据范围内");
        }
        return active;
    }

    /** 单对象可见性：不可见与不存在统一 404（不泄露存在性）。 */
    public void requireVisible(AuthPrincipal caller, UUID departmentId) {
        QcVisibility visibility = visibility(caller);
        if (visibility.unrestricted()) {
            return;
        }
        if (departmentId == null
                || !visibility.allowedDeptIds().contains(departmentId.toString())) {
            throw new NoSuchElementException("质控记录不存在或不可见");
        }
    }

    private static boolean hasObjectScopeTypes(DataScopeAssignments scope, DataScope... types) {
        for (DataScopeAssignment assignment : scope.assignments()) {
            for (DataScope type : types) {
                if (assignment != null && assignment.type() == type) {
                    return true;
                }
            }
        }
        return false;
    }
}
