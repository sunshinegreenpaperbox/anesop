package com.anesop.m4.api.qc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 指标计算数据范围过滤条件（M4-M17-01）。
 *
 * <p>质控指标是科室/全院聚合口径：{@code unrestricted=true} 表示 ALL（全院不过滤）；
 * 否则 {@code orgIds} 必非空，按科室/院区过滤（服务层已 fail-closed，适配器不得退化为全表）。
 *
 * @param from          周期起始（Asia/Shanghai 本地日 00:00 对应 Instant，含）
 * @param toExclusive   周期结束次日 00:00 对应 Instant（不含）
 * @param deptId        收窄科室（可空；非空时必须在 orgIds 内或 unrestricted）
 * @param unrestricted  是否全院（ALL）
 * @param orgIds        允许的科室/院区标识（unrestricted 时为空列表）
 */
public record QcIndicatorFilter(Instant from, Instant toExclusive, UUID deptId,
        boolean unrestricted, List<UUID> orgIds) {

    /** 防御性复制：避免调用方修改可见范围造成越权。 */
    public QcIndicatorFilter {
        orgIds = orgIds == null ? List.of() : List.copyOf(orgIds);
    }

    /** 不可变副本（SpotBugs EI_EXPOSE_REP 防护）。 */
    @Override
    public List<UUID> orgIds() {
        return List.copyOf(orgIds);
    }
}
