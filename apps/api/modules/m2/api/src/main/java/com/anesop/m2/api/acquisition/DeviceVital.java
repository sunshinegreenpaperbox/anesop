package com.anesop.m2.api.acquisition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 单条标准化体征（契约 device.yaml 的 DeviceVital）：一帧可展开为多条指标行。
 *
 * @param metric       指标编码（HR/SPO2/NIBP_SYS/NIBP_DIA/ETCO2/TEMP）
 * @param value        指标数值（规范单位）
 * @param unit         规范单位（bpm/%/mmHg/C）
 * @param measuredAt   设备原始测量时间（幂等键组成部分，不因时钟校正改写）
 * @param quality      质量码：0 正常、1 可疑、2 缺失、3 超时
 * @param qualityFlags 质量标记明细；无标记为空数组
 */
public record DeviceVital(
        String metric,
        BigDecimal value,
        String unit,
        Instant measuredAt,
        int quality,
        List<String> qualityFlags) {

    /** 规范构造器：标记列表不可变。 */
    public DeviceVital {
        qualityFlags = qualityFlags == null ? List.of() : List.copyOf(qualityFlags);
    }
}
