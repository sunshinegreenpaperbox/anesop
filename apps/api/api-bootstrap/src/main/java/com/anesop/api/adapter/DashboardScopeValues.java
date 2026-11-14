package com.anesop.api.adapter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;

/**
 * 看板适配器的数据范围参数转换（M5-M18-01）。
 *
 * <p>为什么需要转换：M0 数据范围以字符串标识给出，而 {@code m1_sched}/{@code m2_anes} 的
 * 标识列是 {@code uuid}；PostgreSQL 不存在 `uuid = varchar` 的隐式比较，直接绑定字符串会
 * 报 `operator does not exist: uuid = character varying`。这里统一解析为 {@link UUID} 再绑定
 * （与 M4 报表适配器一致），并保持 fail-closed：无法解析的标识按“不可见”丢弃，
 * 若因此没有任何可用条件则拒绝查询。
 */
final class DashboardScopeValues {

    private DashboardScopeValues() {
    }

    /** 解析标识列表：非法值丢弃（fail-closed），去重保序。 */
    static List<UUID> uuids(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<UUID> parsed = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                parsed.add(UUID.fromString(value.trim()));
            } catch (IllegalArgumentException ignored) {
                // 绑定数据异常按不可见处理，不扩大范围。
            }
        }
        return new ArrayList<>(parsed);
    }

    /** 解析 SELF 用户标识；缺失/非法 → 拒绝（SELF 不允许退化为全院）。 */
    static UUID selfUserId(String value) {
        if (value == null || value.isBlank()) {
            throw new AccessDeniedException("当前用户无可用看板数据范围");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new AccessDeniedException("当前用户无可用看板数据范围");
        }
    }
}
