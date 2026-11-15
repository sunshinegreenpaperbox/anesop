package com.anesop.simulators;

/**
 * 模拟器故障注入模式（SYNTHETIC，仅联调使用）。
 *
 * <ul>
 *   <li>{@link #NONE}：正常响应；延迟由 {@code latencyMs} 单独控制。</li>
 *   <li>{@link #HTTP_ERROR}：所有请求返回 {@code errorStatus}（默认 500），用于验证适配器重试/降级。</li>
 *   <li>{@link #MALFORMED_JSON}：返回 200 + 截断的非法 JSON，用于验证解析失败降级。</li>
 * </ul>
 *
 * <p>超时由 {@code latencyMs} 大于调用方超时时间制造（本枚举不单列 TIMEOUT，避免两处配置冲突）。
 */
public enum FailureMode {
    NONE,
    HTTP_ERROR,
    MALFORMED_JSON
}
