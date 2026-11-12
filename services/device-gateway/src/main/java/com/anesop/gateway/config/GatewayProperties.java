package com.anesop.gateway.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 网关配置（构造器绑定，不可变）。
 *
 * <p>全部组件带默认值，生产用环境变量覆盖（见 docs/gateway/README.md §7）：
 * {@code ANESOP_GATEWAY_*} 环境变量按 Spring Boot 宽松绑定映射到本对象。
 *
 * @param gatewayId               网关实例标识，写入 m2_anes.anesthesia_vital.gateway_id
 * @param source                  数据来源枚举（AUTO/MANUAL/REPLAY），网关自动采集固定 AUTO
 * @param synthetic               合成数据标记；POC 恒为 true，真实接入后由部署配置改 false
 * @param flywayEnabled           是否在启动时自举迁移；生产建议 false，由平台迁移作业负责
 * @param flywayLocations         Flyway 迁移目录（filesystem: 前缀），默认仓库 db/migration
 * @param maxClockSkewSeconds     时钟偏差阈值（秒），超过即按 §3.6.3 规则处理并标记
 * @param backfillThresholdSeconds 超过该秒数视为补传/回放，保留设备时间不改写
 * @param tcp                     TCP 接入参数
 * @param buffer                  本地文件缓冲参数
 * @param upload                  上传参数
 * @param database                数据库连接参数
 */
@ConfigurationProperties(prefix = "anesop.gateway")
public record GatewayProperties(
        @DefaultValue("GW-POC-01") String gatewayId,
        @DefaultValue("AUTO") String source,
        @DefaultValue("true") boolean synthetic,
        @DefaultValue("true") boolean flywayEnabled,
        @DefaultValue("filesystem:${anesop.root:..}/db/migration") String flywayLocations,
        @DefaultValue("5") long maxClockSkewSeconds,
        @DefaultValue("120") long backfillThresholdSeconds,
        @DefaultValue Tcp tcp,
        @DefaultValue Buffer buffer,
        @DefaultValue Upload upload,
        @DefaultValue Database database) {

    /** 数据库 source 列允许的取值（与 V4 的 CHECK 约束一致）。 */
    private static final Set<String> ALLOWED_SOURCES = Set.of("AUTO", "MANUAL", "REPLAY");

    /**
     * 配置自检：错配置在启动期失败，避免运行期每帧入库失败、数据积压。
     *
     * @param gatewayId            网关标识
     * @param source               数据来源
     * @param synthetic            合成数据标记
     * @param flywayEnabled        是否自举迁移
     * @param flywayLocations      迁移目录
     * @param maxClockSkewSeconds  时钟偏差阈值
     * @param backfillThresholdSeconds 补传阈值
     * @param tcp                  TCP 配置
     * @param buffer               缓冲配置
     * @param upload               上传配置
     * @param database             数据库配置
     */
    public GatewayProperties {
        if (gatewayId == null || gatewayId.isBlank() || gatewayId.length() > 64) {
            throw new IllegalArgumentException("gateway-id 必须是 1–64 字符");
        }
        if (!ALLOWED_SOURCES.contains(source)) {
            throw new IllegalArgumentException("source 只能是 AUTO/MANUAL/REPLAY");
        }
        if (maxClockSkewSeconds < 0 || backfillThresholdSeconds < maxClockSkewSeconds) {
            throw new IllegalArgumentException("时钟阈值必须满足 0 <= max-clock-skew <= backfill-threshold");
        }
        if (tcp == null || buffer == null || upload == null || database == null) {
            throw new IllegalArgumentException("tcp/buffer/upload/database 配置不能为空");
        }
        if (tcp.port() < 0 || tcp.port() > 65_535 || tcp.maxFrameBytes() <= 0) {
            throw new IllegalArgumentException("tcp.port 必须在 0–65535，max-frame-bytes 必须为正");
        }
        if (buffer.segmentBytes() <= 0 || buffer.segmentIntervalMs() <= 0 || buffer.maxBytesPerDevice() <= 0) {
            throw new IllegalArgumentException("buffer 容量参数必须为正");
        }
        if (upload.batchSize() <= 0 || upload.maxBatchesPerCycle() <= 0
                || upload.retryBaseMs() <= 0 || upload.retryMaxMs() < upload.retryBaseMs()) {
            throw new IllegalArgumentException("upload 批量与退避参数非法");
        }
        if (database.url() == null || database.url().isBlank() || database.loginTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("database.url 不能为空，login-timeout-seconds 必须为正");
        }
    }

    /**
     * TCP 接入参数。
     *
     * @param enabled       是否监听；压测/单测可关闭
     * @param bindAddress   监听地址；POC 默认仅本机，生产按 VLAN 放开到采集网段
     * @param port          监听端口；0 表示由操作系统分配（测试用）
     * @param maxFrameBytes 单帧最大字节数，超限丢弃并计数，防内存放大攻击
     */
    public record Tcp(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("127.0.0.1") String bindAddress,
            @DefaultValue("19090") int port,
            @DefaultValue("4096") int maxFrameBytes) {
    }

    /**
     * 本地文件缓冲参数。
     *
     * @param directory          缓冲根目录；每设备一个子目录
     * @param segmentBytes       单段大小上限，超过即轮转（字节）
     * @param segmentIntervalMs  单段最长存活时间，超过即轮转（毫秒）
     * @param maxBytesPerDevice  单设备磁盘上限；超限先淘汰最旧已封段，无法淘汰则拒收新帧
     */
    public record Buffer(
            @DefaultValue("./data/buffer") String directory,
            @DefaultValue("8388608") long segmentBytes,
            @DefaultValue("600000") long segmentIntervalMs,
            @DefaultValue("67108864") long maxBytesPerDevice) {
    }

    /**
     * 上传参数。
     *
     * @param enabled            是否启用上传调度
     * @param intervalMs         调度间隔（毫秒），与固定延迟配合
     * @param batchSize          单批最大行数
     * @param retryBaseMs        指数退避基数（毫秒）
     * @param retryMaxMs         指数退避上限（毫秒）
     * @param maxBatchesPerCycle 单轮最多处理批数，防止补传挤占调度线程
     */
    public record Upload(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1000") long intervalMs,
            @DefaultValue("200") int batchSize,
            @DefaultValue("500") long retryBaseMs,
            @DefaultValue("30000") long retryMaxMs,
            @DefaultValue("64") int maxBatchesPerCycle) {
    }

    /**
     * 数据库连接参数。
     *
     * @param url                  JDBC URL
     * @param username             用户名
     * @param password             口令（生产由密钥管理注入，禁止入仓）
     * @param loginTimeoutSeconds  建连超时，健康检查与上传失败都要尽快返回
     */
    public record Database(
            @DefaultValue("jdbc:postgresql://localhost:5432/anesop") String url,
            @DefaultValue("anesop") String username,
            @DefaultValue("") String password,
            @DefaultValue("5") int loginTimeoutSeconds) {
    }
}
