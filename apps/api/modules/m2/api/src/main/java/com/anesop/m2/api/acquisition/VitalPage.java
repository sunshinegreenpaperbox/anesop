package com.anesop.m2.api.acquisition;

import java.util.List;

/**
 * 体征分页结果（契约 device.yaml 的 VitalPage）：items 为当前页采样帧展开后的指标行。
 *
 * @param items 当前页指标行（按 measuredAt 升序）
 * @param meta  分页元数据（按采样帧计数）
 */
public record VitalPage(List<DeviceVital> items, PageMetaView meta) {

    /** 规范构造器：列表不可变。 */
    public VitalPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
