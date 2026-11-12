package com.anesop.gateway.protocol;

/**
 * 非法帧原因码（协议 v1）。
 *
 * <p>原因码同时作为 micrometer 计数器标签值，便于按原因告警与排障；
 * 日志只打印原因码与字段名，不打印原始报文（避免把可能的患者数据写日志）。
 */
public enum FrameError {

    /** 空行或仅空白。 */
    EMPTY_FRAME,
    /** 字段数不等于 11。 */
    FIELD_COUNT,
    /** 帧头不是 DEV。 */
    BAD_PREFIX,
    /** 设备标识不符合 [A-Za-z0-9._-]{1,64}。 */
    BAD_DEVICE_ID,
    /** 序号非整数或为负。 */
    BAD_SEQ,
    /** 测量时间不是 ISO-8601 可解析时间。 */
    BAD_MEASURED_AT,
    /** 数值字段不是"数值 + 可选单位后缀"，也不是缺省符 '-'。 */
    BAD_NUMBER,
    /** 质量码不在 0–2（设备侧只允许 0/1/2）。 */
    BAD_QUALITY,
    /** 必填数值字段为空串。 */
    MISSING_VALUE
}
