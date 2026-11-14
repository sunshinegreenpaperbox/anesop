package com.anesop.m0.impl.auth;

import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.api.security.OrgDepartment;
import com.anesop.m0.api.security.OrganizationQueryService;
import com.anesop.m0.impl.persistence.entity.SysOrgRow;
import com.anesop.m0.impl.persistence.mapper.SysUserOrgMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * 组织（科室）只读查询实现（M4-M15-01 B-3 / S-5）。
 *
 * <p>按调用者 m0 数据范围收敛科室：ALL 全部启用科室；SITE 院区子树；DEPT 授权科室；
 * SELF 本人绑定科室；ROOM/无组织绑定返回空。无数据范围时 fail-closed 抛 403。
 *
 * <p>只读 {@code sys_org}/{@code sys_user_org}，不修改任何组织或绑定；返回体只含
 * 科室标识/编码/名称，不含人员与患者信息。
 */
@Service
public class OrganizationQueryServiceImpl implements OrganizationQueryService {

    /** 统一越权文案（不区分“无范围”与“无绑定”，避免信息泄露）。 */
    private static final String DENIED_MESSAGE = "数据范围不足，拒绝访问";

    private final DataScopeResolver dataScopeResolver;
    private final SysUserOrgMapper userOrgMapper;

    public OrganizationQueryServiceImpl(DataScopeResolver dataScopeResolver,
            SysUserOrgMapper userOrgMapper) {
        this.dataScopeResolver = dataScopeResolver;
        this.userOrgMapper = userOrgMapper;
    }

    @Override
    public List<OrgDepartment> visibleDepartments(UUID callerId) {
        if (callerId == null) {
            throw new AccessDeniedException(DENIED_MESSAGE);
        }
        DataScopeAssignments scopes = dataScopeResolver.resolve(callerId);
        if (scopes == null || scopes.isDenied()) {
            throw new AccessDeniedException(DENIED_MESSAGE);
        }
        if (scopes.isUnrestricted()) {
            return departments(userOrgMapper.selectAllActiveDepartments());
        }
        Map<String, OrgDepartment> merged = new LinkedHashMap<>();
        for (DataScopeAssignment assignment : scopes.assignments()) {
            if (assignment.type() == DataScope.SITE) {
                for (String siteId : assignment.scopeIds()) {
                    merge(merged, userOrgMapper.selectActiveDepartmentsInSubtree(parseUuid(siteId)));
                }
            } else if (assignment.type() == DataScope.DEPT) {
                for (String deptId : assignment.scopeIds()) {
                    merge(merged, Optional.ofNullable(
                            userOrgMapper.selectActiveDepartment(parseUuid(deptId))).stream()
                            .toList());
                }
            } else if (assignment.type() == DataScope.SELF) {
                merge(merged, userOrgMapper.selectActiveDepartmentsByUserId(callerId));
            }
        }
        return List.copyOf(merged.values());
    }

    @Override
    public List<OrgDepartment> departmentsOfUser(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        return departments(userOrgMapper.selectActiveDepartmentsByUserId(userId));
    }

    @Override
    public Optional<OrgDepartment> findActiveDepartment(UUID orgId) {
        if (orgId == null) {
            return Optional.empty();
        }
        SysOrgRow row = userOrgMapper.selectActiveDepartment(orgId);
        return row == null ? Optional.empty() : Optional.of(row.toDepartment());
    }

    private static List<OrgDepartment> departments(List<SysOrgRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream().map(SysOrgRow::toDepartment).toList();
    }

    private static void merge(Map<String, OrgDepartment> merged, List<SysOrgRow> rows) {
        for (SysOrgRow row : rows == null ? List.<SysOrgRow>of() : rows) {
            OrgDepartment department = row.toDepartment();
            merged.putIfAbsent(department.orgId(), department);
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
