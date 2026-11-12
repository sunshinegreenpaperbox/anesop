package com.anesop.m0.api.security;

import java.util.List;

/**
 * 数据范围断言的辅助方法（fail-closed 语义）。
 *
 * <p>调用方（M1 起的查询拦截器与业务服务）必须先判断是否被拒绝，再决定注入过滤条件：
 * {@code NONE} 表示不得返回任何数据，而不是“不过滤”。
 */
public final class DataScopeAssignments {

    private DataScopeAssignments() {
    }

    /** 是否被拒绝（null/空列表或含 {@link DataScope#NONE} 均视为拒绝）。 */
    public static boolean isDenied(List<DataScopeAssignment> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            return true;
        }
        return assignments.stream().anyMatch(assignment ->
                assignment == null || assignment.type() == null || assignment.type() == DataScope.NONE);
    }

    /** 拒绝标记（唯一元素为 NONE、范围为空）。 */
    public static List<DataScopeAssignment> denied() {
        return List.of(new DataScopeAssignment(DataScope.NONE, List.of()));
    }
}
