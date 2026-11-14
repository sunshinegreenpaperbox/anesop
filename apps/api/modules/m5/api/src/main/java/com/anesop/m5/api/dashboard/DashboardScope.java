package com.anesop.m5.api.dashboard;

import java.util.List;

/**
 * 看板数据范围断言（由 m5-impl 从 M0 {@code DataScopeAssignments} 解析后下传给只读端口）。
 *
 * <p>语义与 M4 报表一致（ADR-0021 第 7 条）：{@link #unrestricted()} 为真表示全院（ALL），
 * 此时 {@link #orgIds()}/{@link #roomIds()}/{@link #selfScoped()} 均不生效；
 * 否则按院区/科室（SITE/DEPT）、绑定术间（ROOM）或本人当日台次所涉术间（SELF）过滤。
 *
 * <p>本记录不解释越权语义——fail-closed 判定在 m5-impl 完成；投影层只按传入条件拼装 SQL，
 * 并在“非全院且三个条件全空”时直接拒绝（纵深防御，绝不退化为全表查询）。
 *
 * @param unrestricted 是否全院可见（ALL）
 * @param orgIds       可见院区/科室标识（SITE/DEPT）；空列表表示不按组织过滤
 * @param roomIds      可见术间标识（ROOM）；空列表表示不按术间过滤
 * @param selfScoped   是否按本人创建的麻醉记录单过滤（SELF）
 * @param selfUserId   本人用户标识（SELF 时必填，否则调用方应已拒绝）
 */
public record DashboardScope(
        boolean unrestricted,
        List<String> orgIds,
        List<String> roomIds,
        boolean selfScoped,
        String selfUserId) {

    /** 规整为不可变集合，避免调用方后续修改影响查询。 */
    public DashboardScope {
        orgIds = orgIds == null ? List.of() : List.copyOf(orgIds);
        roomIds = roomIds == null ? List.of() : List.copyOf(roomIds);
    }

    /** 全院范围（不限组织、术间与本人）。 */
    public static DashboardScope all() {
        return new DashboardScope(true, List.of(), List.of(), false, null);
    }
}
