package com.anesop.gateway.buffer;

import java.io.IOException;

/**
 * 缓冲容量不足：设备磁盘达到上限且没有可淘汰的已封段。
 *
 * <p>这是<b>拒收</b>而非<b>静默丢帧</b>：调用方会计数并告警，已落盘数据保持完整。
 * 容量上限只是保护磁盘不被写爆的兜底，默认值可容纳远超 4 小时的数据（见 README §4）。
 */
public final class BufferCapacityException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造容量异常。
     *
     * @param message 说明（不含患者信息）
     */
    public BufferCapacityException(String message) {
        super(message);
    }
}
