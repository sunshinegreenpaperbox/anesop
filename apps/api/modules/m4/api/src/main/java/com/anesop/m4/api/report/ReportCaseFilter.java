package com.anesop.m4.api.report;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 报表统计单元的只读查询条件（M4-M16-01）。
 *
 * <p>时间区间为 {@code [from, toExclusive)} 的 **Instant**（服务层已按 Asia/Shanghai 自然日
 * 换算为左闭右开区间）。数据范围语义由服务层完成解析，本条件只做**表达**：
 * <ul>
 *   <li>{@code unrestricted=true}：全院可见，不做对象范围过滤（{@code deptId} 仍作为收窄条件）；</li>
 *   <li>否则按 {@code orgIds}（申请科室/院区）、{@code roomIds}（术间）、
 *       {@code selfScoped} + {@code selfUserId}（本人创建的麻醉记录单）三者**任一命中**过滤；</li>
 *   <li>全部为空且非全院 → 调用方应在构建本条件前 fail-closed 403，投影层不解释越权语义。</li>
 * </ul>
 *
 * @param from        区间开始（含，Instant）
 * @param toExclusive 区间结束（不含，Instant）
 * @param deptId      可选科室收窄条件（UUID）
 * @param unrestricted 是否全院可见
 * @param orgIds      可见组织标识（科室/院区）；不可变列表
 * @param roomIds     可见术间标识；不可变列表
 * @param selfUserId  本人标识（SELF 范围按麻醉记录单创建人过滤）
 * @param selfScoped  是否包含 SELF 范围
 */
public record ReportCaseFilter(Instant from, Instant toExclusive, UUID deptId,
        boolean unrestricted, List<UUID> orgIds, List<UUID> roomIds, UUID selfUserId,
        boolean selfScoped) {

    public ReportCaseFilter {
        orgIds = orgIds == null ? List.of() : List.copyOf(orgIds);
        roomIds = roomIds == null ? List.of() : List.copyOf(roomIds);
    }
}
