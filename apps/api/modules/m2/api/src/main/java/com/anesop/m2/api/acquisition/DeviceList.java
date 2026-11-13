package com.anesop.m2.api.acquisition;

import java.util.List;

/**
 * 设备登记列表（契约 device.yaml 的 DeviceList；单院百级设备量，不分页）。
 *
 * @param items 设备登记项，按 gatewayId、deviceId 升序
 */
public record DeviceList(List<DeviceView> items) {

    /** 规范构造器：列表不可变。 */
    public DeviceList {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
