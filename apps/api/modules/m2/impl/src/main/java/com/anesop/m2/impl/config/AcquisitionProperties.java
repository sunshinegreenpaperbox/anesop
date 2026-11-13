package com.anesop.m2.impl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 设备采集接入配置（{@code anesop.m2.acquisition.*}，M3-M8-01）。
 *
 * <p>fail-closed 约定：
 * <ul>
 *   <li>{@code gateway-token} 默认空：未配置时 ingest 一律 401（不允许"无密钥即放行"），
 *       生产由密钥托管注入 {@code ANESOP_M2_ACQUISITION_GATEWAY_TOKEN}，禁止入仓/入日志；</li>
 *   <li>{@code offline-timeout-seconds} 为设备离线判定阈值（默认 120s），须为正；</li>
 *   <li>{@code max-query-window-hours} 为体征窗口查询最大跨度（默认 24h），
 *       超限业务拒绝（422），防止一次查询扫出整月明细；</li>
 *   <li>{@code health-window-hours} 为采集健康摘要的统计窗口（默认 24h，可配置 1–168），
 *       批次统计与质量比例分子/分母使用同一窗口。</li>
 * </ul>
 *
 * @param gatewayToken           内部网关令牌（空白=未配置，ingest 一律拒绝）
 * @param offlineTimeoutSeconds  设备离线判定阈值（秒）
 * @param maxQueryWindowHours    体征窗口查询最大跨度（小时）
 * @param healthWindowHours      采集健康摘要统计窗口（小时）
 * @param synthetic              是否合成数据标记（POC/测试恒 true；真实设备接入由部署配置改 false）
 */
@ConfigurationProperties(prefix = "anesop.m2.acquisition")
public record AcquisitionProperties(
        @DefaultValue("") String gatewayToken,
        @DefaultValue("120") long offlineTimeoutSeconds,
        @DefaultValue("24") long maxQueryWindowHours,
        @DefaultValue("24") long healthWindowHours,
        @DefaultValue("true") boolean synthetic) {

    /** 配置自检：非法阈值启动即失败，避免运行期静默错误。 */
    public AcquisitionProperties {
        if (offlineTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("anesop.m2.acquisition.offline-timeout-seconds 必须为正");
        }
        if (maxQueryWindowHours <= 0 || maxQueryWindowHours > 168) {
            throw new IllegalArgumentException(
                    "anesop.m2.acquisition.max-query-window-hours 必须在 1–168 之间");
        }
        if (healthWindowHours <= 0 || healthWindowHours > 168) {
            throw new IllegalArgumentException(
                    "anesop.m2.acquisition.health-window-hours 必须在 1–168 之间");
        }
    }
}
