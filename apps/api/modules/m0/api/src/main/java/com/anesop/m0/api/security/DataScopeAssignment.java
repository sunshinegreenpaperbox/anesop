package com.anesop.m0.api.security;

import java.util.List;

/**
 * 数据权限范围绑定：范围类型 + 范围对象标识列表。
 *
 * <p>{@link DataScope#ALL} 时 {@code scopeIds} 为空数组；其余类型在 M1 由绑定表
 * 填充（SITE=院区 ID、DEPT=科室 ID、ROOM=术间 ID、SELF=本人患者）。
 */
public record DataScopeAssignment(DataScope type, List<String> scopeIds) {

    public DataScopeAssignment {
        scopeIds = scopeIds == null ? List.of() : List.copyOf(scopeIds);
    }
}
