package com.anesop.gateway.protocol;

/**
 * 帧解析失败（协议 v1 非法帧）。
 *
 * <p>只携带原因码与出错字段名；网关捕获后丢弃该帧、计数并继续处理后续帧，
 * 单帧错误不中断连接（设备侧常见脏数据不应导致整条链路停摆）。
 */
public final class FrameParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FrameError error;
    private final String field;

    /**
     * 构造解析异常。
     *
     * @param error 原因码
     * @param field 出错字段名（可为空串）
     */
    public FrameParseException(FrameError error, String field) {
        super(error.name() + (field == null || field.isEmpty() ? "" : ":" + field));
        this.error = error;
        this.field = field == null ? "" : field;
    }

    /**
     * 原因码。
     *
     * @return 原因码
     */
    public FrameError error() {
        return error;
    }

    /**
     * 出错字段名。
     *
     * @return 字段名，无则为空串
     */
    public String field() {
        return field;
    }
}
