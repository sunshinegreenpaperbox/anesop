package com.anesop.m5.impl.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 实时看板口径配置（M5-M18-01，口径 dashboard-1.0.0）。
 *
 * <pre>
 * anesop:
 *   m5:
 *     dashboard:
 *       stale-threshold-seconds: 120
 *       device-offline-timeout-seconds: 120
 *       on-time-threshold-minutes: 15
 *       room-daily-available-minutes: 600
 *       audit-sample-seconds: 300
 * </pre>
 *
 * <p>所有阈值只允许**收紧**而不允许放宽到已确认/上游口径之外（启动校验 + 夹紧）：
 * 数据时效阈值最大 120s（方案书 §2.4.9“延迟超过 2 分钟须标注”）；准点阈值与
 * 设备离线阈值沿用 M2 的 15 分钟 / 120 秒默认值；术间每日可用分钟沿用 M1 排程分析的
 * 600 分钟默认值（【待确认】Q-3）。审计采样间隔 &gt; 0 时同一用户在该间隔内只写一条
 * {@code DASHBOARD_VIEW}，避免 10s 轮询刷屏；0 表示每次请求都写（仅测试/排障使用）。
 */
@ConfigurationProperties(prefix = "anesop.m5.dashboard")
public class DashboardProperties {

    /** 数据时效阈值（秒）：延迟超过该值返回 DEGRADED；配置 &gt; 120 时夹紧为 120。 */
    private int staleThresholdSeconds = 120;

    /** 设备离线判定阈值（秒），与 M2 采集一致。 */
    private int deviceOfflineTimeoutSeconds = 120;

    /** 准点判定阈值（分钟），与 M1 排程分析一致。 */
    private int onTimeThresholdMinutes = 15;

    /** 每术间每日可用分钟数（利用率分母），与 M1 排程分析一致（【待确认】）。 */
    private int roomDailyAvailableMinutes = 600;

    /** 审计采样间隔（秒）：同一用户在该间隔内只写一条看板审计。 */
    private long auditSampleSeconds = 300;

    public int getStaleThresholdSeconds() {
        return staleThresholdSeconds;
    }

    public void setStaleThresholdSeconds(int staleThresholdSeconds) {
        this.staleThresholdSeconds = staleThresholdSeconds;
    }

    public int getDeviceOfflineTimeoutSeconds() {
        return deviceOfflineTimeoutSeconds;
    }

    public void setDeviceOfflineTimeoutSeconds(int deviceOfflineTimeoutSeconds) {
        this.deviceOfflineTimeoutSeconds = deviceOfflineTimeoutSeconds;
    }

    public int getOnTimeThresholdMinutes() {
        return onTimeThresholdMinutes;
    }

    public void setOnTimeThresholdMinutes(int onTimeThresholdMinutes) {
        this.onTimeThresholdMinutes = onTimeThresholdMinutes;
    }

    public int getRoomDailyAvailableMinutes() {
        return roomDailyAvailableMinutes;
    }

    public void setRoomDailyAvailableMinutes(int roomDailyAvailableMinutes) {
        this.roomDailyAvailableMinutes = roomDailyAvailableMinutes;
    }

    public long getAuditSampleSeconds() {
        return auditSampleSeconds;
    }

    public void setAuditSampleSeconds(long auditSampleSeconds) {
        this.auditSampleSeconds = auditSampleSeconds;
    }
}
