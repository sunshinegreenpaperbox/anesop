package com.anesop.m2.impl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * PACU 复苏与转出配置（{@code anesop.m2.pacu.*}，M3-M10-01）。
 *
 * <p>{@code aldrete-threshold} 为 Aldrete 转出阈值（0–10，默认 9）。**院方阈值【待确认】**
 * （见 docs/modules/m2/README.md §15.9 Q-1）：默认值仅为演示口径，不作为临床决策依据；
 * 阈值调整只改配置、不改代码，转出时的阈值与总分以快照冻结在 pacu_discharge.criteria_snapshot，
 * 便于追溯“当时依据”。非法配置启动即失败（fail-closed），避免静默放宽转出条件。
 *
 * @param aldreteThreshold Aldrete 转出阈值（0–10）
 */
@ConfigurationProperties(prefix = "anesop.m2.pacu")
public record PacuProperties(@DefaultValue("9") int aldreteThreshold) {

    /** 配置自检：阈值必须是 0–10 的整数，否则启动即失败。 */
    public PacuProperties {
        if (aldreteThreshold < 0 || aldreteThreshold > 10) {
            throw new IllegalArgumentException(
                    "anesop.m2.pacu.aldrete-threshold 必须在 0–10 之间");
        }
    }
}
