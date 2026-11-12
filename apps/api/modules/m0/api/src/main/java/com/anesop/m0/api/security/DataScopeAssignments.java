package com.anesop.m0.api.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据范围断言集合（方案书 §3.9.2，fail-closed 语义）。
 *
 * <p>调用方（M1 起的查询拦截器与业务服务）必须先判断是否被拒绝，再决定注入过滤条件：
 * {@link #isDenied()} 为真表示不得返回任何数据，而不是“不过滤”；{@link #isUnrestricted()}
 * 为真表示全院可见（任一角色 data_scope=ALL）。
 *
 * <p>{@link #allowedOrgIds()} 返回 SITE/DEPT 绑定的组织标识并集（来自 {@code sys_user_org}），
 * {@link #allowedRoomIds()} 返回 ROOM 绑定的术间标识并集（来自 {@code sys_user_room}）。
 * ALL/未受限时两列表为空，表示“不受对象集合限制”；SELF 不产生对象标识，按角色语义
 * 过滤到本人数据。集合不可变，线程安全，可缓存于单次请求内复用。
 */
public final class DataScopeAssignments {

    /** 拒绝标记（唯一元素为 NONE、范围为空）；不可变，可安全共享。 */
    private static final DataScopeAssignments DENIED =
            new DataScopeAssignments(List.of(new DataScopeAssignment(DataScope.NONE, List.of())));

    private final List<DataScopeAssignment> assignments;

    private DataScopeAssignments(List<DataScopeAssignment> assignments) {
        this.assignments = List.copyOf(assignments);
    }

    /**
     * 由解析结果构造断言集合。
     *
     * @param assignments 解析出的范围绑定；null 视为拒绝
     * @return 断言集合
     */
    public static DataScopeAssignments of(List<DataScopeAssignment> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return DENIED;
        }
        for (DataScopeAssignment assignment : assignments) {
            if (assignment == null) {
                // 任一元素缺失即整体拒绝（List.copyOf 不接受 null，fail-closed 语义优先）。
                return DENIED;
            }
        }
        return new DataScopeAssignments(assignments);
    }

    /**
     * 拒绝标记（{@link DataScope#NONE}）。
     *
     * @return 表示无任何数据权限的断言集合
     */
    public static DataScopeAssignments denied() {
        return DENIED;
    }

    /** 原始解析结果（契约输出与审计展示使用；含 NONE 时表示拒绝）。 */
    public List<DataScopeAssignment> assignments() {
        return assignments;
    }

    /** 是否被拒绝（空列表、null 元素、未知类型或含 {@link DataScope#NONE} 均视为拒绝）。 */
    public boolean isDenied() {
        if (assignments.isEmpty()) {
            return true;
        }
        return assignments.stream().anyMatch(assignment ->
                assignment == null || assignment.type() == null || assignment.type() == DataScope.NONE);
    }

    /** 是否全院可见（任一范围断言为 {@link DataScope#ALL}）；被拒绝时恒为 false。 */
    public boolean isUnrestricted() {
        return !isDenied() && assignments.stream().anyMatch(assignment -> assignment.type() == DataScope.ALL);
    }

    /** 允许的组织标识（SITE + DEPT 的 scopeIds 并集，去重保序）；全院可见时为空列表。 */
    public List<String> allowedOrgIds() {
        if (isUnrestricted()) {
            return List.of();
        }
        return collectScopeIds(DataScope.SITE, DataScope.DEPT);
    }

    /** 允许的术间标识（ROOM 的 scopeIds 并集，去重保序）；全院可见时为空列表。 */
    public List<String> allowedRoomIds() {
        if (isUnrestricted()) {
            return List.of();
        }
        return collectScopeIds(DataScope.ROOM);
    }

    private List<String> collectScopeIds(DataScope... types) {
        Set<String> ids = new LinkedHashSet<>();
        for (DataScopeAssignment assignment : assignments) {
            if (assignment == null || assignment.type() == null || !matches(assignment.type(), types)) {
                continue;
            }
            for (String id : assignment.scopeIds()) {
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return List.copyOf(ids);
    }

    private static boolean matches(DataScope candidate, DataScope... types) {
        for (DataScope type : types) {
            if (type == candidate) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "DataScopeAssignments" + assignments;
    }
}
