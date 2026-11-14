package com.anesop.m5.api.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * 看板术间只读端口：由装配层适配器用**单条只读 SELECT**投影 M1 术间主数据。
 *
 * <p>软删除术间也会返回（看板以“停用”计入 {@code OUT_OF_SERVICE}，【待确认】）；
 * SELF 范围按“本人当日台次所涉术间”过滤（由适配器的 EXISTS 子查询完成，仍是单条 SELECT）。
 */
public interface DashboardRoomSource {

    /**
     * 查询可见术间。
     *
     * @param from        当日窗口起点（含；仅 SELF 判定使用）
     * @param toExclusive 当日窗口终点（不含；仅 SELF 判定使用）
     * @param scope       数据范围断言
     * @return 术间投影列表（按院区→科室→术间编号稳定排序）；无数据返回空列表
     */
    List<DashboardRoomFact> findRooms(Instant from, Instant toExclusive, DashboardScope scope);
}
