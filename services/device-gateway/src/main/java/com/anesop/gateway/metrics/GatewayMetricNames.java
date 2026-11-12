package com.anesop.gateway.metrics;

/**
 * 网关指标名常量（micrometer 命名，点分小写）。
 *
 * <p>指标是运维契约：变更名称等于破坏看板与告警，须同步更新
 * docs/gateway/README.md §6 与部署告警规则。所有帧级计数带 {@code device} 标签，
 * 非法帧带 {@code reason} 标签（{@code FrameError} 名）。
 */
public final class GatewayMetricNames {

    /** 成功解析并接收的有效帧数（按设备）。 */
    public static final String FRAMES_RECEIVED = "anesop.gateway.frames.received";

    /** 非法帧丢弃数（按原因码）。 */
    public static final String FRAMES_INVALID = "anesop.gateway.frames.invalid";

    /** 已落盘缓冲的帧数（按设备）。 */
    public static final String FRAMES_BUFFERED = "anesop.gateway.frames.buffered";

    /** 已入库的帧数（按设备）。 */
    public static final String FRAMES_UPLOADED = "anesop.gateway.frames.uploaded";

    /** 唯一键冲突（重复补传）的帧数（按设备）。 */
    public static final String FRAMES_DUPLICATE = "anesop.gateway.frames.duplicate";

    /** 上传失败尝试的帧数（按设备）。 */
    public static final String FRAMES_FAILED = "anesop.gateway.frames.failed";

    /** 缓冲待上传记录数（按设备，gauge）。 */
    public static final String BUFFER_PENDING_RECORDS = "anesop.gateway.buffer.pending.records";

    /** 缓冲待上传记录总数（gauge）。 */
    public static final String BUFFER_PENDING_TOTAL = "anesop.gateway.buffer.pending.records.total";

    /** 缓冲超限淘汰的段数（counter）。 */
    public static final String BUFFER_EVICTED_SEGMENTS = "anesop.gateway.buffer.evicted.segments";

    /** 缓冲拒收帧数（counter）。 */
    public static final String BUFFER_REJECTED_RECORDS = "anesop.gateway.buffer.rejected.records";

    /** 缓冲损坏记录/段修复数（counter）。 */
    public static final String BUFFER_CORRUPT_RECORDS = "anesop.gateway.buffer.corrupt.records";

    /** 上传通道是否可用（gauge：1=可用，0=失败）。 */
    public static final String UPLOAD_CONNECTED = "anesop.gateway.upload.connected";

    /** 连续上传失败次数（gauge）。 */
    public static final String UPLOAD_CONSECUTIVE_FAILURES = "anesop.gateway.upload.consecutive.failures";

    /** 上传是否被人工暂停（gauge：1=暂停）。 */
    public static final String UPLOAD_PAUSED = "anesop.gateway.upload.paused";

    private GatewayMetricNames() {
    }
}
