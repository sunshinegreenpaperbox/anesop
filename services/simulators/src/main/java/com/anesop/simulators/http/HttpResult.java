package com.anesop.simulators.http;

/**
 * 路由处理结果。
 *
 * @param status HTTP 状态码
 * @param body   响应体；{@code raw=true} 时为原样输出的字符串，否则按 JSON 序列化
 * @param raw    是否原样输出（故障注入 MALFORMED_JSON 用）
 */
public record HttpResult(int status, Object body, boolean raw) {

    /** JSON 响应（200）。 */
    public static HttpResult ok(Object body) {
        return new HttpResult(200, body, false);
    }

    /** JSON 响应（指定状态码）。 */
    public static HttpResult json(int status, Object body) {
        return new HttpResult(status, body, false);
    }

    /** 原样输出（用于构造非法 JSON 等故障响应）。 */
    public static HttpResult raw(int status, String body) {
        return new HttpResult(status, body, true);
    }
}
