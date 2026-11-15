package com.anesop.m2.impl.vital;

import com.anesop.contracts.anesthesia.model.VitalSignSample;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 记录单体征只读端口（M9-01，m2 模块内部）。
 *
 * <p>记录单的 {@code vitalSigns} 由本端口按「记录时间窗 + 记录单术间」查询 m2 自身
 * {@code anesthesia_vital} / {@code device_registry}，不跨模块访问 Mapper/数据表。
 * 只读，不写库、不改采集链路。
 */
public interface VitalSignReadPort {

    /** 单次返回的体征采样条数上限（契约 anesthesia 0.11.0）。 */
    int MAX_SAMPLES = 2000;

    /**
     * 查询窗口内体征采样（按 measuredAt 升序，指标展开）。
     *
     * @param roomId 记录单创建时快照的术间（空则返回空窗口）
     * @param from   窗口起点（含）；空则返回空窗口
     * @param to     窗口终点（含）；空则返回空窗口；早于 from 时返回空窗口
     */
    Window read(UUID roomId, Instant from, Instant to);

    /**
     * 体征窗口结果。
     *
     * @param samples   按 measuredAt 升序的采样点（≤ {@link #MAX_SAMPLES}）
     * @param truncated 是否因超过上限被截断（true 时前端提示仅显示前 2000 条）
     */
    record Window(List<VitalSignSample> samples, boolean truncated) {

        public Window {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }

        /** 空窗口。 */
        public static Window empty() {
            return new Window(List.of(), false);
        }
    }
}
